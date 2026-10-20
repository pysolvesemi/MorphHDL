package morphhdl.compiler

/** Compiler-only attachment issued by authenticated configuration discovery. */
private[compiler] final case class ConfigurationFormalPath(root: String, fields: List[String],
    conversion: String, specializations: List[List[String]])
