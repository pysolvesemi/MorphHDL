#!/usr/bin/env python3
from pathlib import Path

LOCALIZER = Path(
    "morphhdl/src/main/scala/spinal/core/internals/"
    "MorphHdlExternalEnumLocalizer.scala"
)
TESTS = Path("morphhdl/src/test/scala/morphhdl/SpinalEnumLocalParameterTests.scala")
WORKFLOW = Path(".github/workflows/morphhdl-enum-localparams.yml")
TODO = Path("docs/morphhdl/parameterized-verilog-todo.md")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one anchor, found {count}")
    return text.replace(old, new, 1)


def patch_localizer() -> bool:
    text = LOCALIZER.read_text(encoding="utf-8")
    marker = "toUpperSnakeIdentifier(nativeName)"
    if marker in text:
        return False

    text = replace_once(
        text,
        """  * Every enum value is declared as an uppercase, enum-qualified module-local
  * `localparam`: for example, Scala `State.IDLE` becomes Verilog `STATE_IDLE`.
  * Component, module and hierarchy names are never prefixed. Global
  * preprocessor macros therefore do not escape the module that uses the enum,
  * while distinct enum types remain readable inside one module.
""",
        """  * Every enum value is declared as an upper-snake-case, enum-qualified
  * module-local `localparam`: for example, Scala `Inc53bFormalState.IDLE`
  * becomes Verilog `INC53B_FORMAL_STATE_IDLE`. Component, module and hierarchy
  * names are never prefixed. Global preprocessor macros therefore do not escape
  * the module that uses the enum, while distinct enum types remain readable
  * inside one module.
""",
        "enum-localizer documentation",
    )

    text = replace_once(
        text,
        """  private val SimpleIdentifier: Regex =
    "^[A-Za-z_][A-Za-z0-9_$]*$".r
""",
        """  private val SimpleIdentifier: Regex =
    "^[A-Za-z_][A-Za-z0-9_$]*$".r
  private val AcronymBoundary: Regex =
    "([A-Z]+)([A-Z][a-z])".r
  private val LowerOrDigitBoundary: Regex =
    "([a-z0-9])([A-Z])".r
""",
        "enum-name boundary patterns",
    )

    text = replace_once(
        text,
        """        val localName = nativeName.toUpperCase(java.util.Locale.ROOT)
""",
        """        val localName = toUpperSnakeIdentifier(nativeName)
""",
        "enum localparam canonicalization",
    )

    text = replace_once(
        text,
        """  private def validateLocalName(
""",
        """  private def toUpperSnakeIdentifier(value: String): String = {
    val acronymSplit = AcronymBoundary.replaceAllIn(
      value,
      matched => matched.group(1) + "_" + matched.group(2)
    )
    LowerOrDigitBoundary
      .replaceAllIn(
        acronymSplit,
        matched => matched.group(1) + "_" + matched.group(2)
      )
      .toUpperCase(java.util.Locale.ROOT)
  }

  private def validateLocalName(
""",
        "upper-snake identifier helper",
    )

    text = text.replace(
        "requires uppercase enum localparam",
        "requires upper-snake-case enum localparam",
    )
    text = text.replace(
        "an uppercase enum-qualified localparam would be ambiguous",
        "an upper-snake-case enum-qualified localparam would be ambiguous",
    )

    LOCALIZER.write_text(text, encoding="utf-8")
    return True


def patch_tests() -> bool:
    text = TESTS.read_text(encoding="utf-8")
    marker = "INC53B_HTTP_SERVER_STATE_PACKET_READY"
    if marker in text:
        return False

    text = replace_once(
        text,
        """object Inc53bFormalState extends SpinalEnum(binarySequential) {
  val IDLE, LOAD, RUN, DONE = newElement()
  setGlobal()
}
""",
        """object Inc53bFormalState extends SpinalEnum(binarySequential) {
  val IDLE, LOAD, RUN, DONE = newElement()
  setGlobal()
}

object Inc53bHTTPServerState extends SpinalEnum(binarySequential) {
  val packetReady = newElement("packetReady")
  val WAIT_RESP = newElement("WAIT_RESP")
  setGlobal()
}
""",
        "acronym/camel-case enum fixture",
    )

    text = replace_once(
        text,
        """  active := state === Inc53bFormalState.DONE
  encoded := state.asBits
}

class SpinalEnumLocalParameterTests extends AnyFunSuite {
""",
        """  active := state === Inc53bFormalState.DONE
  encoded := state.asBits
}

final class Inc53bSnakeCaseEnumTop(width: HdlInt) extends Component {
  setDefinitionName("Inc53bSnakeCaseEnumTop")

  val payload = in(morphhdl.frontend.UInt(width bits))
  val active = out Bool()

  val state = Inc53bHTTPServerState()
  state := Inc53bHTTPServerState.packetReady
  when(payload(0)) {
    state := Inc53bHTTPServerState.WAIT_RESP
  }
  active := state === Inc53bHTTPServerState.WAIT_RESP
}

class SpinalEnumLocalParameterTests extends AnyFunSuite {
""",
        "upper-snake component fixture",
    )

    text = text.replace(
        "MorphVerilog replaces global enum macros with uppercase enum-qualified module-local parameters",
        "MorphVerilog replaces global enum macros with upper-snake-case enum-qualified module-local parameters",
    )
    text = text.replace(
        "enum-qualified uppercase names avoid same-module element collisions",
        "enum-qualified upper-snake-case names avoid same-module element collisions",
    )
    text = text.replace(
        "Yosys formally proves legacy macro and uppercase localparam enum RTL equivalent",
        "Yosys formally proves legacy macro and upper-snake-case localparam enum RTL equivalent",
    )

    replacements = (
        (
            "INC53BBINARYENUMLEAF_INC53BGLOBALBINARYSTATE",
            "INC53B_BINARY_ENUM_LEAF_INC53B_GLOBAL_BINARY_STATE",
        ),
        (
            "INC53BONEHOTENUMLEAF_INC53BGLOBALONEHOTSTATE",
            "INC53B_ONE_HOT_ENUM_LEAF_INC53B_GLOBAL_ONE_HOT_STATE",
        ),
        ("INC53BGLOBALBINARYSTATE", "INC53B_GLOBAL_BINARY_STATE"),
        ("INC53BGLOBALONEHOTSTATE", "INC53B_GLOBAL_ONE_HOT_STATE"),
        ("INC53BGLOBALCOLLISIONSTATE", "INC53B_GLOBAL_COLLISION_STATE"),
        ("INC53BFORMALSTATE", "INC53B_FORMAL_STATE"),
    )
    for old, new in replacements:
        text = text.replace(old, new)

    text = replace_once(
        text,
        """  test("ordinary SpinalVerilog keeps native global enum macro behavior") {
""",
        """  test("upper-snake-case names split acronyms and camel-case elements while preserving underscores") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "enum_snake_case.v"
      val report = MorphVerilog(config) {
        val width = HdlInt.param("WIDTH", default = 4, min = 1, max = 16)
        new Inc53bSnakeCaseEnumTop(width)
      }

      assert(report.toplevelName == "Inc53bSnakeCaseEnumTop")
      assert(report.parameters.map(_.name) == Vector("WIDTH"))
      val output = directory.resolve("enum_snake_case.v")
      val verilog = read(output)
      assert(verilog.contains("localparam INC53B_HTTP_SERVER_STATE_PACKET_READY = 1'd0;"))
      assert(verilog.contains("localparam INC53B_HTTP_SERVER_STATE_WAIT_RESP = 1'd1;"))
      assert(!verilog.contains("INC53BHTTPSERVERSTATE"))
      assert(!verilog.contains("INC53B_SNAKE_CASE_ENUM_TOP_INC53B_HTTP_SERVER_STATE"))
      lint(output, directory, "Inc53bSnakeCaseEnumTop")
    }
  }

  test("ordinary SpinalVerilog keeps native global enum macro behavior") {
""",
        "upper-snake naming regression",
    )

    TESTS.write_text(text, encoding="utf-8")
    return True


def patch_workflow() -> bool:
    text = WORKFLOW.read_text(encoding="utf-8")
    marker = "MorphHDL upper-snake-case module-local SpinalEnum parameters"
    if marker in text:
        return False

    text = text.replace(
        "MorphHDL uppercase module-local SpinalEnum parameters and formal equivalence",
        "MorphHDL upper-snake-case module-local SpinalEnum parameters and formal equivalence",
    )
    text = text.replace(
        "# code, emits uppercase enum-qualified module-local parameters, and formally",
        "# code, emits upper-snake-case enum-qualified localparams, and formally",
    )
    text = text.replace(
        "MorphHDL-only uppercase enum source boundary",
        "MorphHDL-only upper-snake-case enum source boundary",
    )
    text = text.replace(
        "grep -Fq 'nativeName.toUpperCase(java.util.Locale.ROOT)'",
        "grep -Fq 'toUpperSnakeIdentifier(nativeName)'",
    )
    text = text.replace(
        "Uppercase enum formal-equivalence proof Scala",
        "Upper-snake-case enum formal-equivalence proof Scala",
    )
    text = text.replace(
        "Validate uppercase enum localization, formal equivalence and inherited publication",
        "Validate upper-snake-case enum localization, formal equivalence and inherited publication",
    )

    anchor = """          grep -Fq 'equiv_status -assert' \\
            morphhdl/src/test/scala/morphhdl/SpinalEnumLocalParameterTests.scala
"""
    replacement = anchor + """          grep -Fq 'INC53B_FORMAL_STATE_IDLE' \\
            morphhdl/src/test/scala/morphhdl/SpinalEnumLocalParameterTests.scala
          grep -Fq 'INC53B_HTTP_SERVER_STATE_PACKET_READY' \\
            morphhdl/src/test/scala/morphhdl/SpinalEnumLocalParameterTests.scala
"""
    text = replace_once(text, anchor, replacement, "workflow upper-snake assertions")

    WORKFLOW.write_text(text, encoding="utf-8")
    return True


def patch_todo() -> bool:
    text = TODO.read_text(encoding="utf-8")
    marker = "`INC53B_FORMAL_STATE_IDLE`"
    if marker in text:
        return False

    old = """  the native graph, replace global enum `` `define `` references with
  module-local Verilog-2001 `localparam`s named by the uppercase enum and
  element, for example Scala `State.IDLE` becomes Verilog `STATE_IDLE`.
  Never add a component, module or hierarchy prefix. Retain encoding-specific
  values and one-hot index helpers, remove recognized global macros from the
  final `MorphVerilog` output, and allow identical names in different module
  scopes. Fail closed on conflicting final names or existing identifiers.
  Ordinary `SpinalVerilog` output must remain unchanged. In both supported
"""
    new = """  the native graph, replace global enum `` `define `` references with
  module-local Verilog-2001 `localparam`s named by the deterministic
  upper-snake-case enum and element. Split lower-case-or-digit to upper-case
  boundaries and acronym-to-word boundaries while preserving existing
  underscores; for example Scala `Inc53bFormalState.IDLE` becomes Verilog
  `INC53B_FORMAL_STATE_IDLE`. Never add a component, module or hierarchy
  prefix. Retain encoding-specific values and one-hot index helpers, remove
  recognized global macros from the final `MorphVerilog` output, and allow
  identical names in different module scopes. Fail closed on conflicting final
  names or existing identifiers. Ordinary `SpinalVerilog` output must remain
  unchanged. In both supported
"""
    text = replace_once(text, old, new, "Increment 53b roadmap naming contract")
    TODO.write_text(text, encoding="utf-8")
    return True


changed = []
for path, patch in (
    (LOCALIZER, patch_localizer),
    (TESTS, patch_tests),
    (WORKFLOW, patch_workflow),
    (TODO, patch_todo),
):
    if patch():
        changed.append(str(path))

if changed:
    print("Increment 53b upper-snake-case patch applied:")
    for path in changed:
        print(f"  {path}")
else:
    print("Increment 53b upper-snake-case patch already present")
