package spinal.core

import java.lang.ref.{ReferenceQueue, WeakReference}
import scala.collection.mutable

/** Native, simulation-only parameter legality. This registry is not proof that
  * an obligation holds, nor may structural consumers use it to narrow a domain.
  * Only authenticated typed expressions can enter; records belong to the exact
  * live Component identity. No copyable public annotation grants this authority.
  */
object NativeSymbolicLegality {
  private object Enabled
  /** Backend mode setup; this flag alone grants no expression or proof authority. */
  def configure(config: SpinalConfig, enabled: Boolean): SpinalConfig = {
    require(config != null, "SpinalConfig must not be null")
    val flags = config.flags.clone()
    if (enabled) flags += Enabled else flags -= Enabled
    config.copy(flags = flags)
  }
  private val Missing = "SPINAL-ELAB-DOMAIN-PRODUCT-AUTHORITY-MISSING"
  private final case class Obligation(condition: ElaborationBooleanExpression,
      encoded: ElaborationIntegerExpression, message: String, location: Option[String])
  private final class Identity(value: Component, queue: ReferenceQueue[Component])
      extends WeakReference[Component](value, queue) {
    private val hash = System.identityHashCode(value)
    override def hashCode(): Int = hash
    override def equals(other: Any): Boolean = other match {
      case that: Identity => (this eq that) || ((get ne null) && (get eq that.get))
      case _ => false
    }
  }
  private val queue = new ReferenceQueue[Component]()
  private val storage = mutable.HashMap.empty[Identity, Vector[Obligation]]
  private def reap(): Unit = {
    var reference = queue.poll()
    while (reference != null) {
      storage.remove(reference.asInstanceOf[Identity]); reference = queue.poll()
    }
  }
  private def records(component: Component): Vector[Obligation] = synchronized {
    reap()
    if (component == null) Vector.empty else storage.getOrElse(new Identity(component, null), Vector.empty)
  }
  private def enabled: Boolean =
    try Component.current != null && GlobalData.get.config.flags.contains(Enabled)
    catch { case _: NullPointerException => false }

  /** Publication classification is deliberately not the exact/structural query.
    * Unknown means neither universal result was proved, not default == true.
    */
  private[spinal] def classification(condition: ElabBool): ElabBool.Truth = {
    if (condition eq null) throw new IllegalArgumentException("require condition must not be null")
    if (ElaborationProductDomain.isRetained(condition.expression))
      ElaborationProductDomain.publicationTruth(condition.expression)
    else ElabBool.projectedTruth(condition)
  }

  private def validate(record: Obligation): Unit = {
    // Top-level obligations have no captured branch restrictions. The exact
    // owner object is the private registry key; the AST must retain its own
    // native authority as well. Validating does not ask for universal truth.
    ElaborationProductDomain.owner(record.encoded, "symbolic legality publication", record.location) {
      (_, universe) => universe
    }.getOrElse(ParameterizedVerilogException.fail(Missing, "legality expression lost native authority", record.location))
    ()
  }

  private[spinal] def requireSymbolic(condition: ElabBool, message: => Any, location: Option[String]): Unit = {
    classification(condition) match {
      case ElabBool.AlwaysTrue => ()
      case ElabBool.AlwaysFalse =>
        ParameterizedVerilogException.fail("SPINAL-ELAB-REQUIRE-ALWAYS-FALSE", String.valueOf(message), location)
      case ElabBool.Unknown =>
        if (!enabled)
          ParameterizedVerilogException.fail("SPINAL-ELAB-REQUIRE-PUBLICATION-CONTEXT-MISSING",
            "a deferred requirement needs a native parameterized Component", location)
        // A requirement scoped under structural capture cannot be made global.
        // Keep this boundary fail-closed until obligations have structural-owner
        // activation predicates and participate in capture rollback.
        if (ElaborationDomainContext.hasActiveRestrictions)
          ParameterizedVerilogException.fail("SPINAL-ELAB-REQUIRE-STRUCTURAL-SCOPE-UNSUPPORTED",
            "mixed symbolic legality under a structural branch needs an owner-scoped obligation", location)
        val projected = condition.projectedExpression("symbolic legality")
        val encoded = condition.toElabInt.projectedExpression("symbolic legality")
        val record = Obligation(projected, encoded, String.valueOf(message), location)
        validate(record)
        synchronized {
          reap()
          val component = Component.current
          val key = new Identity(component, queue)
          storage.update(key, storage.getOrElse(key, Vector.empty) :+ record)
        }
    }
  }

  private[core] def packedWidth(raw: ElaborationIntegerExpression, role: String): ElaborationIntegerExpression = {
    if (raw.minimum > 0) return raw
    if (raw.default <= 0 || raw.parameters.isEmpty || !enabled || !ElaborationProductDomain.isRetained(raw))
      return raw // Existing concrete/default/domain validators give their precise diagnostic.
    requireSymbolic(ElabInt.fromExpression(raw) > 0,
      s"$role must be greater than zero: ${raw.verilog}", raw.sourceLocation)
    ElaborationProductDomain.safePackedWidth(raw)
  }

  private[spinal] def parametersOf(component: Component): Vector[ElaborationIntegerParameter] = {
    val values = records(component)
    values.foreach(validate)
    values.flatMap(_.encoded.parameters).distinct
  }
  private[spinal] def rootsOf(component: Component): Vector[ElaborationIntegerParameterRoot] = {
    val values = records(component)
    values.foreach(validate)
    values.flatMap(_.encoded.completedParameterRoots).distinct
  }
  private[spinal] def hasRequirements(component: Component): Boolean = records(component).nonEmpty

  /** Called by ComponentEmitterVerilog, not a generated-text post-processor. */
  private[core] def render(component: Component, allocate: String => String): String = {
    val values = records(component)
    if (values.isEmpty) return ""
    values.foreach(validate)
    def quote(text: String): String = text.flatMap {
      case '\\' => "\\\\"
      case '"' => "\\\""
      case '\n' => "\\n"
      case '\r' => "\\r"
      case '\t' => "\\t"
      case c if c < ' ' => " "
      case c => c.toString
    }
    val guards = values.zipWithIndex.map { case (value, index) =>
      val label = allocate(s"g_morphhdl_parameter_legality_$index")
      s"""    if (!(${value.condition.verilog})) begin : $label
         |      initial begin
         |        $$error("${quote(value.message)}");
         |        $$fatal(1, "MorphHDL parameter legality failed");
         |      end
         |    end
         |""".stripMargin
    }.mkString
    // Icarus $error alone does not fail the process. $fatal makes automation
    // fail deterministically too; both are excluded from synthesized hardware.
    "\n`ifndef SYNTHESIS\n  generate\n" + guards + "  endgenerate\n`endif\n"
  }
}
