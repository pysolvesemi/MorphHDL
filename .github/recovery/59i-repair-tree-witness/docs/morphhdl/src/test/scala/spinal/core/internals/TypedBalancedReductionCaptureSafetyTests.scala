package spinal.core.internals

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib._
import morphhdl.frontend.HdlInt

/** Rejection is tested at the native boundary, never through a mock RTL tree. */
class TypedBalancedReductionCaptureSafetyTests extends AnyFunSuite {
  private def native[T <: Data]: ElabBalancedReduction.Native[T] =
    (values, operation, bridge) =>
      new TraversableOnceAnyPimped[T](values).reduceBalancedTree(operation, bridge)

  private def reject(code: String)(build: => Component): Unit = {
    val error = intercept[Exception] {
      SpinalConfig(targetDirectory = Files.createTempDirectory("reduce-capture-rejection-").toString)
        .generateVerilog(build)
    }
    def detail(error: Throwable): String =
      if (error == null) "" else Option(error.getMessage).getOrElse("") + "\n" + detail(error.getCause)
    assert(detail(error).contains(code), detail(error))
  }

  private class Inputs extends Component {
    val input = in Bits(15 bits)
    val words = Vec(UInt(5 bits), HdlInt.param("COUNT", 3, 1, 3))
    for (i <- 0 until 3) words.vec(i) := input(i * 5, 5 bits).asUInt
  }

  test("operator writes to an existing input are rejected") {
    reject("MORPH-REDUCE-BALANCED-CALLBACK-EXTERNAL-WRITE") {
      new Inputs {
        TypedBalancedReductionCapture(words,
          (a: UInt, b: UInt) => { a := b; a + b },
          (value: UInt, _: Int) => value, native[UInt])
      }
    }
  }

  test("bridge writes to an existing signal are rejected") {
    reject("MORPH-REDUCE-BALANCED-CALLBACK-EXTERNAL-WRITE") {
      new Inputs {
        val external = UInt(5 bits)
        external := 0
        TypedBalancedReductionCapture(words, (a: UInt, b: UInt) => a + b,
          (value: UInt, _: Int) => { external := value; value }, native[UInt])
      }
    }
  }

  test("replacement of an existing native assignment is rejected") {
    reject("MORPH-REDUCE-BALANCED-CALLBACK-MUTATION") {
      new Inputs {
        val assignment = words.vec.head.dlcLast.asInstanceOf[DataAssignmentStatement]
        TypedBalancedReductionCapture(words,
          (a: UInt, b: UInt) => { assignment.source = b; a + b },
          (value: UInt, _: Int) => value, native[UInt])
      }
    }
  }

  test("a callback cannot create child hierarchy") {
    reject("MORPH-REDUCE-BALANCED-CALLBACK-HIERARCHY") {
      new Inputs {
        TypedBalancedReductionCapture(words,
          (a: UInt, b: UInt) => {
            val child = new Component { val result = out UInt(5 bits); result := 0 }
            a + b
          }, (value: UInt, _: Int) => value, native[UInt])
      }
    }
  }

  test("a callback cannot return a foreign component's native data") {
    reject("MORPH-REDUCE-BALANCED-CALLBACK-RESULT") {
      new Inputs {
        val child = new Component { val result = out UInt(5 bits); result := 0 }
        TypedBalancedReductionCapture(words, (_: UInt, _: UInt) => child.result,
          (value: UInt, _: Int) => value, native[UInt])
      }
    }
  }

  test("a callback cannot return null") {
    reject("MORPH-REDUCE-BALANCED-CALLBACK-RESULT") {
      new Inputs {
        TypedBalancedReductionCapture(words, (_: UInt, _: UInt) => null.asInstanceOf[UInt],
          (value: UInt, _: Int) => value, native[UInt])
      }
    }
  }

  test("capture requires the exact typed Vec shape rather than a concrete lookalike") {
    reject("MORPH-REDUCE-BALANCED-CAPTURE-SHAPE-MISSING") {
      new Component {
        val words = Vec(UInt(5 bits), 3)
        words.foreach(_ := 0)
        TypedBalancedReductionCapture(words, (a: UInt, b: UInt) => a + b,
          (value: UInt, _: Int) => value, native[UInt])
      }
    }
  }

  test("capture is confined to the exact owning component") {
    reject("MORPH-REDUCE-BALANCED-CAPTURE-OWNER") {
      new Inputs {
        val child = new Component {
          TypedBalancedReductionCapture(words, (a: UInt, b: UInt) => a + b,
            (value: UInt, _: Int) => value, native[UInt])
        }
      }
    }
  }

  test("null dispatch dependencies fail before entering the native kernel") {
    val error = intercept[IllegalArgumentException] {
      TypedBalancedReductionCapture[UInt](null, null, null, null)
    }
    assert(error.getMessage.contains("MORPH-REDUCE-BALANCED-CAPTURE-NULL"))
  }

  test("the native width guard rejects carrier width mutation before capture") {
    reject("getWidth result differ from last call") {
      new Inputs {
        words.vec.head.setWidth(6)
        TypedBalancedReductionCapture(words, (a: UInt, b: UInt) => a + b,
          (value: UInt, _: Int) => value, native[UInt])
      }
    }
  }

  test("a bridge cannot add an initializer to an existing register") {
    reject("MORPH-REDUCE-BALANCED-CALLBACK-EXTERNAL-WRITE") {
      new Inputs {
        val existing = Reg(UInt(5 bits))
        existing := 0
        TypedBalancedReductionCapture(words, (a: UInt, b: UInt) => a + b,
          (value: UInt, _: Int) => { existing.init(U(1, 5 bits)); value }, native[UInt])
      }
    }
  }

  test("patched concrete Vec routing emits byte-identical RTL to the original helper") {
    final class Parity(usePatched: Boolean, count: Int) extends Component {
      setDefinitionName("ConcreteReductionParity")
      val io = new Bundle { val input = in Vec(UInt(5 bits), count); val result = out UInt(5 bits) }
      val op: (UInt, UInt) => UInt = (a, b) => a + b
      val bridge: (UInt, Int) => UInt = (value, _) => RegNext(value) init U(0, 5 bits)
      io.result := (if (usePatched) io.input.reduceBalancedTree(op, bridge)
        else new TraversableOnceAnyPimped[UInt](io.input.vec).reduceBalancedTree(op, bridge))
    }
    def generate(patched: Boolean, count: Int): Array[Byte] = {
      val root = Files.createTempDirectory("reduce-concrete-parity-")
      SpinalConfig(targetDirectory = root.toString, headerWithDate = false, headerWithRepoHash = false)
        .generateVerilog(new Parity(patched, count))
      Files.readAllBytes(root.resolve("ConcreteReductionParity.v"))
    }
    for (count <- Vector(1, 2, 3, 5, 8, 9, 16, 17))
      assert(generate(false, count).sameElements(generate(true, count)), s"concrete native RTL changed at count=$count")
  }
}
