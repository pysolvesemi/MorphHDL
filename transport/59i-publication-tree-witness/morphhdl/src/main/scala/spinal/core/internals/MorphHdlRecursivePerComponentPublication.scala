package spinal.core.internals

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths, StandardCopyOption, StandardOpenOption}
import java.util.{IdentityHashMap, Locale}

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer
import scala.util.matching.Regex

import morphhdl.runtime.ParameterizedVerilogMode
import spinal.core._

/** MorphHDL-local adapter for native per-component publication of a validated
  * recursive self-reference.
  *
  * The ordinary recursive source uses one same-name BlackBox child solely as a
  * reference to its owning module. Native consolidated publication naturally
  * skips that external definition, while the baseline per-component loop visits
  * it before the owner and therefore reserves the owner's definition name.
  *
  * This adapter is installed only on MorphHDL's private parameterized
  * one-file-per-component configuration. Immediately before the native Verilog
  * phase it gives each exact direct same-name BlackBox identity a collision-free
  * private name. Immediately after native publication it restores the graph and
  * replaces that exact private identifier in the already separated owner source.
  * The normal recursive validator still proves locality, schema and decreasing
  * metric before any public artifact is released.
  */
object MorphHdlRecursivePerComponentPublication {
  private final case class QuarantinedReference(
      owner: Component,
      reference: BlackBox,
      originalDefinitionName: String,
      privateDefinitionName: String
  )

  private final class State(emitter: PhaseVerilog) {
    private var active = Vector.empty[QuarantinedReference]
    private var entered = false

    def quarantine(pc: PhaseContext): Unit = {
      if (!enabled(pc)) return
      if (entered) fail(
        "SPINAL-PARAMETERIZED-VERILOG-RECURSION-PUBLICATION-REENTERED",
        "recursive per-component publication quarantine ran more than once"
      )
      entered = true

      val components = componentGraph(pc.topLevel)
      val reserved = components.iterator
        .flatMap(component => Option(component.definitionName))
        .filter(_.nonEmpty)
        .map(_.toLowerCase(Locale.ROOT))
        .toSet
      val allocated = scala.collection.mutable.HashSet.empty[String]
      var sequence = 0

      def freshName(): String = {
        var candidate = ""
        do {
          candidate = s"__morphhdl_recursive_reference_$sequence"
          sequence += 1
        } while (
          reserved(candidate.toLowerCase(Locale.ROOT)) ||
          allocated(candidate.toLowerCase(Locale.ROOT))
        )
        allocated += candidate.toLowerCase(Locale.ROOT)
        candidate
      }

      val values = components
        .filterNot(_.isInstanceOf[BlackBox])
        .flatMap { owner =>
          val ownerName = Option(owner.definitionName).filter(_.nonEmpty)
          owner.children.toVector.collect {
            case reference: BlackBox
                if reference.isBlackBox &&
                  ownerName.contains(reference.definitionName) &&
                  (reference.parent eq owner) =>
              QuarantinedReference(owner, reference, ownerName.get, freshName())
          }
        }

      val duplicateReferences = new IdentityHashMap[BlackBox, java.lang.Boolean]()
      values.foreach { value =>
        if (duplicateReferences.put(value.reference, java.lang.Boolean.TRUE) != null)
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-RECURSION-PUBLICATION-IDENTITY-DUPLICATE",
            "one recursive reference identity was captured more than once"
          )
      }
      active = values
      active.foreach(value => value.reference.definitionName = value.privateDefinitionName)
    }

    def restoreAndRewrite(pc: PhaseContext): Unit = {
      if (!enabled(pc) || !entered || active.isEmpty) return
      val captured = active
      active = Vector.empty
      try {
        captured.foreach { value =>
          if (value.reference.definitionName != value.privateDefinitionName)
            fail(
              "SPINAL-PARAMETERIZED-VERILOG-RECURSION-PUBLICATION-NAME-CHANGED",
              s"recursive reference '${value.originalDefinitionName}' changed during native publication"
            )
          value.reference.definitionName = value.originalDefinitionName
        }
        val canonical = captured.filter { value =>
          Option(emitter.emitedComponentRef.get(value.owner))
            .forall(_ eq value.owner)
        }
        rewritePublishedIdentifiers(pc, canonical)
      } finally {
        captured.foreach(value => value.reference.definitionName = value.originalDefinitionName)
      }
    }
  }

  def install(phases: ArrayBuffer[Phase]): Unit = {
    val emitters = phases.zipWithIndex.collect {
      case (phase: PhaseVerilog, index) => phase -> index
    }
    require(
      emitters.size == 1,
      s"recursive per-component publication requires exactly one native PhaseVerilog, found ${emitters.size}"
    )
    val (emitter, index) = emitters.head
    val state = new State(emitter)
    phases.insert(index, new PhaseMisc {
      override def impl(pc: PhaseContext): Unit = state.quarantine(pc)
    })
    phases.insert(index + 2, new PhaseMisc {
      override def impl(pc: PhaseContext): Unit = state.restoreAndRewrite(pc)
    })
  }

  private def enabled(pc: PhaseContext): Boolean =
    pc != null && pc.config != null && pc.config.oneFilePerComponent &&
      ParameterizedVerilogMode.isEnabled(pc.config)

  private def componentGraph(top: Component): Vector[Component] = {
    if (top == null) fail(
      "SPINAL-PARAMETERIZED-VERILOG-RECURSION-PUBLICATION-TOP-MISSING",
      "recursive publication quarantine ran without a top-level component"
    )
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

  private def rewritePublishedIdentifiers(
      pc: PhaseContext,
      references: Vector[QuarantinedReference]
  ): Unit = {
    val root = Paths.get(pc.config.targetDirectory).toAbsolutePath.normalize()
    if (!Files.isDirectory(root) || Files.isSymbolicLink(root))
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-RECURSION-PUBLICATION-DIRECTORY-MISSING",
        s"native per-component output directory is unavailable: $root"
      )

    val stream = Files.walk(root)
    val sourceFiles = try {
      stream.iterator().asScala.toVector
        .filter(path => Files.isRegularFile(path) && !Files.isSymbolicLink(path))
        .filter(_.getFileName.toString.endsWith(".v"))
        .sortBy(_.toString)
    } finally stream.close()

    var updated = Map.empty[Path, String]
    references.foreach { reference =>
      val privateFile = root.resolve(reference.privateDefinitionName + ".v")
      if (Files.exists(privateFile)) {
        if (!Files.isRegularFile(privateFile) || Files.isSymbolicLink(privateFile))
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-RECURSION-PUBLICATION-PRIVATE-FILE-INVALID",
            s"private recursive reference publication is not a regular file: $privateFile"
          )
        Files.delete(privateFile)
      }

      val occurrences = ArrayBuffer.empty[(Path, String, Int)]
      sourceFiles.filterNot(_ == privateFile).foreach { path =>
        val current = updated.getOrElse(path, normalize(read(path)))
        val (rewritten, count) = replaceIdentifier(
          current,
          reference.privateDefinitionName,
          reference.originalDefinitionName
        )
        if (count != 0) occurrences += ((path, rewritten, count))
      }
      val count = occurrences.map(_._3).sum
      if (count != 1)
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-RECURSION-PUBLICATION-REFERENCE-COUNT",
          s"recursive reference '${reference.originalDefinitionName}' mapped to $count native instance identifiers; expected exactly one"
        )
      occurrences.foreach { case (path, text, _) => updated += path -> text }
    }

    updated.toVector.sortBy(_._1.toString).foreach { case (path, text) =>
      publishAtomically(path, text)
    }
  }

  private def replaceIdentifier(
      text: String,
      from: String,
      to: String
  ): (String, Int) = {
    val pattern = (
      "(?<![A-Za-z0-9_$])" + Regex.quote(from) + "(?![A-Za-z0-9_$])"
    ).r
    var count = 0
    val rewritten = pattern.replaceAllIn(text, matched => {
      count += 1
      java.util.regex.Matcher.quoteReplacement(to)
    })
    rewritten -> count
  }

  private def read(path: Path): String =
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

  private def normalize(value: String): String =
    value.replace("\r\n", "\n").replace('\r', '\n')

  private def publishAtomically(path: Path, text: String): Unit = {
    val temporary = Files.createTempFile(path.getParent, ".recursive-publication-", ".tmp")
    try {
      Files.write(
        temporary,
        text.getBytes(StandardCharsets.UTF_8),
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE
      )
      try {
        Files.move(
          temporary,
          path,
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING
        )
      } catch {
        case _: java.nio.file.AtomicMoveNotSupportedException =>
          Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
      }
    } finally Files.deleteIfExists(temporary)
  }

  private def fail(code: String, detail: String): Nothing =
    ParameterizedVerilogException.fail(code, detail)
}
