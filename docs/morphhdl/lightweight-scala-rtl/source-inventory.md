# Lightweight source inventory (16 October 2026)

Inventory baseline: compiler `f7135bbd8b1e40b89ee1183de826c90d2f999e31`,
application `312e10d4`, with the current parameter API working changes.
This is an inventory, not passing test evidence. It includes legacy low-level
escape hatches; their presence does not require replacing proven native internals.

| Source | Area | Matching lines | Files | Examples |
|---|---|---:|---:|---|
| compiler/library | parameters | 138 | 15 | `spinal/lib/BalancedTree.scala:30`, `spinal/lib/BalancedTree.scala:31`, `spinal/lib/BalancedTree.scala:32` |
| compiler/library | ranges/guards/indices | 144 | 8 | `spinal/lib/Flow.scala:196`, `spinal/lib/Flow.scala:253`, `spinal/lib/Flow.scala:271` |
| compiler/library | arrays/packing | 717 | 136 | `spinal/lib/Mem.scala:62`, `spinal/lib/Mem.scala:63`, `spinal/lib/Mem.scala:69` |
| compiler/library | hierarchy/recursion | 10 | 2 | `spinal/lib/BalancedTree.scala:11`, `spinal/lib/BalancedTree.scala:12`, `spinal/lib/BalancedTree.scala:24` |
| compiler/library | clocks | 519 | 141 | `spinal/lib.scala:52`, `spinal/lib/Mem.scala:132`, `spinal/lib/Flow.scala:87` |
| compiler/library | callbacks | 46 | 9 | `spinal/lib/Utils.scala:138`, `spinal/lib/Utils.scala:148`, `spinal/lib/Utils.scala:155` |
| compiler/library | naming | 279 | 69 | `spinal/lib/Utils.scala:80`, `spinal/lib/Utils.scala:1307`, `spinal/lib/Utils.scala:1309` |
| application | parameters | 31 | 6 | `displaycontroller/cdc/CdcChannels.scala:27`, `displaycontroller/cdc/CdcChannels.scala:28`, `displaycontroller/cdc/CdcChannels.scala:67` |
| application | ranges/guards/indices | 297 | 38 | `displaycontroller/cdc/CdcChannels.scala:6`, `displaycontroller/cdc/CdcChannels.scala:12`, `displaycontroller/cdc/CdcChannels.scala:23` |
| application | arrays/packing | 151 | 20 | `displaycontroller/cdc/CdcPrimitives.scala:14`, `displaycontroller/cdc/CdcPrimitives.scala:33`, `displaycontroller/cdc/CdcPrimitives.scala:50` |
| application | hierarchy/recursion | 44 | 14 | `displaycontroller/cdc/CdcChannels.scala:178`, `displaycontroller/cdc/CdcChannels.scala:184`, `displaycontroller/common/BalancedTree.scala:14` |
| application | clocks | 211 | 28 | `displaycontroller/cdc/CdcChannels.scala:22`, `displaycontroller/cdc/CdcChannels.scala:34`, `displaycontroller/cdc/CdcChannels.scala:38` |
| application | callbacks | 21 | 2 | `displaycontroller/common/BalancedTree.scala:22`, `displaycontroller/common/BalancedTree.scala:35`, `displaycontroller/common/BalancedTree.scala:58` |
| application | naming | 240 | 35 | `displaycontroller/cdc/CdcChannels.scala:43`, `displaycontroller/cdc/CdcChannels.scala:85`, `displaycontroller/cdc/CdcChannels.scala:96` |

## Migration decisions

- Preserve the native proof/capture APIs as compiler internals and compatibility APIs.
- Production status and tree fixtures import `spinal.lib.BalancedTree`. The duplicate application implementation is retired after focused subtraction, bridge, Bundle, API, singleton, sharing and malformed-input tests. The table above records the pre-migration inventory, including historical paths that are now removed.
- Direct width-preserving native casts now carry child-port geometry, including the production Gray synchronizer wrapper. Other `ModulePortWires` call sites and the one-bit synchronization output adapter remain where their contracts have not been superseded; they are not removed by a global source substitution.
- Representative CDC/CSR ordinary loops and multidimensional status terms were migrated in application `312e10d4`; its focused evidence is in the compiler repair receipt.
- Parameter syntax probes distinguish the existing `RtlParam` interface from new inferred-name `Param` declarations. Packed representation and optional policies have focused cross-version regressions; the current implementation receipt identifies tested snapshots.
- Ordinary concrete Scala loops still unroll. Retained structural loops and ordered procedural priority loops have distinct ownership contracts.

Reproduce the inventory with `rg -n` over `lib/src/main/scala` and the application
`display-controller-morphhdl/hw/spinal` tree using the API names in the table.
