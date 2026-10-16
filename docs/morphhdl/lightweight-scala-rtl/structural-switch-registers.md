# LWR-19: retained register-copy loops in runtime cases

A retained finite `for` may be nested in native `switch/is/default` and
`when/otherwise` scopes. Capture retains the selector, case keys, branch identity
and enclosing conditions. It isolates the loop's writes without claiming other
assignments in the original case. Empty cases are retained as exclusions,
including cases authored after a default body. Native switch normalization and
validation still apply.

```scala
val count: ElabInt = ElabInt.literal(15)
val snapshot = Vec.fill(17)(Reg(Bits(32 bits)) init(0))
when(writeAccepted) {
  switch(address) {
    is(commitAddress) {
      when(!rejectCommit) {
        for (i <- 0 until count) snapshot(i) := shadow(i)
        snapshot(15) := payloadBytes
        snapshot(16) := fetchBytes
      }
    }
  }
}
```

Fixed, authenticated unit-stride register subranges may coexist with disjoint
whole-leaf scalar writers to their complement. Those writers may appear after
the loop in Scala. The compiler checks the complement after elaboration and
keeps the existing checks on lane identities, declaring component, clock,
initialization, competing writers and readback bridges. All generated lanes must
share the same literal reset value; scalar complement lanes retain their own
initializers. An unproven varying prefix is not accepted from a witness alone.
Existing authenticated complete/tail and affine write paths remain available.

No combinational enable workaround, application-specific names or generated-RTL
rewriting is involved. Registers still sample pre-edge source values when source
and destination update together. No CI/full qualification is required by this
roadmap item; focused evidence is recorded separately.

## Focused verification

The implementation was tested as an exact source overlay on
`1800a173b1bde049bc77537a03c634d924a7d60a`. The adjacent
[receipt](structural-switch-registers-receipt.json) records source hashes,
commands, artifact inventories and tool records. Scala 2.12.18 and 2.13.12 each
pass 44 tests: 11 new switch/prefix cases, 19 existing carrier/shared-control
cases and 14 register-ownership cases. All 192 recorded tool invocations pass.
New cases cover packed/unpacked layouts, nested switches, multi-key cases,
default with a later-authored case, all address/enable/reject/update combinations,
X/Z addresses, asynchronous reset, simultaneous source/destination updates,
deterministic emission, missing/overlapping writers and clock/reset mismatch.
Icarus compiles with `-g2001` and runs the scoreboards; Verilator lints generated
RTL as Verilog-2001 with `-DSYNTHESIS`; Yosys synthesizes and checks for undriven
nets and latches. Questa is unavailable. Existing mutation checks remain active.
No full CI or new formal campaign was run.

The frozen application snapshot `lightweight-compositions-gs45b0ip/sources`
compiles with the repaired compiler. Product-top publication advances past both
LWR-19 errors, then fails on the separately recorded **LWR-20** constant bit-write
case `shadow(0)(11) := False`. Its fresh record is
`lightweight-compositions-n91d5qn7/results.json`, retained under the application's
`.work` directory. This is a failed application retest, not passing top evidence.
The application source, compiler cleanup and delivery RTL are not modified by
this implementation.
