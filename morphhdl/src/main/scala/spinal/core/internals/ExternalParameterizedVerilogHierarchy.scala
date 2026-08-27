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

  private final case class PortRewrite(
      name: String,
      width: BindingExpr,
      requiresIntermediate: Boolean,
      forbiddenDirectSignals: Set[String],
      outputAdapter: Option[OutputAdapter]
  )

  private final case class OutputAdapter(
      targetName: String,
      targetWidth: Int,
      witnessSourceWidth: Int
  )

  private final case class AdapterEvidence(
      forbiddenDirectSignals: Set[String],
      outputAdapter: Option[OutputAdapter]
  )

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

        val outputAdapters = ArrayBuffer.empty[(PortRewrite, String)]
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
          if (
            port.requiresIntermediate &&
            port.forbiddenDirectSignals.contains(signalName)
          ) {
            fail(
              "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-PORT-CONNECTION-UNSUPPORTED",
              s"derived port '${port.name}' of instance '${instance.instanceName}' is connected directly to fixed parent signal '$signalName'; an explicit resize intermediate is required"
            )
          }
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
          port.outputAdapter.foreach(_ => outputAdapters += port -> signalName)
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
        outputAdapters.foreach { case (port, signalName) =>
          current = rewriteOutputAdapter(
            current,
            instance,
            port,
            signalName
          )
        }
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

  private def rewriteOutputAdapter(
      verilog: String,
      instance: InstancePlan,
      port: PortRewrite,
      signalName: String
  ): String = {
    val adapter = port.outputAdapter.getOrElse {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-PORT-ADAPTER-UNPROVEN",
        s"derived output port '${port.name}' of instance '${instance.instanceName}' has no exact adapter evidence"
      )
    }
    val lines = verilog.split("\n", -1).toVector
    val assignmentPattern =
      ("^(\\s*)assign\\s+" + Pattern.quote(adapter.targetName) +
        "\\s*=\\s*(.*?)\\s*;\\s*$").r
    val matches = lines.zipWithIndex.flatMap { case (line, index) =>
      assignmentPattern.findFirstMatchIn(line).map(index -> _)
    }
    if (matches.size != 1) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-PORT-ADAPTER-NOT-FOUND",
        s"derived output port '${port.name}' of instance '${instance.instanceName}' has ${matches.size} native assignments to exact parent target '${adapter.targetName}'"
      )
    }
    val (index, matched) = matches.head
    val witnessPadding = adapter.targetWidth - adapter.witnessSourceWidth
    val directWitness = ("^" + Pattern.quote(signalName) + "$").r
    val paddedWitness =
      ("^\\{\\s*([0-9]+)'d0\\s*,\\s*" + Pattern.quote(signalName) +
        "\\s*\\}$").r
    val witnessMatches =
      if (witnessPadding == 0)
        directWitness.findFirstIn(matched.group(2)).nonEmpty
      else
        paddedWitness.findFirstMatchIn(matched.group(2)).exists(value =>
          BigInt(value.group(1)) == BigInt(witnessPadding)
        )
    if (!witnessMatches) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-PORT-ADAPTER-SHAPE-MISMATCH",
        s"derived output port '${port.name}' of instance '${instance.instanceName}' did not emit the exact ${adapter.witnessSourceWidth}-to-${adapter.targetWidth} unsigned witness adapter"
      )
    }

    val labelBase =
      s"g_width_adapter_${instance.instanceName}_${port.name}"
    if (verilog.contains(s"begin : $labelBase")) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-PORT-ADAPTER-LABEL-CONFLICT",
        s"derived output port '${port.name}' of instance '${instance.instanceName}' conflicts with generated label '$labelBase'"
      )
    }
    val indent = matched.group(1)
    val width = port.width.render
    val padded =
      s"${indent}assign ${adapter.targetName} = {{(${adapter.targetWidth} - ($width)){1'b0}}, $signalName};"
    val replacement =
      if (
        port.width.minimum == BigInt(adapter.targetWidth) &&
        port.width.maximum == BigInt(adapter.targetWidth)
      ) s"${indent}assign ${adapter.targetName} = $signalName;"
      else if (port.width.maximum < BigInt(adapter.targetWidth)) padded
      else
        {
          val generateDepth = lines.take(index).foldLeft(0) { (depth, line) =>
            line.replaceFirst("//.*$", "").trim match {
              case "generate"    => depth + 1
              case "endgenerate" => math.max(0, depth - 1)
              case _             => depth
            }
          }
          if (generateDepth != 0) {
            fail(
              "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-PORT-ADAPTER-SCOPE-UNSUPPORTED",
              s"derived output port '${port.name}' of instance '${instance.instanceName}' requires a conditional width adapter inside an existing generate region"
            )
          }
          Vector(
            s"${indent}generate",
            s"${indent}  if (($width) < ${adapter.targetWidth}) begin : $labelBase",
            s"${indent}    assign ${adapter.targetName} = {{(${adapter.targetWidth} - ($width)){1'b0}}, $signalName};",
            s"${indent}  end else begin : ${labelBase}_exact",
            s"${indent}    assign ${adapter.targetName} = $signalName;",
            s"${indent}  end",
            s"${indent}endgenerate"
          ).mkString("\n")
        }
    lines.updated(index, replacement).mkString("\n")
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

        val actualWidth = ParameterizedWidth
          .expressionOf(actual)
          .filter(_.parameters.nonEmpty)
        val expectedWidth = ParameterizedWidth
          .expressionOf(expected)
          .filter(_.parameters.nonEmpty)
        if (
          actualWidth.map(widthSignature) != expectedWidth.map(widthSignature)
        ) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-CANONICAL-SCHEMA-CONFLICT",
            s"port '$name' of instance '$instanceName' and canonical definition '$definitionName' retain different symbolic width expressions"
          )
        }
    }

    val actualByName = actualPorts.toMap
    val canonicalByName = canonicalPorts.toMap
    val canonicalParameters = componentParameters(canonical)
    val bindings = canonicalParameters.map { parameter =>
      val parameterPorts = canonicalPorts.collect {
        case (name, port)
            if ParameterizedWidth.parameterOf(port).exists(_.name == parameter.name) =>
          name
      }
      if (parameterPorts.isEmpty) {
        val expression = componentOnlyBinding(
          canonical = canonical,
          child = child,
          parameter = parameter,
          definitionName = definitionName,
          instanceName = instanceName
        )
        validateParameterBinding(expression, parameter, instanceName, pc)
        parameter.name -> expression
      } else {
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

        validateParameterBinding(expression, parameter, instanceName, pc)
        parameter.name -> expression
      }
    }.sortBy(_._1)

    val canonicalParameterNames = canonicalParameters.map(_.name).toSet
    canonicalPorts.foreach {
      case (name, expectedPort) =>
        val expectedWidth = ParameterizedWidth
          .expressionOf(expectedPort)
          .filter(_.parameters.nonEmpty)
        if (expectedWidth.isEmpty) {
          connectionEvidence(
            parent,
            child,
            actualByName(name),
            assignments,
            s"concrete port '$name' of instance '$instanceName'",
            allowConcreteInternal = true
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

    val actualParameterNames = componentParameters(child).map(_.name).toSet
    if (actualParameterNames != canonicalParameterNames) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-CANONICAL-SCHEMA-CONFLICT",
        s"instance '$instanceName' parameter dependency set ${actualParameterNames.toVector.sorted.mkString(",")} differs from canonical definition '$definitionName' set ${canonicalParameterNames.toVector.sorted.mkString(",")}"
      )
    }

    val bindingMap = bindings.toMap
    val ports = canonicalPorts.flatMap {
      case (name, port) =>
        ParameterizedWidth
          .expressionOf(port)
          .filter(_.parameters.nonEmpty)
          .flatMap { definitionWidth =>
            val actualPort = actualByName(name)
            val expression = instantiatePortWidth(
              definitionWidth,
              bindingMap,
              actualPort,
              definitionName,
              instanceName,
              name
            )
            val hasParentReference = assignments.exists { assignment =>
              references(assignment.source, actualPort) ||
              references(assignment.target, actualPort) ||
              assignment.finalTarget == actualPort
            }
            if (expression.isSymbolic) {
              val directParameter = ParameterizedWidth.parameterOf(port).exists {
                parameter =>
                  definitionWidth.parameters == Vector(parameter) &&
                    definitionWidth.verilog.trim == parameter.name
              }
              // Native emission retains a private carrier even for an
              // unconsumed child output.  It has no graph edge to validate, but
              // its instance slice and declaration must still be rewritten to
              // the instantiated symbolic width.  PortRewrite proves that the
              // emitted connection is exactly one portable packed signal;
              // every referenced output continues through the stricter graph
              // edge validation below.
              val unconsumedOutput =
                actualPort.isOutput && !hasParentReference
              val adapter =
                if (directParameter || unconsumedOutput) None
                else
                  validateDerivedPortConnection(
                    parent,
                    child,
                    actualPort,
                    assignments,
                    expression,
                    instanceName,
                    name
                  )
              Some(
                PortRewrite(
                  name,
                  expression,
                  requiresIntermediate = adapter.nonEmpty,
                  forbiddenDirectSignals = adapter
                    .map(_.forbiddenDirectSignals)
                    .getOrElse(Set.empty),
                  outputAdapter = adapter.flatMap(_.outputAdapter)
                )
              )
            } else None
          }
    }
    InstancePlan(definitionName, instanceName, bindings, ports)
  }

  /**
    * Translate one definition-side packed width into its parent-scope actual.
    *
    * The retained expression and its exact formal schemas are authoritative;
    * substitution is limited to complete Verilog identifiers, requires every
    * referenced formal to have one already-proven instance binding, and wraps
    * every actual in parentheses. This lets derived widths such as
    * `clog2(DEPTH + 1, 1)` cross a hierarchy boundary without inferring from a
    * concrete witness width or from signal/module names.
    */
  private def instantiatePortWidth(
      definitionWidth: ElaborationIntegerExpression,
      bindings: Map[String, BindingExpr],
      actualPort: BaseType,
      definitionName: String,
      instanceName: String,
      portName: String
  ): BindingExpr = {
    val formals = definitionWidth.parameters.distinct
    val duplicateNames = formals.groupBy(_.name).collectFirst {
      case (name, values) if values.size != 1 => name
    }
    duplicateNames.foreach { name =>
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-CANONICAL-SCHEMA-CONFLICT",
        s"derived width of port '$portName' on canonical child '$definitionName' repeats formal '$name' with incompatible identities"
      )
    }

    val byName = formals.map(parameter => parameter.name -> parameter).toMap
    val missing = byName.keySet.diff(bindings.keySet)
    if (missing.nonEmpty) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-BINDING-UNRESOLVED",
        s"derived width of port '$portName' on instance '$instanceName' references unbound child formal(s) ${missing.toVector.sorted.mkString(", ")}"
      )
    }

    byName.foreach {
      case (name, formal) =>
        val retained = bindings(name)
        if (
          retained.default != formal.default ||
          retained.minimum < formal.minimum ||
          retained.maximum > formal.maximum
        ) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-CANONICAL-SCHEMA-CONFLICT",
            s"derived width of port '$portName' on instance '$instanceName' cannot substitute binding '${retained.render}' for formal '$name'"
          )
        }
    }

    definitionWidth.parameters match {
      case Vector(formal) if definitionWidth.verilog.trim == formal.name =>
        return bindings(formal.name)
      case _ =>
    }

    val names = byName.keys.toVector.sortBy(name => (-name.length, name))
    val identifierPattern =
      ("(?<![A-Za-z0-9_$])(?:" +
        names.map(Pattern.quote).mkString("|") +
        ")(?![A-Za-z0-9_$])").r
    val seen = mutable.HashSet.empty[String]
    val rendered = identifierPattern.replaceAllIn(
      definitionWidth.verilog,
      matched => {
        val name = matched.matched
        var next = matched.end
        while (
          next < definitionWidth.verilog.length &&
          definitionWidth.verilog.charAt(next).isWhitespace
        ) next += 1
        // A retained formal is a value, never a function name.  A legal formal
        // may nevertheless share the spelling of an internal helper (for
        // example `morphhdl_ceil_log2`).  Preserve such callee tokens and
        // substitute only free value references.
        if (
          next < definitionWidth.verilog.length &&
          definitionWidth.verilog.charAt(next) == '('
        ) matched.matched
        else {
          seen += name
          Matcher.quoteReplacement(s"(${bindings(name).render})")
        }
      }
    )
    if (seen.toSet != byName.keySet) {
      val absent = byName.keySet.diff(seen.toSet)
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-CANONICAL-SCHEMA-CONFLICT",
        s"derived width of port '$portName' on canonical child '$definitionName' does not render retained formal(s) ${absent.toVector.sorted.mkString(", ")} as complete identifiers"
      )
    }

    val actualParameters = formals
      .flatMap(formal => bindings(formal.name).parameters)
      .distinct
      .sortBy(_.name)
    if (actualParameters.isEmpty) LiteralBinding(definitionWidth.default)
    else {
      val textuallyInstantiated = ElaborationIntegerExpression(
        verilog = rendered,
        default = definitionWidth.default,
        minimum = definitionWidth.minimum,
        maximum = definitionWidth.maximum,
        parameters = actualParameters,
        sourceLocation = definitionWidth.sourceLocation
      )
      ExternalNativeIntShadowRegistry.widthExpressionsOf(actualPort) match {
        case Some((definition, actual))
            if ExternalFormalParameterRegistry.equivalentExpression(
              definition,
              definitionWidth
            ) && actual.default == definitionWidth.default &&
              actual.minimum >= BigInt(1) &&
              actual.minimum >= definitionWidth.minimum &&
              actual.maximum <= definitionWidth.maximum &&
              actual.parameters.distinct.sortBy(_.name) == actualParameters =>
          ExpressionBinding(
            actual.copy(sourceLocation = definitionWidth.sourceLocation)
          )
        case Some((definition, actual)) =>
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-DERIVED-WIDTH-ACTUAL-CONFLICT",
            s"derived width of port '$portName' on instance '$instanceName' has canonical definition '${definition.verilog}' and exact native actual '${actual.verilog}', which do not match retained definition '${definitionWidth.verilog}' and binding parameters"
          )
        case None => ExpressionBinding(textuallyInstantiated)
      }
    }
  }

  private def widthSignature(
      expression: ElaborationIntegerExpression
  ): BindingSignature =
    BindingSignature(
      render = expression.verilog,
      default = expression.default,
      minimum = expression.minimum,
      maximum = expression.maximum,
      parameters = expression.parameters.distinct.sortBy(_.name)
    )

  /**
    * Validate one non-direct (derived) symbolic child width against the exact
    * parent graph edge.  A normal edge must carry the same retained expression.
    * A concrete edge is accepted only through a surviving whole-assignment
    * Resize chain; publication must then rewrite a distinct intermediate, never
    * the fixed parent leaf itself.
    */
  private def validateDerivedPortConnection(
      parent: Component,
      child: Component,
      port: BaseType,
      assignments: Vector[DataAssignmentStatement],
      expected: BindingExpr,
      instanceName: String,
      portName: String
  ): Option[AdapterEvidence] = {
    val context = s"derived port '$portName' of instance '$instanceName'"
    val parentPorts = parent.getOrdredNodeIo.toVector.flatMap(value =>
      Option(value.getName()).filter(_.nonEmpty)
    ).toSet

    def stableName(value: BaseType): Set[String] =
      Option(value.getName()).filter(_.nonEmpty).toSet

    def resizeRoot(value: Expression): Option[Expression] = value match {
      case resize: Resize =>
        def root(current: Expression): Option[Expression] = current match {
          case nested: Resize if nested.getWidth >= nested.input.getWidth =>
            root(nested.input)
          case _: Resize => None
          case other     => Some(other)
        }
        if (resize.getWidth < resize.input.getWidth) None
        else root(resize.input)
      case _ => None
    }

    def requireMatching(value: BindingExpr): Option[AdapterEvidence] = {
      if (bindingSignature(value) != bindingSignature(expected)) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-PORT-WIDTH-MISMATCH",
          s"$context has instantiated width '${expected.render}', but its direct parent connection has width '${value.render}'"
        )
      }
      None
    }

    if (port.isInput) {
      val touching = assignments.filter(assignment =>
        references(assignment.target, port) || assignment.finalTarget == port
      )
      touching match {
        case Vector(assignment)
            if assignment.target == port && assignment.finalTarget == port =>
          resizeRoot(assignment.source) match {
            case Some(root: BaseType) if root.component == parent =>
              fail(
                "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-PORT-CONNECTION-UNSUPPORTED",
                s"$context fixed-parent resize requires symmetric input-adapter lowering, which is not yet proven"
              )
            case Some(_) =>
              fail(
                "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-PORT-CONNECTION-UNSUPPORTED",
                s"$context resize is not rooted at one parent packed leaf"
              )
            case None =>
              requireMatching(
                bindingOf(parent, assignment.source, context, allowConcreteInternal = false)
              )
          }
        case _ =>
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-PORT-CONNECTION-UNSUPPORTED",
            s"$context requires exactly one direct whole child-input assignment"
          )
      }
    } else if (port.isOutput) {
      val touching = assignments.filter(assignment =>
        references(assignment.source, port)
      )
      touching match {
        case Vector(assignment)
            if assignment.target == assignment.finalTarget &&
              assignment.finalTarget.component == parent =>
          if (assignment.source == port) {
            requireMatching(
              bindingOf(
                parent,
                assignment.finalTarget,
                context,
                allowConcreteInternal = false
              )
            )
          } else {
            resizeRoot(assignment.source) match {
              case Some(root) if root eq port =>
                (port, assignment.finalTarget) match {
                  case (source: UInt, target: UInt) =>
                    val targetName = Option(target.getName()).filter(_.nonEmpty).getOrElse {
                      fail(
                        "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-PORT-ADAPTER-TARGET-NAME-MISSING",
                        s"$context exact parent resize target has no stable emitted name"
                      )
                    }
                    val targetWidth = target.getBitsWidth
                    if (
                      targetWidth < 1 ||
                      expected.minimum < 1 ||
                      expected.maximum > BigInt(targetWidth) ||
                      expected.default != BigInt(source.getBitsWidth)
                    ) {
                      fail(
                        "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-PORT-WIDTH-MISMATCH",
                        s"$context unsigned width '${expected.render}' reaches [${expected.minimum}, ${expected.maximum}], which cannot be zero-extended into fixed ${targetWidth}-bit parent target '$targetName'"
                      )
                    }
                    Some(
                      AdapterEvidence(
                        parentPorts ++ stableName(target),
                        outputAdapter = Some(
                          OutputAdapter(
                            targetName,
                            targetWidth,
                            source.getBitsWidth
                          )
                        )
                      )
                    )
                  case _ =>
                    fail(
                      "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-PORT-CONNECTION-UNSUPPORTED",
                      s"$context whole-output Resize must connect one unsigned child port to one unsigned fixed parent target"
                    )
                }
              case _ =>
                fail(
                  "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-PORT-CONNECTION-UNSUPPORTED",
                  s"$context requires a matching direct parent leaf or one exact whole-assignment Resize boundary"
                )
            }
          }
        case _ =>
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-PORT-CONNECTION-UNSUPPORTED",
            s"$context requires exactly one direct whole child-output assignment"
          )
      }
    } else {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-PORT-DIRECTION-UNSUPPORTED",
        s"$context is neither input nor output"
      )
    }
  }

  private def componentParameters(
      component: Component
  ): Vector[ElaborationIntegerParameter] = {
    val values =
      ParameterizedWidth.parametersOf(component) ++
        ExternalParameterizedMemoryRegistry.parametersOf(component) ++
        ExternalParameterizedValueRegistry.parametersOf(component) ++
        ParameterizedStructure.parametersOf(component) ++
        ParameterizedProcess.parametersOf(component)
    val grouped = values.groupBy(_.name)
    grouped.collectFirst {
      case (name, declarations) if declarations.distinct.size != 1 => name
    }.foreach { name =>
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-SCHEMA-CONFLICT",
        s"component '${component.definitionName}' has conflicting hierarchy parameter declarations for '$name'"
      )
    }
    grouped.toVector.map(_._2.head).sortBy(_.name)
  }

  private def componentOnlyBinding(
      canonical: Component,
      child: Component,
      parameter: ElaborationIntegerParameter,
      definitionName: String,
      instanceName: String
  ): BindingExpr = {
    val canonicalBindings =
      ExternalFormalParameterRegistry
        .bindingsOf(canonical)
        .filter(_.formal == parameter)
    val actualBindings =
      ExternalFormalParameterRegistry
        .bindingsOf(child)
        .filter(_.formal == parameter)

    if (canonicalBindings.isEmpty || actualBindings.isEmpty) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-BINDING-UNRESOLVED",
        s"scalar parameter '${parameter.name}' of canonical child '$definitionName' requires one exact formalComponent.parameter binding on canonical and actual instance '$instanceName'"
      )
    }

    val all = canonicalBindings ++ actualBindings
    if (all.exists(_.formal != parameter)) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-FORMAL-SCHEMA-CONFLICT",
        s"scalar formal of instance '$instanceName' does not match canonical child parameter '${parameter.name}' of '$definitionName'",
        all.flatMap(_.sourceLocation).headOption
      )
    }
    val declarationKeys = all.map(_.declarationKey).distinct
    if (declarationKeys.size != 1) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-FORMAL-SLOT-IDENTITY-CONFLICT",
        s"scalar formal '${parameter.name}' of instance '$instanceName' does not map to one canonical declaration identity",
        all.flatMap(_.sourceLocation).headOption
      )
    }
    val owners = all.map(_.ownerClassName).distinct
    if (owners.size != 1) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-FORMAL-SLOT-IDENTITY-CONFLICT",
        s"scalar formal '${parameter.name}' of instance '$instanceName' maps to multiple definition owners",
        all.flatMap(_.sourceLocation).headOption
      )
    }
    val actualExpressions = actualBindings
      .map(binding =>
        ExternalFormalParameterRegistry.normalizedExpression(binding.actual)
      )
      .distinct
    if (actualExpressions.size != 1) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-FORMAL-SLOT-AMBIGUOUS",
        s"scalar formal '${parameter.name}' of instance '$instanceName' maps to multiple actual expressions: ${actualExpressions.map(_.verilog).sorted.mkString(", ")}",
        actualBindings.flatMap(_.sourceLocation).headOption
      )
    }
    ExpressionBinding(actualExpressions.head)
  }

  private def validateParameterBinding(
      expression: BindingExpr,
      parameter: ElaborationIntegerParameter,
      instanceName: String,
      pc: PhaseContext
  ): Unit = {
    if (expression.default != parameter.default) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-WITNESS-MISMATCH",
        s"binding '${expression.render}' has concrete default ${expression.default}, but child parameter '${parameter.name}' of instance '$instanceName' was elaborated with ${parameter.default}"
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

  private def directConcreteOutputAdapter(
      expression: Expression,
      port: BaseType
  ): Boolean = expression match {
    case value if value eq port => true
    case value: Resize          => directConcreteOutputAdapter(value.input, port)
    case _                      => false
  }

  private def connectionEvidence(
      parent: Component,
      child: Component,
      port: BaseType,
      assignments: Vector[DataAssignmentStatement],
      context: String,
      allowConcreteInternal: Boolean = false
  ): Vector[BindingExpr] = {
    if (port.isInput) {
      assignments.flatMap { assignment =>
        val touches = references(assignment.target, port) || assignment.finalTarget == port
        if (!touches) Vector.empty
        else if (assignment.target == port && assignment.finalTarget == port) {
          Vector(
            bindingOf(
              parent,
              assignment.source,
              context,
              allowConcreteInternal
            )
          )
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
          Vector(
            bindingOf(
              parent,
              assignment.finalTarget,
              context,
              allowConcreteInternal
            )
          )
        } else if (
          allowConcreteInternal &&
          assignment.target == assignment.finalTarget &&
          assignment.finalTarget.component == parent &&
          directConcreteOutputAdapter(assignment.source, port)
        ) {
          Vector(LiteralBinding(port.getBitsWidth))
        } else if (
          allowConcreteInternal && assignment.source == port &&
          assignment.finalTarget.component == parent
        ) {
          Vector(
            bindingOf(
              parent,
              assignment.target,
              context,
              allowConcreteInternal
            )
          )
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
      context: String,
      allowConcreteInternal: Boolean
  ): BindingExpr = expression match {
    case _: BitAssignmentFixed if allowConcreteInternal =>
      LiteralBinding(1)
    case _: BitVectorBitAccessFixed if allowConcreteInternal =>
      LiteralBinding(1)
    case _: BoolLiteral if allowConcreteInternal =>
      LiteralBinding(1)
    case _: BoolPoison if allowConcreteInternal =>
      LiteralBinding(1)
    case value: BitVectorLiteral if allowConcreteInternal =>
      LiteralBinding(value.getWidth)
    case value: Bool => LiteralBinding(1)
    case value: BitVector if value.component == parent =>
      ParameterizedWidth.expressionOf(value) match {
        case Some(expression) => ExpressionBinding(expression)
        case None if value.isIo || allowConcreteInternal =>
          LiteralBinding(value.getBitsWidth)
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
