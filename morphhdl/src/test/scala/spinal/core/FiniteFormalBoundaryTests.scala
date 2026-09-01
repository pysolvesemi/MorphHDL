package spinal.core

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicInteger

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer

import org.scalatest.funsuite.AnyFunSuite

import morphhdl.MorphVerilog
import morphhdl.frontend.{formalParam, HdlInt}
import morphhdl.frontend._
import spinal.core.internals.{Phase, PhaseContext, PhaseMisc, PhaseVerilog}
import spinal.lib.CountOne

private object FiniteFormalBoundaryFixture {
  final case class FormalGraph(
      formalName: String,
      formalDefault: BigInt,
      formalMinimum: BigInt,
      formalMaximum: BigInt,
      actualVerilog: String,
      actualDefault: BigInt,
      actualMinimum: BigInt,
      actualMaximum: BigInt,
      actualParameters: Int,
      actualRoots: Int,
      inputWidth: Int,
      outputWidth: Int,
      inputWidthExpression: String,
      outputWidthExpression: String
  )

  final class TypedLiteralChild(width: ElabInt) extends Component {
    setDefinitionName("FiniteFormalLiteralChild")
    val din = in(spinal.core.Bits(width bits)).setName("din")
    val dout = out(spinal.core.Bits(width bits)).setName("dout")
    dout := ~din
  }

  final class TypedLiteralTop(actual: ElabInt) extends Component {
    setDefinitionName("FiniteFormalLiteralTop")
    val din = in(spinal.core.Bits(8 bits)).setName("din")
    val dout = out(spinal.core.Bits(8 bits)).setName("dout")
    val child = ElabFormalComponent
      .parameter(actual, "CHILD_WIDTH", BigInt(1), BigInt(16))(width => new TypedLiteralChild(width))
      .setName("child")
    child.din := din
    dout := child.dout
  }

  final class EstablishedLiteralChild(actual: HdlInt) extends Component {
    setDefinitionName("FiniteFormalLiteralChild")
    @dontName
    private val width =
      formalParam(actual, "CHILD_WIDTH", BigInt(1), BigInt(16))
    val din = in(morphhdl.frontend.Bits(width bits)).setName("din")
    val dout = out(morphhdl.frontend.Bits(width bits)).setName("dout")
    dout := ~din
  }

  final class EstablishedLiteralTop(actual: HdlInt) extends Component {
    setDefinitionName("FiniteFormalLiteralTop")
    val din = in(spinal.core.Bits(8 bits)).setName("din")
    val dout = out(spinal.core.Bits(8 bits)).setName("dout")
    val child = new EstablishedLiteralChild(actual).setName("child")
    child.din := din
    dout := child.dout
  }

  final class CorrelatedFormalTop(actual: ElabInt) extends Component {
    setDefinitionName("CorrelatedFormalTop")
    val din = in(spinal.core.Bits(actual bits)).setName("din")
    val dout = out(spinal.core.Bits(actual bits)).setName("dout")
    val child = ElabFormalComponent
      .parameter(actual, "CHILD_WIDTH", BigInt(1), BigInt(8))(width => new TypedLiteralChild(width))
      .setName("child")
    child.din := din
    dout := child.dout
  }

  final class TypedTokenLeaf(width: ElabInt) extends Component {
    setDefinitionName("TypedTokenLeaf")
    val din = in(spinal.core.Bits(width bits)).setName("din")
    val dout = out(spinal.core.Bits(width bits)).setName("dout")
    dout := ~din
  }

  final class SameClassTypedTokenTop(leftActual: ElabInt, rightActual: ElabInt) extends Component {
    setDefinitionName("SameClassTypedTokenTop")
    val leftIn = in(spinal.core.Bits(leftActual bits)).setName("leftIn")
    val rightIn = in(spinal.core.Bits(rightActual bits)).setName("rightIn")
    val leftOut = out(spinal.core.Bits(leftActual bits)).setName("leftOut")
    val rightOut = out(spinal.core.Bits(rightActual bits)).setName("rightOut")
    val left = ElabFormalComponent
      .parameter(leftActual, "CHILD_WIDTH", BigInt(1), BigInt(8))(width => new TypedTokenLeaf(width))
      .setName("left")
    val right = ElabFormalComponent
      .parameter(rightActual, "CHILD_WIDTH", BigInt(1), BigInt(8))(width => new TypedTokenLeaf(width))
      .setName("right")
    left.din := leftIn
    right.din := rightIn
    leftOut := left.dout
    rightOut := right.dout
  }

  final class TypedTokenLayoutLeafA(width: ElabInt) extends Component {
    setDefinitionName("TypedTokenLayoutLeaf")
    val din = in(spinal.core.Bits(width bits)).setName("din")
    val dout = out(spinal.core.Bits(width bits)).setName("dout")
    dout := ~din
  }

  final class TypedTokenLayoutLeafB(width: ElabInt) extends Component {
    setDefinitionName("TypedTokenLayoutLeaf")
    val din = in(spinal.core.Bits(width bits)).setName("din")
    val dout = out(spinal.core.Bits(width bits)).setName("dout")
    dout := ~din
  }

  final class TypedTokenDifferentLayoutLeaf(width: ElabInt) extends Component {
    setDefinitionName("TypedTokenLayoutLeaf")
    val din = in(spinal.core.Bits(width bits)).setName("din")
    val dout = out(spinal.core.Bits(width bits)).setName("dout")
    val marker = out(Bool()).setName("marker")
    dout := ~din
    marker := din.orR
  }

  final class DifferentClassTypedTokenTop(actual: ElabInt) extends Component {
    setDefinitionName("DifferentClassTypedTokenTop")
    val din = in(spinal.core.Bits(actual bits)).setName("din")
    val leftOut = out(spinal.core.Bits(actual bits)).setName("leftOut")
    val rightOut = out(spinal.core.Bits(actual bits)).setName("rightOut")
    val left = ElabFormalComponent
      .parameter(actual, "CHILD_WIDTH", BigInt(1), BigInt(8))(width => new TypedTokenLayoutLeafA(width))
      .setName("left")
    val right = ElabFormalComponent
      .parameter(actual, "CHILD_WIDTH", BigInt(1), BigInt(8))(width => new TypedTokenLayoutLeafB(width))
      .setName("right")
    left.din := din
    right.din := din
    leftOut := left.dout
    rightOut := right.dout
  }

  final class DifferentClassDifferentLayoutTop(actual: ElabInt) extends Component {
    setDefinitionName("DifferentClassDifferentLayoutTop")
    val din = in(spinal.core.Bits(actual bits)).setName("din")
    val leftOut = out(spinal.core.Bits(actual bits)).setName("leftOut")
    val rightOut = out(spinal.core.Bits(actual bits)).setName("rightOut")
    val marker = out(Bool()).setName("marker")
    val left = ElabFormalComponent
      .parameter(actual, "CHILD_WIDTH", BigInt(1), BigInt(8))(width => new TypedTokenLayoutLeafA(width))
      .setName("left")
    val right = ElabFormalComponent
      .parameter(actual, "CHILD_WIDTH", BigInt(1), BigInt(8))(width => new TypedTokenDifferentLayoutLeaf(width))
      .setName("right")
    left.din := din
    right.din := din
    leftOut := left.dout
    rightOut := right.dout
    marker := right.marker
  }

  /** Exercise the registry directly after the ordinary typed boundary has
    * installed its one exact capability. A rejected duplicate typed claim must
    * leave the original opaque capability and its exact port tokens unchanged.
    */
  final class TypedRegistryAtomicityTop(actual: ElabInt) extends Component {
    setDefinitionName("TypedRegistryAtomicityTop")
    val din = in(spinal.core.Bits(actual bits)).setName("din")
    val dout = out(spinal.core.Bits(actual bits)).setName("dout")
    val child = ElabFormalComponent
      .parameter(actual, "CHILD_WIDTH", BigInt(1), BigInt(8))(width => new TypedTokenLeaf(width))
      .setName("child")
    child.din := din
    dout := child.dout

    val originalCapability = typedCapabilityOf(child)
    val originalPortTokens =
      Vector(child.din, child.dout).map(port => typedPortTokenOf(port))

    val duplicateFormal = ElaborationIntegerParameter(
      "SECOND_WIDTH",
      originalCapability.binding.formal.default,
      originalCapability.binding.formal.minimum,
      originalCapability.binding.formal.maximum
    )
    val duplicateFailure = caughtParameterizedFailure {
      ExternalFormalParameterRegistry.retainTypedComponent(
        child,
        duplicateFormal,
        originalCapability.binding.actual,
        Some("<duplicate-typed-formal-test>")
      )
    }
    val capabilitiesAfterDuplicate =
      ExternalFormalParameterRegistry.typedBindingsOf(child)
    val portTokensAfterDuplicate =
      Vector(child.din, child.dout).map(port => typedPortTokenOf(port))
  }

  final class TypedFiniteDefaultTop(count: ElabInt) extends Component {
    setDefinitionName("TypedFiniteDefaultTop")
    val source = in(Bool()).setName("source")
    val observed = out(Bool()).setName("observed")
    observed := source

    ElabFiniteRange.foreach(count, "typed_default_range") { _ =>
      val replica = Bool().setName("typed_replica")
      replica := source
      replica.dontSimplifyIt()
    }
  }

  final class CorrelatedFiniteTop(count: ElabInt) extends Component {
    setDefinitionName("CorrelatedFiniteTop")
    val source = in(spinal.core.Bits(count bits)).setName("source")
    val folded = out(UInt(3 bits)).setName("folded")
    val passthrough = in(Bool()).setName("passthrough")
    val observed = out(Bool()).setName("observed")
    folded := ElabFiniteRange.countOne(source, count)(CountOne(source)).resized
    observed := passthrough

    ElabFiniteRange.foreach(count, "correlated_finite_range") { _ =>
      val replica = Bool().setName("correlated_replica")
      replica := passthrough
      replica.dontSimplifyIt()
    }
  }

  final class HdlStructuralDefaultTop(count: HdlInt) extends Component {
    setDefinitionName("HdlStructuralDefaultTop")
    val source = in(Bool()).setName("source")
    val observed = out(Bool()).setName("observed")
    observed := source

    (0 until count).named("g_hdl_default", "hdl_default_index").foreach { _ =>
      val replica = Bool().setName("hdl_replica")
      replica := source
      replica.dontSimplifyIt()
    }
  }

  final class HdlProceduralDefaultTop(count: HdlInt) extends Component {
    setDefinitionName("HdlProceduralDefaultTop")
    val din = in(spinal.core.Bits(4 bits)).setName("din")
    val dout = out(spinal.core.Bits(4 bits)).setName("dout")
    dout := 0

    (0 until count).named("p_hdl_default", "hdl_default_bit").foreach { index =>
      val one = HdlInt.literal(BigInt(1))
      val offset = index * one
      dout(offset, one) := din(offset, one)
    }
  }

  final class InexactForeachTop(count: ElabInt) extends Component {
    val observed = out(Bool())
    observed := False
    ElabFiniteRange.foreach(count, "inexact_foreach") { _ =>
      val unreachable = Bool()
      unreachable := True
      unreachable.dontSimplifyIt()
    }
  }

  final class InexactCountOneTop(count: ElabInt) extends Component {
    val source = in(spinal.core.Bits(4 bits))
    val observed = out(UInt(3 bits))
    observed := ElabFiniteRange.countOne(source, count)(CountOne(source)).resized
  }

  def formalGraph(child: Component, din: Bits, dout: Bits): FormalGraph = {
    val binding = ExternalFormalParameterRegistry.bindingsOf(child) match {
      case Vector(value) => value
      case other =>
        throw new IllegalStateException(
          s"literal formal child retained ${other.size} component bindings"
        )
    }
    FormalGraph(
      formalName = binding.formal.name,
      formalDefault = binding.formal.default,
      formalMinimum = binding.formal.minimum,
      formalMaximum = binding.formal.maximum,
      actualVerilog = binding.actual.verilog,
      actualDefault = binding.actual.default,
      actualMinimum = binding.actual.minimum,
      actualMaximum = binding.actual.maximum,
      actualParameters = binding.actual.parameters.size,
      actualRoots = binding.actual.completedParameterRoots.size,
      inputWidth = din.getBitsWidth,
      outputWidth = dout.getBitsWidth,
      inputWidthExpression = ParameterizedWidth
        .expressionOf(din)
        .map(_.verilog)
        .getOrElse("<native>"),
      outputWidthExpression = ParameterizedWidth
        .expressionOf(dout)
        .map(_.verilog)
        .getOrElse("<native>")
    )
  }

  def inexactCount(default: Int = 2): ElabInt = {
    val parameter = ElaborationIntegerParameter(
      "INEXACT_COUNT",
      default = BigInt(default),
      minimum = BigInt(1),
      maximum = BigInt(4)
    )
    ElabInt.fromExpression(
      ElaborationIntegerExpression(
        verilog = parameter.name,
        default = parameter.default,
        minimum = parameter.minimum,
        maximum = parameter.maximum,
        parameters = Vector(parameter),
        parameterRoots = Vector(parameter.declarationRoot),
        exactDomain = None
      )
    )
  }

  def correlatedFour(): ElabInt = {
    val root = HdlInt
      .param("D", default = BigInt(2), min = BigInt(1), max = BigInt(4))
      .asElabInt
    (root - root) + 4
  }

  def typedTokenOf(component: Component): ExternalTypedFormalDeclarationToken =
    typedCapabilityOf(component).declarationToken

  def typedCapabilityOf(component: Component): ExternalTypedFormalBinding =
    ExternalFormalParameterRegistry.typedBindingsOf(component) match {
      case Vector(value) => value
      case other =>
        throw new IllegalStateException(
          s"typed child retained ${other.size} opaque formal capabilities"
        )
    }

  def typedPortTokenOf(port: BaseType): ExternalTypedFormalDeclarationToken =
    ExternalFormalParameterRegistry.typedBindingOf(port) match {
      case Some(value) => value.declarationToken
      case None =>
        throw new IllegalStateException(
          s"typed child port '${port.getName()}' retained no opaque formal capability"
        )
    }

  def caughtParameterizedFailure(body: => Unit): ParameterizedVerilogException =
    try {
      body
      throw new IllegalStateException(
        "adversarial formal registry operation unexpectedly succeeded"
      )
    } catch {
      case failure: ParameterizedVerilogException => failure
    }

  /** Replace the native emitter's exact child aliases after emission but before
    * MorphHDL captures them. The publication phase must treat the resulting two
    * same-name terminal identities as ambiguous instead of regrouping by text.
    */
  def dropEmittedChildCanonicalAliases(
      config: SpinalConfig
  ): AtomicInteger = {
    val removedAliases = new AtomicInteger(0)
    config.phasesInserters += { phases: ArrayBuffer[Phase] =>
      val emitters = phases.collect { case phase: PhaseVerilog => phase }
      if (emitters.size != 1)
        throw new IllegalStateException(
          s"canonical identity adversarial fixture found ${emitters.size} Verilog emitters"
        )
      val emitter = emitters.head
      phases += new PhaseMisc {
        override def impl(pc: PhaseContext): Unit =
          pc.topLevel.children.foreach { child =>
            if (emitter.emitedComponentRef.remove(child) ne null)
              removedAliases.incrementAndGet()
          }
      }
    }
    removedAliases
  }
}

class FiniteFormalBoundaryTests extends AnyFunSuite {
  import FiniteFormalBoundaryFixture._

  test("typed literal child formal matches the established literal formal graph and RTL") {
    withTemporaryDirectory { directory =>
      var typedTop: TypedLiteralTop = null
      val typed = emit(
        directory.resolve("typed"),
        "literal_formal.v"
      ) {
        typedTop = new TypedLiteralTop(ElabInt.literal(8))
        typedTop
      }

      var establishedTop: EstablishedLiteralTop = null
      val established = emit(
        directory.resolve("established"),
        "literal_formal.v"
      ) {
        establishedTop = new EstablishedLiteralTop(HdlInt.literal(BigInt(8)))
        establishedTop
      }

      assert(
        formalGraph(
          typedTop.child,
          typedTop.child.din,
          typedTop.child.dout
        ) == formalGraph(
          establishedTop.child,
          establishedTop.child.din,
          establishedTop.child.dout
        )
      )
      assert(
        java.util.Arrays.equals(typed, established),
        "typed ElabInt literal child-formal RTL differs from the established HdlInt literal path"
      )
    }
  }

  test("typed literal child formals reject non-literals and values outside finite bounds") {
    withTemporaryDirectory { directory =>
      Vector(0, 17).foreach { actual =>
        expectFailureWithoutRtl(
          directory.resolve(s"actual_$actual"),
          "invalid_literal_formal.v",
          "SPINAL-ELAB-FORMAL-DOMAIN-INVALID"
        ) {
          new TypedLiteralTop(ElabInt.literal(actual))
        }
      }
      expectFailureWithoutRtl(
        directory.resolve("non_literal"),
        "invalid_non_literal_formal.v",
        "SPINAL-ELAB-FORMAL-ACTUAL-LITERAL-INVALID"
      ) {
        val hidden = ElabInt.fromExpression(
          ElaborationIntegerExpression(
            verilog = "HIDDEN_WIDTH",
            default = BigInt(8),
            minimum = BigInt(8),
            maximum = BigInt(8),
            parameters = Vector.empty
          )
        )
        new TypedLiteralTop(hidden)
      }
    }
  }

  test("correlated exact child actual is projected before exact-extrema validation") {
    withTemporaryDirectory { directory =>
      val actual = correlatedFour()
      assert(actual.expression.verilog.contains("D - D"), actual.expression.verilog)
      assert(actual.expression.exactDomain.exists(_.evaluations.forall(_._2 == 4)))

      var top: CorrelatedFormalTop = null
      val verilog = emitText(
        directory.resolve("correlated_formal"),
        "correlated_formal.v"
      ) {
        top = new CorrelatedFormalTop(actual)
        top
      }
      val binding = ExternalFormalParameterRegistry.bindingsOf(top.child) match {
        case Vector(value) => value
        case other         => fail(s"correlated child retained ${other.size} formal bindings")
      }
      assert(binding.actual.default == 4)
      assert(binding.actual.minimum == 4)
      assert(binding.actual.maximum == 4)
      assert(binding.actual.verilog.contains("D - D"), binding.actual.verilog)
      assert(verilog.contains("parameter integer D = 2"), verilog)
      assert(verilog.contains(".CHILD_WIDTH("), verilog)
      assert(verilog.contains("D - D"), verilog)
    }
  }

  test("same-class typed child instances retain distinct opaque formal tokens") {
    withTemporaryDirectory { directory =>
      var top: SameClassTypedTokenTop = null
      val verilog = emitText(
        directory.resolve("same_class_tokens"),
        "same_class_tokens.v"
      ) {
        val leftActual = HdlInt
          .param("LEFT_WIDTH", default = BigInt(2), min = BigInt(1), max = BigInt(4))
          .asElabInt
        val rightActual = HdlInt
          .param("RIGHT_WIDTH", default = BigInt(2), min = BigInt(1), max = BigInt(4))
          .asElabInt
        top = new SameClassTypedTokenTop(leftActual, rightActual)
        top
      }
      assert(top.left.getClass == top.right.getClass)
      val leftCapability = typedCapabilityOf(top.left)
      val rightCapability = typedCapabilityOf(top.right)
      val leftToken = leftCapability.declarationToken
      val rightToken = rightCapability.declarationToken
      assert(leftToken ne rightToken)
      assert(leftCapability.binding.formal == rightCapability.binding.formal)
      assert(leftCapability.binding.formal ne rightCapability.binding.formal)
      assert(leftCapability.binding.actual.verilog == "LEFT_WIDTH")
      assert(rightCapability.binding.actual.verilog == "RIGHT_WIDTH")
      assert(
        leftCapability.binding.actual.completedParameterRoots.head ne
          rightCapability.binding.actual.completedParameterRoots.head
      )
      Vector(top.left.din, top.left.dout).foreach(port => assert(typedPortTokenOf(port) eq leftToken))
      Vector(top.right.din, top.right.dout).foreach(port => assert(typedPortTokenOf(port) eq rightToken))
      assert("module\\s+TypedTokenLeaf\\b".r.findAllMatchIn(verilog).size == 1)
      assert("(?s)TypedTokenLeaf\\s*#\\(.*?\\)\\s+left\\s*\\(".r.findFirstIn(verilog).nonEmpty)
      assert("(?s)TypedTokenLeaf\\s*#\\(.*?\\)\\s+right\\s*\\(".r.findFirstIn(verilog).nonEmpty)
      assert("\\.CHILD_WIDTH\\(".r.findAllMatchIn(verilog).size >= 2)
    }
  }

  test("different child classes with one exact layout share canonical typed RTL") {
    withTemporaryDirectory { directory =>
      var top: DifferentClassTypedTokenTop = null
      val verilog = emitText(
        directory.resolve("different_class_tokens"),
        "different_class_tokens.v"
      ) {
        top = new DifferentClassTypedTokenTop(correlatedFour())
        top
      }
      assert(top.left.getClass != top.right.getClass)
      val leftCapability = typedCapabilityOf(top.left)
      val rightCapability = typedCapabilityOf(top.right)
      val leftToken = leftCapability.declarationToken
      val rightToken = rightCapability.declarationToken
      assert(leftToken ne rightToken)
      assert(leftCapability.binding.formal == rightCapability.binding.formal)
      assert(leftCapability.binding.formal ne rightCapability.binding.formal)
      assert(
        ElabInt.equivalentExactFunction(
          leftCapability.binding.actual,
          rightCapability.binding.actual
        )
      )
      Vector(top.left.din, top.left.dout).foreach(port => assert(typedPortTokenOf(port) eq leftToken))
      Vector(top.right.din, top.right.dout).foreach(port => assert(typedPortTokenOf(port) eq rightToken))
      assert("module\\s+TypedTokenLayoutLeaf\\b".r.findAllMatchIn(verilog).size == 1)
      assert("(?s)TypedTokenLayoutLeaf\\s*#\\(.*?\\)\\s+left\\s*\\(".r.findFirstIn(verilog).nonEmpty)
      assert("(?s)TypedTokenLayoutLeaf\\s*#\\(.*?\\)\\s+right\\s*\\(".r.findFirstIn(verilog).nonEmpty)
      assert("\\.CHILD_WIDTH\\(".r.findAllMatchIn(verilog).size >= 2)
    }
  }

  test("different child classes with different layouts fail closed before typed publication") {
    withTemporaryDirectory { directory =>
      expectFailureWithoutRtl(
        directory.resolve("different_class_layout_conflict"),
        "different_class_layout_conflict.v",
        "different layout"
      ) {
        new DifferentClassDifferentLayoutTop(correlatedFour())
      }
    }
  }

  test("duplicate typed claims are atomic and preserve opaque tokens") {
    withTemporaryDirectory { directory =>
      var top: TypedRegistryAtomicityTop = null
      val verilog = emitText(
        directory.resolve("typed_registry_atomicity"),
        "typed_registry_atomicity.v"
      ) {
        val actual = HdlInt
          .param(
            "PARENT_WIDTH",
            default = BigInt(2),
            min = BigInt(1),
            max = BigInt(4)
          )
          .asElabInt
        top = new TypedRegistryAtomicityTop(actual)
        top
      }

      assert(
        top.duplicateFailure.code ==
          "SPINAL-ELAB-FORMAL-TYPED-TOKEN-DUPLICATE"
      )
      assert(
        top.capabilitiesAfterDuplicate match {
          case Vector(value) => value eq top.originalCapability
          case _             => false
        }
      )
      assert(
        top.portTokensAfterDuplicate
          .zip(top.originalPortTokens)
          .forall { case (after, before) => after eq before }
      )
      assert(!verilog.contains("SECOND_WIDTH"), verilog)
      assert(
        "parameter\\s+integer\\s+CHILD_WIDTH\\b".r
          .findAllMatchIn(verilog)
          .size == 1,
        verilog
      )
    }
  }

  test("same-name distinct emitted canonical terminals fail without textual regrouping or RTL") {
    withTemporaryDirectory { directory =>
      val filename = "canonical_identity_ambiguity.v"
      val adversarialConfig = config(directory, filename)
      val removedAliases =
        dropEmittedChildCanonicalAliases(adversarialConfig)
      val generation = MorphVerilog.tryGenerate(adversarialConfig) {
        val leftActual = HdlInt
          .param(
            "LEFT_WIDTH",
            default = BigInt(2),
            min = BigInt(1),
            max = BigInt(4)
          )
          .asElabInt
        val rightActual = HdlInt
          .param(
            "RIGHT_WIDTH",
            default = BigInt(2),
            min = BigInt(1),
            max = BigInt(4)
          )
          .asElabInt
        new SameClassTypedTokenTop(leftActual, rightActual)
      }

      generation match {
        case Left(failure) =>
          assert(
            failure.detail.contains(
              "SPINAL-PARAMETERIZED-VERILOG-EXTERNAL-CANONICAL-NAME-AMBIGUOUS"
            ),
            failure.detail
          )
        case Right(report) =>
          fail(s"Expected exact canonical-identity ambiguity, received $report")
      }
      assert(removedAliases.get() >= 1)
      assert(!Files.exists(directory.resolve(filename)))
    }
  }

  test("correlated exact foreach and countOne retain their four-wide function") {
    withTemporaryDirectory { directory =>
      val count = correlatedFour()
      assert(count.expression.verilog.contains("D - D"), count.expression.verilog)
      assert(count.expression.exactDomain.exists(_.evaluations.forall(_._2 == 4)))

      val verilog = emitText(
        directory.resolve("correlated_finite"),
        "correlated_finite.v"
      ) {
        new CorrelatedFiniteTop(count)
      }
      val correlatedBounds =
        "<\\s*\\(\\(D\\s*-\\s*D\\)\\s*\\+\\s*4\\)\\s*;".r
          .findAllMatchIn(verilog)
          .size
      assert(correlatedBounds >= 2, verilog)
      assert(verilog.contains("parameter integer D = 2"), verilog)
      assert(verilog.contains("[((D - D) + 4)-1:0]"), verilog)
      assert(verilog.contains("source"), verilog)
    }
  }

  test("typed and established finite ranges admit zero defaults independently") {
    withTemporaryDirectory { directory =>
      val typedZero = emitText(
        directory.resolve("typed_zero"),
        "typed_default.v"
      ) {
        new TypedFiniteDefaultTop(exactCount(default = 0))
      }
      val typedOne = emitText(
        directory.resolve("typed_one"),
        "typed_default.v"
      ) {
        new TypedFiniteDefaultTop(exactCount(default = 1))
      }
      assert(defaultNeutral(typedZero) == defaultNeutral(typedOne))
      assert(typedZero.contains("parameter integer COUNT = 0"), typedZero)
      assert(typedZero.contains("< COUNT;"), typedZero)

      val structuralZero = emitText(
        directory.resolve("hdl_structural_zero"),
        "hdl_structural_default.v"
      ) {
        new HdlStructuralDefaultTop(hdlCount(default = 0))
      }
      val structuralOne = emitText(
        directory.resolve("hdl_structural_one"),
        "hdl_structural_default.v"
      ) {
        new HdlStructuralDefaultTop(hdlCount(default = 1))
      }
      assert(defaultNeutral(structuralZero) == defaultNeutral(structuralOne))
      assert(structuralZero.contains("parameter integer COUNT = 0"), structuralZero)
      assert(structuralZero.contains("< COUNT;"), structuralZero)

      val proceduralZero = emitText(
        directory.resolve("hdl_procedural_zero"),
        "hdl_procedural_default.v"
      ) {
        new HdlProceduralDefaultTop(hdlCount(default = 0))
      }
      val proceduralOne = emitText(
        directory.resolve("hdl_procedural_one"),
        "hdl_procedural_default.v"
      ) {
        new HdlProceduralDefaultTop(hdlCount(default = 1))
      }
      assert(defaultNeutral(proceduralZero) == defaultNeutral(proceduralOne))
      assert(proceduralZero.contains("parameter integer COUNT = 0"), proceduralZero)
      assert(proceduralZero.contains("< COUNT;"), proceduralZero)
    }
  }

  test("inexact symbolic foreach and countOne fail before publishing RTL") {
    withTemporaryDirectory { directory =>
      expectFailureWithoutRtl(
        directory.resolve("foreach"),
        "inexact_foreach.v",
        "SPINAL-ELAB-FINITE-RANGE-EXACT-DOMAIN-REQUIRED"
      ) {
        new InexactForeachTop(inexactCount())
      }
      expectFailureWithoutRtl(
        directory.resolve("count_one"),
        "inexact_count_one.v",
        "SPINAL-ELAB-FINITE-FOLD-EXACT-DOMAIN-REQUIRED"
      ) {
        new InexactCountOneTop(inexactCount())
      }
    }
  }

  private def exactCount(default: Int): ElabInt = hdlCount(default).asElabInt

  private def hdlCount(default: Int): HdlInt =
    HdlInt.param(
      "COUNT",
      default = BigInt(default),
      min = BigInt(0),
      max = BigInt(4)
    )

  private def defaultNeutral(verilog: String): String =
    verilog.replaceAll(
      "parameter integer COUNT = [01]",
      "parameter integer COUNT = <DEFAULT>"
    )

  private def config(directory: Path, filename: String): SpinalConfig = {
    Files.createDirectories(directory)
    val value = SpinalConfig(targetDirectory = directory.toString)
    value.netlistFileName = filename
    value
  }

  private def emit(
      directory: Path,
      filename: String
  )(component: => Component): Array[Byte] = {
    MorphVerilog(config(directory, filename))(component)
    Files.readAllBytes(directory.resolve(filename))
  }

  private def emitText(
      directory: Path,
      filename: String
  )(component: => Component): String =
    new String(emit(directory, filename)(component), StandardCharsets.UTF_8)

  private def expectFailureWithoutRtl(
      directory: Path,
      filename: String,
      code: String
  )(component: => Component): Unit = {
    val rtl = directory.resolve(filename)
    MorphVerilog.tryGenerate(config(directory, filename))(component) match {
      case Left(failure) =>
        assert(
          failure.detail.contains(code),
          s"expected $code, received ${failure.detail}"
        )
      case Right(report) =>
        fail(s"expected $code, generation succeeded with $report")
    }
    assert(!Files.exists(rtl), s"failure published unexpected RTL at $rtl")
  }

  private def withTemporaryDirectory(body: Path => Unit): Unit = {
    val directory = Files.createTempDirectory("morphhdl-finite-formal-boundary-")
    try body(directory)
    finally {
      val stream = Files.walk(directory)
      try {
        stream.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach { path =>
          Files.deleteIfExists(path)
        }
      } finally stream.close()
    }
  }
}
