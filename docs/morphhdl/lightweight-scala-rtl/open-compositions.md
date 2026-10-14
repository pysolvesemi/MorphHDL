# Open lightweight compositions

These are current development findings, not reopened historical CA claims.
No application source or compiler pin has changed.

## Literal-depth parent Vec to generic child Vec (LWR-08)

A generic child with `COUNT` domain 1..8 and literal actual 3 rejects a direct
assignment from a three-element parent Vec. The source reports
`SPINAL-ELAB-VEC-ASSIGNMENT-SHAPE-MISMATCH` for COUNT versus 3. The symbolic parent
Vec path already has passing library-adapter tests.

The first observed source was a negative ownership fixture; it failed at this
connection before reaching the intended ownership check:

```scala
val tree = operation.component(ElabInt.literal(3))
tree.io.terms := Vec.fill(3)(U(0, 8 bits))
```

Evidence: `/tmp/lwr-library-boundaries-212-v2.log`. The ownership test now uses a
supported symbolic parent Vec so it can independently check foreign capture.
This does not resolve the literal-depth connection.

`ParameterizedVec.equivalentDirectHierarchyShape` currently requires matching
physical carrier capacities, and `Vec.assignFromImpl` requires equal native
vector sizes. The child retains capacity 8 while the parent owns 3 leaves. A fix
must authenticate literal actual substitution and adapt native audit geometry
without inventing width evidence, losing lanes, or relaxing writer/ownership
checks. Merely removing the capacity checks is not sufficient. Add separate
positive and negative boundary regressions before closure.

## Remaining acceptance work

The durable progress record and the frozen 86-item baseline remain authoritative
for task scope. In particular, general affine writes, broader helper inference,
implicit recursive clock ports, callback-effect restrictions, cleanup/readability,
current-source integrity enrollment, remote qualification and application migration
are not closed by the local library tests. Statement-valued symbolic chains with no final else now have local cross-version
composition and side-effect rejection evidence. Remote qualification remains pending.
