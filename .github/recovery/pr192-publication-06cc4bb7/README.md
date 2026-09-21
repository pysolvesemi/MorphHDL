# PR192 targeted verification repair

This separate controller starts from integration 155df6 and qualifies the exact
06cc4bb7 source/tree pinned in manifest.json. The compiler implementation is
unchanged from the successful focused qualification at fc61cad0. The new source
repairs the sealed branch authorization route and old naming assumptions in
proof harnesses, and archives exact focused receipts.

Only three workflows launch now: the failed pass workspace and the already
finished CDC/native-source gates. Five older-head workflows are still active;
they are explicitly deferred to avoid cancelling them through concurrency
groups. Their older-head outcomes are diagnostic only. All eight gates still
require success at the final source before full CI. The monitor must launch the
remaining exact-head gates through a new bounded continuation when safe.

The dispatcher validates the original failed boundary job, live PR/ref/tree,
exact workflow definitions, and existing current-head runs before any allowed
POST. It never retries ambiguous writes, changes refs, cancels work, starts full
CI or merges. AGENTS.md supplies standing authorization for this mechanism.
