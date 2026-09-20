package morphhdl.ir.v1

/** Deterministic ordering for the immutable v1 graph.
  *
  * Normalization never deduplicates or repairs invalid metadata. Callers that
  * require a trusted graph must use [[CanonicalIrValidator]], which validates
  * first and normalizes only a valid design.
  */
object CanonicalIrNormalizer {
  def normalize(design: Design): Design =
    design.copy(modules = design.modules.map(normalizeModule).sortBy(_.id.value))

  private def normalizeModule(module: Module): Module =
    module.copy(
      parameters = module.parameters.map(normalizeParameter).sortBy(_.id.value),
      scopes = module.scopes.sortBy(_.id.value),
      generateIndices = module.generateIndices.sortBy(_.id.value),
      declarations = module.declarations.map(normalizeDeclaration).sortBy(_.id.value),
      drivers = module.drivers.map(normalizeDriver).sortBy(_.id.value)
    )

  private def normalizeParameter(parameter: Parameter): Parameter = parameter match {
    case value: IntegerParameter =>
      value.copy(
        domain = value.domain.copy(
          admittedValues = value.domain.admittedValues.sorted
        )
      )
    case value: BooleanParameter =>
      value.copy(
        domain = value.domain.copy(
          admittedValues = value.domain.admittedValues.sortBy(flag => if (flag) 1 else 0)
        )
      )
  }

  private def normalizeDeclaration(declaration: Declaration): Declaration =
    declaration.copy(
      packedType = declaration.packedType.map(normalizePackedType),
      attributes = declaration.attributes.sortBy(attributeKey),
      comments = declaration.comments.sortBy(commentKey)
    )

  private def normalizeDriver(driver: Driver): Driver =
    driver.copy(
      value = normalizeRtlExpr(driver.value),
      attributes = driver.attributes.sortBy(attributeKey),
      comments = driver.comments.sortBy(commentKey)
    )

  private def normalizePackedType(value: PackedType): PackedType =
    value.copy(width = normalizeIntExpr(value.width))

  private def normalizeIntExpr(expression: IntExpr): IntExpr = expression match {
    case value @ IntExpr.Literal(_)             => value
    case value @ IntExpr.ParameterRef(_)        => value
    case value @ IntExpr.GenerateIndexRef(_)    => value
    case IntExpr.Negate(value)                  => IntExpr.Negate(normalizeIntExpr(value))
    case IntExpr.Add(left, right)               => IntExpr.Add(normalizeIntExpr(left), normalizeIntExpr(right))
    case IntExpr.Subtract(left, right)          => IntExpr.Subtract(normalizeIntExpr(left), normalizeIntExpr(right))
    case IntExpr.Multiply(left, right)          => IntExpr.Multiply(normalizeIntExpr(left), normalizeIntExpr(right))
    case IntExpr.Divide(left, right)            => IntExpr.Divide(normalizeIntExpr(left), normalizeIntExpr(right))
    case IntExpr.Modulo(left, right)            => IntExpr.Modulo(normalizeIntExpr(left), normalizeIntExpr(right))
    case IntExpr.Min(left, right)               => IntExpr.Min(normalizeIntExpr(left), normalizeIntExpr(right))
    case IntExpr.Max(left, right)               => IntExpr.Max(normalizeIntExpr(left), normalizeIntExpr(right))
    case IntExpr.Select(condition, yes, no)     =>
      IntExpr.Select(normalizeBoolExpr(condition), normalizeIntExpr(yes), normalizeIntExpr(no))
    case IntExpr.CeilLog2(value)                => IntExpr.CeilLog2(normalizeIntExpr(value))
    case IntExpr.AddressWidth(value)            => IntExpr.AddressWidth(normalizeIntExpr(value))
    case IntExpr.Pow2(value)                    => IntExpr.Pow2(normalizeIntExpr(value))
  }

  private def normalizeBoolExpr(expression: BoolExpr): BoolExpr = expression match {
    case value @ BoolExpr.Literal(_)                 => value
    case value @ BoolExpr.ParameterRef(_)            => value
    case BoolExpr.LessThan(left, right)              => BoolExpr.LessThan(normalizeIntExpr(left), normalizeIntExpr(right))
    case BoolExpr.LessThanOrEqual(left, right)       => BoolExpr.LessThanOrEqual(normalizeIntExpr(left), normalizeIntExpr(right))
    case BoolExpr.GreaterThan(left, right)           => BoolExpr.GreaterThan(normalizeIntExpr(left), normalizeIntExpr(right))
    case BoolExpr.GreaterThanOrEqual(left, right)    => BoolExpr.GreaterThanOrEqual(normalizeIntExpr(left), normalizeIntExpr(right))
    case BoolExpr.Equal(left, right)                 => BoolExpr.Equal(normalizeIntExpr(left), normalizeIntExpr(right))
    case BoolExpr.NotEqual(left, right)              => BoolExpr.NotEqual(normalizeIntExpr(left), normalizeIntExpr(right))
    case BoolExpr.IsPow2(value)                      => BoolExpr.IsPow2(normalizeIntExpr(value))
    case BoolExpr.Not(value)                         => BoolExpr.Not(normalizeBoolExpr(value))
    case BoolExpr.And(left, right)                   => BoolExpr.And(normalizeBoolExpr(left), normalizeBoolExpr(right))
    case BoolExpr.Or(left, right)                    => BoolExpr.Or(normalizeBoolExpr(left), normalizeBoolExpr(right))
  }

  private def normalizeRtlExpr(expression: RtlExpr): RtlExpr = expression match {
    case value @ RtlExpr.Ref(_, _, _, _)        => value
    case value @ RtlExpr.Literal(_, _, _)       => value
    case RtlExpr.Unary(operator, value)         =>
      RtlExpr.Unary(operator, normalizeRtlExpr(value))
    case RtlExpr.Binary(operator, left, right)  =>
      RtlExpr.Binary(operator, normalizeRtlExpr(left), normalizeRtlExpr(right))
    case RtlExpr.Mux(condition, yes, no)        =>
      RtlExpr.Mux(normalizeRtlExpr(condition), normalizeRtlExpr(yes), normalizeRtlExpr(no))
    case RtlExpr.Concat(values)                 =>
      RtlExpr.Concat(values.map(normalizeRtlExpr))
    case RtlExpr.BitSelect(value, index)        =>
      RtlExpr.BitSelect(normalizeRtlExpr(value), normalizeRtlExpr(index))
    case RtlExpr.PartSelect(value, offset, width) =>
      RtlExpr.PartSelect(normalizeRtlExpr(value), normalizeIntExpr(offset), normalizeIntExpr(width))
    case RtlExpr.Resize(value, width, signedness) =>
      RtlExpr.Resize(normalizeRtlExpr(value), normalizeIntExpr(width), signedness)
    case RtlExpr.Cast(value, signedness)        =>
      RtlExpr.Cast(normalizeRtlExpr(value), signedness)
  }

  private def attributeKey(
      value: IrAttribute
  ): (String, String, Int, String, String, Int, Int) = {
    val location = locationKey(value.sourceLocation)
    val attributeValue = value.value match {
      case None       => (0, "")
      case Some(text) => (1, Option(text).getOrElse(""))
    }
    (
      value.kind.label,
      value.name,
      attributeValue._1,
      attributeValue._2,
      location._1,
      location._2,
      location._3
    )
  }

  private def commentKey(value: IrComment): (String, Int, Int, String) = {
    val location = locationKey(value.sourceLocation)
    (location._1, location._2, location._3, value.text)
  }

  private def locationKey(
      value: Option[SourceLocation]
  ): (String, Int, Int) = value match {
    case Some(location) => (location.path, location.line, location.column)
    case None           => ("", 0, 0)
  }
}
