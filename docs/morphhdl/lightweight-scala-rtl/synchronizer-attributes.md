# Synchronizer attributes (LWR-43)

The default device phase checks the native attributes on the exact CDC register
before inserting a synchronizer annotation. This applies to `crossClockDomain`
and `crossClockBuffer` registers, including retained register Vecs.

For the default and Xilinx targets, string attributes named `ASYNC_REG` or
`async_reg` with values `TRUE` or `true` satisfy the request. The authored object
and spelling are retained. If several equivalent attributes exist on that
register, the uppercase spelling is preferred; otherwise the first is retained.
Repeated device-phase insertion is idempotent. With no annotation, the existing
automatic `async_reg = "true"` spelling is used.

Efinix continues to require `async_reg = "true"`. Its documented lowercase
spelling is not suppressed by an uppercase user attribute. An uppercase
annotation is preserved separately, without assuming the target recognizes it.
Other vendors retain their previous behavior: this handler neither inserts nor
normalizes their synchronizer attributes.

A recognized synthesis annotation with a conflicting or unsupported value
(including a flag or integer in place of the supported string) fails with
`SPINAL-SYNCHRONIZER-ATTRIBUTE-CONFLICT`, identifying the register and target.
Comment attributes do not satisfy a synthesis annotation. Untagged registers and
unrelated case-distinct attributes are unchanged. This is not general
case-insensitive attribute deduplication, and does not change CDC connectivity,
clock/reset handling or array publication.

The target conventions follow the [Vivado synthesis attribute documentation](https://docs.amd.com/r/en-US/ug901-vivado-synthesis/ASYNC_REG)
and [Efinity synthesis guide](https://www.efinixinc.com/docs/efinity-synthesis-v4.7.pdf).
Vendor synthesis acceptance is distinct from the local native-emission tests.

Focused verification covers default/Xilinx/Efinix and unchanged Altera, Lattice,
Actel, ASIC, NONE and unknown targets. The new fixtures exercise uppercase,
lowercase, duplicate equivalent attributes, automatic insertion, repeated
insertion, conflicts, unsupported value types, comment-only attributes and
untagged controls. Symbolic unpacked register chains are checked at widths 1/8
and depths 2/4 with asynchronous reset, randomized inputs and X/Z samples.
Existing constant-array, symbolic-initializer and CDC hierarchy regressions
remain in the focused set. Native Verilog compilation, strict lint and synthesis
are recorded in the adjacent JSON receipt, together with exact source and tool
identities. No full qualification or CI is claimed.
