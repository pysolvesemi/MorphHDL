package spinal.core

/** Optional HDL spelling for a constructor-owned Param formal.
  * Without this annotation the constructor argument name is used.
  */
final class rtlParamName(val name:String) extends scala.annotation.StaticAnnotation
