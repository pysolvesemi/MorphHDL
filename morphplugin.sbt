LocalProject("morph") / Compile / scalacOptions += (LocalProject("morphplugin") / Compile / packageBin / artifactPath).map { file =>
  s"-Xplugin:${file.getAbsolutePath}"
}.value

LocalProject("morph") / Test / scalacOptions += (LocalProject("morphplugin") / Compile / packageBin / artifactPath).map { file =>
  s"-Xplugin:${file.getAbsolutePath}"
}.value

LocalProject("morph") / Test / scalacOptions += "-Xplugin-require:morphhdl"
