package morphhdl.ir.v1

private[v1] object IrIdentifierSyntax {
  private val Valid = "\\S+".r

  def validate(value: String, label: String): Either[String, String] = {
    val candidate = Option(value).map(_.trim).getOrElse("")
    candidate match {
      case Valid() => Right(candidate)
      case _       => Left(s"$label must be non-empty and contain no whitespace")
    }
  }
}

/** Stable logical module identity. It is independent of emitted HDL names. */
final case class ModuleId private (value: String) extends AnyVal {
  override def toString: String = value
}

object ModuleId {
  def from(value: String): Either[String, ModuleId] =
    IrIdentifierSyntax.validate(value, "module id") match {
      case Right(valid) => Right(new ModuleId(valid))
      case Left(error)  => Left(error)
    }

  def unsafe(value: String): ModuleId = from(value) match {
    case Right(id)    => id
    case Left(error)  => throw new IllegalArgumentException(error)
  }
}

/** Stable lexical-scope identity within a canonical design. */
final case class ScopeId private (value: String) extends AnyVal {
  override def toString: String = value
}

object ScopeId {
  def from(value: String): Either[String, ScopeId] =
    IrIdentifierSyntax.validate(value, "scope id") match {
      case Right(valid) => Right(new ScopeId(valid))
      case Left(error)  => Left(error)
    }

  def unsafe(value: String): ScopeId = from(value) match {
    case Right(id)    => id
    case Left(error)  => throw new IllegalArgumentException(error)
  }
}

/** Stable declaration identity used by every driver and reference. */
final case class SymbolId private (value: String) extends AnyVal {
  override def toString: String = value
}

object SymbolId {
  def from(value: String): Either[String, SymbolId] =
    IrIdentifierSyntax.validate(value, "symbol id") match {
      case Right(valid) => Right(new SymbolId(valid))
      case Left(error)  => Left(error)
    }

  def unsafe(value: String): SymbolId = from(value) match {
    case Right(id)    => id
    case Left(error)  => throw new IllegalArgumentException(error)
  }
}

/** Stable identity of one complete or partial driver. */
final case class DriverId private (value: String) extends AnyVal {
  override def toString: String = value
}

object DriverId {
  def from(value: String): Either[String, DriverId] =
    IrIdentifierSyntax.validate(value, "driver id") match {
      case Right(valid) => Right(new DriverId(valid))
      case Left(error)  => Left(error)
    }

  def unsafe(value: String): DriverId = from(value) match {
    case Right(id)    => id
    case Left(error)  => throw new IllegalArgumentException(error)
  }
}

/** Stable identity of one exact reference occurrence in an RTL expression. */
final case class ReferenceId private (value: String) extends AnyVal {
  override def toString: String = value
}

object ReferenceId {
  def from(value: String): Either[String, ReferenceId] =
    IrIdentifierSyntax.validate(value, "reference id") match {
      case Right(valid) => Right(new ReferenceId(valid))
      case Left(error)  => Left(error)
    }

  def unsafe(value: String): ReferenceId = from(value) match {
    case Right(id)    => id
    case Left(error)  => throw new IllegalArgumentException(error)
  }
}

/** Stable public parameter identity. Names are descriptive, never identity. */
final case class ParameterId private (value: String) extends AnyVal {
  override def toString: String = value
}

object ParameterId {
  def from(value: String): Either[String, ParameterId] =
    IrIdentifierSyntax.validate(value, "parameter id") match {
      case Right(valid) => Right(new ParameterId(valid))
      case Left(error)  => Left(error)
    }

  def unsafe(value: String): ParameterId = from(value) match {
    case Right(id)    => id
    case Left(error)  => throw new IllegalArgumentException(error)
  }
}

/** Stable identity of one generate index declaration. */
final case class GenerateIndexId private (value: String) extends AnyVal {
  override def toString: String = value
}

object GenerateIndexId {
  def from(value: String): Either[String, GenerateIndexId] =
    IrIdentifierSyntax.validate(value, "generate-index id") match {
      case Right(valid) => Right(new GenerateIndexId(valid))
      case Left(error)  => Left(error)
    }

  def unsafe(value: String): GenerateIndexId = from(value) match {
    case Right(id)    => id
    case Left(error)  => throw new IllegalArgumentException(error)
  }
}
