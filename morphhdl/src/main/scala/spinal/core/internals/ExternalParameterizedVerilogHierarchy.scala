package spinal.core.internals

import java.util.regex.{Matcher, Pattern}

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

import spinal.core._

/** MorphHDL-owned external analysis for ordinary Component hierarchy.
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
    def parameterRoots: Set[ElaborationIntegerParameterRoot]
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
    override def parameterRoots: Set[ElaborationIntegerParameterRoot] =
      Set(parameter.declarationRoot)
  }

  private final case class LiteralBinding(value: BigInt) extends BindingExpr {
    override def render: String = value.toString
    override def default: BigInt = value
    override def minimum: BigInt = value
    override def maximum: BigInt = value
    override def parameters: Vector[ElaborationIntegerParameter] = Vector.empty
    override def parameterRoots: Set[ElaborationIntegerParameterRoot] = Set.empty
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
    override def parameterRoots: Set[ElaborationIntegerParameterRoot] =
      expression.completedParameterRoots.toSet
  }


  private final case class BooleanExpressionBinding(
      expression: ElaborationBooleanExpression
  ) extends BindingExpr {
    override def render: String = expression.verilog
    override def default: BigInt =
      if (expression.default) BigInt(1) else BigInt(0)
    override def minimum: BigInt = BigInt(0)
    override def maximum: BigInt = BigInt(1)
    override def parameters: Vector[ElaborationIntegerParameter] =
      expression.parameters.distinct.sortBy(_.name)
    override def parameterRoots: Set[ElaborationIntegerParameterRoot] =
      expression.completedParameterRoots.toSet
  }


  private final case class BindingSignature(
      render: String,
      default: BigInt,
      minimum: BigInt,
      maximum: BigInt,
      parameters: Vector[ElaborationIntegerParameter],
      parameterRoots: Set[ElaborationIntegerParameterRoot],
      exactDomain: Option[ElaborationExactDomain[BigInt]],
      projection: Option[ProjectionSignature]
  )

  private final case class ProjectionSignature(
      root: ElaborationIntegerParameterRoot,
      admitted: Set[BigInt],
      representative: BigInt
  )

  private final case class PortRewrite(name: String, width: BindingExpr)

  private final case class AggregateBindingEvidence(
      present: Boolean,
      bindings: Vector[BindingExpr],
      canonicalPorts: Vector[BaseType]
  )

  private final case class FormalBindingEvidence(
      binding: ExternalFormalParameterBinding,
      typedToken: Option[ExternalTypedFormalDeclarationToken]
  ) {
    def isTyped: Boolean = typedToken.nonEmpty
  }

  private final case class InstancePlan(
      definitionName: String,
      instanceName: String,
      bindings: Vector[(String, BindingExpr)],
      ports: Vector[PortRewrite],
      preserveExistingGenericAssociations: Boolean = false
  )

  private[internals] final class Plan private[ExternalParameterizedVerilogHierarchy] (
      val parameters: Vector[ElaborationIntegerParameter],
      val hasParameterizedInstances: Boolean,
      private val instances: Vector[InstancePlan]
  ) {
    def rewrite(verilog: String): (String, Vector[(String, String)]) = {
      var current = verilog
      val declarationRanges = ArrayBuffer.empty[(String, String)]

      instances.filter(instance => instance.bindings.nonEmpty || instance.ports.nonEmpty).foreach { instance =>
        val lines = current.split("\\n", -1).toVector
        val attributePrefix = "((?:\\(\\*[^\\r\\n]*?\\*\\)\\s*)*)"
        val plainStartPattern =
          ("^(\\s*)" + attributePrefix + Pattern.quote(instance.definitionName) + "\\s+" +
            Pattern.quote(instance.instanceName) + "\\s*\\(\\s*$").r
        val parameterizedStartPattern =
          ("^(\\s*)" + attributePrefix + Pattern.quote(instance.definitionName) +
            "\\s*#\\s*\\(\\s*$").r
        val parameterizedTerminatorPattern =
          ("^\\s*\\)\\s+" + Pattern.quote(instance.instanceName) +
            "\\s*\\(\\s*$").r
        val anyParameterizedTerminatorPattern =
          "^\\s*\\)\\s+[A-Za-z_][A-Za-z0-9_$]*\\s*\\(\\s*$".r

        val plainStarts = lines.zipWithIndex.collect {
          case (line, index) if plainStartPattern.findFirstIn(line).nonEmpty =>
            val matched = plainStartPattern.findFirstMatchIn(line).get
            val indent = matched.group(1)
            val attributes = matched.group(2)
            (index, index, indent, attributes)
        }
        val parameterizedStarts = lines.zipWithIndex.flatMap {
          case (line, index) if parameterizedStartPattern.findFirstIn(line).nonEmpty =>
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
                val matched = parameterizedStartPattern.findFirstMatchIn(line).get
                val indent = matched.group(1)
                val attributes = matched.group(2)
                (index, bodyStart, indent, attributes)
            }
          case _ => None
        }
        val starts = plainStarts ++ parameterizedStarts
        if (starts.size != 1) {
          val sameDefinition = lines.filter(line =>
            line.trim.startsWith(instance.definitionName + " ") ||
              line.trim.startsWith(instance.definitionName + " #")
          )
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-INSTANCE-NOT-FOUND",
            s"normal Verilog emission contains ${starts.size} instances matching '${instance.definitionName} ${instance.instanceName}'; definition candidates: ${sameDefinition.mkString(" | ")}"
          )
        }
        val (start, bodyStart, indent, attributes) = starts.head
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
              "([A-Za-z_][A-Za-z0-9_$]*)(\\s*)(?:\\[[^\\]]+\\])?(\\s*\\))").r
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

        val rewrittenBlock =
          if (instance.preserveExistingGenericAssociations) {
            if (instance.bindings.nonEmpty && bodyStart == start) {
              fail(
                "SPINAL-PARAMETERIZED-VERILOG-BLACKBOX-GENERIC-HEADER-MISSING",
                s"native BlackBox instance '${instance.instanceName}' has typed generic bindings but no emitted generic block"
              )
            }
            var prefix = lines.slice(start, bodyStart)
            instance.bindings.foreach { case (name, expression) =>
              val pattern =
                ("^(\\s*\\." + Pattern.quote(name) + "\\s*\\(\\s*)(.*?)(\\s*\\)\\s*,?\\s*)$").r
              val matching = prefix.zipWithIndex.collect {
                case (line, index) if pattern.findFirstIn(line).nonEmpty => index
              }
              if (matching.size != 1) {
                fail(
                  "SPINAL-PARAMETERIZED-VERILOG-BLACKBOX-GENERIC-ASSOCIATION-NOT-FOUND",
                  s"native BlackBox instance '${instance.instanceName}' contains ${matching.size} associations for typed generic '$name'"
                )
              }
              val lineIndex = matching.head
              val matched = pattern.findFirstMatchIn(prefix(lineIndex)).get
              prefix = prefix.updated(
                lineIndex,
                matched.group(1) + expression.render + matched.group(3)
              )
            }
            prefix ++ block
          } else if (instance.bindings.nonEmpty) {
            val bindingLines = instance.bindings.zipWithIndex.map { case ((name, expression), index) =>
              val comma = if (index == instance.bindings.size - 1) "" else ","
              s"${indent}  .$name(${expression.render})$comma"
            }
            val header =
              Vector(s"$indent$attributes${instance.definitionName} #(") ++
                bindingLines ++
                Vector(s"${indent}) ${instance.instanceName} (")
            header ++ block.drop(1)
          } else {
            lines.slice(start, bodyStart) ++ block
          }
        current = (lines.take(start) ++ rewrittenBlock ++ lines.drop(end + 1)).mkString("\n")
      }

      val grouped = declarationRanges.groupBy(_._1)
      grouped
        .collectFirst {
          case (name, values) if values.map(_._2).distinct.size != 1 => name
        }
        .foreach { name =>
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
      case _                                   =>
    }

    val instances = component.children.toVector.map {
      case blackBox: BlackBox if blackBox.isBlackBox =>
        analyzeBlackBoxInstance(component, blackBox, pc)
      case child =>
        val canonical = canonicalOf(child)
        analyzeInstance(component, child, canonical, assignments.toVector, pc)
    }

    val referenced = instances.flatMap { instance =>
      instance.bindings.flatMap(_._2.parameters) ++
        instance.ports.flatMap(_.width.parameters)
    }
    val grouped = referenced.groupBy(_.name)
    grouped
      .collectFirst {
        case (name, values) if values.distinct.size != 1 => name
      }
      .foreach { name =>
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-SCHEMA-CONFLICT",
          s"parameter '$name' has conflicting hierarchy binding declarations on component '${component.definitionName}'"
        )
      }
    new Plan(
      parameters = grouped.toVector.map(_._2.head).sortBy(_.name),
      hasParameterizedInstances = instances.exists(instance => instance.bindings.nonEmpty || instance.ports.nonEmpty),
      instances = instances
    )
  }



  private def analyzeBlackBoxInstance(
      parent: Component,
      blackBox: BlackBox,
      pc: PhaseContext
  ): InstancePlan = {
    val instanceName = Option(blackBox.getName()).filter(_.nonEmpty).getOrElse {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-INSTANCE-NAME-MISSING",
        s"BlackBox child of '${parent.definitionName}' has no stable instance name after native emission"
      )
    }
    val definitionName = Option(blackBox.definitionName).filter(_.nonEmpty).getOrElse {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-DEFINITION-NAME-MISSING",
        s"BlackBox child '$instanceName' has no external definition name"
      )
    }

    val nativeGenerics = blackBox.genericElements.toVector
    nativeGenerics
      .groupBy(_._1)
      .collectFirst {
        case (name, values) if values.size != 1 => name -> values.size
      }
      .foreach { case (name, count) =>
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-BLACKBOX-GENERIC-DUPLICATE",
          s"BlackBox instance '$instanceName' declares generic '$name' $count times"
        )
      }

    val portableIdentifier = "[A-Za-z_][A-Za-z0-9_]*".r
    val reserved = Set(
      "always", "and", "assign", "automatic", "begin", "buf", "bufif0",
      "bufif1", "case", "casex", "casez", "cell", "cmos", "config",
      "deassign", "default", "defparam", "design", "disable", "edge",
      "else", "end", "endcase", "endconfig", "endfunction", "endgenerate",
      "endmodule", "endprimitive", "endspecify", "endtable", "endtask",
      "event", "for", "force", "forever", "fork", "function", "generate",
      "genvar", "highz0", "highz1", "if", "ifnone", "incdir", "include",
      "initial", "inout", "input", "instance", "integer", "join",
      "large", "liblist", "library", "localparam", "macromodule", "medium",
      "module", "nand", "negedge", "nmos", "nor", "noshowcancelled", "not",
      "notif0", "notif1", "or", "output", "parameter", "pmos", "posedge",
      "primitive", "pull0", "pull1", "pulldown", "pullup", "pulsestyle_ondetect",
      "pulsestyle_onevent", "rcmos", "real", "realtime", "reg", "release",
      "repeat", "rnmos", "rpmos", "rtran", "rtranif0", "rtranif1", "scalared",
      "showcancelled", "signed", "small", "specify", "specparam", "strong0",
      "strong1", "supply0", "supply1", "table", "task", "time", "tran",
      "tranif0", "tranif1", "tri", "tri0", "tri1", "triand", "trior",
      "trireg", "unsigned", "use", "vectored", "wait", "wand", "weak0",
      "weak1", "while", "wire", "wor", "xnor", "xor"
    )
    nativeGenerics.foreach { case (name, _) =>
      if (
        name == null ||
        !portableIdentifier.pattern.matcher(name).matches()
      ) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-BLACKBOX-GENERIC-NAME-INVALID",
          s"BlackBox instance '$instanceName' generic '${String.valueOf(name)}' is not a portable Verilog/VHDL identifier"
        )
      }
      if (reserved.contains(name.toLowerCase(java.util.Locale.ROOT))) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-BLACKBOX-GENERIC-NAME-RESERVED",
          s"BlackBox instance '$instanceName' generic '$name' is a reserved Verilog-2001 word"
        )
      }
    }

    val retained = ParameterizedBlackBoxGenericRegistry.recordsOf(blackBox)
    retained
      .groupBy(_.name)
      .collectFirst {
        case (name, values) if values.size != 1 => name -> values.size
      }
      .foreach { case (name, count) =>
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-BLACKBOX-GENERIC-DUPLICATE",
          s"BlackBox instance '$instanceName' retains typed generic '$name' $count times"
        )
      }

    retained.foreach { record =>
      val native = nativeGenerics.filter(_._1 == record.name)
      if (native.size != 1) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-BLACKBOX-GENERIC-NATIVE-ASSOCIATION-MISSING",
          s"BlackBox instance '$instanceName' has ${native.size} native associations for retained typed generic '${record.name}'",
          record.sourceLocation
        )
      }
      val witnessMatches = (record, native.head._2) match {
        case (value: ParameterizedBlackBoxIntegerGeneric, witness: Int) =>
          value.witness == witness
        case (value: ParameterizedBlackBoxBooleanGeneric, witness: Boolean) =>
          value.witness == witness
        case _ => false
      }
      if (!witnessMatches) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-BLACKBOX-GENERIC-REGISTRY-MISMATCH",
          s"BlackBox instance '$instanceName' generic '${record.name}' native witness no longer matches its exact typed record",
          record.sourceLocation
        )
      }
    }

    val bindings = retained.collect {
      case value: ParameterizedBlackBoxIntegerGeneric
          if value.expression.parameters.nonEmpty =>
        val role = s"BlackBox integer generic '${value.name}' of instance '$instanceName'"
        val ownerEvaluation = ParameterizedStructure.projectedChildEvaluationOf(
          parent, blackBox, value.expression, role, value.sourceLocation
        )
        def validateIntegerDomain(): Unit = {
          ElabInt.requireAuthoritativeIntegerDomain(
            value.expression,
            role,
            "SPINAL-PARAMETERIZED-VERILOG-BLACKBOX-INTEGER-GENERIC-DOMAIN-INVALID",
            requireExactExtrema = false
          )
          ()
        }
        ownerEvaluation match {
          case Some(evaluation) =>
            ElaborationDomainContext.withAdmitted(
              value.expression.exactDomain.get.root,
              evaluation.rootValues,
              value.sourceLocation
            )(validateIntegerDomain())
          case None => validateIntegerDomain()
        }
        value.name -> ExpressionBinding(value.expression)
      case value: ParameterizedBlackBoxBooleanGeneric
          if value.expression.parameters.nonEmpty =>
        ElabInt.requireAuthoritativeBooleanDomain(
          value.expression,
          s"BlackBox Boolean generic '${value.name}' of instance '$instanceName'",
          "SPINAL-PARAMETERIZED-VERILOG-BLACKBOX-BOOLEAN-GENERIC-DOMAIN-INVALID"
        )
        value.name -> BooleanExpressionBinding(value.expression)
    }

    val ports = blackBox.getOrdredNodeIo.toVector
      .filterNot(_.isSuffix)
      .flatMap { port =>
        ParameterizedWidth.expressionOf(port).filter(_.parameters.nonEmpty).map { expression =>
          val name = Option(port.getName()).filter(_.nonEmpty).getOrElse {
            fail(
              "SPINAL-PARAMETERIZED-VERILOG-BLACKBOX-PORT-NAME-MISSING",
              s"BlackBox instance '$instanceName' has one unnamed symbolic packed port",
              expression.sourceLocation
            )
          }
          ElabInt.requireAuthoritativeIntegerDomain(
            expression,
            s"BlackBox port '$name' width of instance '$instanceName'",
            "SPINAL-PARAMETERIZED-VERILOG-BLACKBOX-PORT-WIDTH-DOMAIN-INVALID",
            requireExactExtrema = false
          )
          if (expression.default != BigInt(port.getBitsWidth)) {
            fail(
              "SPINAL-PARAMETERIZED-VERILOG-BLACKBOX-PORT-WITNESS-MISMATCH",
              s"BlackBox port '$name' of instance '$instanceName' has native width ${port.getBitsWidth}, but its retained typed width has witness ${expression.default}",
              expression.sourceLocation
            )
          }
          if (
            expression.minimum < 1 ||
            expression.maximum > BigInt(pc.config.bitVectorWidthMax)
          ) {
            fail(
              "SPINAL-PARAMETERIZED-VERILOG-BLACKBOX-PORT-WIDTH-DOMAIN-INVALID",
              s"BlackBox port '$name' of instance '$instanceName' reaches width [${expression.minimum}, ${expression.maximum}], outside [1, ${pc.config.bitVectorWidthMax}]",
              expression.sourceLocation
            )
          }
          PortRewrite(name, ExpressionBinding(expression))
        }
      }

    InstancePlan(
      definitionName,
      instanceName,
      bindings,
      ports,
      preserveExistingGenericAssociations = true
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

    ExternalFormalParameterRegistry.bindingsOf(child).foreach { binding =>
      if (binding.actual.exactDomain.nonEmpty) {
        ParameterizedStructure
          .projectedChildEvaluationOf(
            parent,
            child,
            binding.actual,
            s"formal actual '${binding.formal.name}' of child '$instanceName'",
            binding.sourceLocation.orElse(binding.actual.sourceLocation)
          )
          .getOrElse {
            fail(
              "SPINAL-ELAB-DOMAIN-PROJECTION-EVIDENCE-MISSING",
              s"formal actual '${binding.formal.name}' of child '$instanceName' lost its exact typed evaluation evidence",
              binding.sourceLocation.orElse(binding.actual.sourceLocation)
            )
          }
      }
    }

    val actualPorts = indexedPorts(child, "actual", instanceName)
    val canonicalPorts = indexedPorts(canonical, "canonical", instanceName)
    if (actualPorts.map(_._1) != canonicalPorts.map(_._1)) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-PORT-LAYOUT-MISMATCH",
        s"instance '$instanceName' and canonical definition '$definitionName' have different ordered port names"
      )
    }

    actualPorts.zip(canonicalPorts).foreach { case ((name, actual), (_, expected)) =>
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
    val canonicalParameters = componentParameters(canonical)
    val aggregateEvidence = canonicalParameters.map { parameter =>
      parameter.name -> pulledAggregateBindingEvidence(
        parent,
        child,
        canonical,
        actualPorts,
        canonicalPorts,
        assignments,
        parameter,
        instanceName
      )
    }.toMap
    val bindings = canonicalParameters
      .map { parameter =>
        val parameterPorts = canonicalPorts.collect {
          case (name, port) if ParameterizedWidth.parameterOf(port).exists(_.name == parameter.name) =>
            name
        }
        val aggregate = aggregateEvidence(parameter.name)
        if (aggregate.present) {
          val uncovered = parameterPorts.filterNot { name =>
            aggregate.canonicalPorts.exists(_ eq canonicalByName(name))
          }
          if (uncovered.nonEmpty) {
            fail(
              "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-AGGREGATE-SURFACE-MIXED",
              s"parameter '${parameter.name}' of instance '$instanceName' appears on exact pulled Vec ports and unrelated scalar ports ${uncovered.sorted
                  .mkString(", ")}"
            )
          }
          val expressions = distinctBindings(aggregate.bindings)
          if (expressions.size != 1) {
            fail(
              "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-BINDING-CONFLICT",
              s"exact pulled Vec ports of instance '$instanceName' constrain parameter '${parameter.name}' with ${expressions.map(_.render).sorted.mkString(", ")}"
            )
          }
          val expression = expressions.head
          validateParameterBinding(expression, parameter, instanceName, pc)
          parameter.name -> expression
        } else if (parameterPorts.isEmpty) {
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
            formalBindingEvidenceOf(canonicalByName(name))
          }
          val actualPortFormals = parameterPorts.flatMap { name =>
            formalBindingEvidenceOf(actualByName(name))
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
              (canonicalFormals ++ actualFormals).foreach { evidence =>
                val binding = evidence.binding
                if (binding.formal != parameter) {
                  fail(
                    "SPINAL-PARAMETERIZED-VERILOG-FORMAL-SCHEMA-CONFLICT",
                    s"formal slot '${binding.formal.name}' does not match canonical child parameter '${parameter.name}' of '$definitionName'",
                    binding.sourceLocation
                  )
                }
              }
              validateMappedFormalIdentity(
                canonicalFormals,
                actualFormals,
                parameter,
                instanceName
              )
              val actualExpressions = ExternalFormalParameterRegistry
                .distinctExpressions(actualFormals.map(_.binding.actual))
                .map(ExternalFormalParameterRegistry.normalizedExpression)
              if (actualExpressions.size != 1) {
                fail(
                  "SPINAL-PARAMETERIZED-VERILOG-FORMAL-SLOT-AMBIGUOUS",
                  s"formal slot '${parameter.name}' of instance '$instanceName' maps to multiple actual expressions: ${actualExpressions.map(_.verilog).sorted.mkString(", ")}",
                  actualFormals.flatMap(_.binding.sourceLocation).headOption
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
                !sameFormalActualConnection(
                  connectionBindings.head,
                  explicit
                )
              ) {
                fail(
                  "SPINAL-PARAMETERIZED-VERILOG-FORMAL-ACTUAL-CONNECTION-CONFLICT",
                  s"connections of instance '$instanceName' constrain formal '${parameter.name}' with ${connectionBindings
                      .map(_.render)
                      .sorted
                      .mkString(", ")}, but explicit actual is '${explicit.render}'",
                  actualFormals.flatMap(_.binding.sourceLocation).headOption
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
      }
      .sortBy(_._1)

    val canonicalParameterNames = canonicalParameters.map(_.name).toSet
    val aggregatePorts = aggregateEvidence.valuesIterator
      .filter(_.present)
      .flatMap(_.canonicalPorts)
      .toVector
    canonicalPorts.foreach { case (name, expectedPort) =>
      val expectedWidth = ParameterizedWidth
        .expressionOf(expectedPort)
        .filter(_.parameters.nonEmpty)
      val actualWidth = ParameterizedWidth
        .expressionOf(actualByName(name))
        .filter(_.parameters.nonEmpty)
      if (expectedWidth.isEmpty && actualWidth.nonEmpty) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-CANONICAL-SCHEMA-CONFLICT",
          s"port '$name' of instance '$instanceName' is symbolic while canonical definition '$definitionName' is concrete"
        )
      }
      if (expectedWidth.nonEmpty && actualWidth.isEmpty) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-CANONICAL-SCHEMA-CONFLICT",
          s"port '$name' of instance '$instanceName' is concrete while canonical definition '$definitionName' is symbolic"
        )
      }
      if (
        expectedWidth.isEmpty &&
        !aggregatePorts.exists(_ eq expectedPort)
      ) {
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
        s"instance '$instanceName' parameter dependency set ${actualParameterNames.toVector.sorted
            .mkString(",")} differs from canonical definition '$definitionName' set ${canonicalParameterNames.toVector.sorted
            .mkString(",")}"
      )
    }

    val bindingMap = bindings.toMap
    val ports = canonicalPorts.flatMap { case (name, port) =>
      ParameterizedWidth.expressionOf(port).flatMap { definitionWidth =>
        val expression = ParameterizedWidth.parameterOf(port) match {
          case Some(parameter) => bindingMap(parameter.name)
          case None =>
            instantiateDerivedPortWidth(
              definitionWidth,
              bindingMap,
              definitionName,
              instanceName,
              name
            )
        }
        if (expression.isSymbolic) Some(PortRewrite(name, expression)) else None
      }
    }
    InstancePlan(definitionName, instanceName, bindings, ports)
  }

  /** Substitute one definition-side derived packed width with the already
    * proven actual bindings of this exact child instance. Direct formal widths
    * keep the older identity-preserving path above; this helper covers only
    * expressions such as `addressWidth(DEPTH + 1)`.
    *
    * The definition bounds remain a conservative envelope because every
    * actual binding was validated to stay inside its formal domain. Replacing
    * all identifiers in one pass prevents an actual expression from being
    * rewritten again when it happens to mention another formal name.
    */
  private def instantiateDerivedPortWidth(
      definition: ElaborationIntegerExpression,
      bindings: Map[String, BindingExpr],
      definitionName: String,
      instanceName: String,
      portName: String
  ): BindingExpr = {
    val formals = definition.parameters.distinct.sortBy(_.name)
    if (formals.isEmpty) return ExpressionBinding(definition)

    val replacements = formals.map { formal =>
      val actual = bindings.getOrElse(
        formal.name,
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-BINDING-UNRESOLVED",
          s"derived width '${definition.verilog}' of port '$portName' on canonical child '$definitionName' references unbound formal '${formal.name}' for instance '$instanceName'"
        )
      )
      formal.name -> actual
    }.toMap
    val substituted = mutable.HashSet.empty[String]
    val rendered = new StringBuilder
    var cursor = 0
    while (cursor < definition.verilog.length) {
      val current = definition.verilog.charAt(cursor)
      if (isIdentifierStart(current)) {
        val start = cursor
        cursor += 1
        while (
          cursor < definition.verilog.length &&
          isIdentifierCharacter(definition.verilog.charAt(cursor))
        ) cursor += 1

        val identifier = definition.verilog.substring(start, cursor)
        replacements.get(identifier) match {
          case Some(actual) =>
            var next = cursor
            while (
              next < definition.verilog.length &&
              definition.verilog.charAt(next).isWhitespace
            ) next += 1

            // Retained integer expressions use ordinary identifiers both for
            // formal parameters and for portable helper calls. Verilog keeps
            // module parameters and functions in one identifier namespace, so
            // a formal that also appears as a call target (for example
            // `clog2`) cannot be published safely. Fail closed instead of
            // rewriting the function token into a call of the parent actual.
            if (
              next < definition.verilog.length &&
              definition.verilog.charAt(next) == '('
            ) {
              fail(
                "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-DERIVED-WIDTH-IDENTIFIER-COLLISION",
                s"derived width '${definition.verilog}' of port '$portName' on canonical child '$definitionName' uses formal '$identifier' as a function-call identifier for instance '$instanceName'"
              )
            } else {
              rendered.append('(').append(actual.render).append(')')
              substituted += identifier
            }
          case None => rendered.append(identifier)
        }
      } else {
        rendered.append(current)
        cursor += 1
      }
    }
    val missing = replacements.keySet.diff(substituted.toSet)
    if (missing.nonEmpty) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-BINDING-UNRESOLVED",
        s"derived width '${definition.verilog}' of port '$portName' on canonical child '$definitionName' does not contain retained formal identifiers ${missing.toVector.sorted
            .mkString(", ")} for instance '$instanceName'"
      )
    }

    val actualParameters = replacements.values.toVector
      .flatMap(_.parameters)
      .groupBy(_.name)
      .toVector
      .sortBy(_._1)
      .map {
        case (name, schemas) if schemas.distinct.size == 1 => schemas.head
        case (name, _) =>
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-SCHEMA-CONFLICT",
            s"derived width of port '$portName' on instance '$instanceName' maps actual parameter '$name' to conflicting schemas"
          )
      }
    val actualRoots = replacements.values.toVector
      .flatMap(_.parameterRoots)
      .toSet
    actualRoots
      .groupBy(_.name)
      .collectFirst {
        case (name, roots) if roots.size > 1 => name -> roots
      }
      .foreach { case (name, roots) =>
        fail(
          "SPINAL-ELAB-INT-INDEPENDENT-ROOTS-UNSUPPORTED",
          s"derived width of port '$portName' on instance '$instanceName' combines independently sourced actual declarations for parameter '$name'",
          roots.iterator.flatMap(_.sourceLocation).toVector.headOption
        )
      }
    ExpressionBinding(
      definition.copy(
        verilog = rendered.toString,
        parameters = actualParameters,
        sourceLocation = None,
        parameterRoots = actualRoots.toVector.sortBy(_.name),
        // This BindingExpr is a rendered parent-actual substitution, not a
        // child-root typed carrier. Definition-side exact evidence must never
        // be copied onto the independently rooted actual expression.
        exactDomain = None
      )
    )
  }

  private def isIdentifierStart(value: Char): Boolean =
    value == '_' || value == '$' || value.isLetter

  private def isIdentifierCharacter(value: Char): Boolean =
    isIdentifierStart(value) || value.isDigit

  private def componentParameters(
      component: Component
  ): Vector[ElaborationIntegerParameter] = {
    val values =
      ParameterizedWidth.parametersOf(component) ++
        ParameterizedMemory.parametersOf(component) ++
        ExternalParameterizedValueRegistry.parametersOf(component) ++
        ParameterizedBlackBoxGenericRegistry.parametersOf(component) ++
        ParameterizedVerilogVecs.parametersOf(component) ++
        ParameterizedStructure.parametersOf(component) ++
        ParameterizedProcess.parametersOf(component)
    val grouped = values.groupBy(_.name)
    grouped
      .collectFirst {
        case (name, declarations) if declarations.distinct.size != 1 => name
      }
      .foreach { name =>
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-SCHEMA-CONFLICT",
          s"component '${component.definitionName}' has conflicting hierarchy parameter declarations for '$name'"
        )
      }
    grouped.toVector.map(_._2.head).sortBy(_.name)
  }

  /** Classify a pulled typed Vec port as one aggregate hierarchy surface.
    *
    * Discovery starts from the canonical Vec object and its exact flattened
    * port identities, then maps those identities to the actual instance by
    * native port ordinal.  The parent connection is admitted only when that
    * exact actual Vec retained a packed-read operation whose exact result is
    * consumed in the parent. Rendered port, signal and parameter names are
    * used only after this identity proof for diagnostics/publication.
    */
  private def pulledAggregateBindingEvidence(
      parent: Component,
      child: Component,
      canonical: Component,
      actualPorts: Vector[(String, BaseType)],
      canonicalPorts: Vector[(String, BaseType)],
      assignments: Vector[DataAssignmentStatement],
      parameter: ElaborationIntegerParameter,
      instanceName: String
  ): AggregateBindingEvidence = {
    val bindings = ArrayBuffer.empty[BindingExpr]
    val covered = ArrayBuffer.empty[BaseType]
    var present = false

    def vectorLeaves(vector: Vec[_]): Vector[BaseType] =
      vector.vec.flatMap(element => element.asInstanceOf[Data].flatten).toVector

    def exactReferences(expression: Expression, target: Expression): Boolean = {
      var found = false
      def visit(current: Expression): Unit =
        if (!found && current != null) {
          if (current eq target) found = true
          else current.foreachExpression(visit)
        }
      visit(expression)
      found
    }

    def registryFormals(
        component: Component,
        role: String
    ): Vector[FormalBindingEvidence] = {
      val typed = ExternalFormalParameterRegistry.typedBindingsOf(component)
      if (typed.nonEmpty) {
        if (typed.size != 1) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-AGGREGATE-FORMAL-IDENTITY-CONFLICT",
            s"candidate pulled Vec surface of instance '$instanceName' retains ${typed.size} opaque $role formal capabilities; exactly one is required",
            typed.flatMap(_.binding.sourceLocation).headOption
          )
        }
        val value = typed.head
        val schemaMatches =
          if (role == "canonical") value.binding.formal eq parameter
          else value.binding.formal == parameter
        if (schemaMatches)
          Vector(
            FormalBindingEvidence(
              value.binding,
              Some(value.declarationToken)
            )
          )
        else Vector.empty
      } else
        ExternalFormalParameterRegistry
          .bindingsOf(component)
          .filter(_.formal == parameter)
          .map(FormalBindingEvidence(_, None))
    }

    val canonicalRegistryFormals = registryFormals(canonical, "canonical")
    val actualRegistryFormals = registryFormals(child, "actual")

    def exactVecFormals(
        vector: Vec[_],
        candidates: Vector[FormalBindingEvidence]
    ): Vector[FormalBindingEvidence] = {
      val retained = ParameterizedVec.formalBindingsOf(vector)
      candidates.filter { candidate =>
        retained.exists { binding =>
          (binding.formal eq candidate.binding.formal) &&
          ElabInt.equivalentExactFunction(
            binding.actual,
            candidate.binding.actual
          )
        }
      }
    }

    val actualVectors = ParameterizedVec.vectorsOf(child)
    ParameterizedVec.vectorsOf(canonical).foreach { canonicalVector =>
      val canonicalShape = ParameterizedVec.shapeOf(canonicalVector).getOrElse {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-VEC-SHAPE-MISSING",
          s"canonical pulled Vec of instance '$instanceName' lost its retained shape"
        )
      }
      val dimensions = canonicalShape.geometryExpressions
      val canonicalFormals = exactVecFormals(
        canonicalVector,
        canonicalRegistryFormals
      )
      if (canonicalFormals.size > 1) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-AGGREGATE-FORMAL-IDENTITY-CONFLICT",
          s"candidate pulled Vec surface of instance '$instanceName' retains multiple exact canonical formal identities for parameter '${parameter.name}'",
          canonicalFormals.flatMap(_.binding.sourceLocation).headOption
        )
      }
      val dependsOnParameter = canonicalFormals.headOption match {
        case Some(canonicalBinding) =>
          dimensions.exists(
            _.completedParameterRoots.exists(
              _ eq canonicalBinding.binding.formal.declarationRoot
            )
          )
        case None =>
          dimensions.exists(
            ParameterizedVec.isExactDirectParameterSchema(_, parameter)
          )
      }
      val canonicalLeaves = vectorLeaves(canonicalVector)
      if (
        dependsOnParameter && canonicalLeaves.nonEmpty &&
        canonicalLeaves.forall(_.isIo)
      ) {
        val portOrdinals = canonicalLeaves.map { leaf =>
          canonicalPorts.indexWhere { case (_, port) => port eq leaf }
        }
        if (portOrdinals.exists(_ < 0)) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-PORT-LAYOUT-MISMATCH",
            s"canonical pulled Vec of instance '$instanceName' is not wholly represented by exact canonical port identities",
            canonicalShape.sourceLocation
          )
        }
        val actualLeaves = portOrdinals.map(index => actualPorts(index)._2)
        val candidates = actualVectors.filter { vector =>
          val leaves = vectorLeaves(vector)
          leaves.size == actualLeaves.size &&
          leaves.zip(actualLeaves).forall { case (left, right) => left eq right }
        }
        if (candidates.size > 1) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-AGGREGATE-IDENTITY-AMBIGUOUS",
            s"canonical pulled Vec of instance '$instanceName' maps to ${candidates.size} actual Vec identities",
            canonicalShape.sourceLocation
          )
        }
        candidates.headOption.foreach { actualVector =>
          val actualFormals = exactVecFormals(
            actualVector,
            actualRegistryFormals
          )
          val formalPair = (canonicalFormals, actualFormals) match {
            case (Vector(canonicalBinding), Vector(actualBinding)) =>
              validateMappedFormalIdentity(
                Vector(canonicalBinding),
                Vector(actualBinding),
                parameter,
                instanceName
              )
              Some(canonicalBinding.binding -> actualBinding.binding)
            case (Vector(), Vector()) => None
            case _ =>
              fail(
                "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-AGGREGATE-FORMAL-IDENTITY-CONFLICT",
                s"candidate pulled Vec surface of instance '$instanceName' does not retain one matching canonical and actual formal declaration identity for parameter '${parameter.name}'",
                (canonicalFormals ++ actualFormals)
                  .flatMap(_.binding.sourceLocation)
                  .headOption
              )
          }
          val reads = ParameterizedVec.operationsOf(actualVector).collect {
            case read: ParameterizedVecPackedRead
                if assignments.exists(assignment =>
                  (assignment.finalTarget.component eq parent) &&
                    (exactReferences(assignment.source, read.result) ||
                      exactReferences(assignment.source, read.carrier))
                ) =>
              read
          }
          // Whole native Vec assignment/auto-connect is also an aggregate
          // boundary. A depth-only parameter has no scalar width port to
          // constrain it, so retain the complete Vec relation by exact live
          // statements instead of asking for a fabricated scalar formal.
          val boundaries = ParameterizedVec.vectorsOf(parent).flatMap { parentVector =>
            val parentLeaves = vectorLeaves(parentVector)
            val childOwned = ParameterizedVec.operationsOf(actualVector).collect {
              case value: ParameterizedVecWholeAssignment if value.source eq parentVector => value.assignments
              case value: ParameterizedVecAutoConnect if value.peer eq parentVector => value.assignments
            }
            val parentOwned = ParameterizedVec.operationsOf(parentVector).collect {
              case value: ParameterizedVecWholeAssignment if value.source eq actualVector => value.assignments
              case value: ParameterizedVecAutoConnect if value.peer eq actualVector => value.assignments
            }
            (childOwned ++ parentOwned).filter { evidence =>
              evidence.size == actualLeaves.size && parentLeaves.size == actualLeaves.size &&
              evidence.forall(statement => assignments.exists(_ eq statement) &&
                (statement.parentScope eq evidence.head.parentScope) &&
                ((statement.parentScope eq parent.dslBody) || actualLeaves.forall(leaf => leaf.isOutput && !leaf.isInput)) &&
                (statement.target eq statement.finalTarget)) &&
              actualLeaves.zip(parentLeaves).forall { case (childLeaf, parentLeaf) =>
                val input = childLeaf.isInput && !childLeaf.isOutput && !childLeaf.isInOut
                val output = childLeaf.isOutput && !childLeaf.isInput && !childLeaf.isInOut
                (input || output) && (parentLeaf.component eq parent) && !parentLeaf.isInOut &&
                evidence.count { statement =>
                  val target = if (input) childLeaf else parentLeaf
                  val source = if (input) parentLeaf else childLeaf
                  (statement.finalTarget eq target) && (statement.source match {
                    case actual: BaseType => actual eq source
                    case _ => false
                  })
                } == 1
              }
            }.map { evidence =>
              ParameterizedVec.requireCompatible(actualVector, parentVector)
              evidence
            }
          }
          if (reads.nonEmpty || boundaries.nonEmpty) {
            present = true
            val actual = formalPair
              .flatMap { case (canonicalBinding, actualBinding) =>
                ParameterizedVec.exactAggregateHierarchyBinding(
                  canonicalVector,
                  actualVector,
                  canonicalBinding.formal,
                  canonicalBinding.actual,
                  actualBinding.formal,
                  actualBinding.actual
                )
              }
              .orElse {
                if (formalPair.isEmpty)
                  ParameterizedVec.exactDirectAggregateHierarchyBinding(
                    canonicalVector,
                    actualVector,
                    parameter
                  )
                else None
              }
              .getOrElse {
                fail(
                  "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-AGGREGATE-SHAPE-MISMATCH",
                  s"exact pulled Vec port of instance '$instanceName' does not preserve the canonical root/function layout for parameter '${parameter.name}'",
                  canonicalShape.sourceLocation
                )
              }
            reads.foreach { read =>
              if (
                !ParameterizedVec.exactPackedShapeMatches(
                  actualVector,
                  read.result
                ) ||
                ParameterizedVec.packedWidthExpressionOf(read.result).isEmpty
              ) {
                fail(
                  "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-AGGREGATE-PACKED-IDENTITY-MISMATCH",
                  s"exact pulled Vec port of instance '$instanceName' lost its identity-owned packed shape",
                  canonicalShape.sourceLocation
                )
              }
            }
            bindings += ExpressionBinding(actual)
            canonicalLeaves.foreach { leaf =>
              if (!covered.exists(_ eq leaf)) covered += leaf
            }
          }
        }
      }
    }
    AggregateBindingEvidence(present, bindings.toVector, covered.toVector)
  }

  private def componentOnlyBinding(
      canonical: Component,
      child: Component,
      parameter: ElaborationIntegerParameter,
      definitionName: String,
      instanceName: String
  ): BindingExpr = {
    val canonicalTyped = ExternalFormalParameterRegistry.typedBindingsOf(canonical)
    val actualTyped = ExternalFormalParameterRegistry.typedBindingsOf(child)
    val hasTyped = canonicalTyped.nonEmpty || actualTyped.nonEmpty
    val (canonicalBindings, actualBindings) =
      if (hasTyped) {
        if (canonicalTyped.size != 1 || actualTyped.size != 1) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-FORMAL-SLOT-IDENTITY-CONFLICT",
            s"scalar typed formal '${parameter.name}' of instance '$instanceName' requires one opaque capability on each exact mapped component",
            (canonicalTyped ++ actualTyped).flatMap(_.binding.sourceLocation).headOption
          )
        }
        val canonicalEvidence = FormalBindingEvidence(
          canonicalTyped.head.binding,
          Some(canonicalTyped.head.declarationToken)
        )
        val actualEvidence = FormalBindingEvidence(
          actualTyped.head.binding,
          Some(actualTyped.head.declarationToken)
        )
        if (
          !(canonicalEvidence.binding.formal eq parameter) ||
          actualEvidence.binding.formal != parameter
        ) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-FORMAL-SCHEMA-CONFLICT",
            s"scalar typed formal of instance '$instanceName' does not preserve its exact canonical declaration and compatible actual schema",
            Vector(canonicalEvidence, actualEvidence).flatMap(_.binding.sourceLocation).headOption
          )
        }
        validateMappedFormalIdentity(
          Vector(canonicalEvidence),
          Vector(actualEvidence),
          parameter,
          instanceName
        )
        Vector(canonicalEvidence) -> Vector(actualEvidence)
      } else {
        val canonicalLegacy = ExternalFormalParameterRegistry
          .bindingsOf(canonical)
          .filter(_.formal == parameter)
          .map(FormalBindingEvidence(_, None))
        val actualLegacy = ExternalFormalParameterRegistry
          .bindingsOf(child)
          .filter(_.formal == parameter)
          .map(FormalBindingEvidence(_, None))
        canonicalLegacy -> actualLegacy
      }

    if (canonicalBindings.isEmpty || actualBindings.isEmpty) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-BINDING-UNRESOLVED",
        s"scalar parameter '${parameter.name}' of canonical child '$definitionName' requires one exact typed scalar-formal binding on canonical and actual instance '$instanceName'"
      )
    }

    val all = canonicalBindings ++ actualBindings
    if (all.exists(_.binding.formal != parameter)) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-FORMAL-SCHEMA-CONFLICT",
        s"scalar formal of instance '$instanceName' does not match canonical child parameter '${parameter.name}' of '$definitionName'",
        all.flatMap(_.binding.sourceLocation).headOption
      )
    }
    if (!hasTyped)
      validateMappedFormalIdentity(
        canonicalBindings,
        actualBindings,
        parameter,
        instanceName
      )
    val actualExpressions = ExternalFormalParameterRegistry
      .distinctExpressions(actualBindings.map(_.binding.actual))
      .map(ExternalFormalParameterRegistry.normalizedExpression)
    if (actualExpressions.size != 1) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-FORMAL-SLOT-AMBIGUOUS",
        s"scalar formal '${parameter.name}' of instance '$instanceName' maps to multiple actual expressions: ${actualExpressions.map(_.verilog).sorted.mkString(", ")}",
        actualBindings.flatMap(_.binding.sourceLocation).headOption
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

  /** Prefer exact per-port metadata. If a later native transformation has
    * removed it from every selected port, recover only from the exact owning
    * component identity retained by the external formal registry. A partial
    * loss remains an error because it cannot prove one complete slot layout.
    */
  private def retainedFormals(
      component: Component,
      portFormals: Vector[FormalBindingEvidence],
      parameterPorts: Vector[String],
      parameter: ElaborationIntegerParameter,
      role: String,
      instanceName: String
  ): Vector[FormalBindingEvidence] = {
    if (portFormals.size == parameterPorts.size) portFormals
    else if (portFormals.nonEmpty) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-FORMAL-LAYOUT-CONFLICT",
        s"formal slot '${parameter.name}' of instance '$instanceName' is retained on only ${portFormals.size} of ${parameterPorts.size} $role packed ports"
      )
    } else {
      val typedAll = ExternalFormalParameterRegistry.typedBindingsOf(component)
      if (typedAll.size > 1) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-FORMAL-SLOT-IDENTITY-CONFLICT",
          s"typed component on the $role side of instance '$instanceName' retains ${typedAll.size} opaque formal capabilities; ElabFormalComponent admits exactly one",
          typedAll.flatMap(_.binding.sourceLocation).headOption
        )
      }
      val typed = typedAll.flatMap { value =>
        val schemaMatches =
          if (role == "canonical") value.binding.formal eq parameter
          else value.binding.formal == parameter
        if (schemaMatches)
          Some(
            FormalBindingEvidence(value.binding, Some(value.declarationToken))
          )
        else None
      }
      val all = ExternalFormalParameterRegistry
        .bindingsOf(component)
        .filter(_.formal == parameter)
      val legacy = all
        .filterNot(binding => typed.exists(value => value.binding eq binding))
        .map(FormalBindingEvidence(_, None))
      if (typed.nonEmpty && legacy.nonEmpty) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-FORMAL-AUTHORITY-MIXED",
          s"formal slot '${parameter.name}' of instance '$instanceName' mixes opaque typed and legacy $role declarations",
          (typed ++ legacy).flatMap(_.binding.sourceLocation).headOption
        )
      }
      val componentBindings = typed ++ legacy
      if (componentBindings.isEmpty) Vector.empty
      else {
        validateOneSideFormalIdentity(
          componentBindings,
          parameter,
          role,
          instanceName
        )
        val expressions = ExternalFormalParameterRegistry
          .distinctExpressions(componentBindings.map(_.binding.actual))
          .map(ExternalFormalParameterRegistry.normalizedExpression)
        if (expressions.size != 1) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-FORMAL-SLOT-AMBIGUOUS",
            s"formal slot '${parameter.name}' of instance '$instanceName' maps to multiple $role component actual expressions: ${expressions.map(_.verilog).sorted.mkString(", ")}",
            componentBindings.flatMap(_.binding.sourceLocation).headOption
          )
        }
        Vector.fill(parameterPorts.size)(componentBindings.head)
      }
    }
  }

  private def formalBindingEvidenceOf(
      port: BaseType
  ): Option[FormalBindingEvidence] =
    ExternalFormalParameterRegistry
      .typedBindingOf(port)
      .map(value => FormalBindingEvidence(value.binding, Some(value.declarationToken)))
      .orElse(
        ExternalFormalParameterRegistry
          .bindingOf(port)
          .map(FormalBindingEvidence(_, None))
      )

  private def distinctTypedTokens(
      values: Vector[FormalBindingEvidence]
  ): Vector[ExternalTypedFormalDeclarationToken] =
    values
      .flatMap(_.typedToken)
      .foldLeft(
        Vector.empty[ExternalTypedFormalDeclarationToken]
      ) {
        case (known, token) if known.exists(_ eq token) => known
        case (known, token)                             => known :+ token
      }

  /** Typed authority is one opaque token on each exact mapped component plus
    * its exact leaf layout. Tokens are intentionally not compared across the
    * canonical/actual boundary. Legacy declaration keys and owner class names
    * remain isolated in the non-typed branch below.
    */
  private def validateMappedFormalIdentity(
      canonical: Vector[FormalBindingEvidence],
      actual: Vector[FormalBindingEvidence],
      parameter: ElaborationIntegerParameter,
      instanceName: String
  ): Unit = {
    val all = canonical ++ actual
    val typed = all.filter(_.isTyped)
    if (typed.nonEmpty) {
      validateTypedMappedFormalIdentity(
        canonical,
        actual,
        parameter,
        instanceName
      )
    } else {
      val canonicalKeys = canonical.map(_.binding.declarationKey).distinct
      val actualKeys = actual.map(_.binding.declarationKey).distinct
      val owners = all.map(_.binding.ownerClassName).distinct
      if (
        canonicalKeys.size != 1 || actualKeys.size != 1 ||
        canonicalKeys.head != actualKeys.head || owners.size != 1
      ) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-FORMAL-SLOT-IDENTITY-CONFLICT",
          s"legacy formal slot '${parameter.name}' of instance '$instanceName' does not map to one canonical declaration identity",
          all.flatMap(_.binding.sourceLocation).headOption
        )
      }
    }
  }

  private def validateTypedMappedFormalIdentity(
      canonical: Vector[FormalBindingEvidence],
      actual: Vector[FormalBindingEvidence],
      parameter: ElaborationIntegerParameter,
      instanceName: String
  ): Unit = {
    val all = canonical ++ actual
    if (!all.forall(_.isTyped)) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-FORMAL-AUTHORITY-MIXED",
        s"formal slot '${parameter.name}' of instance '$instanceName' mixes opaque typed and legacy authority",
        all.flatMap(_.binding.sourceLocation).headOption
      )
    }
    validateTypedOneSideFormalIdentity(
      canonical,
      parameter,
      "canonical",
      instanceName
    )
    validateTypedOneSideFormalIdentity(
      actual,
      parameter,
      "actual",
      instanceName
    )
    if (!canonical.forall(value => value.binding.formal eq parameter)) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-FORMAL-SLOT-IDENTITY-CONFLICT",
        s"typed formal slot '${parameter.name}' of instance '$instanceName' is not retained by the exact canonical declaration object",
        canonical.flatMap(_.binding.sourceLocation).headOption
      )
    }
  }

  private def validateOneSideFormalIdentity(
      values: Vector[FormalBindingEvidence],
      parameter: ElaborationIntegerParameter,
      role: String,
      instanceName: String
  ): Unit = {
    if (values.forall(_.isTyped)) {
      validateTypedOneSideFormalIdentity(
        values,
        parameter,
        role,
        instanceName
      )
    } else {
      val keys = values.map(_.binding.declarationKey).distinct
      val owners = values.map(_.binding.ownerClassName).distinct
      if (keys.size != 1 || owners.size != 1) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-FORMAL-SLOT-IDENTITY-CONFLICT",
          s"legacy formal slot '${parameter.name}' of instance '$instanceName' maps to multiple $role declaration identities",
          values.flatMap(_.binding.sourceLocation).headOption
        )
      }
    }
  }

  private def validateTypedOneSideFormalIdentity(
      values: Vector[FormalBindingEvidence],
      parameter: ElaborationIntegerParameter,
      role: String,
      instanceName: String
  ): Unit = {
    if (!values.forall(_.isTyped) || distinctTypedTokens(values).size != 1) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-FORMAL-SLOT-IDENTITY-CONFLICT",
        s"typed formal slot '${parameter.name}' of instance '$instanceName' does not map to one opaque $role declaration capability",
        values.flatMap(_.binding.sourceLocation).headOption
      )
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
    ports
      .groupBy(_._1)
      .collectFirst {
        case (name, values) if values.size != 1 => name
      }
      .foreach { name =>
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
    case _: BoolLiteral => LiteralBinding(1)
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
    values
      .groupBy(bindingSignature)
      .toVector
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
      parameters = value.parameters.distinct.sortBy(_.name),
      parameterRoots = value.parameterRoots,
      exactDomain = value match {
        case ExpressionBinding(expression) => expression.exactDomain
        case _                             => None
      },
      projection = value match {
        case ExpressionBinding(expression) =>
          expression.projectionProvenance.map { value =>
            ProjectionSignature(value.root, value.admitted, value.representative)
          }
        case _ => None
      }
    )

  /** Compare one exact parent connection with its explicit instance actual.
    *
    * Established HdlInt formals retain their actual through the frontend's
    * bounded AST proof, while a later packed-width adapter may enrich that same
    * declaration-root function with a complete exact table. That enrichment is
    * not a conflicting actual. The fallback below admits only this one-sided,
    * full-domain refinement after rendered algebra, summaries, schemas and JVM
    * declaration-root identities already match; partial evidence and two
    * disagreeing exact functions remain rejected.
    */
  private def sameFormalActualConnection(
      connection: BindingExpr,
      explicit: BindingExpr
  ): Boolean = {
    if (bindingSignature(connection) == bindingSignature(explicit)) return true

    (connection, explicit) match {
      case (ExpressionBinding(left), ExpressionBinding(right)) if ElabInt.equivalentExactFunction(left, right) =>
        true
      case (ExpressionBinding(left), ExpressionBinding(right)) =>
        val leftBase = bindingSignature(connection).copy(
          exactDomain = None,
          projection = None
        )
        val rightBase = bindingSignature(explicit).copy(
          exactDomain = None,
          projection = None
        )

        def isCompleteRefinement(
            expression: ElaborationIntegerExpression
        ): Boolean =
          expression.exactDomain.exists { domain =>
            val roots = expression.completedParameterRoots.foldLeft(
              Vector.empty[ElaborationIntegerParameterRoot]
            ) { (known, root) =>
              if (known.exists(_ eq root)) known else known :+ root
            }
            val authoritativeIdentity = expression.parameters match {
              case Vector(schema) =>
                roots match {
                  case Vector(root) =>
                    (root eq domain.root) &&
                    (schema eq domain.parameter) &&
                    schema.name == domain.root.name
                  case _ => false
                }
              case _ => false
            }
            val results = domain.evaluations.map(_._2)
            authoritativeIdentity &&
            domain.hasCompleteCoverage &&
            results.nonEmpty &&
            results.min == expression.minimum &&
            results.max == expression.maximum &&
            domain
              .evaluate(domain.parameter.default)
              .contains(expression.default) &&
            expression.projectionProvenance.forall { projection =>
              (projection.root eq domain.root) &&
              projection.admitted == domain.universe &&
              projection.representative == domain.parameter.default
            }
          }

        val oneSidedCompleteRefinement =
          (left.exactDomain.isEmpty &&
            left.projectionProvenance.isEmpty &&
            isCompleteRefinement(right)) ||
            (right.exactDomain.isEmpty &&
              right.projectionProvenance.isEmpty &&
              isCompleteRefinement(left))
        leftBase == rightBase && oneSidedCompleteRefinement
      case _ => false
    }
  }

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
