#!/usr/bin/env python3
"""Finish the exact 59i widening-publication/capture composition candidate.

Run only after the source-bound publication, audited-construction and capture
transactions. This file adds no new algorithm: it joins four exact seams that
those independently reviewed slices intentionally left separate.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BACKEND = ROOT / "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionBackend.scala"
CERTIFIED = ROOT / "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCertifiedCallbackPolicy.scala"
REPLAY = ROOT / "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCompositeReplay.scala"
WIDENING_TEST = ROOT / "morphhdl/src/test/scala/spinal/core/internals/TypedBalancedReductionCompositeWideningTests.scala"
PUBLICATION_TEST = ROOT / "morphhdl/src/test/scala/spinal/core/internals/TypedBalancedReductionCompositeWideningPublicationTests.scala"


def require(condition: bool, detail: str) -> None:
    if not condition:
        raise RuntimeError(detail)


def replace_once(text: str, before: str, after: str, label: str) -> str:
    require(text.count(before) == 1,
            label + " exact anchor count changed: " + str(text.count(before)))
    return text.replace(before, after, 1)


def main() -> None:
    backend = BACKEND.read_text()
    require(backend.count("private final case class WideningCompositeStage") == 1,
            "generic widening publication backend was not applied exactly once")
    require(backend.count("private def buildWideningComposite") == 1,
            "generic widening builder was not applied exactly once")

    certified = CERTIFIED.read_text()
    require(certified.count("private final class FreshComposite") == 1,
            "audited Bundle construction policy was not applied exactly once")
    certified = replace_once(certified,
        '"$amp", "$bar", "$up", "$plus", "$plus$up", "$minus",',
        '"$amp", "$bar", "$up", "$plus", "$plus$up", "$plus$bar", "$minus",',
        "exact native UInt saturation method")
    CERTIFIED.write_text(certified)

    replay = REPLAY.read_text()
    require(replay.count("capturedEvidence") >= 3,
            "shape-changing capture proof handoff is missing")
    replay = replace_once(replay,
        "      val callbackSet = inventory(callback.declarations ++ callback.assignments)\n",
        "      val callbackSet = inventory(callback.declarations ++ callback.assignments ++ callback.statements)\n",
        "validated callback statement inventory")
    REPLAY.write_text(replay)

    widening = WIDENING_TEST.read_text()
    widening = replace_once(widening, '''  private def typedWidth(value: BaseType): ElabInt =
    ElabInt.fromExpression(ParameterizedWidth.expressionOf(value)
      .getOrElse(ElabInt.literal(value.getBitsWidth).expression))
''', '''  private def typedWidth(value: BaseType): ElabInt =
    ElabInt.widthOf(value)
''', "generic widening exact typed-width query")
    WIDENING_TEST.write_text(widening)

    publication = PUBLICATION_TEST.read_text()
    publication = replace_once(publication, '''    val base = SpinalConfig(targetDirectory = directory.toString, headerWithDate = false,
      headerWithRepoHash = false, bitVectorWidthMax = 65536)
''', '''    val base = SpinalConfig(targetDirectory = directory.toString, headerWithDate = false,
      bitVectorWidthMax = 65536)
''', "supported widening publication config")
    PUBLICATION_TEST.write_text(publication)

    print("59i widening publication, audited construction, captures and saturation composed")


if __name__ == "__main__":
    main()
