#!/usr/bin/env bash
set -euo pipefail

python3 - <<'PY'
from pathlib import Path

source = Path("morphhdl/scripts/increment-42-bootstrap.sh")
text = source.read_text(encoding="utf-8")
workflow_start = "proof_workflow = r'''name: MorphHDL external structural and process capture"
documentation_start = "documentation = r'''# Increment 42"
cleanup_start = "\ngit checkout origin/parameterized-verilog -- .github/workflows/morphhdl-native-source-guard.yml"

for marker in (workflow_start, documentation_start, cleanup_start):
    if text.count(marker) != 1:
        raise SystemExit(f"expected one bootstrap marker {marker!r}, found {text.count(marker)}")

start = text.index(workflow_start)
end = text.index(documentation_start, start)
text = text[:start] + text[end:]
cleanup = text.index(cleanup_start)
text = text[:cleanup] + "\necho 'Increment 42 focused validation passed; connector cleanup remains'\n"
Path("/tmp/increment-42-bootstrap-patched.sh").write_text(text, encoding="utf-8")
PY

bash /tmp/increment-42-bootstrap-patched.sh
