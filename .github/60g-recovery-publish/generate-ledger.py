import hashlib, difflib, json, subprocess
from pathlib import Path
root = Path.cwd()
base = "39977b32cc47c54a0481716c181069184a32fbe5"
paths = ['morphhdl/scripts/check-increment-59f-source-scope.py', 'morphhdl/scripts/check-increment-60c-signed-declarations.py', 'morphhdl/scripts/check-increment-60d-pure-sint-casts.py', 'morphhdl/scripts/check-increment-60e-signedness-boundaries.py', 'morphhdl/src/test/scala/nativeapplication/SIntSignedDeclarationsFixture.scala', 'morphhdl/src/test/scala/nativeapplication/SIntSignedVerilogBaselineFixture.scala', 'morphhdl/src/main/scala/spinal/core/internals/MorphHdlSignednessAnalysis.scala', 'morphhdl/src/main/scala/spinal/core/internals/MorphHdlSignedDeclarationPolicy.scala', 'morphhdl/src/main/scala/morphhdl/MorphVerilog.scala', 'morphhdl/src/main/scala/morphhdl/MorphSignedCasts.scala', 'morphhdl/src/main/scala/morphhdl/MorphSignedDeclarations.scala', 'core/src/main/scala/spinal/core/internals/Phase.scala', 'morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedNativeResize.scala', 'morphhdl/src/main/scala/spinal/core/internals/ParameterizedVerilogStructural.scala', 'morphhdl/scripts/check-increment-60f-artifacts.py', 'morphhdl/scripts/check-increment-60f-equivalence-closure.py', 'morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogNativeFallback.scala', 'morphhdl/src/test/scala/morphhdl/SignednessBoundaryTests.scala']
def digest(text): return hashlib.sha256(text.encode()).hexdigest()
files = []
for path in paths:
    before = subprocess.check_output(["git", "show", base + ":" + path], text=True)
    after = (root / path).read_text()
    assert before != after, path
    a, b = before.splitlines(True), after.splitlines(True)
    edits = []
    for group in difflib.SequenceMatcher(None, a, b, autojunk=False).get_grouped_opcodes(2):
        aa = "".join(a[group[0][1]:group[-1][2]])
        bb = "".join(b[group[0][3]:group[-1][4]])
        assert aa and bb and before.count(aa) == after.count(bb) == 1, path
        edits.append(dict(before=aa, after=bb))
    restored = after
    for edit in reversed(edits): restored = restored.replace(edit["after"], edit["before"], 1)
    assert restored == before, path
    files.append(dict(path=path, before_sha256=digest(before), after_sha256=digest(after), edits=edits))
(root / "morphhdl/contracts/increment-60g-publication-edits.json").write_text(json.dumps(dict(base=base, files=files), indent=2) + "\n")
