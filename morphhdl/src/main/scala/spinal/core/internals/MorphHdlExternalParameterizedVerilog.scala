package spinal.core.internals

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths, StandardCopyOption, StandardOpenOption}
import java.util.IdentityHashMap

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.util.matching.Regex

import spinal.core._

/**
  * MorphHDL-owned final publication transform for Increments 41 and 42.
  *
  * Native SpinalHDL remains authoritative for elaboration, validation,
  * expression semantics, module deduplication and concrete Verilog emission.
  * This external phase observes the finished native graph by object identity,
  * proves symbolic expression, connection, hierarchy, structural and process
  * contracts, then rewrites only the published Verilog artifact.
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
      parameters: Vector[ElaborationIntegerParameter]
  )

  def rewrite(pc: PhaseContext): Unit = {
    if (!pc.config.parameterizedVerilog) return
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
    val groups = components.groupBy(componentName)
    val canonicalByName = groups.toVector.map { case (name, candidates) =>
      val schemas = candidates.map(componentSchema).distinct
      if (schemas.size != 1) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-CANONICAL-SCHEMA-CONFLICT",
          s"native module identity '$name' maps to ${schemas.size} distinct graph schemas"
        )
      }
      val representative = candidates.head
      name -> representative
    }.toMap

    if (!components.exists(hasParameterizedMetadata)) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-UNTAGGED-PORT",
        s"component '${componentName(top)}' contains no retained MorphHDL parameter metadata"
      )
    }

    val canonicalByIdentity = new IdentityHashMap[Component, Component]()
    groups.foreach { case (name, candidates) =>
      val representative = canonicalByName(name)
      candidates.foreach(candidate => canonicalByIdentity.put(candidate, representative))
    }
    def canonicalOf(component: Component): Component =
      Option(canonicalByIdentity.get(component)).getOrElse {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-EXTERNAL-CANONICAL-IDENTITY-MISSING",
          s"component '${componentName(component)}' is absent from the captured native graph"
        )
      }

    val expectedModules = canonicalByName.values.filterNot { component =>
      component.isInBlackBoxTree || component.isInstanceOf[BlackBox]
    }.map(componentName).toSet
    val missingModules = expectedModules.diff(blockByName.keySet)
    if (missingModules.nonEmpty) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-EXTERNAL-MODULE-MAPPING-MISSING",
        s"native publication has no unique module block for ${missingModules.toVector.sorted.mkString(", ")}"
      )
    }

    val rewrittenByName = expectedModules.toVector.sorted.flatMap { name =>
      val component = canonicalByName(name)
      if (requiresPublicationRewrite(component)) {
        val block = blockByName(name)
        val text = lines.slice(block.start, block.end + 1).mkString("\n")
        val rewritten = withPulledExternalClockInputs(component) {
          val withProcesses = ParameterizedVerilogProcesses.rewrite(
            component,
            text,
            pc
          )
          val withStructure = ParameterizedVerilogStructural.rewrite(
            component,
            withProcesses,
            pc,
            canonicalOf
          )
          if (requiresExpressionHierarchyRewrite(component)) {
            ExternalParameterizedVerilogNativeFallback.rewrite(
              component,
              withStructure,
              pc,
              canonicalOf
            )
          } else withStructure
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
        retained = ParameterizedWidth.expressionOf(port)
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
    ComponentSchema(orderedPorts, componentParameters(component))
  }

  private def componentParameters(
      component: Component
  ): Vector[ElaborationIntegerParameter] = {
    val values =
      ParameterizedWidth.parametersOf(component) ++
        ParameterizedMemory.parametersOf(component) ++
        ParameterizedStructure.parametersOf(component) ++
        ParameterizedProcess.parametersOf(component)
    val grouped = values.groupBy(_.name)
    grouped.collectFirst {
      case (name, declarations) if declarations.distinct.size != 1 => name
    }.foreach { name =>
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-SCHEMA-CONFLICT",
        s"component '${componentName(component)}' has conflicting external parameter declarations for '$name'"
      )
    }
    grouped.toVector.map(_._2.head).sortBy(_.name)
  }

  private def hasParameterizedMetadata(component: Component): Boolean =
    componentParameters(component).nonEmpty

  /**
    * Preserve the publication order that existed before Increment 42:
    * native memory lowering first, then procedural loops, structural generate
    * regions, and finally Increment 41 expression/hierarchy rewriting.
    * Structure-only modules deliberately skip hierarchy text analysis after
    * their captured module items have been relocated.
    */
  private def requiresPublicationRewrite(component: Component): Boolean =
    ParameterizedVerilogProcesses.hasLoops(component) ||
      ParameterizedVerilogStructural.hasRegions(component) ||
      requiresExpressionHierarchyRewrite(component)

  private def requiresExpressionHierarchyRewrite(
      component: Component
  ): Boolean =
    ParameterizedWidth.parametersOf(component).nonEmpty ||
      ParameterizedMemory.parametersOf(component).nonEmpty ||
      ParameterizedProcess.parametersOf(component).nonEmpty ||
      component.children.exists { child =>
        ParameterizedWidth.parametersOf(child).nonEmpty ||
          ParameterizedMemory.parametersOf(child).nonEmpty
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

  private def fail(code: String, detail: String): Nothing =
    ParameterizedVerilogException.fail(code, detail)
}
