package spinal.core

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
  /** Runtime supplies an exact, identity-validated lexical owner. Core keeps
    * no dependency on the structural capture implementation. The owner must
    * validate its completed path before returning any publication authority.
    */
  private[spinal] trait Scope {
    def component: Component
    def validate(): Unit
    def classify(condition: ElabBool, baseline: ElabBool.Truth): ElabBool.Truth = baseline
    def admitted(root: ElaborationIntegerParameterRoot, universe: Set[BigInt]): Set[BigInt]
    def parameters: Vector[ElaborationIntegerParameter]
    def roots: Vector[ElaborationIntegerParameterRoot]
    def wrap(body: String, allocate: String => String): String
  }
  private final case class Obligation(condition: ElaborationBooleanExpression,
      encoded: ElaborationIntegerExpression, message: String, location: Option[String],
      scope: Option[Scope])
  private object StorageKey
  private final class Storage(val component: Component) {
    var obligations = Vector.empty[Obligation]
    var declarationDomains = Vector.empty[ElaborationIntegerParameter]
  }
  /** The native publication phase supplies the validated parameter inventory
    * for explicit formal APIs. These declaration contracts must also reject
    * downstream overrides outside the domain used to prove the hardware.
    */
  private[spinal] def retainDeclarationDomains(component: Component,
      parameters: Vector[ElaborationIntegerParameter]): Unit = {
    val retained = storage(component)
    require(retained.declarationDomains.isEmpty, "parameter domain publication prepared twice")
    require(parameters.map(_.name).distinct.size == parameters.size, "ambiguous parameter domains")
    retained.declarationDomains = parameters
  }
  private def checked(component: Component, retained: Storage): Storage = {
    if (retained.component ne component)
      ParameterizedVerilogException.fail("SPINAL-ELAB-REQUIRE-OWNER-MISMATCH",
        "legality records cannot transfer to another Component", None)
    retained
  }
  private def storage(component: Component): Storage =
    checked(component, component.userCache.getOrElseUpdate(StorageKey, new Storage(component)).asInstanceOf[Storage])
  private def records(component: Component): Vector[Obligation] =
    Option(component).flatMap(_.userCache.get(StorageKey)).map(value => checked(component, value.asInstanceOf[Storage]).obligations)
      .getOrElse(Vector.empty)
  private val activeScope = new ScopeProperty[Option[() => Scope]] {
    override def default: Option[() => Scope] = None
  }

  private[spinal] def withScope[T](factory: () => Scope)(body: => T): T = {
    val previous = activeScope.set(Some(factory))
    try body finally previous.restore()
  }
  private[spinal] def checkpoint(component: Component): Int = records(component).size
  private[spinal] def rollback(component: Component, size: Int): Unit = {
    val retained = records(component)
    require(size >= 0 && size <= retained.size, "invalid legality capture checkpoint")
    if (retained.size != size) storage(component).obligations = retained.take(size)
  }
  private[spinal] def transaction[T](component: Component)(body: => T): T = {
    val before = checkpoint(component)
    try body catch { case error: Throwable => rollback(component, before); throw error }
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

  private def validate(record: Obligation, completed: Boolean = true): Unit = {
    if (completed) record.scope.foreach(_.validate())
    ElaborationProductDomain.ownerWithCompact(record.encoded, "symbolic legality publication", record.location)(
      (root, universe) => if (completed) record.scope.map(_.admitted(root, universe)).getOrElse(universe)
        else ElaborationDomainContext.admitted(root, universe),
      (_, _) => ()
    ).getOrElse(ParameterizedVerilogException.fail(Missing, "legality expression lost native authority", record.location))
    ()
  }

  private[spinal] def requireSymbolic(condition: ElabBool, message: => Any, location: Option[String]): Unit = {
    val baseline = classification(condition)
    if (baseline == ElabBool.AlwaysTrue) return
    val scope = activeScope.get.map(_())
    val truth = scope.map(_.classify(condition, baseline)).getOrElse(baseline)
    if (truth == ElabBool.AlwaysTrue) return
    if (truth == ElabBool.AlwaysFalse && scope.isEmpty)
      ParameterizedVerilogException.fail("SPINAL-ELAB-REQUIRE-ALWAYS-FALSE", String.valueOf(message), location)
    if (!enabled)
      ParameterizedVerilogException.fail("SPINAL-ELAB-REQUIRE-PUBLICATION-CONTEXT-MISSING",
        "a deferred requirement needs a native parameterized Component", location)
    if (scope.isEmpty && ElaborationDomainContext.hasActiveRestrictions)
      ParameterizedVerilogException.fail("SPINAL-ELAB-REQUIRE-STRUCTURAL-SCOPE-UNSUPPORTED",
        "mixed symbolic legality under a structural branch needs an owner-scoped obligation", location)
    scope.foreach(value => require(value.component eq Component.current,
      "legality scope belongs to another component"))
    val projected = condition.projectedExpression("symbolic legality")
    val encoded = condition.toElabInt.projectedExpression("symbolic legality")
    val record = Obligation(projected, encoded, String.valueOf(message), location, scope)
    validate(record, completed = false)
    val retained = storage(Component.current)
    retained.obligations :+= record
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
    values.foreach(value => validate(value))
    values.flatMap(value => value.encoded.parameters ++ value.scope.toVector.flatMap(_.parameters)).distinct
  }
  private[spinal] def rootsOf(component: Component): Vector[ElaborationIntegerParameterRoot] = {
    val values = records(component)
    values.foreach(value => validate(value))
    values.flatMap(value => value.encoded.completedParameterRoots ++ value.scope.toVector.flatMap(_.roots)).distinct
  }
  private[spinal] def hasRequirements(component: Component): Boolean = records(component).nonEmpty

  /** Called by ComponentEmitterVerilog, not a generated-text post-processor. */
  private[core] def render(component: Component, allocate: String => String): String = {
    val values = records(component)
    val domains = component.userCache.get(StorageKey).map(value => checked(component, value.asInstanceOf[Storage]).declarationDomains).getOrElse(Vector.empty)
    if (values.isEmpty && domains.isEmpty) return ""
    values.foreach(value => validate(value))
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
      val label = allocate(s"G_PARAMETER_LEGALITY_$index")
      val diagnostic = s"""    if (!(${value.condition.verilog})) begin : $label
         |      initial $$fatal(1, "%s", "${quote(value.message)}");
         |    end
         |""".stripMargin
      value.scope.map(_.wrap(diagnostic, allocate)).getOrElse(diagnostic)
    }.mkString
    val domainGuards = domains.map { parameter =>
      val label = allocate(s"G_PARAMETER_DOMAIN_${parameter.name}")
      s"""    if ((${parameter.name} < ${parameter.minimum}) || (${parameter.name} > ${parameter.maximum}) || (^${parameter.name} === 1'bx)) begin : $label
         |      initial $$fatal(1, "%s", "${parameter.name} must be in ${parameter.minimum}..${parameter.maximum}");
         |    end
         |""".stripMargin
    }.mkString
    // A failed require is fatal, not a recoverable simulation report. Use one
    // task carrying the original message, with a fixed format so user '%' text
    // is literal. The finish_number is 1; the simulator determines its nonzero
    // process status. This diagnostic is never synthesized hardware.
    "\n`ifndef SYNTHESIS\n  generate\n" + guards + domainGuards + "  endgenerate\n`endif\n"
  }
}
