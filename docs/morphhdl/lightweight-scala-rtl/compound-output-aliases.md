# Compound child-output aliases (LWR-33)

A parent `cloneOf(child.result)` now projects authenticated compound port widths
through the child's retained formal-to-actual bindings. For a child output
`Bits((A + B) bits)` instantiated with parent `P` and `Q`, the parent clone uses
`P + Q`, preserving the parent declarations and their complete admitted domains.
Literal actuals produce a literal parent width. Bits, UInt and SInt retain their
native types and signedness.

The projection is restricted to immediate child ports and exact formal identities.
It proves every actual is within its formal domain and evaluates the compound
width over the bounded parent domain before issuing a private width certificate.
Existing direct-formal projection is unchanged. Product-domain comparison can
consume these certificates through a read-only bridge that validates ownership
and scope; it cannot accept caller-provided proof tables. Unrelated roots,
forged/copied evidence, incomplete bindings, out-of-domain actuals and mismatched
widths remain rejected. No hardware width is inferred from a construction witness.

The finite proof path retains its existing enumeration limit; this change does
not add unbounded Cartesian-domain projection or support arbitrary expression
connections where hierarchy publication requires a direct carrier.

Focused evidence and the isolated production replay are recorded in the adjacent
`compound-output-aliases.json`. Historical LWR-32 failure evidence is unchanged.
Application Scala and delivery RTL are unchanged. No full CI or full qualification
is required by LWR-33; Questa is unavailable in this environment.
