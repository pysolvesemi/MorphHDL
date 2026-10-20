#!/usr/bin/env python3
"""Apply the exact source-bound native geometry successor after scoped replay.

This stages tested native provenance/replay/publication source. It does not
mark Increment 59i complete or waive permanent-source review/final-head gates.
All source preimages, the patch and its complete output are validated before
writing any source. New paths must be absent, including dangling symlinks.
"""
from pathlib import Path
import hashlib
import stat
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
PATCH = Path(__file__).with_name("symbolic-geometry.patch")
PATCH_SHA256 = '8b70667350e9631c925762b5d5e389a4ceccb4f8dfdb5b521973d14952980fc6'
EXPECTED = {'core/src/main/scala/spinal/core/Bits.scala': ('ee46af0079f4c7867e0d61b4a61b6e819a9c00f9',
                                                'a4c503d86e86170b2c9abd71dd3256409dc669bb'),
 'core/src/main/scala/spinal/core/NativeWidthProvenance.scala': ('8f80ebeeb44455b58ef3e99ca455b1f4d855942e',
                                                                 '5ccfdcde487bf0fd2f4fe709d4ed0e8330cbe5fa'),
 'core/src/main/scala/spinal/core/UInt.scala': ('b93a3c1220604ae4aa1ecdc091a66272dd362810',
                                                '006b26d2e0f8a2738a1b372421d485543fa5bfd6'),
 'morphhdl/src/main/scala/morphhdl/MorphVerilog.scala': ('044d185e0f8c5e3ea2777dfeed9f6baca4fd48a5',
                                                         '3cb6e375c934d7e0189d9bfd76afa7449386f36d'),
 'morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedNativeGeometry.scala': (None,
                                                                                             '8badac8923f24d68c260efb0610492a52c0a34a0'),
 'morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogNativeFallback.scala': ('cf867cb7ba8aee41dc8b2f1e42cc5d2657e84168',
                                                                                                    '116cea0723f8684dd4d982dffd308abd2ac3728a'),
 'morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionClosedGraph.scala': ('4c47f3e5719e6882f19cfee2f45ef60660d95a87',
                                                                                           'de86e2564eeea33dba9d6f2fa2e60ff6f19c6e0b'),
 'morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionScalarGraphReplay.scala': ('e4970b3102a778ead64eff4cf126d42b6e975d88',
                                                                                                 'f402dc369961e7bb60549146a487fc5784582409'),
 'morphhdl/src/test/scala/spinal/core/internals/TypedBalancedReductionCompositeCaptureArtifacts.scala': ('50629268ed3b21ded5a2873fab580a1704073679',
                                                                                                         '518fd518acafae9fa56c285ce6dfb739ff21c440'),
 'morphhdl/src/test/scala/spinal/core/internals/TypedBalancedReductionCompositeSaturationTests.scala': ('4e6996ab2c3e763326f29eb4071bf7948799960f',
                                                                                                        'd0bb446741cace2392afdeaa67e37a341ae66e72'),
 'morphhdl/src/test/scala/spinal/core/internals/TypedBalancedReductionNativeGeometryTests.scala': (None,
                                                                                                   'ad648e85f968cb0a1c19db9b6c362a95d9bac748'),
 'morphhdl/src/test/scala/spinal/core/internals/TypedBalancedReductionNestedResultArtifactWriter.scala': ('622a693bb592b383a2ab7e0b2645485584045276',
                                                                                                          '0bba0e4cd1e6a3d2be40b63fe980382a65a73c87')}


# These two complete preimage sets retain the disjoint target hierarchy-width
# and wire-assignment changes. Mixed or unknown predecessor sets are rejected.
TARGET_EXPECTED = dict(EXPECTED)
TARGET_EXPECTED.update({'morphhdl/src/main/scala/morphhdl/MorphVerilog.scala': ('4c0d9df35a5d7c2dea7d5a428e0b56c27193593a',
                                                         '98e60c9f474140e08c04781814e7b6aebbfdc9c6'),
 'morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogNativeFallback.scala': ('802f3bef2d3b99e6c67cc5d519a3fcf7a5c31b4c',
                                                                                                    '29e6c7a88f58f44ab055e061ada97f595d412bfb')})


def require(condition: bool, detail: str) -> None:
    if not condition:
        raise RuntimeError("59i symbolic geometry: " + detail)


def regular(path: Path) -> bytes:
    require(path.is_file() and not path.is_symlink() and stat.S_ISREG(path.stat().st_mode),
            "missing regular file: " + str(path))
    require(not path.stat().st_mode & 0o111, "source/patch became executable: " + str(path))
    return path.read_bytes()


def blob(raw: bytes) -> str:
    return hashlib.sha1(b"blob " + str(len(raw)).encode() + b"\0" + raw).hexdigest()


def preimages(root: Path, expected: dict) -> dict[str, bytes]:
    result = {}
    for relative, (before, _) in expected.items():
        path = root / relative
        require(not any(parent.is_symlink() for parent in path.parents if parent != root.parent),
                "source parent is a symlink: " + relative)
        if before is None:
            require(not path.exists() and not path.is_symlink(), "new source already exists: " + relative)
        else:
            raw = regular(path)
            require(blob(raw) == before, "source predecessor changed: " + relative)
            result[relative] = raw
    return result


def verify_output(root: Path, expected: dict) -> None:
    for relative, (_, after) in expected.items():
        require(blob(regular(root / relative)) == after, "source result changed: " + relative)


def main() -> None:
    raw = regular(PATCH)
    require(hashlib.sha256(raw).hexdigest() == PATCH_SHA256, "patch fingerprint changed")
    matches = []
    for expected in (EXPECTED, TARGET_EXPECTED):
        try:
            matches.append((expected, preimages(ROOT, expected)))
        except RuntimeError:
            pass
    require(len(matches) == 1, "source does not match one complete reviewed predecessor set")
    expected, before = matches[0]
    with tempfile.TemporaryDirectory(prefix="morphhdl-59i-geometry-") as directory:
        trial = Path(directory)
        for relative, source in before.items():
            path = trial / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(source)
        subprocess.run(["git", "apply", "--check", "--whitespace=error-all", str(PATCH)], cwd=trial, check=True)
        subprocess.run(["git", "apply", "--whitespace=error-all", str(PATCH)], cwd=trial, check=True)
        require({str(path.relative_to(trial)) for path in trial.rglob("*") if path.is_file()} == set(expected),
                "patch output inventory changed")
        verify_output(trial, expected)
    require(preimages(ROOT, expected) == before, "source changed during preflight")
    subprocess.run(["git", "apply", "--check", "--whitespace=error-all", str(PATCH)], cwd=ROOT, check=True)
    subprocess.run(["git", "apply", "--whitespace=error-all", str(PATCH)], cwd=ROOT, check=True)
    verify_output(ROOT, expected)
    print("59i symbolic geometry: twelve exact source transitions applied")


if __name__ == "__main__":
    main()
