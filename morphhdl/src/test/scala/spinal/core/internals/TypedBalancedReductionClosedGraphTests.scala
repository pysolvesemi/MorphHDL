package spinal.core.internals

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib._
import morphhdl.frontend.HdlInt

/** Exercise the real native helper and graph before compiler normalization. */
class TypedBalancedReductionClosedGraphTests extends AnyFunSuite {
  private def native[T <: Data]: ElabBalancedReduction.Native[T] =
    (values, operation, bridge) =>
      new TraversableOnceAnyPimped[T](values).reduceBalancedTree(operation, bridge)

  private def withWords(maximum: Int = 3)(body: Vec[UInt] => Unit): Unit = {
    val count = HdlInt.param("COUNT", 1, 1, maximum)
    val directory = Files.createTempDirectory("balanced-closed-graph-")
    SpinalConfig(targetDirectory = directory.toString, headerWithDate = false,
      headerWithRepoHash = false).generateVerilog(new Component {
      val input = in Bits(5 * maximum bits)
      val words = Vec(UInt(5 bits), count)
      for (index <- 0 until maximum)
        words.vec(index) := input(index * 5, 5 bits).asUInt
      body(words)
      val anchor = out Bool()
      anchor := False
    })
  }

  private def capture(words: Vec[UInt],
      op: (UInt, UInt) => UInt = (a: UInt, b: UInt) => a + b,
      bridge: (UInt, Int) => UInt = (value: UInt, _: Int) => value
  ): TypedBalancedReductionClosedGraph.ReductionObservation[UInt] =
    TypedBalancedReductionClosedGraph.capture(words, op, bridge, native[UInt])

  private def reject(code: String)(body: Vec[UInt] => Unit): Unit = {
    val error = intercept[Exception] { withWords()(body) }
    def detail(error: Throwable): String =
      if (error == null) "" else Option(error.getMessage).getOrElse("") + "\n" + detail(error.getCause)
    assert(detail(error).contains(code), detail(error))
  }

  private def firstAdd(record: UnvalidatedBalancedReduction[UInt]): Operator.UInt.Add =
    record.rows.head.operator.get.assignments.collectFirst {
      case assignment: DataAssignmentStatement
          if assignment.source.isInstanceOf[Operator.UInt.Add] =>
        assignment.source.asInstanceOf[Operator.UInt.Add]
    }.get

  test("modular addition and identity bridges have closed unchanged native graphs") {
    withWords() { words =>
      val observed = capture(words)
      assert(observed.callbacks.map(_.ordinal) == (0 until 5).toVector)
      assert(observed.callbacks.forall(_.nodeCount > 0))
      assert(observed.callbacks.forall(_.registerCount == 0))
      observed.requireUnchanged()
      val output = out UInt(5 bits)
      output := observed.native.result
    }
  }

  test("native register bridges retain closed initializer and clock identities") {
    withWords(5) { words =>
      val observed = capture(words, bridge = (value: UInt, _: Int) =>
        RegNext(value) init U(0, 5 bits))
      assert(observed.callbacks.map(_.registerCount).sum == 6)
      observed.requireUnchanged()
      val output = out UInt(5 bits)
      output := observed.native.result
    }
  }

  test("singleton-only domains do not invoke callbacks or observations") {
    withWords(1) { words =>
      val observed = capture(words,
        (_: UInt, _: UInt) => fail("singleton invoked operator"),
        (_: UInt, _: Int) => fail("singleton invoked bridge"))
      assert(observed.callbacks.isEmpty)
      assert(observed.native.result eq words.vec.head)
      observed.requireUnchanged()
    }
  }

  test("observation never reexecutes an operator or level bridge") {
    withWords(5) { words =>
      var operators = 0
      var bridges = 0
      val observed = capture(words,
        (a: UInt, b: UInt) => { operators += 1; a + b },
        (value: UInt, _: Int) => { bridges += 1; value })
      observed.requireUnchanged()
      observed.requireUnchanged()
      assert(operators == 4 && bridges == 6)
    }
  }

  test("unsigned minimum maximum and bitwise callbacks use the same closure gate") {
    withWords(5) { words =>
      val operations = Vector[(UInt, UInt) => UInt](
        (a, b) => a min b, (a, b) => a max b,
        (a, b) => a | b, (a, b) => a ^ b, (a, b) => a & b)
      operations.foreach(operation => capture(words, operation).requireUnchanged())
    }
  }

  test("closed signed comparison and cast graphs are not replaced with unsigned logic") {
    withWords() { words =>
      capture(words, (a: UInt, b: UInt) => (a.asSInt min b.asSInt).asUInt).requireUnchanged()
      capture(words, (a: UInt, b: UInt) => (a.asSInt max b.asSInt).asUInt).requireUnchanged()
    }
  }

  test("closed subtraction does not become an associative replay certificate") {
    withWords() { words =>
      val observed = capture(words, (a: UInt, b: UInt) => a - b)
      observed.requireUnchanged()
      val error = intercept[IllegalArgumentException](observed.requireReplayCertificate())
      assert(error.getMessage.contains("MORPH-REDUCE-BALANCED-REPLAY-UNVALIDATED"))
    }
  }

  test("external reads are rejected even in a right-hand branch after a valid operand") {
    reject("MORPH-REDUCE-BALANCED-GRAPH-EXTERNAL-READ") { words =>
      val external = in UInt(5 bits)
      capture(words, (a: UInt, b: UInt) => (a + b) + external)
    }
  }

  test("preexisting registers are external dependencies rather than captured bridge state") {
    reject("MORPH-REDUCE-BALANCED-GRAPH-EXTERNAL-READ") { words =>
      val external = Reg(UInt(5 bits)) init 0
      external := 1
      capture(words, (a: UInt, b: UInt) => a + b,
        (value: UInt, _: Int) => value + external)
    }
  }

  test("unused callback-created register state is rejected") {
    reject("MORPH-REDUCE-BALANCED-GRAPH-UNREACHABLE") { words =>
      capture(words, (a: UInt, b: UInt) => {
        val unused = RegNext(a) init U(0, 5 bits)
        a + b
      })
    }
  }

  test("runtime-dependent bridge initializers are rejected") {
    reject("MORPH-REDUCE-BALANCED-GRAPH-INITIALIZER") { words =>
      capture(words, bridge = (value: UInt, _: Int) => RegNext(value) init value)
    }
  }

  test("callback-local register feedback is rejected before recursive width queries") {
    reject("MORPH-REDUCE-BALANCED-GRAPH-CYCLE") { words =>
      capture(words, (a: UInt, b: UInt) => {
        val feedback = Reg(UInt(5 bits)) init 0
        feedback := feedback + a + b
        feedback
      })
    }
  }

  test("conditional drivers are outside the unconditional closure subset") {
    reject("MORPH-REDUCE-BALANCED-GRAPH-ASSIGNMENT-SHAPE") { words =>
      capture(words, (a: UInt, b: UInt) => {
        val result = UInt(5 bits)
        when(a === b) { result := a } otherwise { result := b }
        result
      })
    }
  }

  test("partial-object callback drivers are rejected") {
    reject("MORPH-REDUCE-BALANCED-GRAPH-ASSIGNMENT-SHAPE") { words =>
      capture(words, (a: UInt, b: UInt) => {
        val result = UInt(5 bits)
        result := a
        result(0) := b(0)
        result
      })
    }
  }

  test("multiple full drivers are not silently collapsed into a replay body") {
    reject("MORPH-REDUCE-BALANCED-GRAPH-DRIVERS") { words =>
      capture(words, (a: UInt, b: UInt) => {
        val result = UInt(5 bits)
        result := a
        result := b
        result
      })
    }
  }

  test("unknown operator classes fail closed rather than inheriting admission") {
    reject("MORPH-REDUCE-BALANCED-GRAPH-EXPRESSION") { words =>
      capture(words, (a: UInt, b: UInt) => a / b)
    }
  }

  test("in-place native operator edits invalidate a frozen observation") {
    reject("MORPH-REDUCE-BALANCED-GRAPH-CHANGED") { words =>
      val observed = capture(words)
      val add = firstAdd(observed.native)
      add.right = add.left
      observed.requireUnchanged()
    }
  }

  test("later callbacks cannot mutate an already-observed operator in place") {
    reject("MORPH-REDUCE-BALANCED-GRAPH-CHANGED") { words =>
      var first: Operator.UInt.Add = null
      capture(words, (a: UInt, b: UInt) => {
        val result = a + b
        if (first == null)
          first = result.dlcLast.asInstanceOf[DataAssignmentStatement].source.asInstanceOf[Operator.UInt.Add]
        result
      }, (value: UInt, _: Int) => { first.right = first.left; value })
    }
  }

  test("new unrecorded drivers invalidate a captured callback") {
    reject("MORPH-REDUCE-BALANCED-GRAPH-UNRECORDED-DRIVER") { words =>
      val observed = capture(words)
      observed.native.rows.head.operator.get.result.asInstanceOf[UInt] := U(0, 5 bits)
      observed.requireUnchanged()
    }
  }

  test("null duplicated and forged callback inventories fail closed") {
    val error = intercept[IllegalArgumentException](TypedBalancedReductionClosedGraph.observe(null))
    assert(error.getMessage.contains("MORPH-REDUCE-BALANCED-GRAPH-NULL"))
    withWords() { words =>
      val observed = capture(words)
      val callback = observed.native.rows.head.operator.get
      val duplicate = callback.copy(declarations = callback.declarations ++ callback.declarations)
      val duplicateError = intercept[IllegalArgumentException](TypedBalancedReductionClosedGraph.observe(duplicate))
      assert(duplicateError.getMessage.contains("MORPH-REDUCE-BALANCED-GRAPH-INVENTORY"))
      val missing = callback.copy(assignments = Vector.empty)
      val missingError = intercept[IllegalArgumentException](TypedBalancedReductionClosedGraph.observe(missing))
      assert(missingError.getMessage.contains("MORPH-REDUCE-BALANCED-GRAPH-UNRECORDED-DRIVER"))
      val observerError = intercept[IllegalArgumentException] {
        TypedBalancedReductionCapture(words, (a: UInt, b: UInt) => a + b,
          (value: UInt, _: Int) => value, native[UInt], null)
      }
      assert(observerError.getMessage.contains("MORPH-REDUCE-BALANCED-CAPTURE-NULL"))
    }
  }
}
