package spinal.core.internals

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib._
import morphhdl.frontend.HdlInt

/** Low-level native graph evidence tests, independent of host callback policy. */
class TypedBalancedReductionScalarGraphReplayTests extends AnyFunSuite {
  private def generate(body: => Component): Unit =
    SpinalConfig(targetDirectory = Files.createTempDirectory("reduce-scalar-graph-").toString,
      headerWithDate = false, headerWithRepoHash = false).generateVerilog(body)

  private def record(words: Vec[UInt], op: (UInt, UInt) => UInt): UnvalidatedBalancedCallback = {
    val native: ElabBalancedReduction.Native[UInt] = (values, operation, bridge) =>
      new TraversableOnceAnyPimped[UInt](values).reduceBalancedTree(operation, bridge)
    TypedBalancedReductionCapture(words, op, (value: UInt, _: Int) => value, native).rows.head.operator.get
  }

  private def recordScalar[T <: BaseType](words: Vec[T], op: (T, T) => T): UnvalidatedBalancedCallback = {
    val native: ElabBalancedReduction.Native[T] = (values, operation, bridge) =>
      new TraversableOnceAnyPimped[T](values).reduceBalancedTree(operation, bridge)
    TypedBalancedReductionCapture(words, op, (value: T, _: Int) => value, native).rows.head.operator.get
  }

  private def certify(body: UnvalidatedBalancedCallback, captures: Vector[BaseType] = Vector.empty) =
    TypedBalancedReductionScalarGraphReplay.certify(body,
      body.operands.map(value => TypedBalancedReductionValueEvidence.input(value.asInstanceOf[BaseType])), captures)

  private def withWords(body: (Vec[UInt], ElabInt) => Unit): Unit = {
    val width = HdlInt.param("WIDTH", 5, 1, 32)
    val count = HdlInt.param("COUNT", 2, 1, 2)
    generate(new Component {
      val words = in(Vec(UInt(width bits), count))
      body(words, width.asElabInt)
    })
  }

  private def inferredMux(condition: Bool, yes: UInt, no: UInt): UInt =
    yes.wrapWithWeakClone(yes.newMultiplexer(condition, yes, no))

  private def code(expected: String)(body: => Any): Unit = {
    val error = intercept[IllegalArgumentException](body)
    assert(error.getMessage.contains(expected), error.getMessage)
  }

  test("ordered multi-node graphs replay every original operation without another callback invocation") {
    withWords { (words, _) =>
      var calls = 0
      val body = record(words, (a, b) => { calls += 1; (a + b) ^ a })
      val proof = certify(body)
      val prior = calls
      val output = proof.replay(words.vec(1), words.vec(0))
      assert(calls == prior)
      assert(output ne body.result)
      assert(ElabInt.equivalentExactFunction(ParameterizedWidth.expressionOf(output).get,
        ParameterizedWidth.expressionOf(words.vec.head).get))
      val xor = output.dlcLast.asInstanceOf[DataAssignmentStatement].source.asInstanceOf[Operator.UInt.Xor]
      val add = xor.left.asInstanceOf[BaseType].dlcLast.asInstanceOf[DataAssignmentStatement].source
        .asInstanceOf[Operator.UInt.Add]
      assert(add.left eq words.vec(1))
      assert(add.right eq words.vec(0))
      assert(xor.right eq words.vec(1))
    }
  }

  test("source-equivalent local aliases have equal normalized graph keys") {
    withWords { (words, _) =>
      val direct = certify(record(words, (a, b) => (a + b) ^ a))
      val aliased = certify(record(words, (a, b) => {
        val sum = UInt(); sum := a + b
        val result = UInt(); result := sum ^ a
        result
      }))
      assert(direct.operationKey == aliased.operationKey)
    }
  }

  test("subtraction is admitted without reassociation and ordered keys distinguish swapped inputs") {
    withWords { (words, _) =>
      val forward = certify(record(words, (a, b) => a - b))
      val reverse = certify(record(words, (a, b) => b - a))
      assert(forward.operationKey != reverse.operationKey)
      val replay = reverse.replay(words.vec(0), words.vec(1))
      val native = replay.dlcLast.asInstanceOf[DataAssignmentStatement].source.asInstanceOf[Operator.UInt.Sub]
      assert(native.left eq words.vec(1))
      assert(native.right eq words.vec(0))
    }
  }

  test("comparison and mux compositions preserve selector and arm order") {
    withWords { (words, _) =>
      val body = record(words, (a, b) => inferredMux(a === b, a + b, a ^ b))
      val proof = certify(body)
      val replay = proof.replay(words.vec(0), words.vec(1))
      val native = replay.dlcLast.asInstanceOf[DataAssignmentStatement].source.asInstanceOf[BinaryMultiplexerUInt]
      assert(native.cond.asInstanceOf[BaseType].dlcLast.asInstanceOf[DataAssignmentStatement].source
        .isInstanceOf[Operator.UInt.Equal])
      assert(native.whenTrue.asInstanceOf[BaseType].dlcLast.asInstanceOf[DataAssignmentStatement].source
        .isInstanceOf[Operator.UInt.Add])
      assert(native.whenFalse.asInstanceOf[BaseType].dlcLast.asInstanceOf[DataAssignmentStatement].source
        .isInstanceOf[Operator.UInt.Xor])
    }
  }

  test("unconditional default and exhaustive when alternatives lower to exact native muxes") {
    withWords { (words, _) =>
      val defaulted = certify(record(words, (a, b) => {
        val result = UInt(); result := b
        when(a > b) { result := a }
        result
      }))
      val exhaustive = certify(record(words, (a, b) => {
        val result = UInt()
        when(a > b) { result := a } otherwise { result := b }
        result
      }))
      assert(defaulted.operationKey == exhaustive.operationKey)
      assert(defaulted.replay(words.vec(0), words.vec(1)).dlcLast
        .asInstanceOf[DataAssignmentStatement].source.isInstanceOf[BinaryMultiplexerUInt])
    }
  }

  test("incomplete when is rejected before replay") {
    withWords { (words, _) =>
      val body = record(words, (a, b) => {
        val result = UInt()
        when(a > b) { result := a }
        result
      })
      code("INCOMPLETE-WHEN") { certify(body) }
      // Keep the test's deliberately incomplete native temporary from reaching
      // Spinal's later latch check after the intended certificate rejection.
      body.assignments.foreach(_.removeStatement())
      body.declarations.foreach(_.removeStatement())
      body.statements.collect { case value: WhenStatement => value }.reverse.foreach(_.removeStatement())
    }
  }

  test("captured hardware remains runtime input with exact identity in the graph key") {
    withWords { (words, width) =>
      val bias = in(UInt(width bits))
      val other = in(UInt(width bits))
      val body = record(words, (a, b) => (a + b) + bias)
      code("EXTERNAL-READ") { certify(body) }
      val proof = certify(body, Vector(bias))
      val changed = certify(record(words, (a, b) => (a + b) + other), Vector(other))
      assert(proof.operationKey != changed.operationKey)
      val replay = proof.replay(words.vec(0), words.vec(1))
      assert(replay.dlcLast.asInstanceOf[DataAssignmentStatement].source.asInstanceOf[Operator.UInt.Add].right eq bias)
      code("EXTERNAL-READ") { certify(body, Vector(other)) }
    }
  }

  test("typed resize carries a widened sum back to an exactly fixed result width") {
    withWords { (words, width) =>
      val body = record(words, (a, b) => {
        val sum = a.resize(width + 1) + b.resize(width + 1)
        val maximum = ~U(0).resize(width)
        inferredMux(sum > maximum.resize(width + 1), maximum, sum.resize(width))
      })
      val proof = certify(body)
      val replay = proof.replay(words.vec(0), words.vec(1))
      assert(replay.getBitsWidth == 5)
      assert(ElabInt.equivalentExactFunction(ParameterizedWidth.expressionOf(replay).get, width.expression))
    }
  }

  test("native concatenation and bit selection retain exact symbolic transfer") {
    withWords { (words, width) =>
      val concat = certify(record(words, (a, b) => (a.asBits ## b.asBits).asUInt.resize(width) ^ a))
      assert(concat.replay(words.vec(0), words.vec(1)).getBitsWidth == 5)
      val bit = certify(record(words, (a, b) => inferredMux(a(0), a, b)))
      assert(bit.replay(words.vec(0), words.vec(1)).getBitsWidth == 5)
      val part = certify(record(words, (a, b) => (a(0 downto 0).resize(width) ^ b)))
      assert(part.replay(words.vec(0), words.vec(1)).getBitsWidth == 5)
    }
  }

  test("a fixed selection valid only at the default width is rejected") {
    withWords { (words, _) =>
      val body = record(words, (a, b) => inferredMux(a(4), a, b))
      code("SELECT-DOMAIN") { certify(body) }
    }
  }

  test("fixed local witness widths cannot impersonate symbolic width authority") {
    withWords { (words, _) =>
      val body = record(words, (a, b) => {
        val result = UInt(5 bits); result := a + b; result
      })
      code("LOCAL-WIDTH") { certify(body) }
    }
  }

  test("changed expression children are detected even if assignment pointers stay identical") {
    withWords { (words, _) =>
      val body = record(words, (a, b) => (a + b) ^ a)
      val proof = certify(body)
      val native = body.result.asInstanceOf[BaseType].dlcLast.asInstanceOf[DataAssignmentStatement].source
        .asInstanceOf[Operator.UInt.Xor]
      val original = native.right
      native.right = words.vec(1)
      code("STALE") { proof.validateFreshness() }
      native.right = original
      proof.validateFreshness()
    }
  }

  test("changed literal values and resize sizes invalidate graph certificates") {
    withWords { (words, width) =>
      val body = record(words, (a, b) => (a + b + U(1)).resize(width))
      val proof = certify(body)
      val literal = body.assignments.map(_.source).collectFirst {
        case value: UIntLiteral => value
      }.get
      val originalLiteral = literal.value
      literal.value = BigInt(0)
      code("STALE") { proof.validateFreshness() }
      literal.value = originalLiteral
      proof.validateFreshness()
      val resize = body.result.asInstanceOf[BaseType].dlcLast.asInstanceOf[DataAssignmentStatement].source
        .asInstanceOf[ResizeUInt]
      val original = resize.size
      resize.size += 1
      code("STALE") { proof.validateFreshness() }
      resize.size = original
      proof.validateFreshness()
    }
  }

  test("nested conditional temporaries replay and changed when conditions invalidate the graph") {
    withWords { (words, _) =>
      val body = record(words, (a, b) => {
        val result = UInt()
        when(a > b) {
          val sum = a + b
          result := sum ^ a
        } otherwise {
          val difference = a - b
          result := difference ^ b
        }
        result
      })
      val proof = certify(body)
      assert(proof.replay(words.vec(0), words.vec(1)).getBitsWidth == 5)
      val conditional = body.statements.collectFirst { case value: WhenStatement => value }.get
      val original = conditional.cond
      conditional.cond = new BoolLiteral(false)
      code("STALE") { proof.validateFreshness() }
      conditional.cond = original
      proof.validateFreshness()
    }
  }

  test("distinct capture slots may bind the same exact read-only signal") {
    withWords { (words, width) =>
      val bias = in(UInt(width bits))
      val body = record(words, (a, b) => (a + b) + bias)
      val proof = certify(body, Vector(bias, bias))
      assert(proof.replay(words.vec(0), words.vec(1)).dlcLast.asInstanceOf[DataAssignmentStatement]
        .source.asInstanceOf[Operator.UInt.Add].right eq bias)
    }
  }

  test("capture width mutations invalidate exact read-only bindings") {
    withWords { (words, width) =>
      val bias = in(UInt(width bits))
      val proof = certify(record(words, (a, b) => (a + b) + bias), Vector(bias))
      bias.setWidth(4)
      code("VALUE-EVIDENCE") { proof.validateFreshness() }
      bias.setWidth(5)
      proof.validateFreshness()
    }
  }

  test("Bits Bool and SInt compositions preserve exact native scalar kinds") {
    val width = HdlInt.param("WIDTH", 5, 1, 32)
    val count = HdlInt.param("COUNT", 2, 1, 2)
    generate(new Component {
      val bits = in(Vec(Bits(width bits), count))
      val bools = in(Vec(Bool(), count))
      val signed = in(Vec(SInt(width bits), count))
      val bitProof = certify(recordScalar(bits, (a: Bits, b: Bits) => (a | b) & ~a))
      val boolProof = certify(recordScalar(bools, (a: Bool, b: Bool) => (a ^ b) && a))
      val signedProof = certify(recordScalar(signed,
        (a: SInt, b: SInt) => (a + b) ^ S(0).resize(width.asElabInt)))
      assert(bitProof.replay(bits.vec(0), bits.vec(1)).getTypeObject == TypeBits)
      assert(boolProof.replay(bools.vec(0), bools.vec(1)).getTypeObject == TypeBool)
      assert(signedProof.replay(signed.vec(0), signed.vec(1)).getTypeObject == TypeSInt)
    })
  }
}
