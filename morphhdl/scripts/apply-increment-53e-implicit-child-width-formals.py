#!/usr/bin/env python3
from pathlib import Path

hierarchy = Path(
    "morphhdl/src/main/scala/spinal/core/internals/"
    "ExternalParameterizedVerilogHierarchy.scala"
)
value = hierarchy.read_text()

signature_marker = '''  private final case class BindingSignature(
      render: String,
      default: BigInt,
      minimum: BigInt,
      maximum: BigInt,
      parameters: Vector[ElaborationIntegerParameter]
  )

'''
if value.count(signature_marker) != 1:
    raise SystemExit(
        f"implicit child-formal type marker count={value.count(signature_marker)}"
    )
slot_types = '''  private final case class ImplicitPackedPort(
      name: String,
      port: BitVector,
      actual: ElaborationIntegerExpression
  )

  private final case class ImplicitPackedSlot(
      parent: Component,
      child: Component,
      definitionName: String,
      ports: Vector[ImplicitPackedPort],
      actual: ElaborationIntegerExpression
  ) {
    val portNames: Vector[String] = ports.map(_.name).sorted
    val key: String = portNames.mkString("|")
  }

'''
value = value.replace(signature_marker, signature_marker + slot_types, 1)

analyze_marker = '''  def analyze(
      component: Component,
      pc: PhaseContext,
      canonicalOf: Component => Component
  ): Plan = {
'''
if value.count(analyze_marker) != 1:
    raise SystemExit(
        f"implicit child-formal analysis marker count={value.count(analyze_marker)}"
    )
method = '''  /**
    * Recover one native child packed-width formal from exact hierarchy
    * connection identity when an untouched library constructor cloned only the
    * concrete witness. This runs after normal elaboration and before canonical
    * module grouping, so every instance of one native definition receives the
    * same definition-side formal schema while retaining its own parent-side
    * actual expression.
    *
    * Width equality alone is never a discovery key. A port is selected only
    * when the exact child port participates in one direct full-packed parent
    * connection whose retained width is symbolic and whose default equals the
    * native witness. Ports are grouped into a slot only when their complete
    * actual expression signatures agree.
    */
  private[internals] def promoteImplicitPackedFormals(
      components: Vector[Component],
      pc: PhaseContext
  ): Unit = {
    val slots = ArrayBuffer.empty[ImplicitPackedSlot]

    components.foreach { parent =>
      val assignments = ArrayBuffer.empty[DataAssignmentStatement]
      parent.dslBody.walkLeafStatements {
        case assignment: DataAssignmentStatement => assignments += assignment
        case _                                    =>
      }

      parent.children.toVector.filterNot(_.isInstanceOf[BlackBox]).foreach {
        child =>
          val instanceName =
            Option(child.getName()).filter(_.nonEmpty).getOrElse("<unnamed>")
          val definitionName =
            Option(child.definitionName).filter(_.nonEmpty).getOrElse {
              fail(
                "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-DEFINITION-NAME-MISSING",
                s"child '$instanceName' has no native definition name during implicit packed-width formalization"
              )
            }

          val candidates = indexedPorts(child, "actual", instanceName).flatMap {
            case (name, port: BitVector)
                if ParameterizedWidth.expressionOf(port).isEmpty =>
              val evidence =
                try {
                  distinctBindings(
                    connectionEvidence(
                      parent,
                      child,
                      port,
                      assignments.toVector,
                      s"implicit packed port '$name' of instance '$instanceName'",
                      allowConcreteInternal = true
                    )
                  )
                } catch {
                  case _: ParameterizedVerilogException => Vector.empty
                }
              if (
                evidence.size == 1 && evidence.head.isSymbolic &&
                evidence.head.default == BigInt(port.getBitsWidth)
              ) {
                val expression = bindingExpression(evidence.head)
                Some(ImplicitPackedPort(name, port, expression))
              } else None
            case _ => None
          }

          candidates
            .groupBy(port => expressionSignature(port.actual))
            .toVector
            .sortBy { case (signature, _) =>
              (signature.render, signature.minimum, signature.maximum)
            }
            .foreach { case (_, grouped) =>
              val actuals = grouped.map(_.actual).distinct
              if (actuals.size != 1) {
                fail(
                  "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-IMPLICIT-FORMAL-AMBIGUOUS",
                  s"native child '$definitionName' instance '$instanceName' maps one packed slot to multiple actual width expressions"
                )
              }
              slots += ImplicitPackedSlot(
                parent = parent,
                child = child,
                definitionName = definitionName,
                ports = grouped.sortBy(_.name),
                actual = actuals.head
              )
            }
      }
    }

    val byDefinition = slots.groupBy(_.definitionName).toVector.sortBy(_._1)
    byDefinition.foreach { case (definitionName, definitionSlots) =>
      val instances = components.filter(component =>
        Option(component.definitionName).contains(definitionName)
      )
      val slotLayoutByInstance = definitionSlots
        .groupBy(_.child)
        .map { case (child, values) => child -> values.map(_.key).sorted }
      val expectedLayout = slotLayoutByInstance.values.toVector.distinct
      if (expectedLayout.size != 1 || slotLayoutByInstance.size != instances.size) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-IMPLICIT-FORMAL-LAYOUT-CONFLICT",
          s"native definition '$definitionName' does not expose one identical symbolic packed-port slot layout on every concrete instance"
        )
      }

      val occupied = instances.flatMap { component =>
        componentParameters(component).map(_.name) ++
          component.getOrdredNodeIo.toVector.flatMap(port =>
            Option(port.getName()).filter(_.nonEmpty)
          )
      }.toSet
      var allocated = occupied
      def allocate(base: String): String = {
        var candidate = base
        var index = 1
        while (allocated.contains(candidate) || pc.verilogKeywords.contains(candidate)) {
          candidate = s"${base}_$index"
          index += 1
        }
        allocated += candidate
        candidate
      }

      val keys = expectedLayout.head.sorted
      val formalNameByKey = keys.zipWithIndex.map { case (key, index) =>
        val base = if (keys.size == 1) "WIDTH" else s"WIDTH_${index + 1}"
        key -> allocate(base)
      }.toMap

      keys.foreach { key =>
        val occurrences = definitionSlots.filter(_.key == key)
        val widths = occurrences.map(_.ports.head.port.getBitsWidth).distinct
        val ownerClasses = occurrences.map(_.child.getClass.getName).distinct
        if (widths.size != 1 || ownerClasses.size != 1) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-IMPLICIT-FORMAL-SCHEMA-CONFLICT",
            s"native definition '$definitionName' slot '$key' has inconsistent concrete witnesses or component owners"
          )
        }
        val actuals = occurrences.map(_.actual)
        if (actuals.exists(_.default != BigInt(widths.head))) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-IMPLICIT-FORMAL-WITNESS-MISMATCH",
            s"native definition '$definitionName' slot '$key' has a parent actual whose default differs from concrete width ${widths.head}"
          )
        }
        val formal = ElaborationIntegerParameter(
          name = formalNameByKey(key),
          default = BigInt(widths.head),
          minimum = actuals.map(_.minimum).min,
          maximum = actuals.map(_.maximum).max
        )
        if (
          formal.minimum < 1 || formal.maximum < formal.minimum ||
          formal.default < formal.minimum || formal.default > formal.maximum ||
          formal.maximum > BigInt(pc.config.bitVectorWidthMax)
        ) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-IMPLICIT-FORMAL-DOMAIN-UNSUPPORTED",
            s"native definition '$definitionName' slot '$key' infers invalid formal domain [${formal.minimum}, ${formal.maximum}] with default ${formal.default}"
          )
        }

        val declarationKey =
          s"implicit-packed-width::${ownerClasses.head}::$definitionName::$key"
        occurrences.foreach { occurrence =>
          val binding = ExternalFormalParameterBinding(
            formal = formal,
            actual = occurrence.actual,
            declarationKey = declarationKey,
            ownerClassName = ownerClasses.head,
            sourceLocation = occurrence.actual.sourceLocation
          )
          val token = ExternalNativeIntFormalizationToken(
            callSite = s"MorphHDL implicit packed-width formalization for $definitionName",
            valueOrigin = occurrence.actual.sourceLocation.getOrElse("<retained-hierarchy-expression>"),
            role = s"implicitPackedWidth(${formal.name})"
          )
          ExternalNativeIntFormalizationRegistry.attachComponent(
            parent = occurrence.parent,
            component = occurrence.child,
            geometry = occurrence.ports.map(_.port),
            binding = binding,
            token = token
          )
        }
      }
    }
  }

  private def bindingExpression(
      value: BindingExpr
  ): ElaborationIntegerExpression = value match {
    case ParameterBinding(parameter) =>
      ElaborationIntegerExpression(
        verilog = parameter.name,
        default = parameter.default,
        minimum = parameter.minimum,
        maximum = parameter.maximum,
        parameters = Vector(parameter)
      )
    case ExpressionBinding(expression) => expression
    case LiteralBinding(literal) =>
      ElaborationIntegerExpression(
        verilog = literal.toString,
        default = literal,
        minimum = literal,
        maximum = literal,
        parameters = Vector.empty
      )
  }

  private def expressionSignature(
      value: ElaborationIntegerExpression
  ): BindingSignature =
    BindingSignature(
      render = value.verilog,
      default = value.default,
      minimum = value.minimum,
      maximum = value.maximum,
      parameters = value.parameters.distinct.sortBy(_.name)
    )

'''
value = value.replace(analyze_marker, method + analyze_marker, 1)
hierarchy.write_text(value)

publisher = Path(
    "morphhdl/src/main/scala/spinal/core/internals/"
    "MorphHdlExternalParameterizedVerilog.scala"
)
value = publisher.read_text()
old = '''    val components = componentGraph(top)
    components.foreach(ExternalParameterizedMemoryRegistry.discover)
    validateFormalDeclarations(components)
'''
new = '''    val components = componentGraph(top)
    components.foreach(ExternalParameterizedMemoryRegistry.discover)
    ExternalParameterizedVerilogHierarchy.promoteImplicitPackedFormals(
      components,
      pc
    )
    validateFormalDeclarations(components)
'''
if value.count(old) != 1:
    raise SystemExit(
        f"implicit child-formal publication marker count={value.count(old)}"
    )
publisher.write_text(value.replace(old, new, 1))
