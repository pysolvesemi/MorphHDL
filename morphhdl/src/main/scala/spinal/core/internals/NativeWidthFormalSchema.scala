package spinal.core.internals

import spinal.core._

/** Definition/instance separation for native packed-width formals.
  * Authenticated ports and logical Vec read results identify derived geometry;
  * legacy widths retain their existing rules. This is never a source-ref lookup.
  */
private[internals] object NativeWidthFormalSchema {
  /** Native declaration/connection site, before the emitter chooses canonical
    * definitions. A child's port is rendered in its parent's actual namespace.
    */
  def publicationRange(owner: Component, value: BitVector): Option[String] = {
    val expression = if (value.component eq owner) {
      val owned = bindings(owner)
      (ParameterizedWidth.expressionOf(value).orElse(value match {
        case bits: Bits => ParameterizedVec.packedWidthExpressionOf(bits)
        case _ => None
      })).filter(e => e.parameters.nonEmpty &&
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
      } else NativePublicationWidth.validate(e, owner, value, role)
      require(e.default == value.getBitsWidth && e.minimum > 0,
        "native formal declaration/connection must preserve its own width witness")
      val reference = NativeLocalParameters.reference(owner, e).getOrElse(e.verilog)
      if (e.parameters.isEmpty && e.parameterRoots.isEmpty && e.generateIndex.isEmpty)
        if (e.default == 1) "" else s"[${e.default - 1}:0]"
      else s"[$reference-1:0]"
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

  private[internals] def ownsExpression(component: Component,
      expression: ElaborationIntegerExpression): Boolean = {
    val declared = ExternalFormalParameterRegistry.completeTypedBindingsOf(component)
    expression.parameters.nonEmpty && expression.parameters.forall(p => declared.exists(_.binding.formal eq p))
  }

  def bindings(component: Component): Vector[ExternalTypedFormalBinding] = {
    val declared = ExternalFormalParameterRegistry.completeTypedBindingsOf(component)
    if (declared.isEmpty) return Vector.empty
    val ports = component.getOrdredNodeIo.toVector.flatMap(portBinding)
    val geometryParameters = component.getOrdredNodeIo.toVector.flatMap { port =>
      ParameterizedWidth.expressionOf(port).filter(ownsExpression(component, _)).toVector.flatMap { expression =>
        NativePublicationWidth.validate(expression, component, port, "native derived port formal")
        expression.parameters
      }
    }
    // A fixed public port may still feed a logical-width packed Vec. Its
    // exact retained read result supplies width provenance just like a port;
    // the native audit carrier's finite witness is not definition geometry.
    val packedParameters = ParameterizedVec.retainedVectorsOf(component)
      .filterNot(TypedBalancedReductionBackend.ownsConsumedProbe).flatMap { vector =>
        ParameterizedVec.operationsOf(vector).collect { case read: ParameterizedVecPackedRead => read }
          .flatMap { read =>
            ParameterizedVec.packedWidthExpressionOf(read.result).filter(ownsExpression(component, _)).toVector.flatMap { expression =>
              NativePublicationWidth.validate(expression, component, read.result, "native packed Vec formal")
              expression.parameters
            }
          }
      }
    ExternalFormalParameterRegistry.completeTypedBindingsOf(component)
      .filter(entry => ports.exists(_.declarationToken eq entry.declarationToken) ||
        (geometryParameters ++ packedParameters).exists(_ eq entry.binding.formal))
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
      case _ =>
        val a = portSchema(left)
        val b = portSchema(right)
        a.nonEmpty && a == b && a.get.parameters.forall { parameter =>
          val l = bindings(left.component).filter(_.binding.formal.name == parameter.name)
          val r = bindings(right.component).filter(_.binding.formal.name == parameter.name)
          l.size == 1 && r.size == 1 && compatible(l.head, r.head)
        }
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
      parameters: Vector[ElaborationIntegerParameter],
      canonicalSchema: Boolean = false): Vector[ElaborationIntegerParameter] = {
    val owned = bindings(component)
    parameters.map { p =>
      owned.find(_.binding.formal eq p) match {
        // Literal public defaults retain established formalParam publication.
        // Definition equivalence still ignores instance actuals, including two
        // literal instances with different widths sharing one child definition.
        case Some(entry) if canonicalSchema || entry.binding.actual.parameters.nonEmpty ||
            entry.binding.actual.parameterRoots.nonEmpty || entry.binding.actual.generateIndex.nonEmpty =>
          p.copy(default = p.minimum)
        case _ => p
      }
    }
  }

  /** Normalize publication only where one authenticated definition represents
    * distinct width defaults or literal/symbolic publication modes. A single
    * supplied literal keeps its public default.
    */
  def sharedDefaults(candidates: Vector[Component]): Set[String] =
    candidates.flatMap(bindings).groupBy(_.binding.formal.name).collect {
      case (name, entries) if entries.map(_.binding.formal.default).distinct.size > 1 ||
          entries.map { entry =>
            val actual = entry.binding.actual
            actual.parameters.nonEmpty || actual.parameterRoots.nonEmpty || actual.generateIndex.nonEmpty
          }.distinct.size > 1 => name
    }.toSet

  /** Literal formals historically substitute the range after native column
    * formatting. Restore that spacing only at final publication, after native
    * definition sharing has compared the symbolic bodies.
    */
  def literalPortPadding(component: Component, verilog: String,
      sharedDefaults: Set[String] = Set.empty): String = {
    component.getOrdredNodeIo.toVector.foldLeft(verilog) { (text, port) =>
      portBinding(port).filter { entry =>
        val actual = entry.binding.actual
        !sharedDefaults.contains(entry.binding.formal.name) &&
          actual.parameters.isEmpty && actual.parameterRoots.isEmpty && actual.generateIndex.isEmpty
      } match {
        case Some(_) =>
          val concrete = if (port.getBitsWidth == 1) "" else s"[${port.getBitsWidth - 1}:0]"
          val padding = " " * (1 + math.max(0, 8 - concrete.length))
          val declaration = ("(?m)^(\\s*(?:input|output|inout)\\s+(?:wire|reg)\\s+" +
            "(?:signed\\s+)?\\[[^\\]\\n]+\\]) +(" +
            java.util.regex.Pattern.quote(port.getName()) + "\\b)").r
          declaration.replaceAllIn(text, matched => java.util.regex.Matcher.quoteReplacement(
            matched.group(1) + padding + matched.group(2)))
        case _ => text
      }
    }
  }

  def portSchema(port: BaseType): Option[ElaborationIntegerExpression] = portBinding(port).map { entry =>
    val expression = ParameterizedWidth.expressionOf(port).get
    ExternalFormalParameterRegistry.normalizedDefinitionSchema(expression)
      .copy(default=entry.binding.formal.minimum,
        parameters=Vector(entry.binding.formal.copy(default=entry.binding.formal.minimum)))
  }.orElse {
    val owned = bindings(port.component)
    ParameterizedWidth.expressionOf(port).filter(expression =>
      expression.parameters.nonEmpty &&
        expression.parameters.forall(p => owned.exists(_.binding.formal eq p))).map { expression =>
      NativePublicationWidth.validate(expression, port.component, port, "canonical derived port")
      ExternalFormalParameterRegistry.normalizedDefinitionSchema(expression).copy(
        default = expression.minimum,
        parameters = expression.parameters.map(p => p.copy(default = p.minimum)).sortBy(_.name))
    }
  }
}
