package spinal.core

/** Marks a library parameter whose callback constructs hardware during retained
  * elaboration. The MorphHDL plugin rejects source-visible host writes, mutable
  * locals, early returns and throws through exact typed callback/helper symbols.
  * This is not a user assertion of purity and does not bypass native ownership
  * checks. Opaque external implementation purity is not established by this marker.
  */
final class rtlHardwareCallback extends scala.annotation.StaticAnnotation
