# Increment 53c validation contract

Increment 53c is complete only when the exact synchronized PR head satisfies all of the following gates on Scala 2.12.18 and 2.13.12:

- the real, untouched `spinal.lib.bus.amba4.axi.Axi4SlaveFactory` remains the sole factory implementation;
- direct `OFFSET`, derived `OFFSET + 4`, and unrelated fixed `0x080` mappings retain the required symbolic or concrete identity;
- repeated `MorphVerilog` generation is deterministic and ordinary `SpinalVerilog` witnesses remain concrete;
- Icarus Verilog-2001 compilation, Verilator lint, Yosys synthesis, native-source preservation, and inherited MorphHDL regressions pass;
- one parameterized MorphHDL definition, specialized at offsets `0x010`, `0x040`, `0x050`, and `0x070`, is solver-proven equivalent to independently elaborated native-`Int` SpinalVerilog witnesses;
- the deliberate mutation control produces a genuine formal assertion counterexample.

The roadmap checkbox may remain checked and PR #108 may merge only after both dedicated Increment 53c workflows pass on the exact base-synchronized revision.
