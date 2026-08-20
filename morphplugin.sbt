morph / Compile / scalacOptions += (morphplugin / Compile / packageBin / artifactPath).map { file =>
  s"-Xplugin:${file.getAbsolutePath}"
}.value

morph / Test / scalacOptions += (morphplugin / Compile / packageBin / artifactPath).map { file =>
  s"-Xplugin:${file.getAbsolutePath}"
}.value

morph / Test / scalacOptions += "-Xplugin-require:morphhdl"
