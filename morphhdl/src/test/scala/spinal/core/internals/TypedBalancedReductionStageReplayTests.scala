package spinal.core.internals

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib._
import morphhdl.frontend.HdlInt

class TypedBalancedReductionStageReplayTests extends AnyFunSuite {
  private def native[T <: BaseType]: ElabBalancedReduction.Native[T] =
    (values, op, bridge) => new TraversableOnceAnyPimped[T](values).reduceBalancedTree(op, bridge)

  private def withUInt(symbolic: Boolean = true, maximum: Int = 5)
      (body: Vec[UInt] => Unit): Unit = {
    val width = HdlInt.param("WIDTH", 5, 1, 32)
    val count = HdlInt.param("COUNT", 1, 1, maximum)
    SpinalConfig(targetDirectory = Files.createTempDirectory("balanced-stage-").toString,
      headerWithDate = false, headerWithRepoHash = false).generateVerilog(new Component {
      val words = if (symbolic) Vec(UInt(width bits), count) else Vec(UInt(5 bits), count)
      words.vec.foreach(_ := 0)
      body(words)
    })
  }

  private def capture(words: Vec[UInt],
      op: (UInt, UInt) => UInt = (a: UInt, b: UInt) => a + b,
      bridge: (UInt, Int) => UInt = (value: UInt, _: Int) => value
  ): TypedBalancedReductionStageReplay.Certificate[UInt] =
    TypedBalancedReductionStageReplay.capture(words, op, bridge, native[UInt])

  private def inferredRegister(value: UInt): UInt = {
    val result = UInt()
    result.setAsReg()
    result := value
    result.init(U(0))
    result
  }

  private def code(expected: String)(body: => Any): Unit = {
    val failure = intercept[IllegalArgumentException](body)
    assert(failure.getMessage.contains(expected), failure.getMessage)
  }

  test("all native stages carry the original independent WIDTH authority") {
    withUInt() { words =>
      val certificate = capture(words)
      assert(certificate.stages.map(_.geometry.level) == Vector(0, 1, 2))
      assert(certificate.stages.map(_.operators.size).sum == 4)
      assert(certificate.stages.map(_.bridges.size).sum == 6)
      val width = ParameterizedWidth.expressionOf(words.vec.head).get
      assert(ElabInt.equivalentExactFunction(certificate.resultEvidence.width, width))
      assert(certificate.resultEvidence.width.parameters.head eq width.parameters.head)
      assert(certificate.captured.rows.flatMap(_.operator).exists(record =>
        record.operands.exists(data => ParameterizedWidth.expressionOf(data.asInstanceOf[BaseType]).isEmpty)))
      certificate.requireFreshness()
    }
  }

  test("whole native replay never reruns either Scala callback") {
    withUInt() { words =>
      var operators = 0
      var bridges = 0
      val certificate = capture(words,
        (a: UInt, b: UInt) => { operators += 1; a + b },
        (value: UInt, _: Int) => { bridges += 1; value })
      for (count <- 1 to 5) {
        val result = certificate.replay(words.vec.take(count).toVector)
        assert(result.getBitsWidth == 5)
        assert(certificate.latencyFor(count) == 0)
      }
      assert(operators == 4 && bridges == 6)
    }
  }

  test("inferred zero-initialized registers preserve symbolic widths and odd-tail latency") {
    withUInt() { words =>
      val certificate = capture(words, bridge = (value: UInt, _: Int) => inferredRegister(value))
      for (count <- 1 to 5) {
        assert(certificate.latencyFor(count) == (BigInt(count) - 1).bitLength)
        val result = certificate.replay(words.vec.take(count).toVector)
        if (count == 1) assert(result eq words.vec.head) else assert(result.isReg)
      }
    }
  }

  test("ordinary concrete RegNext bridges retain the native register path") {
    withUInt(symbolic = false) { words =>
      val certificate = capture(words, bridge = (value: UInt, _: Int) => RegNext(value) init U(0, 5 bits))
      assert(certificate.stages.forall(_.registerCountPerRow == 1))
      assert(certificate.replay(words.vec.toVector).isReg)
      assert(certificate.captured.rows.head.operator.get.result.asInstanceOf[UInt].fixedWidth == 5)
      val width = ElabInt.literal(5).expression
      assert(TypedBalancedReductionValueEvidence.preservesFixedWidth(-1, 5, width))
      assert(!TypedBalancedReductionValueEvidence.preservesFixedWidth(-1, 6, width))
      assert(!TypedBalancedReductionValueEvidence.preservesFixedWidth(5, -1, width))
    }
  }

  test("level-dependent bridge latency follows the exact native zero-based levels") {
    withUInt() { words =>
      val certificate = capture(words, bridge = (value: UInt, level: Int) =>
        if (level == 0) value else inferredRegister(value))
      assert(certificate.stages.map(_.registerCountPerRow) == Vector(0, 1, 1))
      assert((1 to 5).map(certificate.latencyFor).toVector == Vector(0, 0, 1, 1, 2))
      certificate.replay(words.vec.toVector)
    }
  }

  test("multi-register chains are replayed in input-to-result order") {
    withUInt() { words =>
      val certificate = capture(words, bridge = (value: UInt, _: Int) =>
        inferredRegister(inferredRegister(value)))
      assert(certificate.stages.forall(_.registerCountPerRow == 2))
      assert(certificate.latencyFor(5) == 6)
      certificate.replay(words.vec.toVector)
    }
  }

  test("a different later pair operator cannot inherit the first pair proof") {
    withUInt() { words =>
      var index = 0
      code("STAGE-OPERATOR-NONUNIFORM") {
        capture(words, (a: UInt, b: UInt) => { index += 1; if (index == 2) a ^ b else a + b })
      }
    }
  }

  test("operator uniformity also covers later levels") {
    withUInt() { words =>
      var index = 0
      code("STAGE-OPERATOR-NONUNIFORM") {
        capture(words, (a: UInt, b: UInt) => { index += 1; if (index > 2) a | b else a + b })
      }
    }
  }

  test("an odd-tail bridge cannot silently differ from the paired rows") {
    withUInt() { words =>
      var index = 0
      code("STAGE-BRIDGE-NONUNIFORM") {
        capture(words, bridge = (value: UInt, _: Int) => {
          index += 1
          if (index == 3) inferredRegister(value) else value
        })
      }
    }
  }

  test("equal-looking replacement clock domains are not exact shared bridge authority") {
    withUInt() { words =>
      code("STAGE-BRIDGE-NONUNIFORM") {
        capture(words, bridge = (value: UInt, _: Int) => {
          val context = ClockDomain.current.copy().push()
          try inferredRegister(value) finally context.restore()
        })
      }
    }
  }

  test("later-level fixed aliases cannot specialize the original symbolic WIDTH") {
    withUInt() { words =>
      var index = 0
      code("REPLAY-FIXED-WIDTH") {
        capture(words, (a: UInt, b: UInt) => {
          index += 1
          if (index == 3) { val fixed = UInt(5 bits); fixed := a + b; fixed } else a + b
        })
      }
    }
  }

  test("native HardType cannot freeze a certified symbolic source width to its default") {
    withUInt() { words =>
      val width = ParameterizedWidth.expressionOf(words.vec.head).get
      assert(!TypedBalancedReductionValueEvidence.preservesFixedWidth(-1, 5, width))
      code("REPLAY-STALE-GRAPH") {
        capture(words, bridge = (value: UInt, _: Int) => RegNext(value) init U(0))
      }
    }
  }

  test("a sized initializer cannot force an inferred register to the WIDTH default") {
    withUInt() { words =>
      code("BRIDGE-INITIALIZER-WIDTH") {
        capture(words, bridge = (value: UInt, _: Int) => {
          val result = UInt(); result.setAsReg(); result := value
          result.init(U(0, 5 bits)); result
        })
      }
    }
  }

  test("nonzero bridge initializers are not silently generalized") {
    withUInt(symbolic = false) { words =>
      code("BRIDGE-INITIALIZER") {
        capture(words, bridge = (value: UInt, _: Int) => RegNext(value) init U(1, 5 bits))
      }
    }
  }

  test("arithmetic bridge bodies need a separate proof rather than identity replay") {
    withUInt() { words =>
      code("BRIDGE-EXPRESSION") {
        capture(words, bridge = (value: UInt, _: Int) => value + U(1))
      }
    }
  }

  test("native expression mutation invalidates a whole-stage certificate") {
    withUInt() { words =>
      val certificate = capture(words)
      val body = certificate.captured.rows(1).operator.get
      val add = body.assignments.head.source.asInstanceOf[Operator.UInt.Add]
      val saved = add.right
      add.right = add.left
      code("GRAPH-CHANGED") { certificate.requireFreshness() }
      add.right = saved
      certificate.requireFreshness()
    }
  }

  test("hidden bridge alias width-policy mutation invalidates its certificate") {
    withUInt() { words =>
      val certificate = capture(words, bridge = (value: UInt, _: Int) => {
        val register = inferredRegister(value)
        val alias = UInt(); alias := register; alias
      })
      val register = certificate.captured.rows.head.bridge.declarations.find(_.isReg).get.asInstanceOf[UInt]
      register.setWidth(5)
      code("BRIDGE-STALE-SHAPE") { certificate.requireFreshness() }
      register.unfixWidth()
      certificate.requireFreshness()
    }
  }

  test("same-named independent WIDTH parameters cannot be substituted during stage replay") {
    withUInt() { words =>
      val certificate = capture(words)
      val foreign = UInt(HdlInt.param("WIDTH", 5, 1, 32) bits)
      foreign := 0
      code("VALUE-EVIDENCE") { certificate.replay(Vector(foreign, words.vec(1))) }
    }
  }

  test("empty sizes and unrecorded native statement effects cannot acquire stage permission") {
    withUInt() { words =>
      val certificate = capture(words)
      code("STAGE-COUNT") { certificate.replay(Vector.empty) }
      code("STAGE-COUNT") { certificate.replay(words.vec.toVector :+ words.vec.head) }
      code("STAGE-COUNT") { certificate.latencyFor(6) }
      code("STAGE-STATEMENT-EFFECT") {
        capture(words, (a: UInt, b: UInt) => { spinal.core.assert(a === b); a + b })
      }
    }
  }

  test("singleton-only evidence invokes no callback and still cannot authorize publication") {
    withUInt(maximum = 1) { words =>
      val certificate = capture(words,
        (_: UInt, _: UInt) => fail("operator invoked"),
        (_: UInt, _: Int) => fail("bridge invoked"))
      assert(certificate.stages.isEmpty && certificate.operatorClass.isEmpty)
      assert(certificate.replay(words.vec.toVector) eq words.vec.head)
      code("STAGE-PUBLICATION-UNVALIDATED") { certificate.requirePublicationCertificate() }
    }
  }

  test("scalar Bits SInt and Bool reductions share the whole-stage proof path") {
    val count = HdlInt.param("COUNT", 1, 1, 5)
    SpinalConfig(targetDirectory = Files.createTempDirectory("balanced-stage-types-").toString,
      headerWithDate = false, headerWithRepoHash = false).generateVerilog(new Component {
      val bits = Vec(Bits(5 bits), count); bits.vec.foreach(_ := 0)
      val signed = Vec(SInt(5 bits), count); signed.vec.foreach(_ := 0)
      val flags = Vec(Bool(), count); flags.vec.foreach(_ := False)
      val a = TypedBalancedReductionStageReplay.capture(bits, (x: Bits, y: Bits) => x ^ y,
        (v: Bits, _: Int) => RegNext(v) init B(0), native[Bits])
      val b = TypedBalancedReductionStageReplay.capture(signed, (x: SInt, y: SInt) => x + y,
        (v: SInt, _: Int) => RegNext(v) init S(0), native[SInt])
      val c = TypedBalancedReductionStageReplay.capture(flags, (x: Bool, y: Bool) => x && y,
        (v: Bool, _: Int) => RegNext(v) init False, native[Bool])
      assert(a.replay(bits.vec.toVector).getTypeObject == TypeBits)
      assert(b.replay(signed.vec.toVector).getTypeObject == TypeSInt)
      assert(c.replay(flags.vec.toVector).getTypeObject == TypeBool)
    })
  }

  test("operator input evidence cannot be relabelled onto equal-width different values") {
    withUInt() { words =>
      val certificate = capture(words)
      val body = certificate.captured.rows.head.operator.get
      val evidence = Vector(TypedBalancedReductionValueEvidence.input(words.vec(1)),
        TypedBalancedReductionValueEvidence.input(words.vec(0)))
      code("VALUE-EVIDENCE") { TypedBalancedReductionOperatorReplay.certify(body, evidence) }
    }
  }
}
