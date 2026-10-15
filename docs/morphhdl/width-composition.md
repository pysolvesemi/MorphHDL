# Recursive width composition (CA-051–CA-053)

These repairs compose existing native typed recursion and packed Vec geometry.
They do not change recursion topology, latency, arithmetic or the application API.

* A complete same-component `Bits` copy of `vector.asBits` can retain the exact
  packed geometry in a named intermediate. Publication compares the private
  retained width certificates, including declaration identities and admitted
  branch domains. It does not reopen the construction-time branch. This equality
  check grants no new scope authority: native declaration, assignment and alias
  lineage checks remain required. Fixed-width witness copies and mutated aliases
  remain unsupported.
* An initialized register with both asynchronous reset and soft reset emits its
  initializer at two native reset sites. The lineage audit derives this
  multiplicity from the register's clock domain. Async reset remains independent
  of clock enable; soft reset remains inside the native clock-enable gate.
  Missing, changed or extra reset assignments still fail the audit.
* A compound child port such as `COUNT * DATA_WIDTH` may connect through a fixed
  internal parent leaf when substitution of the complete authenticated formal
  inventory proves an exactly constant positive width. The declaration function
  and instance function must agree, and the full direct connection must have
  that width and belong to the parent. A varying actual's construction witness
  does not grant this permission.

The generic fixture is `nativeapplication.NativeWidthCompositionTree` and its
focused regression suite is `morphhdl.WidthCompositionTests`. The suite compares
recursive sum behavior across count and width overrides, reset and enable stalls,
and checks Verilog-2001 compilation, lint and synthesis. Initializer mutation
checks are in `TypedBalancedReductionBridgeReplayTests`. Existing packed alias
and compound formal negative suites remain applicable.

Validation status must be read from the exact candidate's qualification receipt;
implementation or local regression results alone are not remote CI closure.
