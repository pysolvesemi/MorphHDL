import sbt._
import Keys._

val morphUri = file("../..").getCanonicalFile.toURI
lazy val morphApi = ProjectRef(morphUri, "morph")
lazy val morphLib = ProjectRef(morphUri, "lib")
lazy val idsl = ProjectRef(morphUri, "idslplugin")
lazy val morphPlugin = ProjectRef(morphUri, "morphplugin")
lazy val repro = (project in file("."))
  .dependsOn(morphApi, morphLib)
  .settings(
    name := "remaining-wire-repro",
    version := "0.1.0",
    scalaVersion := "2.12.18",
    scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked", "-language:reflectiveCalls"),
    Compile / scalacOptions ++= Seq(
      "-Xplugin:" + (idsl / Compile / packageBin).value.getAbsolutePath,
      "-Xplugin:" + (morphPlugin / Compile / packageBin).value.getAbsolutePath,
      "-Xplugin-require:idsl-plugin", "-Xplugin-require:morphhdl"
    ),
    Compile / run / fork := true
  )
