#!/usr/bin/env python3
"""Finish the exact 59i widening-publication/capture composition candidate.

Run only after the source-bound publication, audited-construction and capture
transactions. The native algorithms and ordinary width-conflict checks remain
authoritative. This candidate still requires executable and source review.
"""
from pathlib import Path
import hashlib
import subprocess

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


def apply_scoped_conditional_replay() -> None:
    """Compose only the exact reviewed source delta, never a scope allowlist.

    All six before/after blobs are bound independently of the patch context.
    The default observer, complete suite inventory and symbolic-width rejection
    remain unchanged contracts. A passing fixed-width case is not 59i closure.
    """
    patch_directory = ROOT / "morphhdl/repair-59i-widening-captures"
    patch_hashes = {
        "scoped-observer.patch": "00cc5561cd18df5d959b1002175d03b121e8ad0c8ea90c675e31c4f7c35093c5",
        "composite-replay.patch": "2ae6b88214492dd6d1303853fdfde807106adc97a7f543f1c1b6a91c80aa22a7",
        "saturation-test.patch": "cbe1d427b7261b8409d401dfc7adb1e4e414b8b1b6f0d359d550f0e04697c17b",
    }
    patches = [patch_directory / name for name in patch_hashes]
    for patch in patches:
        require(patch.is_file() and not patch.is_symlink(), "missing regular scoped replay patch")
        require(hashlib.sha256(patch.read_bytes()).hexdigest() == patch_hashes[patch.name],
                "scoped replay patch fingerprint changed: " + patch.name)
    expected = {
        'morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionBackend.scala':
            ('aba9411ce495a6396a79900f43eb944ceac2a70b', '1ec30ec83eab77c3d837c6ac7333e78d2bfc0829'),
        'morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionClosedGraph.scala':
            ('2042d868b8b25d9c2eb415d221801221a817938e', '4c47f3e5719e6882f19cfee2f45ef60660d95a87'),
        'morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCompositeLeafReplay.scala':
            ('764f6f7b1f2418101b024e77acb38a62481aefd5', '881411e2ed6e4a5b82bf11e40cf0c86016ef7bfe'),
        'morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCompositeReplay.scala':
            ('2ff657fa2b81606a0c4051cfc22eb0e6851bc496', '062873d6d2a21fa9e23c0cbd5fa8292d7e6b99aa'),
        'morphhdl/src/test/scala/spinal/core/internals/TypedBalancedReductionClosedGraphTests.scala':
            ('1429b50aaa0fc5d230977415fdb8217137bc0899', '0d0dde8787ba9c4c1f7057dcc15c46111743dd73'),
        'morphhdl/src/test/scala/spinal/core/internals/TypedBalancedReductionCompositeSaturationTests.scala':
            ('6e21b307f95be582176064a2f25ff31753a00dea', '4e6996ab2c3e763326f29eb4071bf7948799960f'),
    }

    def git_blob(path: Path) -> str:
        require(path.is_file() and not path.is_symlink(), "missing regular scoped source: " + str(path))
        raw = path.read_bytes()
        return hashlib.sha1(b"blob " + str(len(raw)).encode() + b"\0" + raw).hexdigest()

    for path, (before, _) in expected.items():
        require(git_blob(ROOT / path) == before, "scoped replay predecessor changed: " + path)
    subprocess.run(["git", "apply", "--check", "--whitespace=error-all", *map(str, patches)], cwd=ROOT, check=True)
    subprocess.run(["git", "apply", "--whitespace=error-all", *map(str, patches)], cwd=ROOT, check=True)
    for path, (_, after) in expected.items():
        require(git_blob(ROOT / path) == after, "scoped replay result changed: " + path)
    print("59i scoped conditional replay: six exact source transitions applied")


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

    apply_scoped_conditional_replay()

    print("59i widening/capture composition with recursive native clone factories applied")


if __name__ == "__main__":
    main()
