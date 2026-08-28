#!/usr/bin/env python3
from pathlib import Path

LOCALIZER = Path(
    "morphhdl/src/main/scala/spinal/core/internals/"
    "MorphHdlExternalEnumLocalizer.scala"
)
TODO = Path("docs/morphhdl/parameterized-verilog-todo.md")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"{label} anchor is missing")
    return text.replace(old, new, 1)


def patch_localizer() -> bool:
    text = LOCALIZER.read_text(encoding="utf-8")
    if "nativeName.toUpperCase(java.util.Locale.ROOT)" in text:
        return False

    text = replace_once(
        text,
        """  * Every enum value is declared as a short module-local `localparam` using the
  * element name. Global preprocessor macros and enum/module prefixes therefore
  * do not escape the module that uses the enum. A same-module short-name
  * collision fails closed rather than silently reintroducing a prefix.
""",
        """  * Every enum value is declared as an uppercase, enum-qualified module-local
  * `localparam`: for example, Scala `State.IDLE` becomes Verilog `STATE_IDLE`.
  * Component, module and hierarchy names are never prefixed. Global
  * preprocessor macros therefore do not escape the module that uses the enum,
  * while distinct enum types remain readable inside one module.
""",
        "enum-localizer documentation",
    )
    text = replace_once(
        text,
        """        val localName = requiredName(
          element.getName(),
          s"element ${element.position} of enum '$enumName'"
        )
        validateLocalName(component, enumName, localName)
""",
        """        val elementName = requiredName(
          element.getName(),
          s"element ${element.position} of enum '$enumName'"
        )
        validateLocalName(component, enumName, elementName)
""",
        "enum element name",
    )
    text = replace_once(
        text,
        """        val nativeName = enumName + encodingSuffix + "_" + localName
        val value = encoding.getValue(element)
""",
        """        val nativeName = enumName + encodingSuffix + "_" + elementName
        val localName = nativeName.toUpperCase(java.util.Locale.ROOT)
        validateLocalName(component, enumName, localName)
        val value = encoding.getValue(element)
""",
        "enum final name",
    )
    text = text.replace(
        "requires short localparam '${alias.localName}'",
        "requires uppercase enum localparam '${alias.localName}'",
    )
    text = text.replace(
        "a prefix-free enum localparam would be ambiguous",
        "an uppercase enum-qualified localparam would be ambiguous",
    )
    LOCALIZER.write_text(text, encoding="utf-8")
    return True


def patch_todo() -> bool:
    text = TODO.read_text(encoding="utf-8")
    marker = "Scala `State.IDLE` becomes Verilog `STATE_IDLE`"
    if marker in text:
        return False

    old = """- [x] **Increment 53b — MorphHDL-owned module-local SpinalEnum parameters**

  **Dependencies:** Increment 53 implemented and merged.

  Keep all upstream-owned SpinalHDL `core`, `lib` and `idslplugin`
  production sources byte-identical. In MorphHDL-owned post-publication
  code, discover exact `SpinalEnum` definitions, elements and encodings from
  the native graph, replace global enum `` `define `` references and long
  enum-prefixed constants with module-local Verilog-2001 `localparam`s named
  only by the element (`IDLE`, `RUN`, and so on). Retain encoding-specific
  values and one-hot index helpers, remove recognized global macros from the
  final `MorphVerilog` output, and allow the same short names in different
  module scopes. Fail closed on conflicting same-module names or existing
  identifiers rather than adding module or enum prefixes. Ordinary
  `SpinalVerilog` output must remain unchanged. Prove deterministic
  dual-Scala generation, strict Verilog-2001 lint/synthesis and the native
  source-preservation boundary.
"""
    new = """- [x] **Increment 53b — MorphHDL-owned module-local SpinalEnum parameters**

  **Dependencies:** Increment 53 implemented and merged.

  Keep all upstream-owned SpinalHDL `core`, `lib` and `idslplugin`
  production sources byte-identical. In MorphHDL-owned post-publication
  code, discover exact `SpinalEnum` definitions, elements and encodings from
  the native graph, replace global enum `` `define `` references with
  module-local Verilog-2001 `localparam`s named by the uppercase enum and
  element, for example Scala `State.IDLE` becomes Verilog `STATE_IDLE`.
  Never add a component, module or hierarchy prefix. Retain encoding-specific
  values and one-hot index helpers, remove recognized global macros from the
  final `MorphVerilog` output, and allow identical names in different module
  scopes. Fail closed on conflicting final names or existing identifiers.
  Ordinary `SpinalVerilog` output must remain unchanged. In both supported
  Scala lanes, formally prove the native macro RTL and MorphHDL localparam RTL
  equivalent at the concrete parameter witness using Yosys `equiv_make`,
  sequential induction and `equiv_status -assert`, in addition to deterministic
  generation, strict Verilog-2001 lint/synthesis and native-source preservation.
"""
    text = replace_once(text, old, new, "Increment 53b roadmap")
    TODO.write_text(text, encoding="utf-8")
    return True


changed = patch_localizer() | patch_todo()
print("Increment 53b source patch applied" if changed else "Increment 53b source patch already present")
