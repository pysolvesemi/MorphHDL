#!/usr/bin/env python3
from pathlib import Path

path = Path(
    "morphhdl/scripts/"
    "apply-increment-53e-generic-target-sizing-and-slices.py"
)
value = path.read_text()

replacements = [
    (
        '''    val truncatedIdentifier =
      "^([A-Za-z_][A-Za-z0-9_$]*)\\\\s*\\\\[\\\\s*([0-9]+)\\\\s*:\\\\s*0\\\\s*\\\\]$".r
    val replicatedZeroExtension =
      "^\\\\{\\\\s*\\\\{\\\\s*([0-9]+)\\\\s*\\\\{\\\\s*1'b0\\\\s*\\\\}\\\\s*\\\\}\\\\s*,\\\\s*([A-Za-z_][A-Za-z0-9_$]*)\\\\s*\\\\}$".r
    val sizedZeroExtension =
      "^\\\\{\\\\s*([0-9]+)'[sS]?[bBoOdDhH]([0_]+)\\\\s*,\\\\s*([A-Za-z_][A-Za-z0-9_$]*)\\\\s*\\\\}$".r
''',
        '''    val truncatedIdentifier =
      """^([A-Za-z_][A-Za-z0-9_$]*)\\\\s*\\\\[\\\\s*([0-9]+)\\\\s*:\\\\s*0\\\\s*\\\\]$""".r
    val replicatedZeroExtension =
      """^\\\\{\\\\s*\\\\{\\\\s*([0-9]+)\\\\s*\\\\{\\\\s*1'b0\\\\s*\\\\}\\\\s*\\\\}\\\\s*,\\\\s*([A-Za-z_][A-Za-z0-9_$]*)\\\\s*\\\\}$""".r
    val sizedZeroExtension =
      """^\\\\{\\\\s*([0-9]+)'[sS]?[bBoOdDhH]([0_]+)\\\\s*,\\\\s*([A-Za-z_][A-Za-z0-9_$]*)\\\\s*\\\\}$""".r
''',
        "standalone resize regexes",
    ),
    (
        '''      val assignment = (
        "^(\\\\s*(?:assign\\\\s+)?" + Pattern.quote(targetName) +
          "\\\\s*(?:=|<=)\\\\s*)(.*?)(;\\\\s*(?://.*)?)$"
      ).r
''',
        '''      val assignment = (
        """^(\\\\s*(?:assign\\\\s+)?""" + Pattern.quote(targetName) +
          """\\\\s*(?:=|<=)\\\\s*)(.*?)(;\\\\s*(?://.*)?)$"""
      ).r
''',
        "auto-resize assignment regex",
    ),
    (
        '''      val assignment = (
        "^(\\\\s*(?:assign\\\\s+)?" + Pattern.quote(resultName) +
          "\\\\s*(?:=|<=)\\\\s*)" + Pattern.quote(sourceName) +
          "\\\\s*\\\\[\\\\s*" + access.hi + "\\\\s*:\\\\s*" + access.lo +
          "\\\\s*\\\\](;\\\\s*(?://.*)?)$"
      ).r
''',
        '''      val assignment = (
        """^(\\\\s*(?:assign\\\\s+)?""" + Pattern.quote(resultName) +
          """\\\\s*(?:=|<=)\\\\s*)""" + Pattern.quote(sourceName) +
          """\\\\s*\\\\[\\\\s*""" + access.hi + """\\\\s*:\\\\s*""" + access.lo +
          """\\\\s*\\\\](;\\\\s*(?://.*)?)$"""
      ).r
''',
        "slice assignment regex",
    ),
]

for old, new, label in replacements:
    count = value.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}")
    value = value.replace(old, new, 1)

path.write_text(value)
