import sbt._
import Keys._

// Place this build in MorphHDL/repro/cdc-independent-parameters/.
val morphUri = file("../..").getCanonicalFile.toURI
lazy val morphApi = ProjectRef(morphUri, "morph")
lazy val morphLib = ProjectRef(morphUri, "lib")
lazy val idsl = ProjectRef(morphUri, "idslplugin")
lazy val morphPlugin = ProjectRef(morphUri, "morphplugin")
lazy val cdcIndependentParametersRepro = (project in file("."))
  .dependsOn(morphApi, morphLib)
  .settings(
    name := "cdc-independent-parameters-repro",
    version := "0.1.0",
    scalaVersion := "2.12.18",
    crossScalaVersions := Seq("2.12.18", "2.13.12"),
    scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked", "-language:reflectiveCalls"),
    Compile / scalacOptions ++= Seq(
      "-Xplugin:" + (idsl / Compile / packageBin).value.getAbsolutePath,
      "-Xplugin:" + (morphPlugin / Compile / packageBin).value.getAbsolutePath,
      "-Xplugin-require:idsl-plugin", "-Xplugin-require:morphhdl"
    ),
    Compile / run / fork := true
  )
