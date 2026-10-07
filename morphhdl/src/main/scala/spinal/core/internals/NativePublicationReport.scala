package spinal.core.internals

import morphhdl.{MorphPublicationModule, MorphPublicationReport, MorphSingleSourceVerilogReport}
import spinal.core._

/** Opt-in, thread-confined observer. Never participates in acceptance or sharing. */
object NativePublicationReport {
  private final class Capture {
    var modules: Option[Vector[MorphPublicationModule]] = None
    var settings = Vector.empty[(String, String)]
  }
  private val active = new ThreadLocal[Capture]()
  def capture(body: => MorphSingleSourceVerilogReport): MorphPublicationReport = {
    require(active.get() == null, "publication report capture cannot nest")
    val state = new Capture
    active.set(state)
    try {
      val report = body
      MorphPublicationReport(report, state.modules.getOrElse(
        throw new IllegalStateException("successful publication lost its decision inventory")), state.settings)
    } finally active.remove()
  }
  private[internals] def record(groups: Vector[(Component, Vector[Component])], config: SpinalConfig): Unit = {
    val state = active.get()
    if (state == null) return
    require(state.modules.isEmpty, "publication decisions captured twice")
    val options = VerilogAggregateOptions.of(config)
    state.settings = Vector("preserveConstantLoops" -> options.preserveConstantLoops.toString,
      "preserveConstantVecs" -> options.preserveConstantVecs.toString, "vecLayout" -> options.vecLayout.toString)
    def structural(regions: Vector[ParameterizedStructure.StructuralRegion]): Vector[String] = regions.flatMap { region =>
      val here = region match {
        case loop: ParameterizedStructure.StructuralFor => Vector(s"structural ${loop.label}: ${loop.count.verilog}")
        case _ => Vector.empty
      }
      here ++ region.blocks.flatMap(block => structural(block.regions))
    }
    state.modules = Some(groups.map { case (definition, instances) =>
      val retained = MorphHdlExternalParameterizedVerilog.componentParameters(definition)
      val categories = Vector(
        "packed width" -> ParameterizedWidth.parametersOf(definition),
        "memory geometry" -> ParameterizedMemory.parametersOf(definition),
        "scalar value" -> ExternalParameterizedValueRegistry.parametersOf(definition),
        "Vec geometry" -> ParameterizedVerilogVecs.parametersOf(definition),
        "structural region" -> ParameterizedStructure.parametersOf(definition),
        "procedural loop" -> ParameterizedProcess.parametersOf(definition),
        "child actual binding" -> MorphHdlExternalParameterizedVerilog.forwardedParameters(definition))
      val parameters = retained.map { parameter =>
        val uses = categories.collect { case (reason, values) if values.exists(_.name == parameter.name) => reason }
        parameter.name -> (if (uses.isEmpty) Vector("other authenticated native use") else uses)
      }.sortBy(_._1)
      val loops = ParameterizedProcess.loopsOf(definition).map(loop => s"procedural ${loop.label}: ${loop.count.verilog}") ++
        ParameterizedProcess.conditionalLoopsOf(definition).map(loop => s"conditional procedural ${loop.indexHint}: ${loop.count.verilog}") ++
        ElabProcess.operations(definition).map(op => s"bounded ${if (op.take.isEmpty) "priority" else "prefix"}: ${op.count.expression.verilog}") ++
        ElabScopedProcess.operations(definition).map(op => s"scoped procedural: ${op.builder.count.expression.verilog}") ++
        structural(ParameterizedStructure.regionsOf(definition))
      MorphPublicationModule(definition.definitionName, instances.map(_.getPath()).sorted, parameters,
        loops.sorted, ParameterizedVerilogVecs.logicalSchema(definition))
    }.sortBy(_.name))
  }
}
