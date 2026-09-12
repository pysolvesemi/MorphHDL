#!/usr/bin/env python3
"""Finish the exact 59i widening-publication/capture composition candidate.

Run only after the source-bound publication, audited-construction and capture
transactions. The native algorithms and ordinary width-conflict checks remain
authoritative. This candidate still requires executable and source review.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BACKEND = ROOT / "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionBackend.scala"
CERTIFIED = ROOT / "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCertifiedCallbackPolicy.scala"
REPLAY = ROOT / "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCompositeReplay.scala"
WIDENING_TEST = ROOT / "morphhdl/src/test/scala/spinal/core/internals/TypedBalancedReductionCompositeWideningTests.scala"
PUBLICATION_TEST = ROOT / "morphhdl/src/test/scala/spinal/core/internals/TypedBalancedReductionCompositeWideningPublicationTests.scala"
CAPTURE_TEST = ROOT / "morphhdl/src/test/scala/spinal/core/internals/TypedBalancedReductionCompositeWideningCaptureTests.scala"


def require(condition: bool, detail: str) -> None:
    if not condition:
        raise RuntimeError(detail)


def replace_once(text: str, before: str, after: str, label: str) -> str:
    require(text.count(before) == 1,
            label + " exact anchor count changed: " + str(text.count(before)))
    return text.replace(before, after, 1)


def preserve_substituted_clones(text: str) -> str:
    # The fresh clone's leaves already have the certified substituted widths,
    # but a reflective Bundle clone would still reuse the original constructor
    # arguments. Install the existing native HardType clone factory only on
    # this fresh result, including its recursive Bundle fields. The factory
    # captures the source shape and typed expressions, never a witness lookup.
    before = '''    }
    result
  }

  private val binaries: Map[Class[_], () => BinaryOperator] = Map(
'''
    after = '''    }
    def preserveClone(source: Data, target: Data,
        substituted: Vector[ElaborationIntegerExpression]): Unit = {
      if ((source eq target) || source.flatten.size != substituted.size ||
          target.flatten.size != substituted.size)
        fail("CLONE-FACTORY", "clone factory requires distinct native shapes and exact leaf widths")
      (source, target) match {
        case (a: MultiData, b: MultiData) =>
          val from = a.elements.toVector
          val to = b.elements.toVector
          if (a.getClass != b.getClass || from.map(_._1) != to.map(_._1))
            fail("CLONE-FACTORY", "clone factory changed recursive native field paths")
          var offset = 0
          from.zip(to).foreach { case ((_, childSource), (_, childTarget)) =>
            val size = childSource.flatten.size
            preserveClone(childSource, childTarget, substituted.slice(offset, offset + size))
            offset += size
          }
          if (offset != substituted.size)
            fail("CLONE-FACTORY", "clone factory lost recursive leaf coverage")
        case (_: BaseType, _: BaseType) =>
        case _ => fail("CLONE-FACTORY", "clone factory changed native container kinds")
      }
      target match {
        case bundle: Bundle =>
          // A later ordinary cloneOf/HardType must construct the already-proved
          // shape before copyCloneMetadata checks it. Never overwrite metadata
          // on the source or relax the registry's conflict rejection.
          bundle.hardtype = new HardType[Data](cloneShape(source, substituted))
        case _ =>
      }
    }
    preserveClone(template, result, widths)
    result
  }

  private val binaries: Map[Class[_], () => BinaryOperator] = Map(
'''
    return replace_once(text, before, after, "recursive substituted native clone factory")


def add_clone_regression() -> None:
    text = CAPTURE_TEST.read_text()
    before = '''  test("capture permission does not authorize cross-field widening") {
'''
    after = '''  test("substituted Bundle clones retain symbolic roots without rewriting their source") {
    val width = HdlInt.param("WIDTH", 5, 1, 16)
    val otherWidth = HdlInt.param("OTHER_WIDTH", 5, 1, 16)
    SpinalConfig(targetDirectory = Files.createTempDirectory("captured-widening-clones-").toString,
      headerWithDate = false, headerWithRepoHash = false).generateVerilog(new Component {
      val original = ElabInt.fromExpression(width.bits.expression.get)
      val other = ElabInt.fromExpression(otherWidth.bits.expression.get)
      val source = BalancedCapturedWideningValue(original, original)
      BalancedCapturedWideningFixture.initialize(source)
      val widths = Vector(other.expression, (original + ElabInt.literal(3)).expression)
      val shaped = TypedBalancedReductionCompositeReplay.cloneShape(source, widths)
        .asInstanceOf[BalancedCapturedWideningValue]
      BalancedCapturedWideningFixture.initialize(shaped)
      val nativeClone = cloneOf(shaped)
      nativeClone := shaped
      val externalClone = ParameterizedWidth.cloneOf(nativeClone)
      externalClone := nativeClone
      val factory = HardType(externalClone)
      val factoryClone = factory()
      factoryClone := externalClone
      for (value <- Vector(shaped, nativeClone, externalClone, factoryClone)) {
        assert(value.flatten.size == widths.size)
        value.flatten.toVector.zip(widths).foreach { case (leaf, expected) =>
          assert(leaf.component eq source.component)
          assert(ParameterizedWidth.expressionOf(leaf)
            .exists(ElaborationWidthAuthority.equivalent(_, expected)))
          assert(BigInt(leaf.getBitsWidth) == expected.default)
        }
      }
      source.flatten.foreach { leaf =>
        assert(ParameterizedWidth.expressionOf(leaf)
          .exists(ElaborationWidthAuthority.equivalent(_, original.expression)))
      }
      // WIDTH and OTHER_WIDTH deliberately have equal default witnesses.
      // Repeated native cloning must not erase their distinct symbolic roots.
      assert(!ElaborationWidthAuthority.equivalent(original.expression, other.expression))
      val conflict = intercept[Exception] {
        ParameterizedWidth.attach(source.unsigned, other.bits)
      }
      assert(details(conflict).contains("WIDTH-PROVENANCE-CONFLICT"), details(conflict))
    })
  }

  test("capture permission does not authorize cross-field widening") {
'''
    CAPTURE_TEST.write_text(replace_once(text, before, after,
        "native/external/HardType clone and equal-witness root rejection regression"))


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
    REPLAY.write_text(preserve_substituted_clones(replay))
    add_clone_regression()

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

    print("59i widening/capture composition with recursive native clone factories applied")


if __name__ == "__main__":
    main()
