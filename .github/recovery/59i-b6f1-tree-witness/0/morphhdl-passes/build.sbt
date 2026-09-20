ThisBuild / organization := "morphhdl"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "2.12.18"
ThisBuild / crossScalaVersions := Seq("2.12.18", "2.13.12")
ThisBuild / scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  "-Xfatal-warnings"
)

lazy val canonicalIr = ProjectRef(file("..").toURI, "morphir")

lazy val root = (project in file("."))
  .settings(
    name := "morphhdl-ir-passes",
    Test / fork := true,
    Test / parallelExecution := false,
    libraryDependencies +=
      "org.scalatest" %% "scalatest" % "3.2.18" % Test
  )
  .dependsOn(canonicalIr)
