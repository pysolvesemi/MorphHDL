# Parent comments on helper-returned ports (LWR-17)

Automatic Scala/Scaladoc capture preserves declaration ownership when a parent
alias returns an immediate child's output. Its note attaches to that exact child
instance in the parent, not to the child's reusable port declaration. This applies
to direct aliases and helper-returned outputs without creating a carrier or
changing connectivity. Different parent explanations do not split child definitions.

Comments authored on the child port itself remain there. Explicit `.doc` calls
remain intentional declaration annotations. Automatic aliases of foreign inputs
or non-immediate foreign declarations do not acquire ownership and are ignored;
this rule does not legalize otherwise invalid hardware connections. Aggregate
outputs must consist entirely of output leaves before use-site attachment.

The implementation uses native Data/component identities and current elaboration
ownership. It does not recognize helper names or rewrite emitted prose. Capture
remains opt-in and requires source range positions. Existing pruning, generate
region and definition documentation behavior is covered by RtlDocumentationTests.

Focused cross-version evidence is recorded in `parent-comment-ownership-receipt.json`.
LWR-18 (compiler implementation-comment filtering) is a separate item and is not
claimed as repaired here. No CI/full qualification is required for this increment.
