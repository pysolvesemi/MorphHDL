lazy val exportDisplayControllerToolchain = taskKey[File](
  "Export the pinned MorphHDL/Scala compile and runtime classpath for the Display Controller closure"
)

Global / exportDisplayControllerToolchain := {
  val output = baseDirectory.value / "toolchain-export"
  IO.delete(output)
  IO.createDirectory(output)

  val idslPayloadRef = LocalProject("idslpayload")
  val idslPluginRef = LocalProject("idslplugin")
  val simRef = LocalProject("sim")
  val paramRtlRef = LocalProject("paramrtl")
  val coreRef = LocalProject("core")
  val libRef = LocalProject("lib")
  val frontendRef = LocalProject("frontend")
  val verilogBackendRef = LocalProject("verilogBackend")
  val morphRef = LocalProject("morph")

  val projectJars = Seq(
    (idslPayloadRef / Compile / packageBin).value,
    (idslPluginRef / Compile / packageBin).value,
    (simRef / Compile / packageBin).value,
    (paramRtlRef / Compile / packageBin).value,
    (coreRef / Compile / packageBin).value,
    (libRef / Compile / packageBin).value,
    (frontendRef / Compile / packageBin).value,
    (verilogBackendRef / Compile / packageBin).value,
    (morphRef / Compile / packageBin).value
  )

  val externalJars = (
    (idslPluginRef / Compile / externalDependencyClasspath).value ++
      (coreRef / Compile / externalDependencyClasspath).value ++
      (libRef / Compile / externalDependencyClasspath).value ++
      (frontendRef / Compile / externalDependencyClasspath).value ++
      (verilogBackendRef / Compile / externalDependencyClasspath).value ++
      (morphRef / Compile / externalDependencyClasspath).value
  ).map(_.data)

  val files = (projectJars ++ externalJars)
    .filter(file => file.isFile && file.getName.endsWith(".jar"))
    .groupBy(_.getCanonicalPath)
    .values
    .map(_.head)
    .toSeq
    .sortBy(_.getCanonicalPath)

  val copied = files.zipWithIndex.map { case (source, index) =>
    val targetName = f"${index}%03d-${source.getName}"
    val target = output / targetName
    IO.copyFile(source, target, preserveLastModified = true)
    target
  }

  val plugin = copied.filter(_.getName.contains("idsl-plugin"))
  require(plugin.size == 1, s"Expected one IDSL compiler plugin, found ${plugin.map(_.getName)}")

  IO.write(
    output / "classpath-order.txt",
    copied.map(_.getName).mkString(java.io.File.pathSeparator) + "\n"
  )
  IO.write(output / "idsl-plugin.txt", plugin.head.getName + "\n")
  IO.write(
    output / "provenance.txt",
    Seq(
      "schema=morphhdl-display-controller-toolchain-export/v1",
      "morphhdl_commit=81abd25518551b8a452ecf038e409331e646f726",
      "scala_version=2.12.18",
      s"jar_count=${copied.size}"
    ).mkString("\n") + "\n"
  )

  streams.value.log.info(s"Exported ${copied.size} jars to ${output.getAbsolutePath}")
  output
}
