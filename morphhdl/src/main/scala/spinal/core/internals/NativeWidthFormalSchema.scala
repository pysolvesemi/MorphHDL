package spinal.core.internals

import spinal.core._

/** Definition/instance separation for direct native packed-width formals.
  * Only authenticated port declarations participate; legacy widths retain
  * their existing canonical rules. This is never a source-ref lookup.
  */
private[internals] object NativeWidthFormalSchema {
  /** Native declaration/connection site, before the emitter chooses canonical
    * definitions. A child's port is rendered in its parent's actual namespace.
    */
  def publicationRange(owner: Component, value: BitVector): Option[String] = {
    val expression = if (value.component eq owner) {
      val owned = bindings(owner)
      ParameterizedWidth.expressionOf(value).filter(e => e.parameters.nonEmpty &&
        e.parameters.forall(p => owned.exists(_.binding.formal eq p)))
    } else if ((value.component.parent eq owner) && value.isIo) {
      portBinding(value).map(_.binding.actual).orElse {
        if (!value.component.isInstanceOf[BlackBox]) None
        else {
          val owned = bindings(owner)
          ParameterizedWidth.expressionOf(value).filter(e => e.parameters.nonEmpty &&
            e.parameters.forall(p => owned.exists(_.binding.formal eq p)))
        }
      }
    } else None
    expression.map { e =>
      val role = "native definition width"
      if (value.component ne owner) {
        // A child's actual belongs to its exact completed parent capture.
        // Publication cannot reopen that branch as a module-wide domain.
        def admitted(root: ElaborationIntegerParameterRoot, universe: Set[BigInt]): Set[BigInt] =
          ParameterizedStructure.exactChildDomainOf(owner, value.component, root, universe,
            role, e.sourceLocation).values
        if (ElaborationProductDomain.isRetained(e))
          ElaborationProductDomain.owner(e, role, e.sourceLocation)(admitted).get
        else if (ElaborationWidthAuthority.ownerEvaluation(e, role, e.sourceLocation)(admitted).isEmpty)
          ElabInt.authoritativeIntegerOwnerDomain(e, role,
            "SPINAL-PARAMETERIZED-VERILOG-FORMAL-WIDTH-AUTHORITY-MISSING")(admitted)
      } else ElaborationWidthAuthority.requireAuthoritative(e, role,
        "SPINAL-PARAMETERIZED-VERILOG-FORMAL-WIDTH-AUTHORITY-MISSING")
      require(e.default == value.getBitsWidth && e.minimum > 0,
        "native formal declaration/connection must preserve its own width witness")
      val reference = NativeLocalParameters.reference(owner, e).getOrElse(e.verilog)
      s"[$reference-1:0]"
    }
  }

  def portBinding(port: BaseType): Option[ExternalTypedFormalBinding] =
    ExternalFormalParameterRegistry.typedBindingOf(port).filter { entry =>
      ParameterizedWidth.parameterOf(port).exists(_ eq entry.binding.formal) &&
        ParameterizedWidth.expressionOf(port).exists { expression =>
          expression.generateIndex.isEmpty && expression.verilog == entry.binding.formal.name &&
            expression.parameters.size == 1 && (expression.parameters.head eq entry.binding.formal)
        }
    }

  def bindings(component: Component): Vector[ExternalTypedFormalBinding] = {
    val ports = component.getOrdredNodeIo.toVector.flatMap(portBinding)
    ExternalFormalParameterRegistry.completeTypedBindingsOf(component)
      .filter(entry => ports.exists(_.declarationToken eq entry.declarationToken))
  }

  def compatible(left: ExternalTypedFormalBinding, right: ExternalTypedFormalBinding): Boolean = {
    val a = left.binding
    val b = right.binding
    a.formal.name == b.formal.name && a.formal.minimum == b.formal.minimum &&
      a.formal.maximum == b.formal.maximum && a.declarationKey == b.declarationKey &&
      a.ownerClassName == b.ownerClassName
  }

  def portCompatible(left: BaseType, right: BaseType): Boolean =
    (portBinding(left), portBinding(right)) match {
      case (Some(a), Some(b)) => compatible(a, b)
      case _ => false
    }

  def parameterCompatible(canonical: Component, actual: Component,
      parameter: ElaborationIntegerParameter, witness: ElaborationIntegerParameter): Boolean = {
    val a = bindings(canonical).filter(_.binding.formal eq parameter)
    val b = bindings(actual).filter(_.binding.formal eq witness)
    a.size == 1 && b.size == 1 && compatible(a.head, b.head)
  }

  def witness(canonical: Component, actual: Component,
      parameter: ElaborationIntegerParameter): ElaborationIntegerParameter =
    bindings(actual).find(entry => parameterCompatible(canonical, actual, parameter, entry.binding.formal))
      .map(_.binding.formal).getOrElse(parameter)

  def definitionParameters(component: Component,
      parameters: Vector[ElaborationIntegerParameter]): Vector[ElaborationIntegerParameter] = {
    val owned = bindings(component)
    parameters.map(p => if(owned.exists(_.binding.formal eq p)) p.copy(default=p.minimum) else p)
  }

  def portSchema(port: BaseType): Option[ElaborationIntegerExpression] = portBinding(port).map { entry =>
    val expression = ParameterizedWidth.expressionOf(port).get
    ExternalFormalParameterRegistry.normalizedDefinitionSchema(expression)
      .copy(default=entry.binding.formal.minimum,
        parameters=Vector(entry.binding.formal.copy(default=entry.binding.formal.minimum)))
  }
}
