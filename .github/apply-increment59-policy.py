#!/usr/bin/env python3
import json
from pathlib import Path

path = Path("morphhdl/contracts/increment-55-native-change-review.json")
data = json.loads(path.read_text(encoding="utf-8"))
additions = [
    {
        "path": "core/src/main/scala/spinal/core/BlackBox.scala",
        "baseline_path": "core/src/main/scala/spinal/core/BlackBox.scala",
        "change": "modified",
        "classification": "typed-overload",
        "introduced_by": [
            "Increment 59: typed BlackBox parameter and generic binding"
        ],
        "reason": (
            "Accept neutral typed integer and Boolean generic actuals while "
            "preserving every inherited concrete BlackBox generic lane."
        ),
        "edits": [
            {
                "id": "blackbox-typed-generic-01",
                "kind": "overload",
                "owner": "spinal.core.BlackBox.addGeneric(ElabInt)",
                "reason": (
                    "Validate and retain the exact typed integer expression, "
                    "then delegate only its concrete Int witness to the "
                    "inherited native emitter."
                ),
                "required_exact_text": [
                    {
                        "side": "approved",
                        "text": "case value: ElabInt =>",
                        "count": 1,
                    },
                    {
                        "side": "approved",
                        "text": (
                            "ParameterizedBlackBoxGenericRegistry.retain"
                            "(this, name, value)"
                        ),
                        "count": 2,
                    },
                ],
            },
            {
                "id": "blackbox-typed-generic-02",
                "kind": "overload",
                "owner": "spinal.core.BlackBox.addGeneric(ElabBool)",
                "reason": (
                    "Validate and retain the exact typed Boolean expression, "
                    "then delegate only its concrete Boolean witness to the "
                    "inherited native emitter."
                ),
                "required_exact_text": [
                    {
                        "side": "approved",
                        "text": "case value: ElabBool =>",
                        "count": 1,
                    }
                ],
            },
        ],
    },
    {
        "path": (
            "core/src/main/scala/spinal/core/internals/"
            "ParameterizedBlackBoxGeneric.scala"
        ),
        "baseline_path": None,
        "change": "added",
        "classification": "typed-support-file",
        "introduced_by": [
            "Increment 59: typed BlackBox parameter and generic binding"
        ],
        "reason": (
            "Retain exact typed BlackBox generic and packed-port expressions "
            "by BlackBox object identity while native emitters consume "
            "concrete witnesses."
        ),
        "edits": [],
    },
]
additions_by_path = {entry["path"]: entry for entry in additions}
retained = [
    entry for entry in data["files"] if entry["path"] not in additions_by_path
]
data["files"] = sorted(retained + additions, key=lambda entry: entry["path"])
path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
