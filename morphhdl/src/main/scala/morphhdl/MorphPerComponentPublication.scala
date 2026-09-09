package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths, StandardCopyOption, StandardOpenOption}
import java.security.MessageDigest
import java.util.Locale

import scala.collection.JavaConverters._
import scala.util.control.NonFatal

import spinal.core.SpinalConfig

import morphhdl.MorphVerilogStage.{OutputWrite, SingleSourceGeneration}

private[morphhdl] final case class MorphPreparedPublicationFile(
    relativePath: String,
    content: Array[Byte],
    reportAsSource: Boolean
)

/** Safe publication of the native emitter's already-separated component files.
  *
  * Module boundaries are supplied by the native one-file-per-component emitter.
  * This object never concatenates files or parses Verilog to rediscover logical
  * component identity. It only captures the private workspace and publishes an
  * ownership-tracked file set after the parameterized rewrite phases succeed.
  */
private[morphhdl] object MorphPerComponentPublication {
  private val ManifestVersion = "MORPHDL_ONE_FILE_PER_COMPONENT_V1"
  private val ManifestSuffix = ".morphhdl-one-file-per-component.manifest"

  def capture(
      workspace: Path,
      top: String
  ): Either[MorphVerilogFailure, Vector[MorphPreparedPublicationFile]] =
    try {
      val root = workspace.toAbsolutePath.normalize()
      if (!Files.isDirectory(root) || Files.isSymbolicLink(root)) {
        throw new IllegalArgumentException(
          "MORPHDL-ONE-FILE-CAPTURE-WORKSPACE-INVALID: private generation workspace is unavailable"
        )
      }
      val stream = Files.walk(root)
      val paths = try {
        stream.iterator().asScala.toVector
      } finally stream.close()
      val files = paths
        .filter(path => path != root)
        .filter { path =>
          if (Files.isSymbolicLink(path)) {
            throw new IllegalArgumentException(
              s"MORPHDL-ONE-FILE-CAPTURE-SYMLINK-REJECTED: $path"
            )
          }
          Files.isRegularFile(path)
        }
        .flatMap { path =>
          val relative = normalizedRelative(root.relativize(path))
          if (
            relative.endsWith(".lst") ||
            relative.endsWith(ManifestSuffix) ||
            relative.contains(".morphhdl.tmp")
          ) None
          else
            Some(
              MorphPreparedPublicationFile(
                relative,
                Files.readAllBytes(path),
                reportAsSource = relative.endsWith(".v")
              )
            )
        }
      validateUniquePaths(files)
      val topFile = top + ".v"
      if (!files.exists(file => file.relativePath == topFile && file.reportAsSource)) {
        throw new IllegalArgumentException(
          s"MORPHDL-ONE-FILE-CAPTURE-TOP-MISSING: expected '$topFile'"
        )
      }
      val ordered = files.sortBy { file =>
        val rank =
          if (!file.reportAsSource) 0
          else if (file.relativePath == topFile) 2
          else 1
        (rank, file.relativePath)
      }
      Right(ordered)
    } catch {
      case NonFatal(error) =>
        Left(
          MorphVerilogFailure(
            SingleSourceGeneration,
            Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getName),
            cause = Some(error)
          )
        )
    }

  def publish(
      config: SpinalConfig,
      top: String,
      files: Vector[MorphPreparedPublicationFile]
  ): Either[MorphVerilogFailure, Vector[Path]] =
    try {
      if (files.isEmpty) {
        throw new IllegalArgumentException(
          "MORPHDL-ONE-FILE-PUBLISH-EMPTY: no generated files were captured"
        )
      }
      validateUniquePaths(files)
      val directory = Paths.get(config.targetDirectory).normalize()
      Files.createDirectories(directory)
      if (!Files.isDirectory(directory) || Files.isSymbolicLink(directory)) {
        throw new IllegalArgumentException(
          s"MORPHDL-ONE-FILE-PUBLISH-DIRECTORY-INVALID: $directory"
        )
      }

      val sources = files.filter(_.reportAsSource)
      if (sources.isEmpty) {
        throw new IllegalArgumentException(
          "MORPHDL-ONE-FILE-PUBLISH-SOURCES-EMPTY: no Verilog source was captured"
        )
      }
      val listRelative = top + ".lst"
      val listContent =
        (sources.map(_.relativePath).mkString("\n") + "\n")
          .getBytes(StandardCharsets.UTF_8)
      val managed = files :+ MorphPreparedPublicationFile(
        listRelative,
        listContent,
        reportAsSource = false
      )
      validateUniquePaths(managed)

      val manifest = directory.resolve("." + top + ManifestSuffix)
      val previous = readManifest(manifest)
      preflight(directory, managed, previous)

      managed.foreach { file =>
        publishAtomically(resolveManaged(directory, file.relativePath), file.content)
      }

      val currentPaths = managed.map(_.relativePath).toSet
      previous.toVector.sortBy(_._1).foreach { case (relative, expectedHash) =>
        if (!currentPaths(relative)) {
          val target = resolveManaged(directory, relative)
          if (Files.exists(target)) {
            requireRegularUnmodified(target, expectedHash, "stale managed output")
            Files.delete(target)
          }
        }
      }

      val manifestContent = renderManifest(managed)
      publishAtomically(manifest, manifestContent.getBytes(StandardCharsets.UTF_8))
      Right(sources.map(file => resolveManaged(directory, file.relativePath)))
    } catch {
      case NonFatal(error) =>
        Left(
          MorphVerilogFailure(
            OutputWrite,
            Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getName),
            cause = Some(error)
          )
        )
    }

  private def preflight(
      directory: Path,
      managed: Vector[MorphPreparedPublicationFile],
      previous: Map[String, String]
  ): Unit = {
    val current = managed.map(file => file.relativePath -> sha256(file.content)).toMap
    managed.foreach { file =>
      val target = resolveManaged(directory, file.relativePath)
      if (Files.exists(target)) {
        if (!Files.isRegularFile(target) || Files.isSymbolicLink(target)) {
          throw new IllegalArgumentException(
            s"MORPHDL-ONE-FILE-PUBLISH-TARGET-INVALID: ${file.relativePath}"
          )
        }
        val actual = sha256(Files.readAllBytes(target))
        previous.get(file.relativePath) match {
          case Some(expected) if actual != expected =>
            throw new IllegalArgumentException(
              s"MORPHDL-ONE-FILE-PUBLISH-MANAGED-MODIFIED: ${file.relativePath}"
            )
          case Some(_) =>
          case None =>
            throw new IllegalArgumentException(
              s"MORPHDL-ONE-FILE-PUBLISH-UNOWNED-COLLISION: ${file.relativePath}"
            )
        }
      }
    }
    previous.toVector.foreach { case (relative, expected) =>
      if (!current.contains(relative)) {
        val target = resolveManaged(directory, relative)
        if (Files.exists(target)) {
          requireRegularUnmodified(target, expected, "stale managed output")
        }
      }
    }
  }

  private def requireRegularUnmodified(
      target: Path,
      expectedHash: String,
      context: String
  ): Unit = {
    if (!Files.isRegularFile(target) || Files.isSymbolicLink(target)) {
      throw new IllegalArgumentException(
        s"MORPHDL-ONE-FILE-PUBLISH-STALE-INVALID: $context '$target'"
      )
    }
    val actual = sha256(Files.readAllBytes(target))
    if (actual != expectedHash) {
      throw new IllegalArgumentException(
        s"MORPHDL-ONE-FILE-PUBLISH-STALE-MODIFIED: $context '$target'"
      )
    }
  }

  private def readManifest(path: Path): Map[String, String] = {
    if (!Files.exists(path)) Map.empty
    else {
      if (!Files.isRegularFile(path) || Files.isSymbolicLink(path)) {
        throw new IllegalArgumentException(
          s"MORPHDL-ONE-FILE-PUBLISH-MANIFEST-INVALID: $path"
        )
      }
      val lines = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .split("\n", -1)
        .toVector
        .dropRight(1)
      if (lines.headOption != Some(ManifestVersion)) {
        throw new IllegalArgumentException(
          s"MORPHDL-ONE-FILE-PUBLISH-MANIFEST-VERSION: $path"
        )
      }
      val entries = lines.drop(1).filter(_.nonEmpty).map { line =>
        val fields = line.split("\\t", -1)
        if (
          fields.length != 2 ||
          fields(0).length != 64 ||
          !fields(0).forall(character => Character.digit(character, 16) >= 0)
        ) {
          throw new IllegalArgumentException(
            s"MORPHDL-ONE-FILE-PUBLISH-MANIFEST-ENTRY: $line"
          )
        }
        val relative = normalizedRelative(Paths.get(fields(1)))
        relative -> fields(0).toLowerCase(Locale.ROOT)
      }
      if (entries.map(_._1).distinct.size != entries.size) {
        throw new IllegalArgumentException(
          s"MORPHDL-ONE-FILE-PUBLISH-MANIFEST-DUPLICATE: $path"
        )
      }
      entries.toMap
    }
  }

  private def renderManifest(
      files: Vector[MorphPreparedPublicationFile]
  ): String = {
    val entries = files
      .map(file => sha256(file.content) + "\t" + file.relativePath)
      .sorted
    (ManifestVersion +: entries).mkString("\n") + "\n"
  }

  private def validateUniquePaths(
      files: Vector[MorphPreparedPublicationFile]
  ): Unit = {
    files.foreach(file => normalizedRelative(Paths.get(file.relativePath)))
    val duplicate = files
      .groupBy(_.relativePath.toLowerCase(Locale.ROOT))
      .collectFirst { case (name, values) if values.size != 1 => name }
    duplicate.foreach { name =>
      throw new IllegalArgumentException(
        s"MORPHDL-ONE-FILE-PUBLISH-FILENAME-COLLISION: $name"
      )
    }
  }

  private def normalizedRelative(path: Path): String = {
    if (path == null || path.isAbsolute || path.getNameCount == 0) {
      throw new IllegalArgumentException(
        s"MORPHDL-ONE-FILE-PUBLISH-PATH-INVALID: $path"
      )
    }
    val normalized = path.normalize()
    if (
      normalized != path ||
      normalized.iterator().asScala.exists { element =>
        val value = element.toString
        value.isEmpty || value == "." || value == ".." ||
        value.contains('\t') || value.contains('\n') || value.contains('\r')
      }
    ) {
      throw new IllegalArgumentException(
        s"MORPHDL-ONE-FILE-PUBLISH-PATH-INVALID: $path"
      )
    }
    normalized.iterator().asScala.map(_.toString).mkString("/")
  }

  private def resolveManaged(directory: Path, relative: String): Path = {
    val normalized = normalizedRelative(Paths.get(relative))
    val root = directory.toAbsolutePath.normalize()
    val target = directory.resolve(normalized).normalize()
    val absoluteTarget = target.toAbsolutePath.normalize()
    if (!absoluteTarget.startsWith(root) || absoluteTarget == root) {
      throw new IllegalArgumentException(
        s"MORPHDL-ONE-FILE-PUBLISH-PATH-ESCAPE: $relative"
      )
    }
    Option(target.getParent).foreach(parent => Files.createDirectories(parent))
    target
  }

  private def sha256(bytes: Array[Byte]): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(bytes)
      .map(value => f"${value & 0xff}%02x")
      .mkString

  private def publishAtomically(target: Path, content: Array[Byte]): Unit = {
    val parent = target.getParent
    Files.createDirectories(parent)
    val temporary = Files.createTempFile(
      parent,
      "." + target.getFileName.toString + ".",
      ".morphhdl.tmp"
    )
    var committed = false
    try {
      Files.write(
        temporary,
        content,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE
      )
      Files.move(
        temporary,
        target,
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING
      )
      committed = true
    } finally {
      if (!committed) Files.deleteIfExists(temporary)
    }
  }
}
