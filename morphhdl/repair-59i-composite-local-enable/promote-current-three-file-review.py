#!/usr/bin/env python3
"""Generate the current 59i local-enable review with backend statement binding.

The historical promotion helper intentionally records the original two replay
files.  The hardened current-head patch also changes the generic reduction
backend so publication observes assignments nested below retained When trees.
This wrapper extends only that exact reviewed inventory before generating the
contract and checker; it does not alter native source or the roadmap.
"""
from __future__ import annotations

import importlib.util
import os
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE = Path(__file__).resolve().parent / "promote-reviewed.py"
BACKEND = "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionBackend.scala"
BRIDGE = "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionBridgeReplay.scala"
COMPOSITE = "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCompositeReplay.scala"


def require(condition: bool, detail: str) -> None:
    if not condition:
        raise RuntimeError(detail)


def main() -> None:
    spec = importlib.util.spec_from_file_location("increment_59i_local_enable_promoter", SOURCE)
    require(spec is not None and spec.loader is not None,
            "cannot load the source-bound 59i local-enable promoter")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)

    require(tuple(module.PRODUCTION) == (BRIDGE, COMPOSITE),
            "historical 59i local-enable production inventory changed")
    module.PRODUCTION = (BACKEND, BRIDGE, COMPOSITE)

    old_paths = '''PATHS = (
    "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionBridgeReplay.scala",
    "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCompositeReplay.scala",
)
'''
    new_paths = '''PATHS = (
    "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionBackend.scala",
    "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionBridgeReplay.scala",
    "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCompositeReplay.scala",
)
'''
    require(module.CHECKER_TEMPLATE.count(old_paths) == 1,
            "59i local-enable checker inventory anchor changed")
    module.CHECKER_TEMPLATE = module.CHECKER_TEMPLATE.replace(old_paths, new_paths, 1)
    module.CHECKER_TEMPLATE = module.CHECKER_TEMPLATE.replace(
        "exact two-file inventory", "exact three-file inventory")

    base = os.environ.get("BASE_FOR_REVIEW", "").strip()
    require(bool(base), "BASE_FOR_REVIEW must identify the exact integrated predecessor")
    contract_sha = module.write_contract(base)
    module.write_reviewer(base, contract_sha)
    module.write_doc(base)

    doc = module.DOC.read_text()
    section = '''
## Exact reviewed production inventory

- `TypedBalancedReductionBackend.scala` recursively inventories the exact native
  callback statement tree retained for publication.
- `TypedBalancedReductionBridgeReplay.scala` replays field-local register data
  with certified same-composite controls.
- `TypedBalancedReductionCompositeReplay.scala` includes validated native
  callback statements in freshness accounting.
'''
    require("## Exact reviewed production inventory" not in doc,
            "59i local-enable production inventory section already exists")
    module.DOC.write_text(doc.rstrip() + "\n" + section)
    print("59i three-file local-enable successor review generated from " + base,
          flush=True)


if __name__ == "__main__":
    main()
