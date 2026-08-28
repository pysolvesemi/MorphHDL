#!/usr/bin/env python3
from pathlib import Path

TODO = Path("docs/morphhdl/parameterized-verilog-todo.md")
LOCALIZER = Path(
    "morphhdl/src/main/scala/spinal/core/internals/"
    "MorphHdlExternalEnumLocalizer.scala"
)
TESTS = Path("morphhdl/src/test/scala/morphhdl/SpinalEnumLocalParameterTests.scala")
WORKFLOW = Path(".github/workflows/morphhdl-enum-localparams.yml")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"{label} anchor is missing")
    return text.replace(old, new, 1)


def patch_todo() -> bool:
    text = TODO.read_text(encoding="utf-8")
    marker = "Increment 53c — SCREAMING_SNAKE_CASE SpinalEnum localparam names"
    if marker in text:
        return False

    text = replace_once(
        text,
        """- Increment 53b depends only on the merged Increment 53. Increments 53a and 53b
  may execute independently, but both must complete before Increment 54.
- Increments 54 through 58 then form a strict sequential closure chain after
  Increments 53a and 53b.
""",
        """- Increment 53b depends only on the merged Increment 53. Increments 53a and 53b
  may execute independently.
- Increment 53c depends only on the merged Increment 53b. Increment 54 requires
  both Increment 53a and Increment 53c.
- Increments 54 through 58 then form a strict sequential closure chain after
  Increments 53a and 53c.
""",
        "Increment 53c dependency graph",
    )

    increment = """
- [ ] **Increment 53c — SCREAMING_SNAKE_CASE SpinalEnum localparam names**

  **Dependencies:** Increment 53b implemented and merged.

  Refine only the MorphHDL-owned module-local enum publication naming from
  Increment 53b. Convert each resolved enum type and element identifier to
  deterministic SCREAMING_SNAKE_CASE before joining them: split lowercase-or-
  digit to uppercase boundaries, split acronym-to-word boundaries, preserve
  existing underscores and digits, and uppercase with locale-independent
  rules. For example, Scala `Inc53bFormalState.IDLE` must become Verilog
  `INC53B_FORMAL_STATE_IDLE`, and `AXI4ReadState.waitResp` must become
  `AXI4_READ_STATE_WAIT_RESP`. Apply the same base name to retained one-hot
  `_OH_ID` bit-index helpers without changing their semantics. Never add a
  component, module, instance or hierarchy prefix. Fail closed when distinct
  source identifiers such as `FooBar` and `Foo_Bar` canonicalize to the same
  module-local name, even when their encoded values happen to match. Keep
  ordinary `SpinalVerilog` macro output and every upstream-owned SpinalHDL
  production source unchanged. Re-run deterministic Verilog-2001 lint and
  synthesis plus macro-versus-localparam sequential formal equivalence on
  Scala 2.12.18 and 2.13.12.

"""
    text = replace_once(
        text,
        "- [ ] **Increment 54 — MorphHDL module extraction and native-tree cleanup**\n",
        increment + "- [ ] **Increment 54 — MorphHDL module extraction and native-tree cleanup**\n",
        "Increment 53c insertion",
    )
    text = replace_once(
        text,
        "  **Dependencies:** Increments 53a and 53b implemented and merged.\n",
        "  **Dependencies:** Increments 53a, 53b and 53c implemented and merged.\n",
        "Increment 54 dependency",
    )

    TODO.write_text(text, encoding="utf-8")
    return True


def patch_localizer() -> bool:
    text = LOCALIZER.read_text(encoding="utf-8")
    if "toScreamingSnake(nativeName)" in text:
        return False

    text = replace_once(
        text,
        """  * Every enum value is declared as an uppercase, enum-qualified module-local
  * `localparam`: for example, Scala `State.IDLE` becomes Verilog `STATE_IDLE`.
  * Component, module and hierarchy names are never prefixed. Global
  * preprocessor macros therefore do not escape the module that uses the enum,
  * while distinct enum types remain readable inside one module.
""",
        """  * Every enum value is declared as an enum-qualified SCREAMING_SNAKE_CASE
  * module-local `localparam`: for example, Scala `Inc53bFormalState.IDLE`
  * becomes Verilog `INC53B_FORMAL_STATE_IDLE`. Component, module, instance and
  * hierarchy names are never prefixed. Global preprocessor macros therefore do
  * not escape the module that uses the enum, while distinct enum types remain
  * readable inside one module.
""",
        "enum-localizer documentation",
    )

    text = replace_once(
        text,
        """  private val SimpleIdentifier: Regex =
    "^[A-Za-z_][A-Za-z0-9_$]*$".r

  private val Verilog2001Keywords = Set(
""",
        """  private val SimpleIdentifier: Regex =
    "^[A-Za-z_][A-Za-z0-9_$]*$".r

  private val AcronymToWordBoundary: Regex =
    "([A-Z]+)([A-Z][a-z])".r

  private val LowerOrDigitToUpperBoundary: Regex =
    "([a-z0-9])([A-Z])".r

  private val Verilog2001Keywords = Set(
""",
        "enum naming boundary patterns",
    )

    text = replace_once(
        text,
        """        val nativeName = enumName + encodingSuffix + "_" + elementName
        val localName = nativeName.toUpperCase(java.util.Locale.ROOT)
""",
        """        val nativeName = enumName + encodingSuffix + "_" + elementName
        val localName = toScreamingSnake(nativeName)
""",
        "enum localparam naming",
    )

    text = replace_once(
        text,
        """  private def validateLocalName(
""",
        """  private def toScreamingSnake(value: String): String = {
    val acronymSeparated = AcronymToWordBoundary.replaceAllIn(
      value,
      matched => matched.group(1) + "_" + matched.group(2)
    )
    LowerOrDigitToUpperBoundary
      .replaceAllIn(
        acronymSeparated,
        matched => matched.group(1) + "_" + matched.group(2)
      )
      .toUpperCase(java.util.Locale.ROOT)
  }

  private def validateLocalName(
""",
        "SCREAMING_SNAKE_CASE converter",
    )

    text = replace_once(
        text,
        """    val declarations = mutable.LinkedHashMap.empty[String, Alias]
    aliases.foreach { alias =>
      declarations.get(alias.localName) match {
        case None => declarations.update(alias.localName, alias)
        case Some(previous) if previous.literal == alias.literal =>
        case Some(previous) =>
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-ENUM-LOCAL-NAME-COLLISION",
            s"module '$moduleName' requires uppercase enum localparam '${alias.localName}' for both ${previous.literal} and ${alias.literal}"
          )
      }
    }
""",
        """    val declarations = mutable.LinkedHashMap.empty[String, Alias]
    aliases.foreach { alias =>
      declarations.get(alias.localName) match {
        case None => declarations.update(alias.localName, alias)
        case Some(previous) if previous == alias =>
        case Some(previous) =>
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-ENUM-LOCAL-NAME-COLLISION",
            s"module '$moduleName' canonicalizes enum identifiers '${previous.nativeName}' and '${alias.nativeName}' to the same SCREAMING_SNAKE_CASE localparam '${alias.localName}'"
          )
      }
    }
""",
        "canonical enum-name collision handling",
    )

    text = text.replace(
        "an uppercase enum-qualified localparam would be ambiguous",
        "a SCREAMING_SNAKE_CASE enum-qualified localparam would be ambiguous",
    )

    LOCALIZER.write_text(text, encoding="utf-8")
    return True


def patch_tests() -> bool:
    text = TESTS.read_text(encoding="utf-8")
    if "object Inc53cAXI4ReadState" in text:
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

object Inc53cAXI4ReadState extends SpinalEnum(binaryOneHot) {
  val waitResp = newElement("waitResp")
  val HTTPDone = newElement("HTTPDone")
  setGlobal()
}

object Inc53cFooBarState extends SpinalEnum(binarySequential) {
  val IDLE, RUN = newElement()
  setGlobal()
}

object Inc53cFoo_BarState extends SpinalEnum(binarySequential) {
  val IDLE, RUN = newElement()
  setGlobal()
}
""",
        "Increment 53c enum fixtures",
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

final class Inc53cSnakeCaseTop(width: HdlInt) extends Component {
  setDefinitionName("Inc53cSnakeCaseTop")

  val payload = in(morphhdl.frontend.UInt(width bits))
  val active = out Bool()

  val state = Inc53cAXI4ReadState()
  state := Inc53cAXI4ReadState.waitResp
  when(payload(0)) {
    state := Inc53cAXI4ReadState.HTTPDone
  }
  active := state === Inc53cAXI4ReadState.HTTPDone
}

final class Inc53cSnakeCollisionTop(width: HdlInt) extends Component {
  setDefinitionName("Inc53cSnakeCollisionTop")

  val payload = in(morphhdl.frontend.UInt(width bits))
  val active = out Bool()

  val left = Inc53cFooBarState()
  val right = Inc53cFoo_BarState()
  left := Inc53cFooBarState.IDLE
  right := Inc53cFoo_BarState.IDLE
  when(payload(0)) {
    left := Inc53cFooBarState.RUN
    right := Inc53cFoo_BarState.RUN
  }
  active :=
    (left === Inc53cFooBarState.RUN) &&
      (right === Inc53cFoo_BarState.RUN)
}

class SpinalEnumLocalParameterTests extends AnyFunSuite {
""",
        "Increment 53c component fixtures",
    )

    replacements = {
        "INC53BGLOBALBINARYSTATE": "INC53B_GLOBAL_BINARY_STATE",
        "INC53BGLOBALONEHOTSTATE": "INC53B_GLOBAL_ONE_HOT_STATE",
        "INC53BGLOBALCOLLISIONSTATE": "INC53B_GLOBAL_COLLISION_STATE",
        "INC53BFORMALSTATE": "INC53B_FORMAL_STATE",
        "INC53BBINARYENUMLEAF": "INC53B_BINARY_ENUM_LEAF",
        "INC53BONEHOTENUMLEAF": "INC53B_ONE_HOT_ENUM_LEAF",
    }
    for old, new in replacements.items():
        text = text.replace(old, new)

    text = text.replace(
        "uppercase enum-qualified module-local parameters",
        "SCREAMING_SNAKE_CASE enum-qualified module-local parameters",
    )

    insertion_anchor = """  test("ordinary SpinalVerilog keeps native global enum macro behavior") {
"""
    snake_test = """  test("MorphVerilog splits camel-case, acronym and digit boundaries in enum names") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "enum_snake_case.v"
      val report = MorphVerilog(config) {
        val width = HdlInt.param("WIDTH", default = 4, min = 1, max = 16)
        new Inc53cSnakeCaseTop(width)
      }

      assert(report.toplevelName == "Inc53cSnakeCaseTop")
      val output = directory.resolve("enum_snake_case.v")
      val verilog = read(output)
      assert(verilog.contains("localparam INC53C_AXI4_READ_STATE_WAIT_RESP = 2'd1;"))
      assert(verilog.contains("localparam INC53C_AXI4_READ_STATE_WAIT_RESP_OH_ID = 0;"))
      assert(verilog.contains("localparam INC53C_AXI4_READ_STATE_HTTP_DONE = 2'd2;"))
      assert(verilog.contains("localparam INC53C_AXI4_READ_STATE_HTTP_DONE_OH_ID = 1;"))
      assert(!verilog.contains("INC53CAXI4READSTATE"))
      lint(output, directory, "Inc53cSnakeCaseTop")
    }
  }

"""
    text = replace_once(
        text,
        insertion_anchor,
        snake_test + insertion_anchor,
        "Increment 53c positive naming test",
    )

    collision_anchor = """  test("Yosys formally proves legacy macro and uppercase localparam enum RTL equivalent") {
"""
    collision_test = """  test("SCREAMING_SNAKE_CASE canonicalization collisions fail closed") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "enum_snake_collision.v"

      MorphVerilog.tryGenerate(config) {
        val width = HdlInt.param("WIDTH", default = 4, min = 1, max = 16)
        new Inc53cSnakeCollisionTop(width)
      } match {
        case Left(failure) =>
          assert(failure.stage == MorphVerilogStage.SingleSourceGeneration)
          val diagnostic = failure.detail + failure.cause.map(_.toString).getOrElse("")
          assert(
            diagnostic.contains(
              "SPINAL-PARAMETERIZED-VERILOG-ENUM-LOCAL-NAME-COLLISION"
            )
          )
          assert(diagnostic.contains("INC53C_FOO_BAR_STATE_IDLE"))
          assert(!Files.exists(directory.resolve("enum_snake_collision.v")))
        case Right(_) =>
          fail("distinct enum identifiers that canonicalize identically must fail closed")
      }
    }
  }

"""
    text = replace_once(
        text,
        collision_anchor,
        collision_test + collision_anchor.replace(
            "uppercase localparam", "SCREAMING_SNAKE_CASE localparam"
        ),
        "Increment 53c collision test",
    )

    TESTS.write_text(text, encoding="utf-8")
    return True


def patch_workflow() -> bool:
    text = WORKFLOW.read_text(encoding="utf-8")
    if "SCREAMING_SNAKE_CASE module-local SpinalEnum" in text:
        return False

    text = text.replace(
        "MorphHDL uppercase module-local SpinalEnum parameters and formal equivalence",
        "MorphHDL SCREAMING_SNAKE_CASE module-local SpinalEnum parameters and formal equivalence",
    )
    text = replace_once(
        text,
        """# Increment 53b localizes native SpinalEnum macros in MorphHDL-owned publication
# code, emits uppercase enum-qualified module-local parameters, and formally
# proves equivalence to the untouched native macro implementation.
""",
        """# Increment 53c refines Increment 53b enum constants to deterministic
# SCREAMING_SNAKE_CASE in MorphHDL-owned publication code and formally proves
# equivalence to the untouched native macro implementation.
""",
        "Increment 53c workflow documentation",
    )
    text = text.replace(
        "      - fix/increment-53b-uppercase-formal-equivalence",
        "      - agent/increment-53c-enum-screaming-snake-localparams",
    )
    text = text.replace(
        "MorphHDL-only uppercase enum source boundary",
        "MorphHDL-only SCREAMING_SNAKE_CASE enum source boundary",
    )
    text = text.replace(
        "Uppercase enum formal-equivalence proof Scala",
        "SCREAMING_SNAKE_CASE enum formal-equivalence proof Scala",
    )
    text = text.replace(
        "Validate uppercase enum localization, formal equivalence and inherited publication",
        "Validate SCREAMING_SNAKE_CASE enum localization, formal equivalence and inherited publication",
    )
    text = replace_once(
        text,
        """          grep -Fq 'nativeName.toUpperCase(java.util.Locale.ROOT)' \\
            morphhdl/src/main/scala/spinal/core/internals/MorphHdlExternalEnumLocalizer.scala
""",
        """          grep -Fq 'toScreamingSnake(nativeName)' \\
            morphhdl/src/main/scala/spinal/core/internals/MorphHdlExternalEnumLocalizer.scala
          grep -Fq 'AcronymToWordBoundary' \\
            morphhdl/src/main/scala/spinal/core/internals/MorphHdlExternalEnumLocalizer.scala
          grep -Fq 'LowerOrDigitToUpperBoundary' \\
            morphhdl/src/main/scala/spinal/core/internals/MorphHdlExternalEnumLocalizer.scala
          grep -Fq 'INC53C_AXI4_READ_STATE_WAIT_RESP' \\
            morphhdl/src/test/scala/morphhdl/SpinalEnumLocalParameterTests.scala
""",
        "Increment 53c workflow source assertions",
    )
    text = text.replace(
        "Increment 53b follow-up must not modify native SpinalHDL production sources",
        "Increment 53c must not modify native SpinalHDL production sources",
    )

    WORKFLOW.write_text(text, encoding="utf-8")
    return True


changed = False
changed |= patch_todo()
changed |= patch_localizer()
changed |= patch_tests()
changed |= patch_workflow()
print("Increment 53c source patch applied" if changed else "Increment 53c source patch already present")
