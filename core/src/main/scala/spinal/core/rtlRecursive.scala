package spinal.core

/** Opt in to compiler-owned direct recursive construction.
  * Use the rooted annotation spelling documented by the lightweight frontend.
  * The native emitter independently proves every recursive edge.
  */
final class rtlRecursive extends scala.annotation.StaticAnnotation
