package spinal.core.internals

import morphhdl.{MorphPublicationModule, MorphPublicationReport, MorphSingleSourceVerilogReport, MorphPublicationFailure, MorphPublicationDiagnostic, MorphPublicationSeparation, MorphVerilogException, MorphVerilogFailure}
import spinal.core._

/** Opt-in, thread-confined observer. Never participates in acceptance or sharing. */
object NativePublicationReport {
  private final class Capture {
    var modules: Option[Vector[MorphPublicationModule]] = None
    var settings = Vector.empty[(String, String)]
    var diagnostics = Vector.empty[MorphPublicationDiagnostic]
    var separations = Vector.empty[MorphPublicationSeparation]
    var traces = Vector.empty[(Component, Vector[(String,String)])]
  }
  private val active = new ThreadLocal[Capture]()
  def capture(body: => MorphSingleSourceVerilogReport): MorphPublicationReport =
    attempt(body) match { case Right(report) => report; case Left(error) => throw new MorphVerilogException(error.failure) }

  def attempt(body: => MorphSingleSourceVerilogReport): Either[MorphPublicationFailure, MorphPublicationReport] = {
    require(active.get() == null, "publication report capture cannot nest")
    val state = new Capture
    active.set(state)
    try {
      val report = body
      Right(MorphPublicationReport(report, state.modules.getOrElse(
        throw new IllegalStateException("successful publication lost its decision inventory")), state.settings, state.separations))
    } catch {
      case error: MorphVerilogException =>
        val chain = scala.collection.mutable.ArrayBuffer.empty[Throwable]
        var next = error.failure.cause.orNull
        while (next != null && !chain.exists(_ eq next)) { chain += next; next = next.getCause }
        val structured = chain.collect { case p: ParameterizedVerilogException =>
          MorphPublicationDiagnostic(p.code, bounded(p.detail), locations = p.sourceLocation.toVector ++ source(p))
        }.toVector
        val details = if(state.diagnostics.nonEmpty) state.diagnostics else if(structured.nonEmpty) structured else
          Vector(MorphPublicationDiagnostic("NATIVE-GENERATION-FAILURE", bounded(error.failure.detail),
            locations = chain.headOption.toVector.flatMap(source)))
        Left(MorphPublicationFailure(error.failure, details.distinct, state.separations))
    } finally active.remove()
  }
  private def bounded(value: String): String = value.replace('\n',' ').replace('\r',' ').take(480)
  private def source(error: Throwable): Vector[String] = ScalaLocated.filterStackTrace(error.getStackTrace).toVector
    .filter(e => e.getLineNumber > 0 && e.getFileName != null && e.getFileName.endsWith(".scala") &&
      !e.getClassName.startsWith("spinal.core.") && !e.getClassName.startsWith("scala.") &&
      !e.getClassName.startsWith("morphhdl.MorphVerilog") && !e.getClassName.startsWith("org.scalatest."))
    .take(1).map(e => e.getFileName + ":" + e.getLineNumber)
  private def locations(component: Component): Vector[String] =
    Option(component.scalaTrace).toVector.flatMap(source) ++
      component.getOrdredNodeIo.toVector.flatMap(port => ParameterizedWidth.expressionOf(port).flatMap(_.sourceLocation)).distinct

  private[internals] def rejected(code: String, detail: String, components: Vector[Component]): Unit = {
    val state = active.get()
    if(state != null) state.diagnostics :+= MorphPublicationDiagnostic(code, bounded(detail),
      components.map(_.getPath()), components.flatMap(locations).distinct)
  }

  /** Snapshot the exact native comparison traces, without changing comparison or RTL.
    * The captured state travels with the phase because native elaboration may use a worker thread.
    */
  def observe(config: SpinalConfig): Unit = {
    val state = active.get()
    if(state == null) return
    config.phasesInserters += { phases =>
      val emitters = phases.collect { case e: PhaseVerilog => e }.toVector
      if(emitters.nonEmpty) {
        val last = phases.lastIndexWhere(_.isInstanceOf[PhaseVerilog])
        phases.insert(last + 1, new PhaseMisc {
          override def impl(pc: PhaseContext): Unit = {
            val labels = Vector("documentation", "definition attributes", "module header", "module footer", "local parameters", "declarations", "logic")
            state.traces = emitters.flatMap(_.emitedComponent.toVector).map { case (trace, component) =>
              component -> (trace.builders.zipWithIndex.map { case (text,i) => labels.lift(i).getOrElse("native section " + i) -> text.toString }.toVector ++
                trace.strings.zipWithIndex.map { case(text,i) => ("port binding " + i) -> text }.toVector)
            }
          }
        })
      }
    }
  }

  private def separation(state: Capture, left: Component, right: Component): MorphPublicationSeparation = {
    val a = MorphHdlExternalParameterizedVerilog.componentSchema(left)
    val b = MorphHdlExternalParameterizedVerilog.componentSchema(right)
    val schema = if(a != b) Some("retained schema" -> MorphHdlExternalParameterizedVerilog.schemaDifference(a,b)) else None
    def trace(c: Component): Vector[(String,String)] = state.traces.find(_._1 eq c).map(_._2).getOrElse(Vector.empty)
    val x = trace(left); val y = trace(right)
    val difference = schema.orElse {
      (0 until math.max(x.size,y.size)).iterator.flatMap { i =>
        val l=x.lift(i);val r=y.lift(i)
        if(l==r) None else {
          val lx=l.map(_._2).getOrElse("<missing>");val rx=r.map(_._2).getOrElse("<missing>")
          val at=lx.zip(rx).indexWhere {case(u,v)=>u!=v} match {case -1=>math.min(lx.length,rx.length);case n=>n}
          val start=math.max(0,at-40)
          Some(l.orElse(r).get._1 -> s"native comparison differs at character $at: left=${bounded(lx.drop(start).take(160))}; right=${bounded(rx.drop(start).take(160))}")
        }
      }.take(1).toVector.headOption
    }.getOrElse("explicit identity" -> (if(left.definitionNameNoMerge || right.definitionNameNoMerge)
      "author requested separate native definition names" else "separate native canonical identities; no differing retained trace is available"))
    MorphPublicationSeparation(left.definitionName,right.definitionName,difference._1,difference._2,
      (locations(left)++locations(right)).distinct)
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
    def scoped(actions: Vector[ElabScopedProcess.Action]): Vector[String] = actions.flatMap {
      case ElabScopedProcess.Repeat(loop, body) =>
        Vector(s"nested scoped procedural ${loop.ordinal}: ${loop.count.expression.verilog}") ++ scoped(body)
      case ElabScopedProcess.Branch(_, yes, no) => scoped(yes) ++ scoped(no)
      case _ => Vector.empty
    }
    // Compare each distinct native definition with the first definition of its
    // construction class. Class identity narrows diagnostic candidates only.
    state.separations = groups.map(_._1).groupBy(_.getClass.getName).toVector.sortBy(_._1).flatMap { case(_, members) =>
      val sorted = members.sortBy(_.definitionName)
      sorted.drop(1).map(other => separation(state,sorted.head,other))
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
        ElabScopedProcess.operations(definition).flatMap(op => Vector(s"scoped procedural: ${op.builder.count.expression.verilog}") ++ scoped(op.actions)) ++
        structural(ParameterizedStructure.regionsOf(definition))
      MorphPublicationModule(definition.definitionName, instances.map(_.getPath()).sorted, parameters,
        loops.sorted, ParameterizedVerilogVecs.logicalSchema(definition))
    }.sortBy(_.name))
  }
}
