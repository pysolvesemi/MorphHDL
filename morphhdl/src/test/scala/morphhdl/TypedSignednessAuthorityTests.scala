package spinal.core.internals

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer
import org.scalatest.funsuite.AnyFunSuite
import morphhdl.MorphVerilog
import morphhdl.frontend.HdlInt
import morphhdl.analysis.SignednessFacts
import morphhdl.analysis.SignednessFacts.{Cast => CastRule, Resize => ResizeRule, _}
import spinal.core._
import MorphHdlSignednessAnalysis._
import nativeapplication.{SIntSignedVerilogBaselineFixture, SIntSignedVerilogBaselineArtifactWriter}

final class TypedSignednessAuthorityTests extends AnyFunSuite {
  private def directory(body: Path => Unit): Unit = {
    val root = Files.createTempDirectory("signedness-authority-")
    try body(root)
    finally {
      val stream = Files.walk(root)
      try stream.iterator.asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists(_))
      finally stream.close()
    }
  }

  /** Raw expression tests run with the ordinary inferred-width context live.
    * These nodes are not attached to the DUT and do not change its publication.
    */
  private def inNativeContext(body: => Unit): Unit = directory { root =>
    val config = SpinalConfig(targetDirectory = root.toString)
    config.phasesInserters += install(_ => body)
    SpinalVerilog(config)(new Component {
      val input = in(Bool())
      val output = out(Bool())
      output := input
    })
  }

  private def sint(value: Int = -3, bits: Int = 8): SIntLiteral = {
    val x = new SIntLiteral
    x.value = BigInt(value); x.poisonMask = BigInt(0); x.bitCount = bits
    x
  }
  private def uint(value: Int = 3, bits: Int = 8): UIntLiteral = {
    val x = new UIntLiteral
    x.value = BigInt(value); x.poisonMask = BigInt(0); x.bitCount = bits
    x
  }
  private def sized[T <: Expression](x: T, bits: Int): T = {
    x match { case w: Widthable => w.inferredWidth = bits; case _ => () }
    x
  }
  private def binary[T <: BinaryOperator](x: T, a: Expression, b: Expression, bits: Int = 8): T = {
    x.left = a.asInstanceOf[x.T]; x.right = b.asInstanceOf[x.T]; sized(x, bits)
  }
  private def unary[T <: UnaryOperator](x: T, a: Expression, bits: Int = 8): T = {
    x.source = a.asInstanceOf[x.T]; sized(x, bits)
  }
  private def fact(x: Expression): Fact = {
    val snapshot = expressions(Vector(x))
    snapshot.validate(x, snapshot.expression(x), ExpressionUse)
  }
  private def rejected(code: String)(body: => Any): Unit = {
    val error = intercept[MorphHdlSignednessException](body)
    assert(error.code == "MORPH-SIGNEDNESS-" + code)
  }

  test("target-neutral transfer matrix preserves unknowns and mixed boundaries") {
    val kinds = Vector(SignedScalar, UnsignedScalar, UnsignedAggregate, BooleanValue, Unknown)
    for (left <- kinds; right <- kinds) {
      val inputs = Vector(left, right)
      val signed = SignednessFacts.transfer(Arithmetic, SignedScalar, inputs)
      assert((signed == SignedScalar) == (left == SignedScalar && right == SignedScalar))
      val mux = SignednessFacts.transfer(Mux, SignedScalar, inputs)
      assert((mux == SignedScalar) == (left == SignedScalar && right == SignedScalar))
      assert(SignednessFacts.transfer(Comparison, BooleanValue, inputs) == BooleanValue)
      assert(SignednessFacts.transfer(Concatenation, SignedScalar, inputs) == UnsignedAggregate)
      assert(SignednessFacts.transfer(Shift, SignedScalar, inputs) == left)
    }
    assert(SignednessFacts.transfer(Arithmetic, SignedScalar, Vector.empty) == Unknown)
    assert(SignednessFacts.transfer(CastRule, SignedScalar, Vector(Unknown)) == Unknown)
    assert(SignednessFacts.transfer(Selection, SignedScalar, Vector(SignedScalar)) == UnsignedScalar)
    assert(SignednessFacts.requirements(ResizeRule).contains(ResizeBoundary))
  }

  test("literal authority comes from explicit type and width, never numeric sign") {
    inNativeContext {
      for (width <- Vector(1, 5, 8, 32)) {
        val negative = sint(-1, width)
        val positive = sint(0, width)
        assert(fact(negative).value == SignedScalar)
        assert(fact(positive).value == SignedScalar)
        assert(fact(negative).width == Fixed(width))
        assert(fact(uint(0, width)).value == UnsignedScalar)
      }
      val unsized = sint(); unsized.hasSpecifiedBitCount = false
      val poison = sint(); poison.poisonMask = BigInt(1)
      for (value <- Vector(unsized, poison)) {
        val snapshot = expressions(Vector(value))
        assert(snapshot.facts.head.value == Unknown)
        rejected("UNKNOWN-FACT")(snapshot.requireKnown(value, snapshot.expression(value), ExpressionUse))
      }
      assert(fact(new BoolLiteral(true)).value == BooleanValue)
    }
  }

  test("reviewed arithmetic and unary operations retain width and sizing obligations") {
    inNativeContext {
      val a = sint(); val b = sint(2)
      val ops = Vector(
        binary(new Operator.SInt.Add, a, b), binary(new Operator.SInt.Sub, a, b),
        binary(new Operator.SInt.Mul, a, b, 16), binary(new Operator.SInt.Div, a, b),
        binary(new Operator.SInt.Mod, a, b), binary(new Operator.SInt.And, a, b),
        binary(new Operator.SInt.Or, a, b), binary(new Operator.SInt.Xor, a, b),
        unary(new Operator.SInt.Minus, a), unary(new Operator.SInt.Not, a))
      ops.foreach { x =>
        assert(fact(x).value == SignedScalar)
        assert(fact(x).requirements.contains(OperandSizing))
      }
      assert(fact(ops(2)).width == Sum(Vector(Fixed(8), Fixed(8))))
      assert(fact(binary(new Operator.SInt.Add, a, uint())).value == Unknown)
      assert(fact(binary(new Operator.UInt.Add, a, b)).value == Unknown)
      assert(fact(binary(new Operator.UInt.Add, uint(), uint())).value == UnsignedScalar)
      assert(fact(binary(new Operator.SInt.Smaller, a, b, 1)).value == BooleanValue)
      assert(fact(binary(new Operator.SInt.Equal, a, b, 1)).value == BooleanValue)
      assert(fact(unary(new Operator.BitVector.orR, a, 1)).value == BooleanValue)
    }
  }

  test("fixed and dynamic shifts use the left operand, not the amount") {
    inNativeContext {
      val a = sint()
      assert(fact(unary(new Operator.SInt.ShiftRightByIntFixedWidth(2), a)).value == SignedScalar)
      val shrinking = unary(new Operator.SInt.ShiftRightByInt(2), a, 6)
      assert(fact(shrinking).width == Maximum(Vector(Fixed(0), Difference(Fixed(8), Fixed(2)))))
      assert(fact(binary(new Operator.SInt.ShiftRightByUInt, a, uint(2, 2))).value == SignedScalar)
      val growing = binary(new Operator.SInt.ShiftLeftByUInt, a, uint(2, 2), 11)
      val snapshot = expressions(Vector(growing))
      assert(snapshot.facts.head.width == UnknownWidth)
      rejected("UNKNOWN-FACT")(snapshot.requireKnown(growing, snapshot.expression(growing), ExpressionUse))
    }
  }

  test("mux joins value alternatives and keeps its selector as a separate dependency") {
    inNativeContext {
      val mux = new BinaryMultiplexerSInt
      mux.cond = new BoolLiteral(true); mux.whenTrue = sint(); mux.whenFalse = sint(2)
      sized(mux, 8)
      assert(fact(mux).value == SignedScalar)
      mux.whenFalse = uint()
      assert(fact(mux).value == Unknown)
      val multiple = new MultiplexerSInt
      multiple.select = uint(0, 1)
      multiple.inputs = ArrayBuffer[Expression with WidthProvider](sint(), sint(2))
      sized(multiple, 8)
      assert(fact(multiple).value == SignedScalar)
    }
  }

  test("explicit casts preserve conversion boundaries and selection loses signed interpretation") {
    inNativeContext {
      val select = new SIntRangedAccessFixed
      select.source = sint(); select.hi = 3; select.lo = 0
      assert(fact(select).intent == SignedScalar)
      assert(fact(select).value == UnsignedScalar)
      assert(fact(select).requirements.contains(SelectedBits))
      val cast = new CastUIntToSInt
      cast.input = uint().asInstanceOf[cast.T]
      sized(cast, 8)
      assert(fact(cast).value == SignedScalar)
      assert(fact(cast).requirements.contains(ExplicitConversion))
      val unsigned = new CastSIntToUInt; unsigned.input = sint().asInstanceOf[unsigned.T]; sized(unsigned, 8)
      assert(fact(unsigned).value == UnsignedScalar)
      assert(fact(unsigned).requirements.contains(ExplicitConversion))
      val cat = binary(new Operator.Bits.Cat, sint(), sint(), 16)
      assert(fact(cat).value == UnsignedAggregate)
      assert(fact(unary(new Operator.SInt.Repeat(2), sint(), 16)).value == UnsignedAggregate)
      val narrow = new ResizeSInt; narrow.input = sint(); narrow.size = 4
      val wide = new ResizeSInt; wide.input = sint(); wide.size = 11
      for (resize <- Vector(narrow, wide)) {
        assert(fact(resize).intent == SignedScalar)
        assert(fact(resize).value == Unknown)
        assert(fact(resize).requirements.contains(ResizeBoundary))
      }
      val equal = new ResizeSInt; equal.input = sint(); equal.size = 8
      assert(fact(equal).value == SignedScalar)
      assert(fact(equal).requirements.contains(ResizeBoundary))
    }
  }

  private class PretendSInt extends Expression with WidthProvider {
    override def opName: String = "SInt"
    override def getTypeObject: Any = TypeSInt
    override def getWidth: Int = 8
    override def foreachExpression(f: Expression => Unit): Unit = ()
    override def remapExpressions(f: Expression => Expression): Unit = ()
  }

  test("unknown expressions and downstream arithmetic subclasses fail closed") {
    inNativeContext {
      val fake = new PretendSInt
      assert(fact(fake).intent == Unknown)
      assert(fact(fake).value == Unknown)
      val disguised = binary(new Operator.SInt.Add {}, sint(), sint())
      assert(fact(disguised).value == Unknown)
      val snapshot = expressions(Vector(fake))
      rejected("UNKNOWN-FACT")(snapshot.requireKnown(fake, snapshot.temporary(fake), TemporaryUse))
    }
  }

  test("foreign sessions, same-valued nodes and wrong use roles cannot reuse evidence") {
    inNativeContext {
      val a = sint(); val b = sint()
      val first = expressions(Vector(a, b)); val second = expressions(Vector(a, b))
      val proof = first.expression(a)
      rejected("FOREIGN-EVIDENCE")(second.validate(a, proof, ExpressionUse))
      rejected("USE-IDENTITY")(first.validate(b, proof, ExpressionUse))
      rejected("USE-IDENTITY")(first.validate(a, proof, TemporaryUse))
      rejected("FOREIGN-SUBJECT")(first.expression(sint()))
      rejected("NULL-SUBJECT")(first.expression(null))
      assert(first.facts.head.copy(value = UnsignedScalar).value == UnsignedScalar)
      assert(first.validate(a, proof, ExpressionUse).value == SignedScalar)
    }
  }

  test("cast-use evidence binds exact parent and operand slot") {
    inNativeContext {
      val a = sint(); val b = sint()
      val add = binary(new Operator.SInt.Add, a, b)
      val subtract = binary(new Operator.SInt.Sub, a, b)
      val snapshot = expressions(Vector(add, subtract))
      val proof = snapshot.castOperand(add, 0)
      assert(snapshot.validateCastOperand(add, 0, proof).value == SignedScalar)
      rejected("OPERAND-IDENTITY")(snapshot.validateCastOperand(subtract, 0, proof))
      rejected("OPERAND-IDENTITY")(snapshot.validateCastOperand(add, 1, proof))
      rejected("OPERAND-SLOT")(snapshot.castOperand(add, 2))
      add.left = b
      rejected("STALE-EVIDENCE")(snapshot.validateCastOperand(add, 0, proof))
    }
  }

  test("deep operand and literal mutations invalidate an existing root fact") {
    inNativeContext {
      val literal = sint()
      val inner = binary(new Operator.SInt.Sub, literal, sint(2))
      val outer = binary(new Operator.SInt.Add, inner, sint(4))
      val snapshot = expressions(Vector(outer)); val proof = snapshot.expression(outer)
      literal.value = BigInt(5)
      rejected("STALE-EVIDENCE")(snapshot.validate(outer, proof, ExpressionUse))
      literal.value = BigInt(-3)
      assert(snapshot.validate(outer, proof, ExpressionUse).value == SignedScalar)
      inner.right = uint()
      rejected("STALE-EVIDENCE")(snapshot.validate(outer, proof, ExpressionUse))
    }
  }

  test("non-reference expression cycles are rejected without walking register drivers") {
    inNativeContext {
      val loop = unary(new Operator.SInt.Not, sint())
      loop.source = loop
      rejected("EXPRESSION-CYCLE")(expressions(Vector(loop)))
    }
  }

  test("typed widths retain exact identity even with independent same-named roots") {
    directory { root =>
      var left: SInt = null; var right: SInt = null
      val config = SpinalConfig(targetDirectory = root.toString)
      config.phasesInserters += install { snapshot =>
        val l = snapshot.declaration(left); val r = snapshot.declaration(right)
        val lw = snapshot.retainedWidths(left, l, DeclarationUse).head
        val rw = snapshot.retainedWidths(right, r, DeclarationUse).head
        assert(lw ne rw)
        assert(lw.parameterRoots.head ne rw.parameterRoots.head)
        assert(snapshot.validate(left, l, DeclarationUse).width != snapshot.validate(right, r, DeclarationUse).width)
        assert(snapshot.retainedWidths(left, l, DeclarationUse).head eq ParameterizedWidth.expressionOf(left).get)
        val leftKey = snapshot.validate(left, l, DeclarationUse).width.asInstanceOf[Retained].key
        val rightKey = snapshot.validate(right, r, DeclarationUse).width.asInstanceOf[Retained].key
        assert(snapshot.widthSource(left, l, DeclarationUse, leftKey) eq lw)
        rejected("WIDTH-USE-IDENTITY")(snapshot.widthSource(left, l, DeclarationUse, rightKey))
        rejected("USE-IDENTITY")(snapshot.validate(right, l, DeclarationUse))
        val saved = left.fixedWidth
        left.setWidth(saved + 1)
        rejected("STALE-EVIDENCE")(snapshot.validate(left, l, DeclarationUse))
        left.setWidth(saved)
      }
      SpinalVerilog(config)(new Component {
        val firstWidth = HdlInt.param("WIDTH", 8, 1, 32)
        val otherWidth = HdlInt.param("WIDTH", 8, 1, 32)
        val a = in(SInt(firstWidth bits)); val b = in(SInt(otherWidth bits))
        val x = out(SInt(firstWidth bits)); val y = out(SInt(otherWidth bits))
        x := a; y := b
        left = a; right = b
      })
    }
  }

  test("scalar memory elements and local hierarchy ports keep separate transport facts") {
    directory { root =>
      var dut: SIntSignedVerilogBaselineFixture.Top = null
      var observed = false
      val config = SpinalConfig(targetDirectory = root.toString)
      config.phasesInserters += install { snapshot =>
        observed = true
        val memory = dut.sequential.signedMemory
        assert(snapshot.validate(memory, snapshot.memoryElement(memory), MemoryElementUse).value == SignedScalar)
        val ports = ArrayBuffer.empty[MemPortStatement]
        memory.foreachStatements(ports += _)
        val read = ports.collectFirst { case port: MemReadSync => port }.get
        val readFact = snapshot.validate(read, snapshot.expression(read), ExpressionUse)
        assert(readFact.value == UnsignedScalar)
        assert(readFact.requirements.contains(MemoryTransport))
        for (port <- Vector(dut.child.dout, dut.external.dout)) {
          val f = snapshot.validate(port, snapshot.declaration(port), DeclarationUse)
          assert(f.value == SignedScalar)
          assert(f.requirements.contains(HierarchyBoundary))
        }
        assert(snapshot.validate(dut.sequential.signedRegister,
          snapshot.declaration(dut.sequential.signedRegister), DeclarationUse).value == SignedScalar)
      }
      SpinalVerilog(config) { dut = SIntSignedVerilogBaselineFixture.fixed(); dut }
      assert(observed)
    }
  }

  test("one-field Bundle memory is not a scalar and analysis does not reevaluate HardType") {
    directory { root =>
      var memory: Mem[Bundle] = null
      var calls = 0; var expectedCalls = 0
      val config = SpinalConfig(targetDirectory = root.toString)
      config.phasesInserters += install { snapshot =>
        assert(calls == expectedCalls)
        val f = snapshot.validate(memory, snapshot.memoryElement(memory), MemoryElementUse)
        assert(f.value == UnsignedAggregate)
        assert(calls == expectedCalls)
      }
      SpinalVerilog(config)(new Component {
        val address = in(UInt(2 bits))
        val result = out(SInt(8 bits))
        val word = HardType[Bundle] {
          calls += 1
          new Bundle { val payload = SInt(8 bits) }
        }
        val mem = Mem(word, 4)
        val read = mem.readAsync(address)
        result := read.flatten.head.asInstanceOf[SInt]
        memory = mem
        expectedCalls = calls
      })
    }
  }

  test("packed Vec width retains independent element-width and depth identities") {
    directory { root =>
      var vector: Vec[SInt] = null; var packed: Bits = null
      val config = SpinalConfig(targetDirectory = root.toString)
      config.phasesInserters += install { snapshot =>
        val f = snapshot.validate(vector, snapshot.aggregate(vector), AggregateUse)
        assert(f.value == UnsignedAggregate)
        assert(f.width.isInstanceOf[Product])
        val p = snapshot.validate(packed, snapshot.declaration(packed), DeclarationUse)
        assert(p.value == UnsignedAggregate)
        assert(p.width.isInstanceOf[Product])
        assert(snapshot.validate(vector(0), snapshot.declaration(vector(0)), DeclarationUse).value == SignedScalar)
      }
      SpinalVerilog(config)(new Component {
        val width = HdlInt.param("WIDTH", 8, 1, 32)
        val depth = HdlInt.param("DEPTH", 2, 2, 4)
        val input = in(Vec(SInt(width bits), depth))
        val bits = input.asBits.setName("packed_value").dontSimplifyIt()
        val output = out(cloneOf(bits))
        output := bits
        vector = input; packed = bits
      })
    }
  }

  test("snapshot replay is deterministic and signal renaming does not provide authority") {
    def generate(root: Path, name: String): String = {
      var result = ""
      var input: SInt = null
      val config = SpinalConfig(targetDirectory = root.toString)
      config.phasesInserters += install { snapshot =>
        val proof = snapshot.declaration(input)
        val before = snapshot.validate(input, proof, DeclarationUse)
        val old = input.getName()
        input.setName("renamed_signed_signal")
        assert(snapshot.validate(input, proof, DeclarationUse) == before)
        input.setName(old)
        result = snapshot.replay
      }
      SpinalVerilog(config)(new Component {
        setDefinitionName("RenamedAuthority")
        val a = in(SInt(8 bits)).setName(name)
        val b = out(SInt(8 bits))
        b := a
        input = a
      })
      result
    }
    directory { root =>
      assert(generate(root.resolve("a"), "first") == generate(root.resolve("b"), "second"))
    }
  }

  test("invalid phase plans fail closed instead of capturing an unvalidated graph") {
    rejected("PHASE-PLAN")(install(_ => ())(ArrayBuffer.empty[Phase]))
    rejected("PHASE-PLAN")(install(null)(ArrayBuffer.empty[Phase]))
    rejected("NULL-TOP")(capture(null))
    rejected("NULL-SUBJECT")(expressions(Vector(null)))
  }

  test("observing the same ordinary fixture leaves native and parameterized Verilog byte-identical") {
    directory { root =>
      val baseline = root.resolve("baseline")
      SIntSignedVerilogBaselineArtifactWriter.main(Array(baseline.toString))
      for (parameterized <- Vector(false, true)) {
        val target = root.resolve(if (parameterized) "parameterized" else "fixed")
        val filename = if (parameterized) "sint_cast_heavy_parameterized.v" else "sint_cast_heavy_fixed.v"
        val config = SpinalConfig(targetDirectory = target.toString)
        config.netlistFileName = filename
        var count = 0
        config.phasesInserters += install { snapshot =>
          count += 1
          assert(snapshot.facts.nonEmpty)
          assert(snapshot.facts.exists(_.value == SignedScalar))
          assert(snapshot.facts.exists(_.rule == MemoryElement))
        }
        if (parameterized) MorphVerilog(config)(SIntSignedVerilogBaselineFixture.parameterized())
        else SpinalVerilog(config)(SIntSignedVerilogBaselineFixture.fixed())
        def normalize(path: Path): String = {
          val text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
          text.replaceFirst("(?s)\\A// Generator :[^\\n]*\\n// Component :[^\\n]*\\n// Git hash  :[^\\n]*\\n\\n", "")
        }
        assert(count >= 1)
        assert(normalize(target.resolve(filename)) == normalize(baseline.resolve(filename)))
      }
    }
  }
}
