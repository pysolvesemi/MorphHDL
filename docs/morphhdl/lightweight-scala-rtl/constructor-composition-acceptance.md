# LWR-13–16 focused local acceptance

The [source/evidence receipt](constructor-composition-receipt.json) identifies
base `ba040f99e9de05247a03d0333e58ec30e3f8bd58`, its tree, the exact tested source
overlay, plugin hashes, commands, RTL hashes and retained logs. The overlay is
published on `work/remaining-parameterized-increments`; the application closure
receipt records the final compiler commit/pin after source seals are generated.
Earlier failed scratch runs remain historical repair evidence.

Both Scala 2.12.18 and 2.13.12 pass 137 native/compiler/library cases and 20
frontend cases: 314 tests total. Seven selected artifacts per Scala version also
pass unchanged Rust cleanup, independent simulation, strict Verilog-2001 lint
and Yosys synthesis/check. Native tests retain domain, ownership, recursion,
missing coverage and competing-driver rejection. Existing hierarchy, scalar
forwarding, resize, packed/optional parameters and recursive reduction controls
are included. Two former literal-slice limitation tests now compare the supported
connections behaviorally; their varying-width and foreign-owner controls remain.

The implementation contracts and limitations are documented in
[constructor parameters](constructor-parameters.md) and
[port composition](port-compositions.md). Existing `Vec.reduceBalancedTree`
semantics are unchanged. Library `BalancedTree` keeps adjacent pairing, odd-tail
handling and bridge/register placement while using a constructor-owned COUNT.

Reproduce using built classpaths for the corresponding Scala version:

```sh
python3 morphhdl/scripts/lightweight-scala-rtl/check-constructor-compositions.py \
  --scala 2.12 --classpath-file /path/to/test-dependency-classpath.txt \
  --output /new/evidence/directory --cleanup-binary /path/to/spinal-cleanup-r

python3 morphhdl/scripts/lightweight-scala-rtl/check-constructor-compositions.py \
  --scala 2.12 --classpath-file /path/to/test-dependency-classpath.txt \
  --output /new/frontend/evidence --frontend-only
```

Use `--suites` for the twelve-suite native inventory recorded in the receipt;
the default selects the eight constructor/reduction suites. The classpath is a
raw colon-separated dependency classpath with the matching built runtime,
library, backend, Scala compiler and ScalaTest dependencies. Both compiler plugins
must be built. Fixtures compile afresh with range positions and both plugins.
The frontend-only mode isolates its native-API stubs from the hardware runtime.

Application migration removes production `ModulePortWires` uses, the two counter
carrier bundles and the 420 Boolean Vec workaround, and adopts constructor formals
in reset/synchronizer declarations. Its separate receipt records repeated CDC,
420 and top generation, native/cleaned lint, eight existing 420 unit runs,
21 CDC profiles and nine counter profiles on each RTL form. Generated `.v` files
are linted as Verilog-2001; byte-identical temporary copies of approved Dan IP
use Verilog-2005 for their existing `$clog2` calls. No delivery RTL or cleanup
implementation is changed by this work.

Questa is unavailable locally. CI, hourly monitoring and full qualification were
not run, as requested. These are proportional local acceptance results, not a
claim of exhaustive compiler verification or customer acceptance.
