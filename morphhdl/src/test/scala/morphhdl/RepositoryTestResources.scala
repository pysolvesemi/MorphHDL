package morphhdl

import java.nio.file.{Files, Path, Paths}

/** Resolve checked-in test inputs independently of the runner's working directory.
  * Mill forks tests into worker sandboxes; compiled test classes remain under
  * the owning checkout. Never substitute a missing input with generated data.
  */
object RepositoryTestResources {
  private lazy val root: Path = {
    val location = Paths.get(getClass.getProtectionDomain.getCodeSource.getLocation.toURI)
    Iterator.iterate(Option(location.toAbsolutePath.normalize))(
      _.flatMap(path => Option(path.getParent)))
      .takeWhile(_.nonEmpty).map(_.get)
      .find(path => Files.isRegularFile(path.resolve("build.mill")) &&
        Files.isDirectory(path.resolve("morphhdl/src/test")))
      .getOrElse(throw new java.nio.file.NoSuchFileException("test class checkout root"))
  }

  def resolve(relative: String): Path = {
    val path = root.resolve(relative).normalize
    require(path.startsWith(root), "test resource must remain inside the checkout")
    if (!Files.isRegularFile(path)) throw new java.nio.file.NoSuchFileException(path.toString)
    path
  }
}
