import sbt._
import sbt.Keys._

/** Temporary export helper used only by the unmerged source-export branch. */
object ExportClasspathBundle extends AutoPlugin {
  object autoImport {
    val exportClasspathBundle = taskKey[File]("Export a self-contained compile/runtime classpath bundle")
  }

  import autoImport._

  override def trigger = allRequirements

  override lazy val projectSettings: Seq[Def.Setting[_]] = Seq(
    exportClasspathBundle := {
      val projectId = thisProjectRef.value.project
      val out = (ThisBuild / baseDirectory).value / "export-classpath" / projectId
      IO.delete(out)
      IO.createDirectory(out)

      val external = (Compile / externalDependencyClasspath).value.map(_.data)
      val scalaJars = scalaInstance.value.allJars.toSeq
      val dependencies = (external ++ scalaJars).filter(_.isFile).distinct
      dependencies.foreach { jar =>
        IO.copyFile(jar, out / jar.getName, preserveLastModified = true)
      }

      val packaged = (Compile / packageBin).value
      IO.copyFile(packaged, out / s"${projectId}.jar", preserveLastModified = true)
      out
    }
  )
}
