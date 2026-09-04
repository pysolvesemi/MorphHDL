#!/usr/bin/env python3
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[2]

def replace_once(path: str, old: str, new: str) -> None:
    target = root / path
    text = target.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one replacement, found {count}: {old!r}")
    target.write_text(text.replace(old, new), encoding="utf-8")

api = "morphhdl-passes/src/main/scala/morphhdl/passes/api/PassContracts.scala"
replace_once(
    api,
    "final class WireAliasPassConfiguration private (\n",
    "private[morphhdl] sealed trait UnnamedSelectionCompatibility\n"
    "private[morphhdl] object UnnamedSelectionCompatibility {\n"
    "  implicit object Enabled extends UnnamedSelectionCompatibility\n"
    "}\n\n"
    "private[morphhdl] sealed trait NamedSelectionCompatibility\n"
    "private[morphhdl] object NamedSelectionCompatibility {\n"
    "  implicit object Enabled extends NamedSelectionCompatibility\n"
    "}\n\n"
    "final class WireAliasPassConfiguration private (\n"
)
replace_once(
    api,
    "  def isEnabled(passId: PassId): Boolean = enabledPasses.contains(passId)\n"
    "  def isDisabled: Boolean = enabledPasses.isEmpty\n",
    "  def isEnabled(passId: PassId): Boolean = enabledPasses.contains(passId)\n"
    "  def isDisabled: Boolean = enabledPasses.isEmpty\n\n"
    "  // Internal read-only compatibility for the already reviewed direct passes.\n"
    "  private[morphhdl] def eliminateUnnamedAliases: Boolean =\n"
    "    isEnabled(PassId.UnnamedWireAliasElimination)\n"
    "  private[morphhdl] def eliminateNamedAliases: Boolean =\n"
    "    isEnabled(PassId.NamedWireAliasElimination)\n"
)
replace_once(
    api,
    "object WireAliasPassConfiguration {\n"
    "  def apply(enabled: Boolean = false): WireAliasPassConfiguration =\n"
    "    new WireAliasPassConfiguration(enabled, None)\n",
    "object WireAliasPassConfiguration {\n"
    "  def apply(enabled: Boolean = false): WireAliasPassConfiguration =\n"
    "    new WireAliasPassConfiguration(enabled, None)\n\n"
    "  private[morphhdl] def apply(\n"
    "      eliminateUnnamedAliases: Boolean\n"
    "  )(implicit compatibility: UnnamedSelectionCompatibility): WireAliasPassConfiguration =\n"
    "    selectedForTesting(\n"
    "      if (eliminateUnnamedAliases) PassId.UnnamedWireAliasElimination else null\n"
    "    )\n\n"
    "  private[morphhdl] def apply(\n"
    "      eliminateNamedAliases: Boolean\n"
    "  )(implicit compatibility: NamedSelectionCompatibility): WireAliasPassConfiguration =\n"
    "    selectedForTesting(\n"
    "      if (eliminateNamedAliases) PassId.NamedWireAliasElimination else null\n"
    "    )\n\n"
    "  private[morphhdl] def apply(\n"
    "      eliminateUnnamedAliases: Boolean,\n"
    "      eliminateNamedAliases: Boolean\n"
    "  ): WireAliasPassConfiguration =\n"
    "    selectedForTesting(\n"
    "      Vector(\n"
    "        if (eliminateUnnamedAliases) Some(PassId.UnnamedWireAliasElimination) else None,\n"
    "        if (eliminateNamedAliases) Some(PassId.NamedWireAliasElimination) else None\n"
    "      ).flatten: _*\n"
    "    )\n"
)
replace_once(
    api,
    "    val requested = passes.toVector\n",
    "    val requested = passes.toVector.filter(_ != null)\n"
)

pipeline = "morphhdl-passes/src/main/scala/morphhdl/passes/pipeline/WireAliasPassPipeline.scala"
replace_once(
    pipeline,
    "  val combinedPassId: String =\n"
    "    PassId.allWireAssignmentPasses.map(_.value).mkString(\"+\")\n",
    "  /** Historical WA-06 two-pass identifier retained for its proof artifacts. */\n"
    "  val combinedPassId: String = Vector(\n"
    "    PassId.UnnamedWireAliasElimination,\n"
    "    PassId.NamedWireAliasElimination\n"
    "  ).map(_.value).mkString(\"+\")\n\n"
    "  /** Production identifier for the public all-pass configuration. */\n"
    "  val allPassId: String =\n"
    "    PassId.allWireAssignmentPasses.map(_.value).mkString(\"+\")\n"
)

fixture = "morphhdl-passes/examples/ParameterizedStreamFifo.scala"
replace_once(
    fixture,
    "  private def directNamedAlias[T <: Data](source: T): T = {\n"
    "    val alias = ParameterizedWidth.cloneOf(source)\n"
    "    alias.setName(\"popPayloadNamedAlias\")\n"
    "    alias.addTag(ExplicitNamedWireAliasSourceTag(\"popPayloadNamedAlias\"))\n"
    "    alias := source\n"
    "    alias\n"
    "  }\n",
    "  private def directNamedAlias[T <: Data](source: T): T = {\n"
    "    val alias = ParameterizedWidth.cloneOf(source)\n"
    "    alias.setName(\"popPayloadNamedAlias\")\n"
    "    alias.addTag(ExplicitNamedWireAliasSourceTag(\"popPayloadNamedAlias\"))\n"
    "    alias := source\n"
    "    alias\n"
    "  }\n\n"
    "  private def expressionUnnamedAlias(source: Bits): Bits = {\n"
    "    val alias = ParameterizedWidth.cloneOf(source)\n"
    "    alias := ~(~source)\n"
    "    alias\n"
    "  }\n"
)
replace_once(
    fixture,
    "  io.pop.payload := directNamedAlias(directUnnamedAlias(popPayloadSource))\n",
    "  io.pop.payload :=\n"
    "    directNamedAlias(directUnnamedAlias(expressionUnnamedAlias(popPayloadSource)))\n"
)

spec = "morphhdl-passes/src/test/scala/morphhdl/passes/transform/UnnamedWireExpressionEliminationPassSpec.scala"
replace_once(
    spec,
    "    all(references.map(_.owner)) shouldBe rootScopeId\n"
    "    all(references.map(_.id.value)) should include(\"wa07-inline\")\n",
    "    references.map(_.owner).distinct shouldBe Vector(rootScopeId)\n"
    "    references.foreach(_.id.value should include(\"wa07-inline\"))\n"
)

for temporary in (
    root / ".github/workflows/wa07-compat-patch.yml",
    root / "morphhdl-passes/scripts/wa07-compat-patch.py",
):
    if temporary.exists():
        temporary.unlink()

subprocess.run(["git", "config", "user.name", "morphhdl-wa07-bot"], cwd=root, check=True)
subprocess.run(["git", "config", "user.email", "morphhdl-wa07-bot@users.noreply.github.com"], cwd=root, check=True)
subprocess.run(["git", "add", "-A"], cwd=root, check=True)
subprocess.run(
    ["git", "commit", "-m", "WA-07: preserve internal proof selection behind one public flag"],
    cwd=root,
    check=True,
)
subprocess.run(["git", "push", "origin", "HEAD:agent/wa-07-unified-expression-alias-pass"], cwd=root, check=True)
