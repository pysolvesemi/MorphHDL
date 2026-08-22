package spinal.core.internals

import java.util.regex.{Matcher, Pattern}

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

import spinal.core._

/**
  * MorphHDL-owned external analysis for ordinary Component hierarchy.
  *
  * The concrete SpinalHDL graph remains authoritative for component
  * construction, naming, port order and instance connections. This helper
  * only proves a bounded direct packed-width binding and rewrites the
  * already-emitted native Verilog instance with explicit named parameters.
  */
private[internals] object ExternalParameterizedVerilogHierarchy {
  private sealed trait BindingExpr {
    def render: String
    def default: BigInt
    def minimum: BigInt
    def maximum: BigInt
    def parameters: Vector[ElaborationIntegerParameter]
    final def isSymbolic: Boolean = parameters.nonEmpty
    final def range: String = s"[$render-1:0]"
  }

  private final case class ParameterBinding(
      parameter: ElaborationIntegerParameter
  ) extends BindingExpr {
    override def render: String = parameter.name
    override def default: BigInt = parameter.default
    override def minimum: BigInt = parameter.minimum
    override def maximum: BigInt = parameter.maximum
    override def parameters: Vector[ElaborationIntegerParameter] = Vector(parameter)
  }

  private final case class LiteralBinding(value: BigInt) extends BindingExpr {
    override def render: String = value.toString
    override def default: BigInt = value
    override def minimum: BigInt = value
    override def maximum: BigInt = value
    override def parameters: Vector[ElaborationIntegerParameter] = Vector.empty
  }

  private final case class ExpressionBinding(
      expression: ElaborationIntegerExpression
  ) extends BindingExpr {
    override def render: String = expression.verilog
    override def default: BigInt = expression.default
    override def minimum: BigInt = expression.minimum
    override def maximum: BigInt = expression.maximum
    override def parameters: Vector[ElaborationIntegerParameter] =
      expression.parameters.distinct.sortBy(_.name)
  }

  private final case class BindingSignature(
      render: String,
      default: BigInt,
      minimum: BigInt,
      maximum: BigInt,
      parameters: Vector[ElaborationIntegerParameter]
  )

  private final case class PortRewrite(name: String, width: BindingExpr)

  private final case class InstancePlan(
      definitionName: String,
      instanceName: String,
      bindings: Vector[(String, BindingExpr)],
      ports: Vector[PortRewrite]
  )

  private[internals] final class Plan private[ExternalParameterizedVerilogHierarchy] (
      val parameters: Vector[ElaborationIntegerParameter],
      val hasParameterizedInstances: Boolean,
      private val instances: Vector[InstancePlan]
  ) {
    def rewrite(verilog: String): (String, Vector[(String, String)]) = {
      var current = verilog
      val declarationRanges = ArrayBuffer.empty[(String, String)]

      instances.filter(_.bindings.nonEmpty).foreach { instance =>
        val lines = current.split("\\n", -1).toVector
        val plainStartPattern =
          ("^(\\s*)" + Pattern.quote(instance.definitionName) + "\\s+" +
            Pattern.quote(instance.instanceName) + "\\s*\\(\\s*$").r
        val parameterizedStartPattern =
          ("^(\\s*)" + Pattern.quote(instance.definitionName) +
            "\\s*#\\s*\\(\\s*$").r
        val parameterizedTerminatorPattern =
          ("^\\s*\\)\\s+" + Pattern.quote(instance.instanceName) +
            "\\s*\\(\\s*$").r
        val anyParameterizedTerminatorPattern =
          "^\\s*\\)\\s+[A-Za-z_][A-Za-z0-9_$]*\\s*\\(\\s*$".r

        val plainStarts = lines.zipWithIndex.collect {
          case (line, index)
              if plainStartPattern.findFirstIn(line).nonEmpty =>
            val indent = plainStartPattern.findFirstMatchIn(line).get.group(1)
            (index, index, indent)
        }
        val parameterizedStarts = lines.zipWithIndex.flatMap {
          case (line, index)
              if parameterizedStartPattern.findFirstIn(line).nonEmpty =>
            val terminator =
              (index + 1 until lines.size).find(candidate =>
                anyParameterizedTerminatorPattern
                  .findFirstIn(lines(candidate))
                  .nonEmpty
              )
            terminator.collect {
              case bodyStart
                  if parameterizedTerminatorPattern
                    .findFirstIn(lines(bodyStart))
                    .nonEmpty =>
                val indent =
                  parameterizedStartPattern.findFirstMatchIn(line).get.group(1)
                (index, bodyStart, indent)
            }
          case _ => None
        }
        val starts = plainStarts ++ parameterizedStarts
        if (starts.size != 1) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-INSTANCE-NOT-FOUND",
            s"normal Verilog emission contains ${starts.size} instances matching '${instance.definitionName} ${instance.instanceName}'"
          )
        }
        val (start, bodyStart, indent) = starts.head
        val end =
          (bodyStart + 1 until lines.size)
            .find(index => lines(index).trim == ");")
            .getOrElse {
            fail(
              "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-INSTANCE-NOT-FOUND",
              s"normal Verilog emission did not terminate instance '${instance.instanceName}'"
            )
          }
        var block = lines.slice(bodyStart, end + 1)

        instance.ports.foreach { port =>
          val marker = ("\\." + Pattern.quote(port.name) + "\\s*\\(").r
          val matchingLines = block.zipWithIndex.collect {
            case (line, index) if marker.findFirstIn(line).nonEmpty => index
          }
          if (matchingLines.size != 1) {
            fail(
              "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-PORT-CONNECTION-NOT-FOUND",
              s"instance '${instance.instanceName}' contains ${matchingLines.size} connections for port '${port.name}'"
            )
          }
          val lineIndex = matchingLines.head
          val connectionPattern =
            ("(\\." + Pattern.quote(port.name) + "\\s*\\(\\s*)" +
              "([A-Za-z_][A-Za-z0-9_$]*)(\\s*)\\[[^\\]]+\\](\\s*\\))").r
          val matches = connectionPattern.findAllMatchIn(block(lineIndex)).toVector
          if (matches.size != 1) {
            fail(
              "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-PORT-CONNECTION-UNSUPPORTED",
              s"port '${port.name}' of instance '${instance.instanceName}' is not connected through one direct portable packed signal"
            )
          }
          val matched = matches.head
          val signalName = matched.group(2)
          val replacement =
            matched.group(1) + signalName + matched.group(3) +
              port.width.range + matched.group(4)
          block = block.updated(
            lineIndex,
            connectionPattern.replaceFirstIn(
              block(lineIndex),
              Matcher.quoteReplacement(replacement)
            )
          )
          declarationRanges += signalName -> port.width.range
        }

        val bindingLines = instance.bindings.zipWithIndex.map {
          case ((name, expression), index) =>
            val comma = if (index == instance.bindings.size - 1) "" else ","
            s"${indent}  .$name(${expression.render})$comma"
        }
        val header =
          Vector(s"${indent}${instance.definitionName} #(") ++
            bindingLines ++
            Vector(s"${indent}) ${instance.instanceName} (")
        val rewrittenBlock = header ++ block.drop(1)
        current =
          (lines.take(start) ++ rewrittenBlock ++ lines.drop(end + 1)).mkString("\n")
      }

      val grouped = declarationRanges.groupBy(_._1)
      grouped.collectFirst {
        case (name, values) if values.map(_._2).distinct.size != 1 => name
      }.foreach { name =>
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-BINDING-CONFLICT",
          s"hierarchy connections infer conflicting declaration widths for signal '$name'"
        )
      }
      (
        current,
        grouped.toVector.map { case (name, values) => name -> values.head._2 }
      )
    }
  }

  def analyze(
      component: Component,
      pc: PhaseContext,
      canonicalOf: Component => Component
  ): Plan = {
    val assignments = ArrayBuffer.empty[DataAssignmentStatement]
    component.dslBody.walkLeafStatements {
      case assignment: DataAssignmentStatement => assignments += assignment
      case _                                    =>
    }

    val instances = component.children.toVector.map { child =>
      if (child.isInstanceOf[BlackBox]) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-BLACKBOX-UNSUPPORTED",
          s"component '${component.definitionName}' contains BlackBox child '${child.getName()}'; Increment 32 covers ordinary Component hierarchy only"
        )
      }
      val canonical = canonicalOf(child)
      analyzeInstance(component, child, canonical, assignments.toVector, pc)
    }

    val referenced = instances.flatMap(
      _.bindings.flatMap(_._2.parameters)
    )
    val grouped = referenced.groupBy(_.name)
    grouped.collectFirst {
      case (name, values) if values.distinct.size != 1 => name
    }.foreach { name =>
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-SCHEMA-CONFLICT",
        s"parameter '$name' has conflicting hierarchy binding declarations on component '${component.definitionName}'"
      )
    }
    new Plan(
      parameters = grouped.toVector.map(_._2.head).sortBy(_.name),
      hasParameterizedInstances = instances.exists(_.bindings.nonEmpty),
      instances = instances
    )
  }

  private def analyzeInstance(
      parent: Component,
      child: Component,
      canonical: Component,
      assignments: Vector[DataAssignmentStatement],
      pc: PhaseContext
  ): InstancePlan = {
    val instanceName = Option(child.getName()).filter(_.nonEmpty).getOrElse {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-INSTANCE-NAME-MISSING",
        s"child of '${parent.definitionName}' has no stable instance name after native emission"
      )
    }
    val definitionName = Option(canonical.definitionName).filter(_.nonEmpty).getOrElse {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-DEFINITION-NAME-MISSING",
        s"child '$instanceName' has no canonical definition name"
      )
    }

    val actualPorts = indexedPorts(child, "actual", instanceName)
    val canonicalPorts = indexedPorts(canonical, "canonical", instanceName)
    if (actualPorts.map(_._1) != canonicalPorts.map(_._1)) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-PORT-LAYOUT-MISMATCH",
        s"instance '$instanceName' and canonical definition '$definitionName' have different ordered port names"
      )
    }

    actualPorts.zip(canonicalPorts).foreach {
      case ((name, actual), (_, expected)) =>
        val actualDirection = directionOf(actual)
        val expectedDirection = directionOf(expected)
        if (
          actualDirection != expectedDirection ||
          actual.getClass != expected.getClass ||
          actual.getBitsWidth != expected.getBitsWidth
        ) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-PORT-LAYOUT-MISMATCH",
            s"port '$name' of instance '$instanceName' differs from canonical definition '$definitionName' in direction, data type or concrete witness width"
          )
        }
    }

    val actualByName = actualPorts.toMap
    val canonicalByName = canonicalPorts.toMap
    val canonicalParameters = ParameterizedWidth.parametersOf(canonical)
    val bindings = canonicalParameters.map { parameter =>
      val parameterPorts = canonicalPorts.collect {
        case (name, port)
            if ParameterizedWidth.parameterOf(port).exists(_.name == parameter.name) =>
          name
      }
      if (parameterPorts.isEmpty) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-BINDING-UNRESOLVED",
          s"parameter '${parameter.name}' of canonical child '$definitionName' is not exposed directly on a packed leaf port"
        )
      }

      parameterPorts.foreach { name =>
        val actualSchema = ParameterizedWidth.parameterOf(actualByName(name)).getOrElse {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-CANONICAL-SCHEMA-CONFLICT",
            s"port '$name' of instance '$instanceName' lost canonical parameter '${parameter.name}'"
          )
        }
        if (actualSchema != parameter) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-CANONICAL-SCHEMA-CONFLICT",
            s"port '$name' of instance '$instanceName' declares parameter '${actualSchema.name}' in [${actualSchema.minimum}, ${actualSchema.maximum}] with default ${actualSchema.default}, but canonical definition '$definitionName' requires '${parameter.name}' in [${parameter.minimum}, ${parameter.maximum}] with default ${parameter.default}"
          )
        }
      }

      val connectionBindings = distinctBindings(parameterPorts.flatMap { name =>
        connectionEvidence(
          parent,
          child,
          actualByName(name),
          assignments,
          s"port '$name' of instance '$instanceName'"
        )
      })
      val canonicalPortFormals = parameterPorts.flatMap { name =>
        ExternalFormalParameterRegistry.bindingOf(canonicalByName(name))
      }
      val actualPortFormals = parameterPorts.flatMap { name =>
        ExternalFormalParameterRegistry.bindingOf(actualByName(name))
      }
      val canonicalFormals = retainedFormals(
        component = canonical,
        portFormals = canonicalPortFormals,
        parameterPorts = parameterPorts,
        parameter = parameter,
        role = "canonical",
        instanceName = instanceName
      )
      val actualFormals = retainedFormals(
        component = child,
        portFormals = actualPortFormals,
        parameterPorts = parameterPorts,
        parameter = parameter,
        role = "actual",
        instanceName = instanceName
      )
      val hasExplicitFormal = canonicalFormals.nonEmpty || actualFormals.nonEmpty

      val expression =
        if (hasExplicitFormal) {
          if (
            canonicalFormals.size != parameterPorts.size ||
            actualFormals.size != parameterPorts.size
          ) {
            fail(
              "SPINAL-PARAMETERIZED-VERILOG-FORMAL-LAYOUT-CONFLICT",
              s"formal slot '${parameter.name}' of instance '$instanceName' is not retained on every canonical and actual packed port"
            )
          }
          (canonicalFormals ++ actualFormals).foreach { binding =>
            if (binding.formal != parameter) {
              fail(
                "SPINAL-PARAMETERIZED-VERILOG-FORMAL-SCHEMA-CONFLICT",
                s"formal slot '${binding.formal.name}' does not match canonical child parameter '${parameter.name}' of '$definitionName'",
                binding.sourceLocation
              )
            }
          }
          val canonicalKeys = canonicalFormals.map(_.declarationKey).distinct
          val actualKeys = actualFormals.map(_.declarationKey).distinct
          if (
            canonicalKeys.size != 1 || actualKeys.size != 1 ||
            canonicalKeys.head != actualKeys.head
          ) {
            fail(
              "SPINAL-PARAMETERIZED-VERILOG-FORMAL-SLOT-IDENTITY-CONFLICT",
              s"formal slot '${parameter.name}' of instance '$instanceName' does not map to one canonical declaration identity",
              actualFormals.flatMap(_.sourceLocation).headOption
            )
          }
          val actualExpressions = actualFormals.map(binding =>
            ExternalFormalParameterRegistry.normalizedExpression(binding.actual)
          ).distinct
          if (actualExpressions.size != 1) {
            fail(
              "SPINAL-PARAMETERIZED-VERILOG-FORMAL-SLOT-AMBIGUOUS",
              s"formal slot '${parameter.name}' of instance '$instanceName' maps to multiple actual expressions: ${actualExpressions.map(_.verilog).sorted.mkString(", ")}",
              actualFormals.flatMap(_.sourceLocation).headOption
            )
          }
          val explicit = ExpressionBinding(actualExpressions.head)
          if (connectionBindings.isEmpty) {
            fail(
              "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-BINDING-UNRESOLVED",
              s"no direct parent connection validates actual '${explicit.render}' for formal '${parameter.name}' of instance '$instanceName'"
            )
          }
          if (
            connectionBindings.size != 1 ||
            bindingSignature(connectionBindings.head) != bindingSignature(explicit)
          ) {
            fail(
              "SPINAL-PARAMETERIZED-VERILOG-FORMAL-ACTUAL-CONNECTION-CONFLICT",
              s"connections of instance '$instanceName' constrain formal '${parameter.name}' with ${connectionBindings.map(_.render).sorted.mkString(", ")}, but explicit actual is '${explicit.render}'",
              actualFormals.flatMap(_.sourceLocation).headOption
            )
          }
          explicit
        } else {
          if (connectionBindings.isEmpty) {
            fail(
              "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-BINDING-UNRESOLVED",
              s"no direct parent connection constrains parameter '${parameter.name}' of instance '$instanceName'"
            )
          }
          if (connectionBindings.size != 1) {
            fail(
              "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-BINDING-CONFLICT",
              s"connections of instance '$instanceName' constrain parameter '${parameter.name}' with ${connectionBindings.map(_.render).sorted.mkString(", ")}"
            )
          }
          connectionBindings.head
        }

      if (expression.default != parameter.default) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-WITNESS-MISMATCH",
          s"binding '${expression.render}' has concrete default ${expression.default}, but child parameter '${parameter.name}' was elaborated with ${parameter.default}"
        )
      }
      if (
        expression.minimum < parameter.minimum ||
        expression.maximum > parameter.maximum ||
        expression.minimum < 1 ||
        expression.maximum > BigInt(pc.config.bitVectorWidthMax)
      ) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-BINDING-DOMAIN-UNSUPPORTED",
          s"binding '${expression.render}' reaches [${expression.minimum}, ${expression.maximum}], outside child parameter '${parameter.name}' domain [${parameter.minimum}, ${parameter.maximum}] or SpinalConfig.bitVectorWidthMax=${pc.config.bitVectorWidthMax}"
        )
      }
      parameter.name -> expression
    }.sortBy(_._1)

    val canonicalParameterNames = canonicalParameters.map(_.name).toSet
    canonicalPorts.foreach {
      case (name, expectedPort) =>
        val expectedParameter = ParameterizedWidth.parameterOf(expectedPort)
        val actualParameter = ParameterizedWidth.parameterOf(actualByName(name))
        if (expectedParameter.isEmpty && actualParameter.nonEmpty) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-CANONICAL-SCHEMA-CONFLICT",
            s"port '$name' of instance '$instanceName' is symbolic while canonical definition '$definitionName' is concrete"
          )
        }
        if (expectedParameter.isEmpty) {
          connectionEvidence(
            parent,
            child,
            actualByName(name),
            assignments,
            s"concrete port '$name' of instance '$instanceName'"
          ).foreach { expression =>
            if (expression.isSymbolic || expression.default != BigInt(expectedPort.getBitsWidth)) {
              fail(
                "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-PORT-WIDTH-MISMATCH",
                s"concrete child port '$name' of instance '$instanceName' has width ${expectedPort.getBitsWidth}, but parent connection has width '${expression.render}'"
              )
            }
          }
        }
    }

    val actualParameterNames = ParameterizedWidth.parametersOf(child).map(_.name).toSet
    if (actualParameterNames != canonicalParameterNames) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-CANONICAL-SCHEMA-CONFLICT",
        s"instance '$instanceName' parameter dependency set ${actualParameterNames.toVector.sorted.mkString(",")} differs from canonical definition '$definitionName' set ${canonicalParameterNames.toVector.sorted.mkString(",")}"
      )
    }

    val bindingMap = bindings.toMap
    val ports = canonicalPorts.flatMap {
      case (name, port) =>
        ParameterizedWidth.parameterOf(port).flatMap { parameter =>
          val expression = bindingMap(parameter.name)
          if (expression.isSymbolic) Some(PortRewrite(name, expression)) else None
        }
    }
    InstancePlan(definitionName, instanceName, bindings, ports)
  }

  /**
    * Prefer exact per-port metadata. If a later native transformation has
    * removed it from every selected port, recover only from the exact owning
    * component identity retained by the external formal registry. A partial
    * loss remains an error because it cannot prove one complete slot layout.
    */
  private def retainedFormals(
      component: Component,
      portFormals: Vector[ExternalFormalParameterBinding],
      parameterPorts: Vector[String],
      parameter: ElaborationIntegerParameter,
      role: String,
      instanceName: String
  ): Vector[ExternalFormalParameterBinding] = {
    if (portFormals.size == parameterPorts.size) portFormals
    else if (portFormals.nonEmpty) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-FORMAL-LAYOUT-CONFLICT",
        s"formal slot '${parameter.name}' of instance '$instanceName' is retained on only ${portFormals.size} of ${parameterPorts.size} $role packed ports"
      )
    } else {
      val componentBindings =
        ExternalFormalParameterRegistry
          .bindingsOf(component)
          .filter(_.formal == parameter)
      if (componentBindings.isEmpty) Vector.empty
      else {
        val declarationKeys = componentBindings.map(_.declarationKey).distinct
        if (declarationKeys.size != 1) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-FORMAL-SLOT-IDENTITY-CONFLICT",
            s"formal slot '${parameter.name}' of instance '$instanceName' maps to multiple $role component declaration identities",
            componentBindings.flatMap(_.sourceLocation).headOption
          )
        }
        val expressions = componentBindings
          .map(binding =>
            ExternalFormalParameterRegistry.normalizedExpression(binding.actual)
          )
          .distinct
        if (expressions.size != 1) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-FORMAL-SLOT-AMBIGUOUS",
            s"formal slot '${parameter.name}' of instance '$instanceName' maps to multiple $role component actual expressions: ${expressions.map(_.verilog).sorted.mkString(", ")}",
            componentBindings.flatMap(_.sourceLocation).headOption
          )
        }
        Vector.fill(parameterPorts.size)(componentBindings.head)
      }
    }
  }

  private def indexedPorts(
      component: Component,
      role: String,
      instanceName: String
  ): Vector[(String, BaseType)] = {
    val ports = component.getOrdredNodeIo.toVector.map { port =>
      val name = Option(port.getName()).filter(_.nonEmpty).getOrElse {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-PORT-NAME-MISSING",
          s"$role port of instance '$instanceName' has no stable flattened name"
        )
      }
      name -> port
    }
    ports.groupBy(_._1).collectFirst {
      case (name, values) if values.size != 1 => name
    }.foreach { name =>
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-PORT-LAYOUT-MISMATCH",
        s"$role child interface of instance '$instanceName' contains duplicate port '$name'"
      )
    }
    ports
  }

  private def directionOf(port: BaseType): String =
    if (port.isInput) "input"
    else if (port.isOutput) "output"
    else if (port.isInOut) "inout"
    else "directionless"

  private def connectionEvidence(
      parent: Component,
      child: Component,
      port: BaseType,
      assignments: Vector[DataAssignmentStatement],
      context: String
  ): Vector[BindingExpr] = {
    if (port.isInput) {
      assignments.flatMap { assignment =>
        val touches = references(assignment.target, port) || assignment.finalTarget == port
        if (!touches) Vector.empty
        else if (assignment.target == port && assignment.finalTarget == port) {
          Vector(bindingOf(parent, assignment.source, context))
        } else {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-PORT-CONNECTION-UNSUPPORTED",
            s"$context uses a sliced, indexed or partial child-input assignment; direct full packed connections are required"
          )
        }
      }
    } else if (port.isOutput) {
      assignments.flatMap { assignment =>
        val touches = references(assignment.source, port)
        if (!touches) Vector.empty
        else if (
          assignment.source == port && assignment.target == assignment.finalTarget &&
          assignment.finalTarget.component == parent
        ) {
          Vector(bindingOf(parent, assignment.finalTarget, context))
        } else {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-PORT-CONNECTION-UNSUPPORTED",
            s"$context uses a sliced, indexed, converted or expression-wrapped child-output connection; direct full packed connections are required"
          )
        }
      }
    } else {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-PORT-DIRECTION-UNSUPPORTED",
        s"$context is neither a direct input nor direct output; inout hierarchy is deferred"
      )
    }
  }

  private def bindingOf(
      parent: Component,
      expression: Expression,
      context: String
  ): BindingExpr = expression match {
    case value: Bool => LiteralBinding(1)
    case value: BitVector if value.component == parent =>
      ParameterizedWidth.expressionOf(value) match {
        case Some(expression) => ExpressionBinding(expression)
        case None if value.isIo => LiteralBinding(value.getBitsWidth)
        case None =>
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-BINDING-UNRESOLVED",
            s"$context is connected through untagged internal signal '${value.getName()}'; Increment 32 requires a directly tagged parent leaf or concrete parent port"
          )
      }
    case value: BaseType if value.component == parent =>
      LiteralBinding(value.getBitsWidth)
    case _ =>
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-PORT-CONNECTION-UNSUPPORTED",
        s"$context is not connected to one direct parent packed leaf"
      )
  }

  private def distinctBindings(values: Vector[BindingExpr]): Vector[BindingExpr] =
    values.groupBy(bindingSignature).toVector
      .sortBy { case (signature, _) =>
        (signature.render, signature.minimum, signature.maximum, signature.default)
      }
      .map(_._2.head)

  private def bindingSignature(value: BindingExpr): BindingSignature =
    BindingSignature(
      render = value.render,
      default = value.default,
      minimum = value.minimum,
      maximum = value.maximum,
      parameters = value.parameters.distinct.sortBy(_.name)
    )

  private def references(expression: Expression, target: Expression): Boolean = {
    var found = false
    def visit(current: Expression): Unit = {
      if (!found) {
        if (current == target) found = true
        else current.foreachExpression(visit)
      }
    }
    visit(expression)
    found
  }

  private def fail(
      code: String,
      detail: String,
      sourceLocation: Option[String] = None
  ): Nothing =
    ParameterizedVerilogException.fail(code, detail, sourceLocation)
}
