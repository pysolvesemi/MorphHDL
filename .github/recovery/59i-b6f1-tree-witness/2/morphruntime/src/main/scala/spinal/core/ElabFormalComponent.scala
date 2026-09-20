package spinal.core

/** Typed scalar-formal boundary for one ordinary native child Component.
  *
  * The constructor receives a fresh definition-side [[ElabInt]] root.  The
  * child instance retains the caller's exact actual expression through the
  * generic formal registry; no native-Int shadow capture participates.
  */
object ElabFormalComponent {
  private val PortableIdentifier = "[A-Za-z_][A-Za-z0-9_]*".r

  /** One named actual and the complete positive domain of its child formal. */
  final case class Parameter(actual: ElabInt, name: String, minimum: BigInt, maximum: BigInt)

  /** Construct one child with its entire typed formal inventory in one call.
    * The callback receives definition-side values in the supplied slot order.
    * It cannot install one slot and subsequently append or replace another.
    */
  def parameters[C <: Component](specifications: Seq[Parameter])(
      constructor: Vector[ElabInt] => C
  ): C = {
    if (specifications == null || specifications.isEmpty || specifications.exists(_ == null))
      fail("SPINAL-ELAB-FORMAL-INVENTORY-INVALID",
        "typed component construction requires one nonempty complete parameter inventory", None)
    if (constructor == null)
      fail("SPINAL-ELAB-FORMAL-CONSTRUCTOR-NULL", "typed component requires a non-null constructor", None)
    val specs = specifications.toVector
    if (specs.map(_.name).distinct.size != specs.size)
      fail("SPINAL-ELAB-FORMAL-INVENTORY-DUPLICATE", "typed inventory contains duplicate formal names", None)
    val prepared = specs.map { spec =>
      if (spec.actual == null)
        fail("SPINAL-ELAB-FORMAL-ACTUAL-NULL", s"typed formal '${spec.name}' requires a non-null actual", None)
      val code = if (spec.actual.parameters.isEmpty) "SPINAL-ELAB-FORMAL-ACTUAL-LITERAL-INVALID"
        else "SPINAL-ELAB-FORMAL-ACTUAL-EXACT-DOMAIN-REQUIRED"
      spec.actual.requireAuthoritativeIntegerDomain("typed formal inventory actual", code, requireExactExtrema = false)
      val actual = spec.actual.projectedExpression("typed formal inventory actual")
      ElabInt.requireAuthoritativeIntegerDomain(actual, "typed formal inventory actual", code, requireExactExtrema = true)
      val source = actual.sourceLocation.orElse(Some(s"<typed-formal:${spec.name}>"))
      if (spec.name == null || !PortableIdentifier.pattern.matcher(spec.name).matches())
        fail("SPINAL-ELAB-FORMAL-NAME-INVALID", s"typed formal name '${spec.name}' is invalid", source)
      if (spec.minimum == null || spec.maximum == null || spec.minimum < 1 ||
          spec.maximum < spec.minimum || spec.maximum > BigInt(Int.MaxValue) ||
          actual.generateIndex.nonEmpty || actual.minimum < spec.minimum || actual.maximum > spec.maximum ||
          actual.default < spec.minimum || actual.default > spec.maximum)
        fail("SPINAL-ELAB-FORMAL-DOMAIN-INVALID",
          s"typed formal '${spec.name}' actual does not fit its complete positive domain", source)
      val formal = ElaborationIntegerParameter(spec.name, actual.default, spec.minimum, spec.maximum)
      (formal, actual, source)
    }
    val parent = Option(Component.current).getOrElse {
      fail("SPINAL-ELAB-FORMAL-PARENT-MISSING", "typed inventory requires an active parent Component", None)
    }
    val child = constructor(prepared.map { case (formal, _, source) => ElabInt.directParameter(formal, source) })
    if (child == null)
      fail("SPINAL-ELAB-FORMAL-COMPONENT-NULL", "typed inventory constructor returned null", None)
    if (child.parent ne parent)
      fail("SPINAL-ELAB-FORMAL-PARENT-MISMATCH", "typed inventory child belongs to a foreign parent", None)
    // Validate the existing Vec boundary before publishing any new slot. Its
    // unchanged retention operation is then safe for the complete inventory.
    val vectors = ParameterizedVec.retainedVectorsOf(child)
    prepared.foreach { case (formal, actual, _) =>
      vectors.foreach { vector =>
        ParameterizedVec.formalBindingsOf(vector).find(_.formal eq formal).foreach { previous =>
          if (!ElabInt.equivalentExpression(previous.actual, actual))
            fail("SPINAL-ELAB-VEC-FORMAL-ACTUAL-CONFLICT",
              s"typed Vec formal '${formal.name}' has a conflicting actual", actual.sourceLocation)
        }
      }
    }
    ExternalFormalParameterRegistry.retainTypedComponentParameters(child, prepared)
    prepared.foreach { case (formal, actual, _) => ParameterizedVec.retainComponentFormal(child, formal, actual) }
    child
  }

  def parameter[C <: Component](
      actual: ElabInt,
      name: String,
      minimum: BigInt,
      maximum: BigInt
  )(constructor: ElabInt => C): C = {
    if (actual == null)
      fail(
        "SPINAL-ELAB-FORMAL-ACTUAL-NULL",
        s"typed formal '$name' requires a non-null ElabInt actual",
        None
      )
    val authoredFailureCode =
      if (actual.parameters.isEmpty)
        "SPINAL-ELAB-FORMAL-ACTUAL-LITERAL-INVALID"
      else "SPINAL-ELAB-FORMAL-ACTUAL-EXACT-DOMAIN-REQUIRED"
    val trustedSymbolic = ElaborationProductDomain.isRetained(actual.expression)
    if (trustedSymbolic) ElaborationProductDomain.requireInteger(actual.expression, "typed formal actual")
    else actual.requireAuthoritativeIntegerDomain(
      "typed formal actual", authoredFailureCode, requireExactExtrema = false)
    val expression = actual.projectedExpression("typed formal actual")
    if (trustedSymbolic) {
      // Scalar binding itself needs an authentic parent expression, not a joint
      // value table. A fresh definition-side root below still supplies the
      // child's structural authority. Keep projected compound bindings closed.
      ElaborationProductDomain.owner(expression, "typed formal actual", expression.sourceLocation) {
        (_, universe) => universe
      }
    } else ElabInt.requireAuthoritativeIntegerDomain(
      expression, "typed formal actual", authoredFailureCode, requireExactExtrema = true)
    val source = expression.sourceLocation.orElse(Some(s"<typed-formal:$name>"))
    // A validated parameter-free expression is the literal-authoritative path:
    // the generic formal registry emits that concrete actual directly while the
    // child definition still receives its fresh formal. Symbolic expressions
    // retain the shared validator's exact root/schema JVM identity.
    if (constructor == null)
      fail(
        "SPINAL-ELAB-FORMAL-CONSTRUCTOR-NULL",
        s"typed formal '$name' requires a non-null constructor",
        source
      )
    if (name == null || !PortableIdentifier.pattern.matcher(name).matches())
      fail(
        "SPINAL-ELAB-FORMAL-NAME-INVALID",
        s"typed formal name '$name' is not a portable Verilog identifier",
        source
      )
    if (
      minimum < 1 || maximum < minimum ||
      maximum > BigInt(Int.MaxValue) ||
      expression.generateIndex.nonEmpty ||
      expression.minimum < minimum || expression.maximum > maximum ||
      expression.default < minimum || expression.default > maximum
    )
      fail(
        "SPINAL-ELAB-FORMAL-DOMAIN-INVALID",
        s"typed formal '$name' cannot admit actual '${expression.verilog}' default ${expression.default} in [${expression.minimum}, ${expression.maximum}] inside [$minimum, $maximum]",
        source
      )

    val parent = Option(Component.current).getOrElse {
      fail(
        "SPINAL-ELAB-FORMAL-PARENT-MISSING",
        s"typed formal '$name' must be constructed inside an active parent Component",
        source
      )
    }
    val formal = ElaborationIntegerParameter(
      name,
      expression.default,
      minimum,
      maximum
    )
    val definitionValue = ElabInt.directParameter(formal, source)
    val component = constructor(definitionValue)
    if (component == null)
      fail(
        "SPINAL-ELAB-FORMAL-COMPONENT-NULL",
        s"typed formal '$name' constructor returned null",
        source
      )
    if (component.parent ne parent) {
      val actualParent =
        Option(component.parent).map(_.getClass.getName).getOrElse("<none>")
      fail(
        "SPINAL-ELAB-FORMAL-PARENT-MISMATCH",
        s"typed formal '$name' child belongs to '$actualParent' instead of '${parent.getClass.getName}'",
        source
      )
    }
    // The typed registry mints one opaque per-instance declaration capability
    // and attaches it to this exact component and its exact dependent ports.
    // Class, definition, instance and source-key text remain diagnostics only.
    ExternalFormalParameterRegistry.retainTypedComponent(
      component,
      formal,
      expression,
      source
    )
    ParameterizedVec.retainComponentFormal(component, formal, expression)
    component
  }

  /** Recover the caller-side actual only through this exact child's opaque
    * typed capability, and refresh Vecs created after the constructor returned.
    *
    * Native helpers may add hierarchy surfaces later through `rework`/`pull`.
    * Those Vecs did not exist during [[parameter]], so they must receive the
    * same exact formal capability before a caller-owned actual-depth clone is
    * allowed to bridge them. Class names, formal names and rendered
    * expressions do not participate in this lookup.
    */
  private[spinal] def parentActualAndRefreshVecFormals(
      component: Component
  ): Option[ElabInt] = {
    if (component == null)
      throw new IllegalArgumentException(
        "typed formal publication component must not be null"
      )
    ExternalFormalParameterRegistry.typedBindingsOf(component) match {
      case Vector() => None
      case Vector(retained) =>
        val binding = retained.binding
        val parent = Option(Component.current).getOrElse {
          fail(
            "SPINAL-ELAB-FORMAL-PUBLICATION-PARENT-MISSING",
            "late typed formal publication requires the active exact parent component",
            binding.sourceLocation
          )
        }
        if (component.parent ne parent)
          fail(
            "SPINAL-ELAB-FORMAL-PUBLICATION-PARENT-MISMATCH",
            "late typed formal publication is not executing in the exact child parent",
            binding.sourceLocation
          )
        ParameterizedVec.retainComponentFormal(
          component,
          binding.formal,
          binding.actual
        )
        Some(ElabInt.fromExpression(binding.actual))
      case values =>
        fail(
          "SPINAL-ELAB-FORMAL-PUBLICATION-BINDING-AMBIGUOUS",
          s"late typed formal publication found ${values.size} opaque bindings on one exact child",
          values.iterator.flatMap(_.binding.sourceLocation).toVector.headOption
        )
    }
  }

  private def fail(
      code: String,
      detail: String,
      sourceLocation: Option[String]
  ): Nothing =
    ParameterizedVerilogException.fail(code, detail, sourceLocation)
}
