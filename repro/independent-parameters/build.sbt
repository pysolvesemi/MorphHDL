import sbt._
import Keys._

val morphUri = file("../..").getCanonicalFile.toURI
lazy val morphApi = ProjectRef(morphUri, "morph")
lazy val morphLib = ProjectRef(morphUri, "lib")
lazy val idsl = ProjectRef(morphUri, "idslplugin")
lazy val morphPlugin = ProjectRef(morphUri, "morphplugin")

lazy val independentParameters = (project in file("."))
  .dependsOn(morphApi, morphLib)
  .settings(
    scalaVersion := "2.12.18",
    Compile / scalacOptions ++= Seq(
      "-Xplugin:" + (idsl / Compile / packageBin).value.getAbsolutePath,
      "-Xplugin:" + (morphPlugin / Compile / packageBin).value.getAbsolutePath,
      "-Xplugin-require:idsl-plugin",
      "-Xplugin-require:morphhdl"
    ),
    Compile / run / fork := true
  )
