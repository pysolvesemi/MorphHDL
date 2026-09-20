# Increment 59i source-audit I/O repair

The unchanged `c74b34bb` inherited-source run 35276203575 exceeded the existing
3,600-second current-positive 59h/60f audit budget. The successful 59g replacement
35275906758, including both Scala lanes and hardware proofs, is separate evidence.
No timeout, negative case, hardware proof or workflow command is removed here.

A bounded 180-second local cProfile diagnostic on the unchanged source observed
19,531 calls to live contract-byte authentication and 161,630 regular-file reads.
It did not complete the full audit and is not a passing qualification result.

## Retained authentication and permitted reuse

Every contract access still reads the complete current manifest and helper from
regular, mode-checked files, with a fresh lstat on every relative ancestor. Only
pure path parsing is reused. No filesystem status, index, HEAD, source bytes,
permission or authorization result is cached.

A bounded eight-entry cache canonicalizes exact (expected SHA-256, immutable
bytes) pairs. The first occurrence computes SHA-256; a subsequent hit requires
full equality of freshly read bytes and the same expected digest. This is not
mtime/size/inode caching. It returns the already equal immutable bytes object,
allowing its Python hash and parsed immutable representation to be reused.
Changed bytes, changed digest, modes and links are still rejected. Helper
normalization is a separately bounded pure function of its entire byte input.

The source checker retains all original tree, parent-history, complete-inventory,
edit-span, index, working-tree and recursive-submodule checks. The next source
certificate preserves c74b34bb as its first parent and the same integrated target
27af65ab as its second. Prior certificates remain checked by their original code.
Authenticated importer pins change only to bind the new verifier. The 60b list
retains every older accepted verifier and adds this exact new hash.

## Tests and boundaries

Twenty-four focused tests cover fresh reads, same-size edits with restored mtime,
file/ancestor links, missing and nonregular files, executable modes, invalid paths,
separate roots, full-byte comparisons, digest separation, eviction and bounded
caches. Four additional real-history continuation tests exercise warmed caches
against modified manifests, helper bytes, source modes and parent symlinks. All
previous continuation and regression tests remain, including the preceding
c74 -> ddf -> 14dc certificate links.

Primitive benchmarks compare identical data, not total audit duration. The entire
source-only audit must reach a terminal pass on this new seal before its timeout
failure can be called resolved. This source-only change does not establish Scala,
RTL or local-enable qualification. Failed-only CI remains the required first
phase; full CI, completion and merge remain blocked until all requirements pass.
