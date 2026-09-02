package morphhdl.integration

import java.nio.file.{Files, Paths}
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

import scala.collection.mutable.ArrayBuffer

import spinal.core.{BaseType, Component, SInt, SpinalConfig, SpinalReport, Verilog}
import spinal.core.internals.{Phase, PhaseContext, PhaseMisc, PhaseVerilog}

/** Immutable port geometry observed after normal SpinalHDL elaboration. */
final case class NativePortSnapshot(
    name: String,
    direction: String,
    width: Int,
    signed: Boolean
)

/** Immutable hierarchy node observed from the normally elaborated graph. */
final case class NativeComponentSnapshot(
    definitionName: String,
    instanceName: String,
    ports: Vector[NativePortSnapshot],
    children: Vector[NativeComponentSnapshot]
)

/** Default read-only graph inspection result supplied by the external boundary. */
final case class NativeGraphSnapshot(top: NativeComponentSnapshot) {
  lazy val components: Vector[NativeComponentSnapshot] = {
    def flatten(value: NativeComponentSnapshot): Vector[NativeComponentSnapshot] =
      value +: value.children.flatMap(flatten)
    flatten(top)
  }
}

object NativeGraphSnapshot {
  def capture[T <: Component](top: T): NativeGraphSnapshot = {
    if (top == null) {
      throw new IllegalArgumentException("native graph top must not be null")
    }

    def nonNullName(value: String, fallback: String): String =
      Option(value).filter(_.nonEmpty).getOrElse(fallback)

    def direction(port: BaseType): String =
      if (port.isInput) "input"
      else if (port.isOutput) "output"
      else if (port.isInOut) "inout"
      else "directionless"

    def loop(component: Component): NativeComponentSnapshot = {
      val definitionName = nonNullName(component.definitionName, component.getClass.getSimpleName)
      NativeComponentSnapshot(
        definitionName = definitionName,
        instanceName = nonNullName(component.getName(), definitionName),
        ports = component.getAllIo.toVector
          .map { port =>
            NativePortSnapshot(
              name = nonNullName(port.getName(), port.getClass.getSimpleName),
              direction = direction(port),
              width = port.getBitsWidth,
              signed = port.isInstanceOf[SInt]
            )
          }
          .sortBy(_.name),
        children = component.children.toVector.map(loop)
      )
    }

    NativeGraphSnapshot(loop(top))
  }
}

/** Result of one normal SpinalHDL Verilog generation invoked through the
  * MorphHDL-owned external boundary.
  */
final case class ExternalSpinalVerilogReport[T <: Component, A](
    nativeReport: SpinalReport[T],
    inspection: A,
    phaseClassNames: Vector[String],
    expectedInheritedValidationPhaseIds: Vector[String],
    inheritedValidationPhaseIds: Vector[String],
    generatedSourcesPaths: Vector[String]
)

/** External elaboration, validation, graph-inspection and publication boundary.
  *
  * This implementation intentionally uses only APIs present in the recorded
  * upstream baseline: SpinalConfig.generateVerilog, SpinalConfig phase
  * inserters, PhaseMisc, PhaseContext and SpinalReport. It does not depend on
  * MorphHDL additions to SpinalConfig, SpinalReport, the native phase planner or
  * the native Verilog emitter.
  */
object ExternalSpinalVerilog {
  private final case class CapturedInspection[A](
      attempt: Int,
      top: Component,
      value: A
  )

  private final case class CapturedPhasePlan(
      attempt: Int,
      classNames: Vector[String],
      inheritedValidationPhaseIds: Vector[String]
  )

  private val InheritedValidationPhases = Vector(
    "spinal.core.internals.PhaseCheckIoBundle" -> "PhaseCheckIoBundle",
    "spinal.core.internals.PhaseCheckHierarchy" -> "PhaseCheckHierarchy",
    "spinal.core.internals.PhaseInferWidth" -> "PhaseInferWidth",
    "spinal.core.internals.PhaseCheck_noLatchNoOverride" -> "PhaseCheck_noLatchNoOverride",
    "spinal.core.internals.PhaseCheck_noRegisterAsLatch" -> "PhaseCheck_noRegisterAsLatch",
    "spinal.core.internals.PhaseCheckCombinationalLoops" -> "PhaseCheckCombinationalLoops",
    "spinal.core.internals.PhaseCheckCrossClock" -> "PhaseCheckCrossClock"
  )
  private val GlobalDataValidationId = "PhaseContext.checkGlobalData"

  /** Stable, MorphHDL-owned inherited-validation contract observed through the baseline phase
    * inserter API. The final global-data check has no Phase object; successful
    * native generation proves that its baseline finalizer also completed.
    */
  val expectedInheritedValidationPhaseIds: Vector[String] =
    InheritedValidationPhases.map(_._2) :+ GlobalDataValidationId

  private def inheritedValidationIds(classNames: Vector[String]): Vector[String] = {
    val byClassName = InheritedValidationPhases.toMap
    classNames.flatMap(byClassName.get) :+ GlobalDataValidationId
  }

  private final class CapturePhase[T <: Component, A](
      attempt: Int,
      inspector: T => A,
      afterPublication: (PhaseContext, Component => Component) => Unit,
      canonicalOf: Component => Component,
      capture: AtomicReference[CapturedInspection[A]]
  ) extends PhaseMisc {
    override def impl(pc: PhaseContext): Unit = {
      val top = pc.topLevel
      if (top == null) {
        throw new IllegalStateException(
          "external graph inspection ran without an elaborated top-level component"
        )
      }
      val typedTop = top.asInstanceOf[T]
      val inspected = inspector(typedTop)
      afterPublication(pc, canonicalOf)
      capture.set(CapturedInspection(attempt, typedTop, inspected))
    }
  }

  def apply[T <: Component](
      component: => T
  ): ExternalSpinalVerilogReport[T, NativeGraphSnapshot] =
    apply(SpinalConfig())(component)

  def apply[T <: Component](
      config: SpinalConfig
  )(component: => T): ExternalSpinalVerilogReport[T, NativeGraphSnapshot] =
    inspect(config)(component)((top: T) => NativeGraphSnapshot.capture(top))

  /** Run a caller-supplied read-only graph inspector as the final configured
    * phase, after normal native publication phases have run. The inspector may
    * retain object identities for later MorphHDL analysis, but must not mutate
    * the native graph.
    */
  def inspect[T <: Component, A](
      config: SpinalConfig
  )(component: => T)(inspector: T => A): ExternalSpinalVerilogReport[T, A] =
    run(config)(component)(inspector, (_: PhaseContext, _: Component => Component) => ())

  /** Run one MorphHDL-owned publication transform as the final configured phase,
    * after native validation and Verilog emission. The callback may rewrite only
    * published artifacts; native graph mutation remains outside this boundary.
    */
  def transform[T <: Component](
      config: SpinalConfig
  )(component: => T)(
      afterPublication: PhaseContext => Unit
  ): ExternalSpinalVerilogReport[T, NativeGraphSnapshot] =
    run(config)(component)(
      (top: T) => NativeGraphSnapshot.capture(top),
      (pc: PhaseContext, _: Component => Component) => afterPublication(pc)
    )

  /** Publication transform with the native Verilog emitter's exact
    * concrete-instance to canonical-component identity map. The map is captured
    * from the exact PhaseVerilog object already present in this phase plan;
    * module/class/name text is never used to reconstruct it.
    */
  def transformWithCanonicalIdentity[T <: Component](
      config: SpinalConfig
  )(component: => T)(
      afterPublication: (PhaseContext, Component => Component) => Unit
  ): ExternalSpinalVerilogReport[T, NativeGraphSnapshot] =
    run(config)(component)(
      (top: T) => NativeGraphSnapshot.capture(top),
      afterPublication
    )

  private def run[T <: Component, A](
      config: SpinalConfig
  )(component: => T)(
      inspector: T => A,
      afterPublication: (PhaseContext, Component => Component) => Unit
  ): ExternalSpinalVerilogReport[T, A] = {
    if (config == null) {
      throw new IllegalArgumentException("SpinalConfig must not be null")
    }
    if (inspector == null) {
      throw new IllegalArgumentException("native graph inspector must not be null")
    }
    if (afterPublication == null) {
      throw new IllegalArgumentException("external publication transform must not be null")
    }

    val attempts = new AtomicInteger(0)
    val captured = new AtomicReference[CapturedInspection[A]]()
    val capturedPlan = new AtomicReference[CapturedPhasePlan]()

    val inserters = config.phasesInserters.clone()
    inserters += { phases: ArrayBuffer[Phase] =>
      val attempt = attempts.incrementAndGet()
      val emitters = phases.collect { case phase: PhaseVerilog => phase }.toVector
      if (emitters.size != 1) {
        throw new IllegalStateException(
          s"external canonical-identity capture requires exactly one native PhaseVerilog, found ${emitters.size}"
        )
      }
      val emitter = emitters.head
      val canonicalOf: Component => Component = value => {
        if (value == null)
          throw new IllegalArgumentException("canonical component lookup must not be null")
        Option(emitter.emitedComponentRef.get(value)).getOrElse(value)
      }
      val capturePhase = new CapturePhase[T, A](
        attempt,
        inspector,
        afterPublication,
        canonicalOf,
        captured
      )
      phases += capturePhase
      val classNames = phases.toVector.map(_.getClass.getName)
      capturedPlan.set(
        CapturedPhasePlan(attempt, classNames, inheritedValidationIds(classNames))
      )
    }

    // Clone every mutable collection exposed by the baseline SpinalConfig so
    // installing the MorphHDL observer does not mutate the caller's config.
    val isolatedConfig = config.copy(
      mode = Verilog,
      flags = config.flags.clone(),
      debugComponents = config.debugComponents.clone(),
      phasesInserters = inserters,
      transformationPhases = config.transformationPhases.clone(),
      memBlackBoxers = config.memBlackBoxers.clone(),
      scopeProperties = config.scopeProperties.clone()
    )

    val nativeReport = isolatedConfig.generateVerilog {
      val value = component
      if (value == null) {
        throw new IllegalArgumentException("component factory returned null")
      }
      value
    }

    val latestAttempt = attempts.get()
    val inspection = captured.get()
    val phasePlan = capturedPlan.get()
    if (latestAttempt == 0 || inspection == null || phasePlan == null) {
      throw new IllegalStateException(
        "normal SpinalHDL generation completed without running the external graph inspector"
      )
    }
    if (inspection.attempt != latestAttempt || phasePlan.attempt != latestAttempt) {
      throw new IllegalStateException(
        "external graph inspection did not correspond to the successful SpinalHDL generation attempt"
      )
    }
    if (!(nativeReport.toplevel eq inspection.top)) {
      throw new IllegalStateException(
        "external graph inspection and native report refer to different top-level components"
      )
    }

    val generated = nativeReport.generatedSourcesPaths.toVector
    if (generated.isEmpty) {
      throw new IllegalStateException(
        "normal SpinalHDL generation did not publish a Verilog source"
      )
    }
    val missing = generated.filterNot(path => Files.isRegularFile(Paths.get(path)))
    if (missing.nonEmpty) {
      throw new IllegalStateException(
        "normal SpinalHDL report references missing generated source(s): " +
          missing.mkString(", ")
      )
    }

    ExternalSpinalVerilogReport(
      nativeReport = nativeReport,
      inspection = inspection.value,
      phaseClassNames = phasePlan.classNames,
      expectedInheritedValidationPhaseIds = expectedInheritedValidationPhaseIds,
      inheritedValidationPhaseIds = phasePlan.inheritedValidationPhaseIds,
      generatedSourcesPaths = generated
    )
  }
}
