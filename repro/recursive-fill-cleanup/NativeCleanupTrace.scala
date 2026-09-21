package morphhdl.examples

import java.util.IdentityHashMap
import spinal.core._
import spinal.core.internals._

/** Read-only diagnostic probe. It uses the production candidate inventories and
  * proofs, and prints all declarations including those excluded before proof.
  * Reflection is confined to this reproduction, never compiler selection.
  */
object NativeCleanupTrace {
  def install(config: SpinalConfig): Unit = config.phasesInserters += { phases =>
    val ids = new IdentityHashMap[BaseType, java.lang.Integer]()
    def id(v: BaseType): Int = {
      if (!ids.containsKey(v)) ids.put(v, ids.size())
      ids.get(v).intValue
    }
    def call(obj: AnyRef, method: String, args: AnyRef*): Any = {
      val m = obj.getClass.getDeclaredMethods.find(_.getName == method).get
      m.setAccessible(true)
      m.invoke(obj, args: _*)
    }
    def snapshot(label: String, intent: Option[NativeConditionSourceIntent]) = new Phase {
      override def hasNetlistImpact: Boolean = false
      override def impl(pc: PhaseContext): Unit = {
        val passes: Vector[(String, AnyRef)] = Vector(
          "unnamed-alias" -> new UnnamedWireAliasNativePhase(intent),
          "named-alias" -> new NamedWireAliasNativePhase(sourceIntent = intent),
          "unnamed-expression" -> new NamedWireExpressionNativePhase(true, intent),
          "named-expression" -> new NamedWireExpressionNativePhase(false, intent))
        val inventories = passes.map { case (name, pass) =>
          val candidates = call(pass, "candidateSnapshot", pc).asInstanceOf[Vector[AnyRef]]
          (name, pass, candidates)
        }
        println("NATIVE-TRACE-START\t" + label)
        pc.components().foreach(_.dslBody.walkDeclarations {
          case v: BaseType =>
            val driver = if (v.hasOnlyOneStatement) v.head match {
              case a: DataAssignmentStatement =>
                val refs = scala.collection.mutable.ArrayBuffer.empty[Int]
                a.source match { case b: BaseType => refs += id(b); case _ => }
                a.source.walkExpression { case b: BaseType => refs += id(b); case _ => }
                a.source.getClass.getSimpleName + ":refs=" + refs.distinct.mkString(",") +
                  ":whole=" + ((a.target eq v) && (a.finalTarget eq v)) +
                  ":root=" + (a.parentScope eq v.rootScopeStatement)
              case s => s.getClass.getSimpleName
            } else "multiple-or-no-drivers"
            val decisions = inventories.map { case (name, pass, candidates) =>
              candidates.find(c => call(c, "alias").asInstanceOf[BaseType] eq v) match {
                case None => name + "=excluded-before-proof"
                case Some(c) => name + "=" + call(pass, "proveCandidate", pc, c).toString
              }
            }
            val width = try NativeWidthProvenance.optionalWidthOf(v).map(_.verilog).getOrElse("unavailable")
              catch { case e: Exception => "diagnostic:" + e.getMessage }
            println((Vector("NATIVE-TRACE", label, "id=" + id(v), "name=" + v.getName(""),
              "origin=" + NativeWireNameProvenance.origin(v), "comb=" + v.isComb,
              "local=" + v.isDirectionLess, "analog=" + v.isAnalog, "typeNode=" + v.isTypeNode,
              "vital=" + v.isVital, "sourceIntent=" + intent.exists(_.permits(v)),
              "scope=" + (v.parentScope != null && (v.parentScope eq v.rootScopeStatement)),
              "oneDriver=" + v.hasOnlyOneStatement, "frozen=" + v.isFrozen(),
              "tags=" + v.getTags().map(_.getClass.getSimpleName).mkString(","),
              "publicationOwned=" + NativeWireAssignmentMetadata.retains(v),
              "width=" + width, "driver=" + driver) ++ decisions).mkString("\t"))
          case _ =>
        })
        println("NATIVE-TRACE-END\t" + label)
      }
    }
    val production = phases.indexWhere(_.getClass.getSimpleName == "ProductionWireAssignmentPhase")
    val intent = phases.collectFirst { case i: NativeConditionSourceIntent => i }
    if (production >= 0) {
      phases.insert(production, snapshot("before-cleanup", intent))
      phases.insert(production + 2, snapshot("after-cleanup", intent))
    }
    val allocated = phases.indexWhere(_.isInstanceOf[PhaseAllocateNames])
    require(allocated >= 0)
    phases.insert(allocated + 1, snapshot("allocated", intent))
  }
}
