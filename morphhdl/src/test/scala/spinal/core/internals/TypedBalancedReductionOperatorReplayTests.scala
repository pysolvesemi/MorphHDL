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

  // Build the native mux node with an inferred result for graph-certificate
  // tests. Separate tests exercise the ordinary Mux method, including 59f's
  // exact common-arm symbolic-width propagation.
  private def inferredMux[T <: BaseType](condition: Bool, yes: T, no: T): T =
    yes.wrapWithWeakClone(yes.newMultiplexer(condition, yes, no)).asInstanceOf[T]

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

  test("inferred unsigned min/max graphs retain symbolic width and distinct semantic keys") {
    withUInt { (words, _) =>
      var calls = 0
      val operations = Vector[(UInt, UInt) => UInt](
        (a, b) => inferredMux(a < b, a, b), (a, b) => inferredMux(a < b, b, a))
      val proofs = operations.map { operation =>
        val captured = record(words, (a: UInt, b: UInt) => { calls += 1; operation(a, b) })
        TypedBalancedReductionOperatorReplay.certify(captured.rows.head.operator.get)
      }
      assert(proofs.forall(_.operatorClass == classOf[BinaryMultiplexerUInt]))
      assert(proofs.head.operationKey != proofs.last.operationKey)
      val before = calls
      proofs.foreach { proof =>
        val output = proof.replay(words.vec(0), words.vec(2))
        val next = proof.replay(output, words.vec(1))
        assert(next.getTypeObject == TypeUInt)
        assert(ElabInt.equivalentExactFunction(ParameterizedWidth.expressionOf(next).get,
          ParameterizedWidth.expressionOf(words.vec.head).get))
      }
      assert(calls == before)
    }
  }

  test("inferred signed min/max graphs replay their exact native signed comparators") {
    val width = HdlInt.param("WIDTH", 5, 1, 32)
    val count = HdlInt.param("COUNT", 3, 1, 3)
    generate(new Component {
      val words = Vec(SInt(width bits), count)
      words.vec.foreach(_ := 0)
      for (operation <- Vector[(SInt, SInt) => SInt](
          (a, b) => inferredMux(a < b, a, b), (a, b) => inferredMux(a < b, b, a))) {
        val captured = record(words, operation)
        val proof = TypedBalancedReductionOperatorReplay.certify(captured.rows.head.operator.get)
        val output = proof.replay(words.vec(0), words.vec(2))
        val mux = output.dlcLast.asInstanceOf[DataAssignmentStatement].source.asInstanceOf[BinaryMultiplexer]
        val compare = mux.cond.asInstanceOf[Bool].dlcLast.asInstanceOf[DataAssignmentStatement].source
        assert(mux.getClass == classOf[BinaryMultiplexerSInt])
        assert(compare.getClass == classOf[Operator.SInt.Smaller])
        assert(output.getTypeObject == TypeSInt)
        assert(ElabInt.equivalentExactFunction(ParameterizedWidth.expressionOf(output).get,
          ParameterizedWidth.expressionOf(words.vec.head).get))
      }
    })
  }

  test("min/max certification preserves comparator and arm order through native aliases") {
    withUInt { (words, _) =>
      val operations = Vector[(UInt, UInt) => UInt](
        (a, b) => inferredMux(a < b, a, b), (a, b) => inferredMux(b < a, b, a),
        (a, b) => inferredMux(a < b, b, a), (a, b) => {
          val alias = UInt(); alias := b
          val condition = Bool(); condition := alias < a
          val output = UInt(); output := inferredMux(condition, a, alias)
          output
        })
      val proofs = operations.zipWithIndex.map { case (operation, index) =>
        val captured = record(words, operation)
        val proof = TypedBalancedReductionOperatorReplay.certify(captured.rows.head.operator.get)
        val output = proof.replay(words.vec(0), words.vec(2))
        val mux = output.dlcLast.asInstanceOf[DataAssignmentStatement].source.asInstanceOf[BinaryMultiplexer]
        val compare = mux.cond.asInstanceOf[Bool].dlcLast.asInstanceOf[DataAssignmentStatement].source.asInstanceOf[BinaryOperator]
        assert(compare.left eq words.vec(if (index % 2 == 1) 2 else 0))
        assert(compare.right eq words.vec(if (index % 2 == 1) 0 else 2))
        assert(mux.whenTrue eq words.vec(if (index == 1 || index == 2) 2 else 0))
        assert(mux.whenFalse eq words.vec(if (index == 1 || index == 2) 0 else 2))
        proof
      }
      assert(proofs(0).operationKey == proofs(1).operationKey)
      assert(proofs(2).operationKey == proofs(3).operationKey)
      assert(proofs(0).operationKey != proofs(2).operationKey)
    }
  }

  test("min/max rejects wrong signedness comparisons and unproved selectors") {
    val operations = Vector[(UInt, UInt) => UInt](
      (a, b) => inferredMux(a.asSInt < b.asSInt, a, b),
      (a, b) => inferredMux(a === b, a, b),
      (a, b) => inferredMux(a <= b, a, b))
    for (operation <- operations) {
      withUInt { (words, _) =>
        val captured = record(words, operation)
        assertCode("REPLAY-MINMAX-COMPARISON") {
          TypedBalancedReductionOperatorReplay.certify(captured.rows.head.operator.get)
        }
      }
    }
  }

  test("min/max requires two exact original operands in both comparison and arms") {
    for (operation <- Vector[(UInt, UInt) => UInt](
        (a, b) => inferredMux(a < a, a, b), (a, b) => inferredMux(a < b, a, a))) {
      withUInt { (words, _) =>
        val captured = record(words, operation)
        assertCode("REPLAY-BODY-OPERANDS") {
          TypedBalancedReductionOperatorReplay.certify(captured.rows.head.operator.get)
        }
      }
    }
    withUInt { (words, width) =>
      val foreign = UInt(width bits); foreign := 0
      for (operation <- Vector[(UInt, UInt) => UInt](
          (a, b) => inferredMux(a < foreign, a, b), (a, b) => inferredMux(a < b, a, foreign))) {
        val captured = record(words, operation)
        assertCode("REPLAY-EXTERNAL-READ") {
          TypedBalancedReductionOperatorReplay.certify(captured.rows.head.operator.get)
        }
      }
    }
  }

  test("live min/max selector comparator and arm edits invalidate certification") {
    withUInt { (words, _) =>
      val captured = record(words, (a: UInt, b: UInt) => inferredMux(a < b, a, b))
      val body = captured.rows.head.operator.get
      val proof = TypedBalancedReductionOperatorReplay.certify(body)
      val mux = body.assignments.collectFirst {
        case assignment if assignment.source.isInstanceOf[BinaryMultiplexerUInt] =>
          assignment.source.asInstanceOf[BinaryMultiplexerUInt]
      }.get
      val compare = body.assignments.collectFirst {
        case assignment if assignment.source.isInstanceOf[Operator.UInt.Smaller] =>
          assignment.source.asInstanceOf[Operator.UInt.Smaller]
      }.get
      val condition = mux.cond
      mux.cond = True
      assertCode("REPLAY-STALE-GRAPH") { proof.validateFreshness() }
      mux.cond = condition
      val arm = mux.whenTrue
      mux.whenTrue = words.vec(2)
      assertCode("REPLAY-STALE-GRAPH") { proof.validateFreshness() }
      mux.whenTrue = arm
      val left = compare.left
      compare.left = words.vec(2)
      assertCode("REPLAY-STALE-GRAPH") { proof.validateFreshness() }
      compare.left = left
      proof.validateFreshness()
    }
  }

  test("min/max replay rejects foreign symbolic roots with equal defaults") {
    withUInt { (words, _) =>
      val captured = record(words, (a: UInt, b: UInt) => inferredMux(a < b, a, b))
      val proof = TypedBalancedReductionOperatorReplay.certify(captured.rows.head.operator.get)
      val foreign = UInt(HdlInt.param("WIDTH", 5, 1, 32) bits)
      foreign := 0
      assertCode("REPLAY-OPERAND-SHAPE") { proof.replay(words.vec(0), foreign) }
    }
  }

  test("native min/max methods admit concrete widths and exact common typed widths") {
    val operations = Vector[(UInt, UInt) => UInt](_ min _, _ max _)
    for (operation <- operations) {
      generate(new Component {
        val words = Vec(UInt(5 bits), HdlInt.param("COUNT", 3, 1, 3))
        words.vec.foreach(_ := 0)
        val captured = record(words, operation)
        val proof = TypedBalancedReductionOperatorReplay.certify(captured.rows.head.operator.get)
        assert(proof.replay(words.vec(0), words.vec(2)).getBitsWidth == 5)
      })
      withUInt { (words, _) =>
        val captured = record(words, operation)
        val proof = TypedBalancedReductionOperatorReplay.certify(captured.rows.head.operator.get)
        val result = proof.replay(words.vec(0), words.vec(2))
        assert(ElabInt.equivalentExactFunction(ParameterizedWidth.expressionOf(result).get,
          ParameterizedWidth.expressionOf(words.vec.head).get))
      }
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
