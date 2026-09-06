package spinal.core.internals;

import spinal.core.UInt;

/** Real JVM superclass/default-interface initialization controls, not mocked bytecode. */
public final class CertifiedCallbackInitializerFixture {
    public static int calls;

    public static class EffectfulBase {
        static { calls += 1; }
    }

    public static final class StaticHelper extends EffectfulBase {
        public static UInt combine(UInt a, UInt b) { return a.$plus(b); }
    }

    public interface EffectfulInterface {
        Object MARKER = initialize();
        static Object initialize() { calls += 1; return new Object(); }
        default void marker() { }
    }

    public static final class InterfaceHelper implements EffectfulInterface {
        public static UInt combine(UInt a, UInt b) { return a.$plus(b); }
    }
}
