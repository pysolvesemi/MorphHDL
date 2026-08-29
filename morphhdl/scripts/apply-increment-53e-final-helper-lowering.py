#!/usr/bin/env python3
from pathlib import Path

fallback = Path(
    "morphhdl/src/main/scala/spinal/core/internals/"
    "ExternalParameterizedVerilogNativeFallback.scala"
)
value = fallback.read_text()
old = '''  private def lowerRetainedIntegerHelpers(
      verilog: String,
      definitionName: String
  ): String = {
'''
new = '''  private[internals] def lowerRetainedIntegerHelpers(
      verilog: String,
      definitionName: String
  ): String = {
'''
if value.count(old) != 1:
    raise SystemExit(
        f"final helper visibility marker count={value.count(old)}"
    )
fallback.write_text(value.replace(old, new, 1))

publisher = Path(
    "morphhdl/src/main/scala/spinal/core/internals/"
    "MorphHdlExternalParameterizedVerilog.scala"
)
value = publisher.read_text()
old = '''          ParameterizedVerilogSlices.rewrite(component, withExpressions)
'''
new = '''          val withSlices =
            ParameterizedVerilogSlices.rewrite(component, withExpressions)
          // Parameterized slices and other late graph-backed rewrites may
          // introduce retained native-Int helper expressions. Normalize those
          // helpers only after every component rewrite has completed so the
          // final IEEE-1364 module cannot leak internal MorphHDL helper names.
          ExternalParameterizedVerilogNativeFallback
            .lowerRetainedIntegerHelpers(
              withSlices,
              component.definitionName
            )
'''
if value.count(old) != 1:
    raise SystemExit(
        f"final helper publication marker count={value.count(old)}"
    )
publisher.write_text(value.replace(old, new, 1))
