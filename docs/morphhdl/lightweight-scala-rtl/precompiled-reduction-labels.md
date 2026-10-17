# Precompiled reduction labels (LWR-34)

The current compiler already retains automatic conditional ownership through
`ElabControl` and scoped finite guards. Publication uses the captured block's
identity to allocate coordinated `g_GEN_n_TRUE/FALSE` labels together with loop
labels. Explicit labels and all reserved identifiers remain protected. No
spelling-based fallback or new naming transformation is needed.

The application evidence used build outputs from its compiler dependency checkout.
A controlled replay of the **same compiled consumer** emits compact labels with
the fresh compiler classpath, but reproduces `g_if_BalancedTree_l50_true/false`
and `guard_l56_true/false` with those dependency build outputs. Inspection shows
that the latter `ElabControl$.class` lacks the automatic-label registrations
present in the checked-out source. The publisher does recognize automatic labels;
it correctly refuses to infer ownership from unmarked label spelling.

This establishes a stale/mixed runtime-artifact problem. It does not establish
why the earlier build cache retained that class. A Git checkout identity or the
runtime banner alone is not evidence that every loaded class matches that source.
The control archives both class files, bytecode inspection, classpaths and hashes.
Refresh the compiler dependency's build outputs before generating application RTL;
do not repair generated labels with text substitution.

## Regression and reproduction

`morphhdl/scripts/check-precompiled-reduction-labels.py` compiles the authoritative
`lib/.../BalancedTree.scala` into a fresh library directory, then compiles the
consumer fixtures in a different invocation and directory. Execution puts the
fresh library first. Library compilation retains the repository's
`-P:morphhdl:ordinary-loops=host` setting; consumer compilation uses the normal
application setting. Tests assert the library and consumer have different code
locations, cover sum/error-union operations, defaults 1/16, combined/per-component
publication, nested branch/loop naming, explicit labels and declaration collisions.
Retained reduction and branch/loop safety suites run alongside the new cases.

```sh
python3 morphhdl/scripts/check-precompiled-reduction-labels.py \
  --scala 2.12 --classpath-file /path/to/compiler-classpath.txt \
  --output /tmp/reduction-labels-212
# Repeat with --scala 2.13 and its matching compiler classpath.
```

Fresh generation of the unchanged production status component produces both
`StatusCounterDeltaSum` and `StatusEventErrorUnion` with `g_GEN_1_TRUE/FALSE`,
`g_GEN_2`, and `g_GEN_3_TRUE/FALSE`. Each fresh artifact passes 1,344 independent
sum/OR comparisons over COUNT 1..16, including zero, all-one, X/Z and randomized
inputs, plus Icarus Verilog-2001 compilation and Verilator synthesis-mode lint.
The singleton reference preserves the input directly, including high impedance.
Application Scala and delivery RTL are unchanged; no full CI/full qualification
is required. This is not a full application behavioral qualification.

Fresh commands, source/artifact hashes, classpath controls and final source
integrity/publication receipts are retained under
`/home/kartik/.local/state/morphhdl/lightweight-scala-rtl/lwr34-20261018/`.
The adjacent JSON records the cross-version test results separately from the
historical application failure.
