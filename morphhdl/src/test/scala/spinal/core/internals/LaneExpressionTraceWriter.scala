package spinal.core.internals

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.IdentityHashMap
import scala.collection.mutable.ArrayBuffer
import spinal.core._
import morphhdl.{LaneExpressionExample, MorphVerilog, MorphWireAssignmentPasses}
import morphhdl.frontend.HdlBool

/** Read-only diagnostic of the unchanged production reproduction.
  * Names are printed for navigation only; all links and counts use identity.
  * No phase is replaced, and no expression, target, tag or configuration flag
  * is modified by an observer. Compare traced RTL with the untraced run.
  */
object LaneExpressionTraceWriter {
  private def width(e: Expression): Int = e match {
    case _ if e.getTypeObject == TypeBool => 1
    case w: WidthProvider => w.getWidth
    case _ => -1
  }

  private def fixed(e: Expression): Option[(Int, Int)] = e match {
    case b: BitAssignmentFixed => Some((b.bitId, b.bitId))
    case r: RangedAssignmentFixed => Some((r.lo, r.hi))
    case _ => None
  }

  private def observe(label: String, output: Path): Phase = new Phase {
    override def hasNetlistImpact: Boolean = false
    override def impl(pc: PhaseContext): Unit = {
      val lines = ArrayBuffer.empty[String]
      val ids = new IdentityHashMap[Expression, java.lang.Integer]()
      def id(e: Expression): Int = {
        val old = ids.get(e)
        if (old != null) old.intValue
        else { val next = ids.size; ids.put(e, next); next }
      }
      def targetBoundary(t: BaseType, c: Component): String = {
        val kind = t.getTypeObject
        if (kind != TypeUInt && kind != TypeBits && kind != TypeBool) "target-kind"
        else if (width(t) <= 0) "target-width"
        else if (t.component ne c) "target-component"
        else if (t.getTags().exists(tag => !(tag eq noBackendCombMerge))) "target-tags"
        else if (ParameterizedWidth.expressionOf(t).nonEmpty) "target-symbolic-width"
        else "accepted"
      }
      def selectionBoundary(t: AssignmentExpression, c: Component): String = {
        val base = targetBoundary(t.finalTarget, c)
        if (base != "accepted") return base
        if (fixed(t).isEmpty) return "dynamic-or-unknown-selection"
        val ranges = ArrayBuffer.empty[(Int, Int)]
        var reason = "accepted"
        t.finalTarget.foreachStatements {
          case a: DataAssignmentStatement => fixed(a.target) match {
            case Some((lo, hi)) if lo >= 0 && hi >= lo && hi < width(t.finalTarget) =>
              if (ranges.exists { case (ol, oh) => lo <= oh && ol <= hi })
                reason = "overlapping-selection"
              ranges += ((lo, hi))
            case _ => reason = "non-fixed-or-invalid-target-assignment"
          }
          case _ =>
        }
        if (ranges.isEmpty) "no-ranges" else reason
      }
      lines += s"phase\t$label\temitter-enabled=${VerilogEmitterExpressionInlining.isEnabled(pc.config)}"
      pc.components().foreach { c =>
        val statements = ArrayBuffer.empty[Statement]
        c.dslBody.walkStatements(s => statements += s)
        val counts = new IdentityHashMap[Expression, java.lang.Integer]()
        statements.foreach(_.walkDrivingExpressions { e =>
          id(e)
          val old = counts.get(e)
          counts.put(e, if (old == null) 1 else old.intValue + 1)
        })
        val plan = VerilogEmitterExpressionInlining.redundantWrappers(c, pc.config)
        val emitted = new IdentityHashMap[Expression, java.lang.Boolean]()
        def node(e: Expression): Unit = if (!emitted.containsKey(e)) {
          emitted.put(e, true)
          val children = ArrayBuffer.empty[Int]
          e.foreachDrivingExpression(child => children += id(child))
          val tags = e match {
            case tagged: SpinalTagReady => tagged.getTags().map(_.getClass.getName).mkString(",")
            case _ => ""
          }
          val extra = e match {
            case b: BaseType => s"name=${b.getName("")};symbolic=${ParameterizedWidth.expressionOf(b)};comb=${b.isComb};reg=${b.isReg};vital=${b.isVital};frozen=${b.isFrozen()}"
            case r: Resize => s"input-width=${r.input.getWidth};size=${r.size};symbolic=${ParameterizedWidth.resizeExpressionOf(r)}"
            case _ => ""
          }
          lines += s"node\t${id(e)}\t${e.getClass.getName}\twidth=${width(e)}\toccurrences=${Option(counts.get(e)).map(_.intValue).getOrElse(0)}\tapproved=${plan.containsKey(e)}\tchildren=${children.mkString(",")}\ttags=$tags\t$extra"
          e.foreachDrivingExpression(node)
        }
        statements.foreach { s =>
          s match {
            case a: DataAssignmentStatement =>
              val t = a.finalTarget
              val ranges = ArrayBuffer.empty[String]
              t.foreachStatements {
                case d: DataAssignmentStatement => ranges += fixed(d.target).toString
                case _ =>
              }
              val eligibility = a.target match {
                case selection: AssignmentExpression => selectionBoundary(selection, c)
                case b: BaseType =>
                  val boundary = targetBoundary(b, c)
                  if (boundary != "accepted") boundary
                  else if (ranges.size != 1) "multiple-data-assignments" else "accepted"
                case _ => "unrepresented-target"
              }
              lines += s"assignment\t${t.getName("")}\ttarget=${a.target.getClass.getName}\ttarget-width=${width(a.target)}\tbase-width=${width(t)}\tranges=${ranges.mkString(",")}\teligibility=$eligibility\trhs=${id(a.source)}"
            case w: WhenStatement =>
              lines += s"condition\trhs=${id(w.cond)}"
            case _ =>
          }
          s.walkDrivingExpressions(node)
        }
      }
      Files.createDirectories(output)
      Files.write(output.resolve(label + ".tsv"), (lines.mkString("\n") + "\n").getBytes(StandardCharsets.UTF_8))
      println(s"LANE-TRACE $label ${lines.size} rows")
    }
  }

  def main(args: Array[String]): Unit = {
    require(args.length == 1, "Usage: LaneExpressionTraceWriter OUTPUT_DIRECTORY")
    val output = Paths.get(args(0)).toAbsolutePath
    Files.createDirectories(output)
    val config = MorphWireAssignmentPasses(SpinalConfig(
      targetDirectory = output.resolve("rtl").toString,
      oneFilePerComponent = false, headerWithDate = false, headerWithRepoHash = true))
    config.netlistFileName = "LaneExpressionExample.v"
    config.phasesInserters += { phases: ArrayBuffer[Phase] =>
      val production = phases.indexWhere(_.getClass.getName == "morphhdl.examples.ProductionWireAssignmentPhase")
      require(production >= 0, "production wire-assignment phase missing")
      phases.insert(production, observe("before-production", output))
      phases.insert(production + 2, observe("after-production", output))
      val emitter = phases.indexWhere(_.isInstanceOf[PhaseVerilog])
      require(emitter >= 0, "native Verilog phase missing")
      phases.insert(emitter, observe("before-emission", output))
    }
    MorphVerilog(config) {
      new LaneExpressionExample(HdlBool.param("PPC4", default = false).asElabBool.toElabInt * 3 + 1)
    }
  }
}
