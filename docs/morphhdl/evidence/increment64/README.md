# Increment 64 local artifact

`RecordLink.v` is actual generated output from
`morphhdl.examples.DerivedLocalParameterArtifactWriter`, also used by
`NativeDerivedLocalParameterTests`. Optional wire passes were disabled.

The generator ran while Increment 64 was in the working tree above
`e3d02da846e2ed75d9648999114b21c267cc80de`; the native header records that base,
not a claim that the feature was already present in that commit. The enclosing
implementation checkpoint retains the writer, implementation and tests together.
This is local implementation evidence, not remote qualification.

Artifact SHA-256:
`433872af39a2a2f15f7ce7b061479bacd12534d7fbde275d48f0c4808b4a5948`.

Writer source SHA-256:
`f78fcb2af14e58a888d3237cde96200b3e83b5485ea9f5a0bd81b323593864c2`.

Reproduce from the repository root:

```sh
sbt 'morph/Test/runMain morphhdl.examples.DerivedLocalParameterArtifactWriter target/increment64-artifacts'
```

The forked test runner places relative output under `morphhdl/target`.
Full local validation receipts and supported boundaries are recorded in
[the implementation note](../../increment-64-derived-localparams.md).
