package spinal.core.internals;

import spinal.core.UInt;

/** A legal Java module-shaped class whose mutable binding defeats a method-body
 * audit unless virtual dispatch is restricted to the exact final module class.
 */
public final class CertifiedCallbackMutableModuleFixture {
    public static int calls;

    public static class Module$ {
        public static Module$ MODULE$ = new Module$();

        public UInt combine(UInt a, UInt b) {
            return a;
        }
    }

    public static final class Replacement extends Module$ {
        @Override
        public UInt combine(UInt a, UInt b) {
            calls += 1;
            return a;
        }
    }
}
