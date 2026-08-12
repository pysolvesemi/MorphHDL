package morphhdl.frontend

import morphhdl.paramrtl.RtlExpr.{IndexedPartSelect, Ref}
import morphhdl.paramrtl._

/**
  * Guarded Increment 6 lowering used by MorphHDL integration fixtures.
  *
  * This is package-scoped until Increment 7 introduces the supported
  * MorphVerilog orchestration entry point.
  */
private[morphhdl] object ParamRtlFrontend {
  def concrete[A](body: => A)(implicit
      file: sourcecode.File,
      line: sourcecode.Line
  ): A = FrontendSession.concrete(body)

  def captureItems(body: => Unit)(implicit
      file: sourcecode.File,
      line: sourcecode.Line
  ): FrontendNode[Vector[ModuleItem]] =
    FrontendSession.captureItems(body, SourceOrigin.capture)

  def integerParameter(value: HdlInt)(implicit
      file: sourcecode.File,
      line: sourcecode.Line
  ): FrontendNode[IntegerParameter] = {
    value.requireLoopInvariant("integer-parameter declaration")
    val token = value.declaration.getOrElse {
      FrontendException.fail(
        "MORPH-FRONTEND-NOT-A-PUBLIC-PARAMETER",
        "only HdlInt.param values can declare public integer parameters"
      )
    }
    FrontendNode(
      token.declaration,
      parameters = Set(token),
      origin = SourceOrigin.capture
    )
  }

  def packedBits(
      width: HdlInt,
      signedness: Signedness = Signedness.Unsigned
  )(implicit file: sourcecode.File, line: sourcecode.Line): FrontendNode[PackedBits] = {
    width.requireLoopInvariant("packed-width construction")
    FrontendNode(
      PackedBits(width.expression, signedness),
      parameters = width.parameters,
      origin = SourceOrigin.capture
    )
  }

  def port(
      name: String,
      direction: PortDirection,
      dataType: FrontendNode[PackedBits]
  )(implicit file: sourcecode.File, line: sourcecode.Line): FrontendNode[Port] = {
    dataType.requireUsable(s"port '$name' data type")
    FrontendNode(
      Port(name, direction, dataType.raw),
      parameters = dataType.parameters,
      scopes = dataType.scopes,
      origin = SourceOrigin.capture
    )
  }

  def parameterBinding(parameterName: String, value: HdlInt)(implicit
      file: sourcecode.File,
      line: sourcecode.Line
  ): FrontendNode[ParameterBinding] = {
    value.requireLoopInvariant(s"parameter binding '$parameterName'")
    FrontendNode(
      ParameterBinding(parameterName, value.expression),
      parameters = value.parameters,
      origin = SourceOrigin.capture
    )
  }

  def ref(name: String)(implicit
      file: sourcecode.File,
      line: sourcecode.Line
  ): FrontendNode[RtlExpr] =
    FrontendNode(Ref(name), origin = SourceOrigin.capture)

  def indexedPartSelect(base: String, offset: HdlInt, width: HdlInt)(implicit
      file: sourcecode.File,
      line: sourcecode.Line
  ): FrontendNode[RtlExpr] = {
    offset.requireUsable(s"indexed part-select '$base' offset")
    width.requireLoopInvariant(s"indexed part-select '$base' width")
    FrontendNode(
      IndexedPartSelect(Ref(base), offset.expression, width.expression),
      parameters = offset.parameters ++ width.parameters,
      scopes = offset.scope.toSet,
      origin = SourceOrigin.capture
    )
  }

  def portConnection(portName: String, actual: FrontendNode[RtlExpr])(implicit
      file: sourcecode.File,
      line: sourcecode.Line
  ): FrontendNode[PortConnection] = {
    actual.requireUsable(s"port connection '$portName'")
    FrontendNode(
      PortConnection(portName, actual.raw),
      parameters = actual.parameters,
      scopes = actual.scopes,
      origin = SourceOrigin.capture
    )
  }

  def emitContinuousAssign(target: String, value: FrontendNode[RtlExpr])(implicit
      file: sourcecode.File,
      line: sourcecode.Line
  ): Unit = {
    value.requireUsable(s"continuous assignment '$target'")
    FrontendSession.emit(
      FrontendNode(
        ModuleItem.ContinuousAssign(Ref(target), value.raw),
        parameters = value.parameters,
        origin = SourceOrigin.capture
      )
    )
  }

  def emitInstance(
      name: String,
      moduleName: String,
      parameterBindings: Vector[FrontendNode[ParameterBinding]] = Vector.empty,
      portConnections: Vector[FrontendNode[PortConnection]] = Vector.empty
  )(implicit file: sourcecode.File, line: sourcecode.Line): Unit = {
    parameterBindings.foreach(_.requireUsable(s"module instance '$name' parameter binding"))
    portConnections.foreach(_.requireUsable(s"module instance '$name' port connection"))
    FrontendSession.emit(
      FrontendNode(
        ModuleItem.ModuleInstance(
          name,
          moduleName,
          parameterBindings.map(_.raw),
          portConnections.map(_.raw)
        ),
        parameters = parameterBindings.flatMap(_.parameters).toSet ++
          portConnections.flatMap(_.parameters),
        origin = SourceOrigin.capture
      )
    )
  }

  def moduleDef(
      name: String,
      parameters: Vector[FrontendNode[IntegerParameter]],
      ports: Vector[FrontendNode[Port]],
      items: FrontendNode[Vector[ModuleItem]]
  )(implicit file: sourcecode.File, line: sourcecode.Line): ModuleDef = {
    val origin = SourceOrigin.capture
    parameters.foreach(_.requireUsable(s"module '$name' parameter declaration"))
    ports.foreach(_.requireUsable(s"module '$name' port"))
    items.requireUsable(s"module '$name' items")

    val declarations = parameters.map { node =>
      node.parameters.toVector match {
        case Vector(token) if token.declaration == node.raw => token
        case _ =>
          FrontendException.failAt(
            "MORPH-FRONTEND-NOT-A-PUBLIC-PARAMETER",
            s"module '$name' received a declaration not produced by integerParameter",
            node.origin
          )
      }
    }
    declarations
      .groupBy(_.declaration.name)
      .collect { case (parameterName, values) if values.size > 1 => parameterName }
      .toVector
      .sorted
      .headOption
      .foreach { duplicate =>
        FrontendException.failAt(
          "MORPH-FRONTEND-PARAMETER-NAME-DUPLICATE",
          s"module '$name' declares public parameter '$duplicate' more than once",
          origin
        )
      }

    val declaredByName = declarations.map(token => token.declaration.name -> token).toMap
    val used = ports.flatMap(_.parameters).toSet ++ items.parameters
    used.toVector
      .sortBy(token => (token.declaration.name, token.origin.file, token.origin.line))
      .foreach { token =>
        declaredByName.get(token.declaration.name) match {
          case Some(declared) if declared eq token =>
          case Some(declared) =>
            FrontendException.failAt(
              "MORPH-FRONTEND-PARAMETER-TOKEN-MISMATCH",
              s"module '$name' declares '${declared.declaration.name}' from ${declared.origin.rendered} " +
                s"but uses a distinct declaration from ${token.origin.rendered}",
              token.origin
            )
          case None =>
            FrontendException.failAt(
              "MORPH-FRONTEND-PARAMETER-NOT-DECLARED",
              s"module '$name' uses public parameter '${token.declaration.name}' without declaring it",
              token.origin
            )
        }
      }

    ModuleDef(
      name = name,
      parameters = parameters.map(_.raw),
      ports = ports.map(_.raw),
      items = items.raw
    )
  }
}
