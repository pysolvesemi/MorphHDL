# Increment 53d typed native-change manifest

The approved typed-elaboration architecture permits the following reviewed
native change. The original algorithm remains authoritative.

| File | Classification | Change | Algorithm impact |
|---|---|---|---|
| `lib/src/main/scala/spinal/lib/Stream.scala` | typed helper and mechanical propagation | `StreamWidthAdapter` obtains payload geometry with `widthOfExpr`, keeps width arithmetic typed, and extracts only domain-proven constant factors/chunk widths for existing host-side collection and counter APIs | none; equal-width, downsize, upsize, ordering, buffering and ready/valid logic are unchanged |

Neutral carrier definition: `core/src/main/scala/spinal/core/ElabInt.scala`.

Forbidden changes checked by CI:

- no separately authored adapter;
- no module, port, signal or emitted-text recognition;
- no native-`Int` shadow capture in the typed adapter path;
- no algorithm change outside the parameter-sensitive width statements above.
