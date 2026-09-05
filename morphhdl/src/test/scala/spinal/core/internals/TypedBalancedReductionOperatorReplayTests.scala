package spinal.core.internals

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib._
import morphhdl.frontend.HdlInt

class TypedBalancedReductionOperatorReplayTests extends AnyFunSuite {
  private def generate(body: => Component): Unit = {
    SpinalConfig(targetDirectory = Files.createTempDirectory("reduce-operator-replay-").toString,
      headerWithDate = false, headerWithRepoHash = false).generateVerilog(body)
  }

  private def record[T <: Data](words: Vec[T], op: (T, T) => T): UnvalidatedBalancedReduction[T] = {
    val native: ElabBalancedReduction.Native[T] = (values, operation, bridge) =>
      new TraversableOnceAnyPimped[T](values).reduceBalancedTree(operation, bridge)
    TypedBalancedReductionCapture(words, op, (value: T, _: Int) => value, native)
  }

  private def assertCode(code: String)(body: => Any): Unit = {
    val error = intercept[IllegalArgumentException](body)
    assert(error.getMessage.contains(code), error.getMessage)
  }

  // Parameter tokens belong outside Component val naming callbacks: those
  // callbacks deliberately cannot hash a symbolic HdlInt as a Scala value.
  private def withUInt(body: (Vec[UInt], HdlInt) => Unit): Unit = {
    val width = HdlInt.param("WIDTH", 5, 1, 32)
    val count = HdlInt.param("COUNT", 3, 1, 3)
    generate(new Component {
      val words = Vec(UInt(width bits), count)
      words.vec.foreach(_ := 0)
      body(words, width)
    })
  }

  test("captured UInt modular add bodies replay with exact typed result widths") {
    withUInt { (words, _) =>
      val captured = record(words, (a: UInt, b: UInt) => a + b)
      val body = captured.rows.head.operator.get
      val proof = TypedBalancedReductionOperatorReplay.certify(body)
      assert(proof.operatorClass == classOf[Operator.UInt.Add])
      val result = proof.replay(words.vec(0), words.vec(1))
      assert(result ne body.result)
      assert(result.getTypeObject == TypeUInt)
      assert(result.getBitsWidth == 5)
      val sourceWidth = ParameterizedWidth.expressionOf(words.vec.head).get
      val resultWidth = ParameterizedWidth.expressionOf(result).get
      assert(ElabInt.equivalentExactFunction(sourceWidth, resultWidth))
      assert(resultWidth.parameters.head eq sourceWidth.parameters.head)
      val next = proof.replay(result, words.vec(2))
      assert(ElabInt.equivalentExactFunction(ParameterizedWidth.expressionOf(next).get, sourceWidth))
      assert(next.getBitsWidth == 5)
    }
  }

  test("UInt AND OR and XOR replay without invoking their Scala callback again") {
    for (operation <- Vector[(UInt, UInt) => UInt](_ & _, _ | _, _ ^ _)) {
      var calls = 0
      withUInt { (words, _) =>
        val captured = record(words, (a: UInt, b: UInt) => { calls += 1; operation(a, b) })
        val proof = TypedBalancedReductionOperatorReplay.certify(captured.rows.head.operator.get)
        val before = calls
        proof.replay(words.vec(0), words.vec(2))
        assert(calls == before)
      }
    }
  }

  test("signed add and bitwise bodies retain their native signed type") {
    for (operation <- Vector[(SInt, SInt) => SInt](_ + _, _ & _, _ | _, _ ^ _)) {
      val width = HdlInt.param("WIDTH", 5, 1, 32)
      val count = HdlInt.param("COUNT", 3, 1, 3)
      generate(new Component {
        val words = Vec(SInt(width bits), count)
        words.vec.foreach(_ := 0)
        val captured = record(words, operation)
        val proof = TypedBalancedReductionOperatorReplay.certify(captured.rows.head.operator.get)
        assert(proof.replay(words.vec(0), words.vec(2)).getTypeObject == TypeSInt)
      })
    }
  }

  test("Bits AND OR and XOR use the same generic replay path") {
    for (operation <- Vector[(Bits, Bits) => Bits](_ & _, _ | _, _ ^ _)) {
      val width = HdlInt.param("WIDTH", 5, 1, 32)
      val count = HdlInt.param("COUNT", 3, 1, 3)
      generate(new Component {
        val words = Vec(Bits(width bits), count)
        words.vec.foreach(_ := 0)
        val captured = record(words, operation)
        val proof = TypedBalancedReductionOperatorReplay.certify(captured.rows.head.operator.get)
        assert(proof.replay(words.vec(0), words.vec(2)).getTypeObject == TypeBits)
      })
    }
  }

  test("Bool AND OR and XOR are supported without a packed-width parameter") {
    for (operation <- Vector[(Bool, Bool) => Bool](_ && _, _ || _, _ ^ _)) {
      generate(new Component {
        val words = Vec(Bool(), HdlInt.param("COUNT", 3, 1, 3))
        words.vec.foreach(_ := False)
        val captured = record(words, operation)
        val proof = TypedBalancedReductionOperatorReplay.certify(captured.rows.head.operator.get)
        assert(proof.replay(words.vec(0), words.vec(2)).getTypeObject == TypeBool)
      })
    }
  }

  test("transparent inferred aliases retain exact operand provenance") {
    withUInt { (words, _) =>
      val captured = record(words, (a: UInt, b: UInt) => {
        val alias = UInt(); alias := a
        val output = UInt(); output := alias + b
        output
      })
      val proof = TypedBalancedReductionOperatorReplay.certify(captured.rows.head.operator.get)
      assert(proof.replay(words.vec(0), words.vec(2)).getBitsWidth == 5)
    }
  }

  test("reversed operand order remains an exact native replay") {
    withUInt { (words, _) =>
      val captured = record(words, (a: UInt, b: UInt) => b + a)
      val proof = TypedBalancedReductionOperatorReplay.certify(captured.rows.head.operator.get)
      val result = proof.replay(words.vec(0), words.vec(2))
      val driver = result.dlcLast.asInstanceOf[DataAssignmentStatement].source.asInstanceOf[BinaryOperator]
      assert(driver.left eq words.vec(2))
      assert(driver.right eq words.vec(0))
    }
  }

  test("subtraction and widening arithmetic are not silently certified") {
    val cases = Vector[((UInt, UInt) => UInt, String)](
      ((a, b) => a - b, "REPLAY-NONASSOCIATIVE-OR-UNSUPPORTED"),
      ((a, b) => a +^ b, "REPLAY-BODY-OPERANDS"),
      ((a, b) => a * b, "REPLAY-NONASSOCIATIVE-OR-UNSUPPORTED")
    )
    for ((operation, code) <- cases) {
      withUInt { (words, _) =>
        val captured = record(words, operation)
        assertCode(code) {
          TypedBalancedReductionOperatorReplay.certify(captured.rows.head.operator.get)
        }
      }
    }
  }

  test("same-width foreign signals cannot replace original operands") {
    withUInt { (words, width) =>
      val external = UInt(width bits); external := 0
      val captured = record(words, (a: UInt, b: UInt) => a + external)
      assertCode("REPLAY-EXTERNAL-READ") {
        TypedBalancedReductionOperatorReplay.certify(captured.rows.head.operator.get)
      }
    }
  }

  test("fixed local widths cannot specialize symbolic widths or truncate concrete widths") {
    withUInt { (words, _) =>
      val captured = record(words, (a: UInt, b: UInt) => {
        val fixed = UInt(5 bits); fixed := a + b; fixed
      })
      assertCode("REPLAY-FIXED-WIDTH") {
        TypedBalancedReductionOperatorReplay.certify(captured.rows.head.operator.get)
      }
    }
    generate(new Component {
      val words = Vec(UInt(5 bits), HdlInt.param("COUNT", 3, 1, 3))
      words.vec.foreach(_ := 0)
      val captured = record(words, (a: UInt, b: UInt) => {
        val narrow = UInt(3 bits); narrow := (a + b).resized; narrow
      })
      assertCode("REPLAY-FIXED-WIDTH") {
        TypedBalancedReductionOperatorReplay.certify(captured.rows.head.operator.get)
      }
    })
  }

  test("unused callback-local effects cannot be discarded by body certification") {
    withUInt { (words, _) =>
      val captured = record(words, (a: UInt, b: UInt) => {
        val unused = UInt(); unused := a ^ b
        a + b
      })
      assertCode("REPLAY-UNCONSUMED-EFFECT") {
        TypedBalancedReductionOperatorReplay.certify(captured.rows.head.operator.get)
      }
    }
  }

  test("live native operand mutation invalidates an existing proof") {
    withUInt { (words, _) =>
      val captured = record(words, (a: UInt, b: UInt) => a + b)
      val body = captured.rows.head.operator.get
      val proof = TypedBalancedReductionOperatorReplay.certify(body)
      val operator = body.assignments.head.source.asInstanceOf[BinaryOperator]
      val original = operator.left
      operator.left = words.vec(2).asInstanceOf[operator.T]
      assertCode("REPLAY-STALE-GRAPH") { proof.validateFreshness() }
      operator.left = original
      proof.validateFreshness()
    }
  }

  test("equal-named equal-width foreign parameter roots are rejected before replay") {
    withUInt { (words, _) =>
      val captured = record(words, (a: UInt, b: UInt) => a + b)
      val proof = TypedBalancedReductionOperatorReplay.certify(captured.rows.head.operator.get)
      val foreign = UInt(HdlInt.param("WIDTH", 5, 1, 32) bits)
      foreign := 0
      assertCode("REPLAY-OPERAND-SHAPE") { proof.replay(words.vec(0), foreign) }
    }
  }

  test("a register body is not a combinational associative operator") {
    withUInt { (words, _) =>
      val captured = record(words, (a: UInt, b: UInt) => RegNext(a + b) init U(0, 5 bits))
      assertCode("REPLAY-BODY-STATE") {
        TypedBalancedReductionOperatorReplay.certify(captured.rows.head.operator.get)
      }
    }
  }

  test("missing or copied incomplete body evidence cannot construct a proof") {
    assertCode("REPLAY-BODY-ARITY") { TypedBalancedReductionOperatorReplay.certify(null) }
    withUInt { (words, _) =>
      val captured = record(words, (a: UInt, b: UInt) => a + b)
      val body = captured.rows.head.operator.get
      assertCode("REPLAY-BODY-DRIVER") {
        TypedBalancedReductionOperatorReplay.certify(body.copy(assignments = Vector.empty))
      }
    }
  }
}
