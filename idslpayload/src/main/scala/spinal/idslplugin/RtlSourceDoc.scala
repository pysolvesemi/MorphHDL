package spinal.idslplugin

/** Compiler-owned annotation retaining source documentation in compiled libraries. */
final class RtlSourceDoc(val text: String, val origin: String, val location: String)
    extends scala.annotation.StaticAnnotation
