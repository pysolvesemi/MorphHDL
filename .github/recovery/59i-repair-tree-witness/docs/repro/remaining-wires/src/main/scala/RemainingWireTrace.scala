package spinal.core.internals

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.collection.mutable.ArrayBuffer
import spinal.core._

/** Read-only native observations; no names, tags, expressions or passes change. */
object RemainingWireTrace {
  def install(config: SpinalConfig, directory: Path): Unit = {
    def observer(label: String): Phase = new Phase {
      override def hasNetlistImpact: Boolean = false
      override def impl(pc: PhaseContext): Unit = {
        val lines = ArrayBuffer.empty[String]
        val ids = new java.util.IdentityHashMap[Expression, java.lang.Integer]()
        def id(e: Expression): Int = {
          val old = ids.get(e)
          if (old != null) old.intValue
          else { val next = ids.size; ids.put(e, next); next }
        }
        pc.components().foreach { c =>
          val planned = VerilogEmitterExpressionInlining.redundantWrappers(c, pc.config)
          def describe(e: Expression, depth: Int = 0): String = {
            if (e == null) return "null"
            if (depth > 24) return "depth-limit"
            val detail = e match {
              case b: BaseType => s"name=${b.getName("")},unnamed=${b.isUnnamed},reg=${b.isReg},typeNode=${b.isTypeNode}"
              case r: Resize => s"size=${r.size},input=${describe(r.input, depth + 1)}"
              case other =>
                val children = ArrayBuffer.empty[String]
                other.foreachDrivingExpression(x => children += describe(x, depth + 1))
                children.mkString("children=[", ",", "]")
            }
            s"${id(e)}:${e.getClass.getSimpleName}(approved=${planned.containsKey(e)},$detail)"
          }
          c.dslBody.walkStatements {
            case d: DataAssignmentStatement =>
              lines += s"assign ${d.finalTarget.getName("")} reg=${d.finalTarget.isReg} <- ${describe(d.source)}"
            case w: WhenStatement => lines += s"when ${describe(w.cond)}"
            case _ =>
          }
        }
        Files.createDirectories(directory)
        Files.write(directory.resolve(label + ".txt"), (lines.mkString("\n") + "\n").getBytes(StandardCharsets.UTF_8))
      }
    }
    config.phasesInserters += { phases: ArrayBuffer[Phase] =>
      val pass = phases.indexWhere(_.getClass.getName == "morphhdl.examples.ProductionWireAssignmentPhase")
      require(pass >= 0, "Default production pass is missing")
      phases.insert(pass, observer("before-production"))
      phases.insert(pass + 2, observer("after-production"))
      val emitter = phases.indexWhere(_.isInstanceOf[PhaseVerilog])
      require(emitter >= 0, "Native Verilog emitter is missing")
      phases.insert(emitter, observer("before-emission"))
    }
  }
}
