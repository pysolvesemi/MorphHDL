package spinal.core.internals

import morphhdl.runtime.ParameterizedVerilogMode

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths, StandardCopyOption, StandardOpenOption}
import java.util.IdentityHashMap

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.util.matching.Regex

import spinal.core._

/** MorphHDL-owned final publication transform for Increments 41 through 43.
  *
  * Native SpinalHDL remains authoritative for elaboration, validation,
  * expression semantics, module deduplication and concrete Verilog emission.
  * This external phase observes the finished native graph by object identity,
  * proves symbolic memory, expression, connection, hierarchy, structural and
  * process contracts, then rewrites only the published Verilog artifact.
  */
object MorphHdlExternalParameterizedVerilog {
  private final case class ModuleBlock(name: String, start: Int, end: Int)

  private final case class PortSchema(
      name: String,
      direction: String,
      dataClass: String,
      concreteWidth: Int,
      retained: Option[ElaborationIntegerExpression]
  )

  private final case class ComponentSchema(
      ports: Vector[PortSchema],
      parameters: Vector[ElaborationIntegerParameter],
      vecs: Vector[String]
  )

  private final case class FormalPort(
      name: String,
      binding: ExternalFormalParameterBinding,
      typedToken: Option[ExternalTypedFormalDeclarationToken]
  )

  private final case class FormalSlot(
      name: String,
      formal: ElaborationIntegerParameter,
      declarationKey: String,
      ownerClassName: String,
      typedToken: Option[ExternalTypedFormalDeclarationToken],
      ports: Vector[String],
      sourceLocation: Option[String]
  )

  @deprecated(
    "Emitted-text rewriting is a serialization compatibility path, never canonical semantic authority",
    "Increment 58"
  )
  def rewrite(
      pc: PhaseContext,
      emittedCanonicalOf: Component => Component
  ): Unit = {
    if (!ParameterizedVerilogMode.isEnabled(pc.config)) return
    if (emittedCanonicalOf == null) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-EXTERNAL-CANONICAL-MAP-MISSING",
        "external lowering requires the native emitter's exact canonical-component identity map"
      )
    }
    if (pc.config.oneFilePerComponent) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-EXTERNAL-MULTI-FILE-UNSUPPORTED",
        "external expression and hierarchy lowering requires one native Verilog publication file"
      )
    }
    if (pc.config.isSystemVerilog) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MODE-UNSUPPORTED",
        "external expression and hierarchy lowering targets Verilog-2001, not SystemVerilog"
      )
    }

    val top = Option(pc.topLevel).getOrElse {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-EXTERNAL-TOP-MISSING",
        "external lowering ran without an elaborated top-level component"
      )
    }
    val target = targetPath(pc, top)
    if (!Files.isRegularFile(target)) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-EXTERNAL-SOURCE-MISSING",
        s"native Verilog publication is missing: $target"
      )
    }

    val native = new String(Files.readAllBytes(target), StandardCharsets.UTF_8)
      .replace("\r\n", "\n")
      .replace('\r', '\n')
    val lines = native.split("\n", -1).toVector
    val blocks = moduleBlocks(lines)
    val blockByName = blocks.map(block => block.name -> block).toMap

    val components = componentGraph(top)
    components.foreach(ParameterizedMemory.discover)
    val recursiveReferences = BoundedRecursiveModuleValidation.validate(components)
    components.foreach(component =>
      validateComponentParameterRootInventory(
        component,
        includeChildActuals = false
      )
    )
    validateFormalDeclarations(components)

    val componentIdentities = new IdentityHashMap[Component, java.lang.Boolean]()
    components.foreach(component => componentIdentities.put(component, java.lang.Boolean.TRUE))
    val canonicalByIdentity = new IdentityHashMap[Component, Component]()
    // Validated self-references participate in exact instance relocation, but
    // never in the emitted module inventory or native definition deduplication.
    recursiveReferences.foreach(reference => canonicalByIdentity.put(reference, reference))
    val exactGroups = ArrayBuffer.empty[(Component, ArrayBuffer[Component])]
    val emittedComponents = components.filterNot { component =>
      component.isInBlackBoxTree || component.isInstanceOf[BlackBox]
    }
    emittedComponents.foreach { component =>
      val canonical = Option(emittedCanonicalOf(component)).getOrElse {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-EXTERNAL-CANONICAL-IDENTITY-MISSING",
          s"native emitter returned no canonical identity for captured component '${componentName(component)}'"
        )
      }
      if (!componentIdentities.containsKey(canonical)) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-EXTERNAL-CANONICAL-IDENTITY-FOREIGN",
          s"native emitter mapped captured component '${componentName(component)}' to an identity outside the captured graph"
        )
      }
      val terminal = Option(emittedCanonicalOf(canonical)).getOrElse {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-EXTERNAL-CANONICAL-IDENTITY-MISSING",
          s"native emitter returned no terminal canonical identity for '${componentName(component)}'"
        )
      }
      if (terminal ne canonical) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-EXTERNAL-CANONICAL-IDENTITY-INCONSISTENT",
          s"native emitter canonical mapping for '${componentName(component)}' is not terminal"
        )
      }
      canonicalByIdentity.put(component, canonical)
      exactGroups.find { case (known, _) => known eq canonical } match {
        case Some((_, candidates)) => candidates += component
        case None =>
          exactGroups += ((canonical, ArrayBuffer(component)))
      }
    }

    val canonicalByName = exactGroups.toVector.map { case (canonical, candidateBuffer) =>
      val candidates = candidateBuffer.toVector
      val name = componentName(canonical)
      validateFormalCanonicalGroup(name, candidates)
      val schemas = candidates.map(componentSchema).distinct
      if (schemas.size != 1) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-CANONICAL-SCHEMA-CONFLICT",
          s"native module identity '$name' maps to ${schemas.size} distinct graph schemas"
        )
      }
      name -> canonical
    }
    canonicalByName
      .groupBy(_._1)
      .collectFirst { case (name, values) if values.size != 1 => name }
      .foreach { name =>
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-EXTERNAL-CANONICAL-NAME-AMBIGUOUS",
          s"native publication name '$name' belongs to multiple exact emitter canonical identities"
        )
      }
    val canonicalPublicationByName = canonicalByName.toMap

    if (!components.exists(hasParameterizedMetadata)) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-UNTAGGED-PORT",
        s"component '${componentName(top)}' contains no retained MorphHDL parameter metadata"
      )
    }

    def canonicalOf(component: Component): Component =
      Option(canonicalByIdentity.get(component)).getOrElse {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-EXTERNAL-CANONICAL-IDENTITY-MISSING",
          s"component '${componentName(component)}' is absent from the captured native graph"
        )
      }

    val expectedModules = canonicalPublicationByName.values
      .filterNot { component =>
        component.isInBlackBoxTree || component.isInstanceOf[BlackBox]
      }
      .map(componentName)
      .toSet
    val missingModules = expectedModules.diff(blockByName.keySet)
    if (missingModules.nonEmpty) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-EXTERNAL-MODULE-MAPPING-MISSING",
        s"native publication has no unique module block for ${missingModules.toVector.sorted.mkString(", ")}"
      )
    }

    val rewrittenByName = expectedModules.toVector.sorted.flatMap { name =>
      val component = canonicalPublicationByName(name)
      if (requiresPublicationRewrite(component)) {
        val block = blockByName(name)
        val text = lines.slice(block.start, block.end + 1).mkString("\n")
        val rewritten = withPulledExternalClockInputs(component) {
          val withMemories = ParameterizedVerilogMemories.rewrite(
            component,
            text,
            pc
          )
          val withProcesses = ParameterizedVerilogProcesses.rewrite(
            component,
            withMemories,
            pc
          )
          val withStructure = ParameterizedVerilogStructural.rewrite(
            component,
            withProcesses,
            pc,
            canonicalOf
          )
          val withExpressions = if (requiresExpressionHierarchyRewrite(component)) {
            ExternalParameterizedVerilogNativeFallback.rewrite(
              component,
              withStructure,
              pc,
              canonicalOf
            )
          } else withStructure
          TypedBalancedReductionBackend.rewrite(component, withExpressions, pc, canonicalOf)
        }
        Some(name -> rewritten.split("\n", -1).toVector)
      } else None
    }.toMap

    if (rewrittenByName.nonEmpty) {
      val rewritten = Vector.newBuilder[String]
      var cursor = 0
      blocks.foreach { block =>
        lines.slice(cursor, block.start).foreach(rewritten += _)
        rewrittenByName.get(block.name) match {
          case Some(value) => value.foreach(rewritten += _)
          case None        => lines.slice(block.start, block.end + 1).foreach(rewritten += _)
        }
        cursor = block.end + 1
      }
      lines.drop(cursor).foreach(rewritten += _)
      publishAtomically(target, rewritten.result().mkString("\n"))
    }
  }

  private def targetPath(pc: PhaseContext, top: Component): Path = {
    val filename =
      if (pc.config.netlistFileName == null) top.definitionName + ".v"
      else pc.config.netlistFileName
    Paths.get(pc.config.targetDirectory).resolve(filename)
  }

  private def componentGraph(top: Component): Vector[Component] = {
    val seen = new IdentityHashMap[Component, java.lang.Boolean]()
    val values = ArrayBuffer.empty[Component]
    def visit(component: Component): Unit = {
      if (seen.put(component, java.lang.Boolean.TRUE) == null) {
        values += component
        component.children.foreach(visit)
      }
    }
    visit(top)
    values.toVector
  }

  private def componentName(component: Component): String =
    Option(component.definitionName).filter(_.nonEmpty).getOrElse {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-EXTERNAL-DEFINITION-NAME-MISSING",
        s"native component ${component.getClass.getName} has no definition name"
      )
    }

  /** Validate the complete parameter namespace which one emitted module may
    * collapse into its Verilog header. Each individual metadata registry also
    * validates its own inventory, but a same-named declaration can otherwise
    * arrive through two different registries (or two direct-child actuals)
    * with equal schemas and lose its declaration identity at the later
    * schema-only merge.
    */
  private[internals] def validateComponentParameterRootInventory(
      component: Component,
      includeChildActuals: Boolean
  ): Unit = {
    final case class RootUse(
        root: ElaborationIntegerParameterRoot,
        sourceLocation: Option[String]
    )

    val uses = ArrayBuffer.empty[RootUse]
    val schemas = ArrayBuffer.empty[(ElaborationIntegerParameter, Option[String])]

    def retainInteger(expression: ElaborationIntegerExpression): Unit = {
      ElabInt.validateExpression(
        expression,
        s"component '${componentName(component)}' parameter inventory"
      )
      expression.parameters.foreach(parameter => schemas += (parameter -> expression.sourceLocation))
      expression.completedParameterRoots.foreach { root =>
        uses += RootUse(root, root.sourceLocation.orElse(expression.sourceLocation))
      }
    }

    def retainBoolean(expression: ElaborationBooleanExpression): Unit = {
      ElabInt.validateExpression(
        expression,
        s"component '${componentName(component)}' parameter inventory"
      )
      expression.parameters.foreach(parameter => schemas += (parameter -> expression.sourceLocation))
      expression.completedParameterRoots.foreach { root =>
        uses += RootUse(root, root.sourceLocation.orElse(expression.sourceLocation))
      }
    }

    component.dslBody.walkLeafStatements {
      case baseType: BaseType =>
        ParameterizedWidth.expressionOf(baseType).foreach(retainInteger)
      case _ =>
    }

    ParameterizedMemory.memoriesOf(component).foreach { memory =>
      ParameterizedMemory.metadataOf(memory).foreach { metadata =>
        retainInteger(metadata.depth)
        retainInteger(metadata.elementWidth)
      }
    }

    ParameterizedVec.vectorsOf(component).foreach { vector =>
      ParameterizedVec.shapeOf(vector).foreach { shape =>
        shape.geometryExpressions.foreach(retainInteger)
      }
    }

    ExternalParameterizedValueRegistry.valuesOf(component).foreach { case (_, record) =>
      retainInteger(record.expression)
    }

    ParameterizedBlackBoxGenericRegistry
      .integerExpressionsOf(component)
      .foreach(retainInteger)
    ParameterizedBlackBoxGenericRegistry
      .booleanExpressionsOf(component)
      .foreach(retainBoolean)

    ExternalParameterizedAutoResize
      .normalizedTypedUIntResizeBoundariesOf(component)
      .foreach { boundary =>
        retainInteger(boundary.sourceWidth)
        retainInteger(boundary.targetWidth)
      }

    def retainRegion(region: ParameterizedStructure.StructuralRegion): Unit = {
      region match {
        case value: ParameterizedStructure.StructuralFor =>
          retainInteger(value.count)
        case value: ParameterizedStructure.StructuralIf =>
          retainBoolean(value.condition)
        case value: ParameterizedStructure.StructuralCase =>
          retainInteger(value.selector)
      }
      region.blocks.foreach(_.regions.foreach(retainRegion))
    }
    ParameterizedStructure.regionsOf(component).foreach(retainRegion)

    ParameterizedProcess
      .loopsOf(component)
      .foreach(loop => retainInteger(loop.count))

    if (includeChildActuals) {
      component.children.foreach { child =>
        ExternalFormalParameterRegistry
          .bindingsOf(child)
          .foreach(binding => retainInteger(binding.actual))
      }
    }

    schemas
      .groupBy(_._1.name)
      .toVector
      .sortBy(_._1)
      .collectFirst {
        case (name, declarations) if declarations.map(_._1).distinct.size > 1 =>
          name -> declarations
      }
      .foreach { case (name, declarations) =>
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-SCHEMA-CONFLICT",
          s"component '${componentName(component)}' has conflicting declarations for parameter '$name' across its complete emitted parameter inventory",
          declarations.iterator.flatMap(_._2).toVector.headOption
        )
      }

    val distinct = uses.foldLeft(Vector.empty[RootUse]) {
      case (known, use) if known.exists(value => value.root eq use.root) => known
      case (known, use)                                                  => known :+ use
    }
    distinct
      .groupBy(_.root.name)
      .toVector
      .sortBy(_._1)
      .collectFirst {
        case (name, declarations) if declarations.size > 1 =>
          name -> declarations
      }
      .foreach { case (name, declarations) =>
        fail(
          "SPINAL-ELAB-INT-INDEPENDENT-ROOTS-UNSUPPORTED",
          s"component '${componentName(component)}' combines independently sourced declarations for parameter '$name' across its complete emitted parameter inventory",
          declarations.iterator.flatMap(_.sourceLocation).toVector.headOption
        )
      }
  }

  /** Validate source-stable formal declarations across the complete concrete
    * component graph before native module-name grouping. This catches a changed
    * default or explicit domain even when the ordinary emitter specialized the
    * unequal concrete witnesses under different native definition names.
    */
  private def validateFormalDeclarations(components: Vector[Component]): Unit = {
    // Opaque typed capabilities are per exact component instance and therefore
    // are intentionally not joined across the graph by legacy source/class
    // strings. Their local token/layout and canonical pairing are validated by
    // componentFormalSlots and ExternalParameterizedVerilogHierarchy.
    val declarations = components
      .flatMap(formalPorts)
      .filter(_.typedToken.isEmpty)
      .groupBy(_.binding.declarationKey)
    declarations.foreach { case (key, occurrences) =>
      val names = occurrences.map(_.binding.formal.name).distinct
      if (names.size != 1) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-FORMAL-SLOT-AMBIGUOUS",
          s"formal declaration identity '$key' maps to multiple names: ${names.sorted.mkString(", ")}",
          occurrences.flatMap(_.binding.sourceLocation).headOption
        )
      }
      val name = names.head
      val defaults = occurrences.map(_.binding.formal.default).distinct
      if (defaults.size != 1) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-FORMAL-DEFAULT-CONFLICT",
          s"formal declaration '$name' has incompatible defaults ${defaults.sorted.mkString(", ")} across component instances",
          occurrences.flatMap(_.binding.sourceLocation).headOption
        )
      }
      val domains = occurrences.map { value =>
        value.binding.formal.minimum -> value.binding.formal.maximum
      }.distinct
      if (domains.size != 1) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-FORMAL-DOMAIN-CONFLICT",
          s"formal declaration '$name' has incompatible domains ${domains
              .sortBy(identity)
              .map { case (minimum, maximum) => s"[$minimum, $maximum]" }
              .mkString(", ")} across component instances",
          occurrences.flatMap(_.binding.sourceLocation).headOption
        )
      }
      val owners = occurrences.map(_.binding.ownerClassName).distinct
      if (owners.size != 1) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-FORMAL-SLOT-IDENTITY-CONFLICT",
          s"formal declaration '$name' identity '$key' maps to multiple component-definition owners",
          occurrences.flatMap(_.binding.sourceLocation).headOption
        )
      }
    }
  }

  /** Prove that every concrete instance mapped to one native module identity
    * exposes the same explicit formal slots. Instance actual expressions are
    * intentionally excluded from this canonical schema comparison.
    */
  private def validateFormalCanonicalGroup(
      definitionName: String,
      candidates: Vector[Component]
  ): Unit = {
    val slotsByCandidate = candidates.map(componentFormalSlots)
    val slotNames = slotsByCandidate.map(_.map(_.name).toSet).distinct
    if (slotNames.size != 1) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-FORMAL-LAYOUT-CONFLICT",
        s"native module identity '$definitionName' maps to incompatible explicit formal slot sets"
      )
    }

    slotNames.headOption.getOrElse(Set.empty).toVector.sorted.foreach { name =>
      val slots = slotsByCandidate.map(_.find(_.name == name).get)
      val defaults = slots.map(_.formal.default).distinct
      if (defaults.size != 1) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-FORMAL-DEFAULT-CONFLICT",
          s"formal slot '$name' of native module '$definitionName' has incompatible defaults ${defaults.sorted.mkString(", ")}",
          slots.flatMap(_.sourceLocation).headOption
        )
      }
      val domains = slots.map(slot => slot.formal.minimum -> slot.formal.maximum).distinct
      if (domains.size != 1) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-FORMAL-DOMAIN-CONFLICT",
          s"formal slot '$name' of native module '$definitionName' has incompatible domains ${domains
              .sortBy(identity)
              .map { case (minimum, maximum) => s"[$minimum, $maximum]" }
              .mkString(", ")}",
          slots.flatMap(_.sourceLocation).headOption
        )
      }
      val typedModes = slots.map(_.typedToken.nonEmpty).distinct
      if (typedModes.size != 1) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-FORMAL-AUTHORITY-MIXED",
          s"formal slot '$name' of native module '$definitionName' mixes opaque typed and legacy authority",
          slots.flatMap(_.sourceLocation).headOption
        )
      }
      if (typedModes.head) {
        validateTypedCanonicalSlots(slots, name, definitionName)
      } else {
        if (slots.map(_.declarationKey).distinct.size != 1) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-FORMAL-SLOT-IDENTITY-CONFLICT",
            s"legacy formal slot '$name' of native module '$definitionName' was declared at multiple deterministic source identities",
            slots.flatMap(_.sourceLocation).headOption
          )
        }
        if (slots.map(_.ownerClassName).distinct.size != 1) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-FORMAL-SLOT-IDENTITY-CONFLICT",
            s"legacy formal slot '$name' of native module '$definitionName' maps to multiple component-definition owners",
            slots.flatMap(_.sourceLocation).headOption
          )
        }
      }
      if (slots.map(_.ports).distinct.size != 1) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-FORMAL-LAYOUT-CONFLICT",
          s"formal slot '$name' of native module '$definitionName' is exposed on incompatible packed-port sets",
          slots.flatMap(_.sourceLocation).headOption
        )
      }
    }
  }

  private def validateTypedCanonicalSlots(
      slots: Vector[FormalSlot],
      name: String,
      definitionName: String
  ): Unit = {
    if (!slots.forall(_.typedToken.nonEmpty)) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-FORMAL-AUTHORITY-MIXED",
        s"formal slot '$name' of native module '$definitionName' lost an opaque typed capability",
        slots.flatMap(_.sourceLocation).headOption
      )
    }
    // Per-instance capabilities deliberately remain distinct. The exact
    // canonical-instance map and the common exact port layout are authority.
  }

  private def componentFormalSlots(component: Component): Vector[FormalSlot] = {
    val grouped = formalPorts(component).groupBy(_.binding.formal.name)
    grouped.toVector
      .map { case (name, occurrences) =>
        val keys = occurrences.map(_.binding.declarationKey).distinct
        val owners = occurrences.map(_.binding.ownerClassName).distinct
        val typedModes = occurrences.map(_.typedToken.nonEmpty).distinct
        if (typedModes.size != 1) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-FORMAL-AUTHORITY-MIXED",
            s"component '${componentName(component)}' mixes opaque typed and legacy authority for formal slot '$name'",
            occurrences.flatMap(_.binding.sourceLocation).headOption
          )
        }
        val typed = typedModes.head
        val typedTokens =
          if (typed)
            validateTypedComponentFormalSlot(component, name, occurrences)
          else Vector.empty
        if (!typed && keys.size != 1) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-FORMAL-DUPLICATE-DECLARATION",
            s"component '${componentName(component)}' declares legacy formal slot '$name' through ${keys.size} explicit formalParam call sites",
            occurrences.flatMap(_.binding.sourceLocation).headOption
          )
        }
        val schemas = occurrences.map(_.binding.formal).distinct
        if (schemas.map(_.default).distinct.size != 1) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-FORMAL-DEFAULT-CONFLICT",
            s"component '${componentName(component)}' declares incompatible defaults for formal slot '$name'",
            occurrences.flatMap(_.binding.sourceLocation).headOption
          )
        }
        if (schemas.map(value => value.minimum -> value.maximum).distinct.size != 1) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-FORMAL-DOMAIN-CONFLICT",
            s"component '${componentName(component)}' declares incompatible domains for formal slot '$name'",
            occurrences.flatMap(_.binding.sourceLocation).headOption
          )
        }
        if (schemas.size != 1) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-FORMAL-SLOT-AMBIGUOUS",
            s"component '${componentName(component)}' has an ambiguous schema for formal slot '$name'",
            occurrences.flatMap(_.binding.sourceLocation).headOption
          )
        }
        val actuals = ExternalFormalParameterRegistry
          .distinctExpressions(occurrences.map(_.binding.actual).toVector)
          .map(ExternalFormalParameterRegistry.normalizedExpression)
        if (actuals.size != 1) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-FORMAL-SLOT-AMBIGUOUS",
            s"component '${componentName(component)}' maps formal slot '$name' to multiple instance actual expressions: ${actuals.map(_.verilog).sorted.mkString(", ")}",
            occurrences.flatMap(_.binding.sourceLocation).headOption
          )
        }
        if (!typed && owners.size != 1) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-FORMAL-SLOT-IDENTITY-CONFLICT",
            s"component '${componentName(component)}' maps formal slot '$name' to multiple definition owners",
            occurrences.flatMap(_.binding.sourceLocation).headOption
          )
        }
        FormalSlot(
          name = name,
          formal = schemas.head,
          declarationKey = keys.headOption.getOrElse("<typed-opaque>"),
          ownerClassName = owners.headOption.getOrElse("<typed-opaque>"),
          typedToken = typedTokens.headOption,
          ports = occurrences.map(_.name).distinct.sorted,
          sourceLocation = occurrences.flatMap(_.binding.sourceLocation).headOption
        )
      }
      .sortBy(_.name)
  }

  private def validateTypedComponentFormalSlot(
      component: Component,
      name: String,
      occurrences: Vector[FormalPort]
  ): Vector[ExternalTypedFormalDeclarationToken] = {
    val tokens = occurrences
      .flatMap(_.typedToken)
      .foldLeft(
        Vector.empty[ExternalTypedFormalDeclarationToken]
      ) {
        case (known, token) if known.exists(_ eq token) => known
        case (known, token)                             => known :+ token
      }
    val schemas = occurrences
      .map(_.binding.formal)
      .foldLeft(
        Vector.empty[ElaborationIntegerParameter]
      ) {
        case (known, schema) if known.exists(_ eq schema) => known
        case (known, schema)                              => known :+ schema
      }
    if (tokens.size != 1 || schemas.size != 1) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-FORMAL-SLOT-IDENTITY-CONFLICT",
        s"exact component '${componentName(component)}' does not map typed formal slot '$name' to one opaque capability and one exact local declaration object",
        occurrences.flatMap(_.binding.sourceLocation).headOption
      )
    }
    tokens
  }

  private def formalPorts(component: Component): Vector[FormalPort] =
    component.getOrdredNodeIo.toVector.filterNot(_.isSuffix).flatMap { port =>
      val evidence = ExternalFormalParameterRegistry
        .typedBindingOf(port)
        .map(value => value.binding -> Some(value.declarationToken))
        .orElse(
          ExternalFormalParameterRegistry
            .bindingOf(port)
            .map(_ -> None)
        )
      evidence.map { case (binding, typedToken) =>
        val name = Option(port.getName()).filter(_.nonEmpty).getOrElse {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-EXTERNAL-PORT-NAME-MISSING",
            s"component '${componentName(component)}' has one unnamed formal packed port",
            binding.sourceLocation
          )
        }
        FormalPort(name, binding, typedToken)
      }
    }

  private def componentSchema(component: Component): ComponentSchema = {
    val ports = component.getOrdredNodeIo.toVector.filterNot(_.isSuffix).map { port =>
      val name = Option(port.getName()).filter(_.nonEmpty).getOrElse {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-EXTERNAL-PORT-NAME-MISSING",
          s"component '${componentName(component)}' has one unnamed native port"
        )
      }
      PortSchema(
        name = name,
        direction =
          if (port.isInput) "input"
          else if (port.isOutput) "output"
          else if (port.isInOut) "inout"
          else "directionless",
        dataClass = port.getClass.getName,
        concreteWidth = port.getBitsWidth,
        retained = ParameterizedWidth
          .expressionOf(port)
          .map(ExternalFormalParameterRegistry.normalizedDefinitionSchema)
      )
    }
    val duplicatePorts = ports.groupBy(_.name).collectFirst {
      case (name, values) if values.size != 1 => name
    }
    duplicatePorts.foreach { name =>
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-EXTERNAL-PORT-IDENTITY-AMBIGUOUS",
        s"component '${componentName(component)}' has multiple native ports named '$name'"
      )
    }
    val orderedPorts = ports.sortBy { port =>
      val direction =
        if (port.direction == "input") 0
        else if (port.direction == "output") 1
        else 2
      (direction, port.name)
    }
    ComponentSchema(
      orderedPorts,
      componentParameters(component),
      ParameterizedVerilogVecs.logicalSchema(component)
    )
  }

  private[internals] def componentParameters(
      component: Component
  ): Vector[ElaborationIntegerParameter] = {
    val values =
      ParameterizedWidth.parametersOf(component) ++
        ExternalParameterizedAutoResize.parametersOf(component) ++
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
          s"component '${componentName(component)}' has conflicting external parameter declarations for '$name'"
        )
      }
    grouped.toVector.map(_._2.head).sortBy(_.name)
  }

  private def hasParameterizedMetadata(component: Component): Boolean =
    ParameterizedWidth.parametersOf(component).nonEmpty ||
      ExternalParameterizedAutoResize.parametersOf(component).nonEmpty ||
      ParameterizedMemory.parametersOf(component).nonEmpty ||
      ExternalParameterizedValueRegistry.parametersOf(component).nonEmpty ||
      ParameterizedBlackBoxGenericRegistry.hasSymbolicBindings(component) ||
      ParameterizedVerilogVecs.hasVectors(component) ||
      ParameterizedVerilogFiniteFolds.hasFolds(component) ||
      ParameterizedVerilogStructural.hasRegions(component) ||
      ParameterizedVerilogProcesses.hasLoops(component) ||
      ExternalFormalParameterRegistry.bindingsOf(component).nonEmpty

  /** Preserve the publication order that existed before Increment 42:
    * external memory lowering first, then procedural loops, structural generate
    * regions, and finally Increment 41 expression/hierarchy rewriting.
    * Structure-only modules deliberately skip hierarchy text analysis after
    * their captured module items have been relocated.
    */
  private def requiresPublicationRewrite(component: Component): Boolean =
    ParameterizedVerilogProcesses.hasLoops(component) ||
      ParameterizedVerilogStructural.hasRegions(component) ||
      ParameterizedVerilogVecs.hasVectors(component) ||
      ParameterizedVerilogFiniteFolds.hasFolds(component) ||
      requiresExpressionHierarchyRewrite(component)

  private def requiresExpressionHierarchyRewrite(
      component: Component
  ): Boolean =
    ParameterizedWidth.parametersOf(component).nonEmpty ||
      ExternalParameterizedAutoResize.parametersOf(component).nonEmpty ||
      ParameterizedMemory.parametersOf(component).nonEmpty ||
      ExternalParameterizedValueRegistry.parametersOf(component).nonEmpty ||
      ParameterizedBlackBoxGenericRegistry.hasSymbolicBindings(component) ||
      ParameterizedVerilogVecs.hasVectors(component) ||
      ParameterizedVerilogFiniteFolds.hasFolds(component) ||
      ParameterizedProcess.parametersOf(component).nonEmpty ||
      component.children.exists { child =>
        ParameterizedWidth.parametersOf(child).nonEmpty ||
        ExternalParameterizedAutoResize.parametersOf(child).nonEmpty ||
        ParameterizedMemory.parametersOf(child).nonEmpty ||
        ExternalParameterizedValueRegistry.parametersOf(child).nonEmpty ||
        ParameterizedBlackBoxGenericRegistry.hasSymbolicBindings(child) ||
        ParameterizedVerilogVecs.hasVectors(child) ||
        ParameterizedVerilogFiniteFolds.hasFolds(child) ||
        ParameterizedStructure.parametersOf(child).nonEmpty ||
        ParameterizedProcess.parametersOf(child).nonEmpty ||
        ExternalFormalParameterRegistry.bindingsOf(child).nonEmpty
      }

  private def moduleBlocks(lines: Vector[String]): Vector[ModuleBlock] = {
    val declaration: Regex =
      "^\\s*module\\s+([A-Za-z_][A-Za-z0-9_$]*)\\b.*$".r
    val blocks = ArrayBuffer.empty[ModuleBlock]
    var openName: String = null
    var openStart = -1
    lines.zipWithIndex.foreach { case (line, index) =>
      line match {
        case declaration(name) if openName == null =>
          openName = name
          openStart = index
        case declaration(name) =>
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-EXTERNAL-MODULE-NESTED",
            s"native module '$name' begins before module '$openName' terminates"
          )
        case _ if line.trim == "endmodule" && openName != null =>
          blocks += ModuleBlock(openName, openStart, index)
          openName = null
          openStart = -1
        case _ =>
      }
    }
    if (openName != null) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-EXTERNAL-MODULE-INCOMPLETE",
        s"native module '$openName' has no endmodule"
      )
    }
    val duplicates = blocks.groupBy(_.name).collectFirst {
      case (name, values) if values.size != 1 => name
    }
    duplicates.foreach { name =>
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-EXTERNAL-MODULE-IDENTITY-AMBIGUOUS",
        s"native publication contains multiple module blocks named '$name'"
      )
    }
    blocks.toVector
  }

  private def withPulledExternalClockInputs[T](component: Component)(body: => T): T = {
    val patched = ArrayBuffer.empty[Bool]
    component.dslBody.walkDeclarations {
      case baseType: BaseType if baseType.isReg && baseType.clockDomain != null =>
        val domain = baseType.clockDomain
        Vector(domain.clock, domain.reset).foreach { source =>
          if (
            source != null && source.component == null && source.isDirectionLess &&
            component.pulledDataCache.get(source).exists(_.isInput) &&
            !patched.exists(_ eq source)
          ) {
            source.dir = in
            patched += source
          }
        }
      case _ =>
    }
    try body
    finally patched.foreach(_.dir = null)
  }

  private def publishAtomically(target: Path, content: String): Unit = {
    val parent = Option(target.getParent).getOrElse(Paths.get("."))
    val temporary = Files.createTempFile(parent, target.getFileName.toString, ".morphhdl.tmp")
    try {
      Files.write(
        temporary,
        content.getBytes(StandardCharsets.UTF_8),
        StandardOpenOption.TRUNCATE_EXISTING
      )
      try {
        Files.move(
          temporary,
          target,
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING
        )
      } catch {
        case _: java.nio.file.AtomicMoveNotSupportedException =>
          Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
      }
    } finally Files.deleteIfExists(temporary)
  }

  private def fail(
      code: String,
      detail: String,
      sourceLocation: Option[String] = None
  ): Nothing =
    ParameterizedVerilogException.fail(code, detail, sourceLocation)
}
