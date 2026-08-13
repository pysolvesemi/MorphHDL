package morphhdl.frontend

import morphhdl.paramrtl.RtlExpr.{IndexedPartSelect, Ref}
import morphhdl.paramrtl._

/** Guarded parameter-aware lowering used by the MorphVerilog orchestration path. */
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

  def generateIf(condition: HdlBool)(whenTrue: => Unit)(implicit
      file: sourcecode.File,
      line: sourcecode.Line
  ): GenerateIfBuilder =
    FrontendSession.startGenerateIf(condition, None, whenTrue, SourceOrigin.capture)

  def generateIf(
      condition: HdlBool,
      whenTrueLabel: String,
      whenFalseLabel: String
  )(whenTrue: => Unit)(implicit
      file: sourcecode.File,
      line: sourcecode.Line
  ): GenerateIfBuilder = {
    val origin = SourceOrigin.capture
    HdlRange.requireIdentifier(whenTrueLabel, "generate-if true label", origin)
    HdlRange.requireIdentifier(whenFalseLabel, "generate-if false label", origin)
    FrontendSession.startGenerateIf(
      condition,
      Some(GenerateIfNames(whenTrueLabel, whenFalseLabel)),
      whenTrue,
      origin
    )
  }

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

  def booleanParameter(value: HdlBool)(implicit
      file: sourcecode.File,
      line: sourcecode.Line
  ): FrontendNode[BooleanParameter] = {
    val token = value.declaration.getOrElse {
      FrontendException.fail(
        "MORPH-FRONTEND-NOT-A-BOOLEAN-PARAMETER",
        "only HdlBool.param values can declare public Boolean parameters"
      )
    }
    FrontendNode(
      token.declaration,
      booleanParameters = Set(token),
      origin = SourceOrigin.capture
    )
  }

  /** Creates one identity-bearing module-local integer parameter handle. */
  def localParam(name: String, value: HdlInt)(implicit
      file: sourcecode.File,
      line: sourcecode.Line
  ): HdlInt = {
    val origin = SourceOrigin.capture
    value.requireLoopInvariant(s"local parameter '$name'")
    requireIdentifier(name, "local parameter", origin)
    HdlInt.local(name, value, origin)
  }

  /** Converts the exact handle returned by localParam into a declaration node. */
  def integerLocalParameter(value: HdlInt)(implicit
      file: sourcecode.File,
      line: sourcecode.Line
  ): FrontendNode[IntegerLocalParameter] = {
    value.requireLoopInvariant("integer-local-parameter declaration")
    val token = value.localDeclaration.getOrElse {
      FrontendException.fail(
        "MORPH-FRONTEND-NOT-A-LOCAL-PARAMETER",
        "only the exact HdlInt returned by localParam can declare a module-local parameter"
      )
    }
    FrontendNode(
      token.declaration,
      parameters = token.parameters,
      booleanParameters = token.booleanParameters,
      localParameters = token.dependencies + token,
      localDeclaration = Some(token),
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
      booleanParameters = width.booleanParameters,
      localParameters = width.localParameters,
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
      booleanParameters = dataType.booleanParameters,
      localParameters = dataType.localParameters,
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
      booleanParameters = value.booleanParameters,
      localParameters = value.localParameters,
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
      booleanParameters = offset.booleanParameters ++ width.booleanParameters,
      localParameters = offset.localParameters ++ width.localParameters,
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
      booleanParameters = actual.booleanParameters,
      localParameters = actual.localParameters,
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
        booleanParameters = value.booleanParameters,
        localParameters = value.localParameters,
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
        booleanParameters = parameterBindings.flatMap(_.booleanParameters).toSet ++
          portConnections.flatMap(_.booleanParameters),
        localParameters = parameterBindings.flatMap(_.localParameters).toSet ++
          portConnections.flatMap(_.localParameters),
        origin = SourceOrigin.capture
      )
    )
  }

  def moduleDef(
      name: String,
      parameters: Vector[FrontendNode[IntegerParameter]],
      ports: Vector[FrontendNode[Port]],
      items: FrontendNode[Vector[ModuleItem]],
      localParameters: Vector[FrontendNode[IntegerLocalParameter]] = Vector.empty,
      booleanParameters: Vector[FrontendNode[BooleanParameter]] = Vector.empty
  )(implicit file: sourcecode.File, line: sourcecode.Line): ModuleDef = {
    val origin = SourceOrigin.capture
    parameters.foreach(_.requireUsable(s"module '$name' parameter declaration"))
    booleanParameters.foreach(_.requireUsable(s"module '$name' Boolean-parameter declaration"))
    localParameters.foreach(_.requireUsable(s"module '$name' local-parameter declaration"))
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

    val boolDeclarations = booleanParameters.map { node =>
      node.booleanParameters.toVector match {
        case Vector(token) if token.declaration == node.raw => token
        case _ =>
          FrontendException.failAt(
            "MORPH-FRONTEND-NOT-A-BOOLEAN-PARAMETER",
            s"module '$name' received a declaration not produced by booleanParameter",
            node.origin
          )
      }
    }
    boolDeclarations
      .groupBy(_.declaration.name)
      .collect { case (parameterName, values) if values.size > 1 => parameterName }
      .toVector
      .sorted
      .headOption
      .foreach { duplicate =>
        FrontendException.failAt(
          "MORPH-FRONTEND-BOOLEAN-PARAMETER-NAME-DUPLICATE",
          s"module '$name' declares Boolean parameter '$duplicate' more than once",
          origin
        )
      }

    val integerParameterNames = declarations.map(_.declaration.name).toSet
    val booleanParameterNames = boolDeclarations.map(_.declaration.name).toSet
    integerParameterNames.intersect(booleanParameterNames).toVector.sorted.headOption.foreach {
      collision =>
        val token = boolDeclarations.find(_.declaration.name == collision).get
        FrontendException.failAt(
          "MORPH-FRONTEND-PARAMETER-KIND-COLLISION",
          s"module '$name' declares '$collision' as both integer and Boolean",
          token.origin
        )
    }

    val localDeclarations = localParameters.map { node =>
      node.localDeclaration match {
        case Some(token) if token.declaration == node.raw => token
        case _ =>
          FrontendException.failAt(
            "MORPH-FRONTEND-LOCAL-PARAMETER-IDENTITY-UNRESOLVED",
            s"module '$name' received a local declaration not produced by integerLocalParameter",
            node.origin
          )
      }
    }
    localDeclarations
      .groupBy(identity)
      .values
      .filter(_.size > 1)
      .flatten
      .toVector
      .sortBy(token => (token.declaration.name, token.origin.file, token.origin.line))
      .headOption
      .foreach { duplicate =>
        FrontendException.failAt(
          "MORPH-FRONTEND-LOCAL-PARAMETER-DECLARATION-DUPLICATE",
          s"module '$name' declares the same local parameter handle " +
            s"'${duplicate.declaration.name}' more than once",
          duplicate.origin
        )
      }
    localDeclarations
      .groupBy(_.declaration.name)
      .toVector
      .sortBy(_._1)
      .collectFirst { case (_, values) if values.size > 1 =>
        values.sortBy(token => (token.origin.file, token.origin.line)).tail.head
      }
      .foreach { duplicate =>
        FrontendException.failAt(
          "MORPH-FRONTEND-LOCAL-PARAMETER-NAME-DUPLICATE",
          s"module '$name' declares local parameter '${duplicate.declaration.name}' more than once",
          duplicate.origin
        )
      }

    val publicNames = integerParameterNames ++ booleanParameterNames
    localDeclarations
      .filter(token => publicNames(token.declaration.name))
      .sortBy(token => (token.declaration.name, token.origin.file, token.origin.line))
      .headOption
      .foreach { collision =>
        FrontendException.failAt(
          "MORPH-FRONTEND-LOCAL-PARAMETER-NAME-COLLISION",
          s"module '$name' declares '${collision.declaration.name}' as both a public and local parameter",
          collision.origin
        )
      }
    LocalParameterToken.requireUnclaimed(localDeclarations)

    val declaredByName = declarations.map(token => token.declaration.name -> token).toMap
    val boolDeclaredByName = boolDeclarations.map(token => token.declaration.name -> token).toMap
    val used = ports.flatMap(_.parameters).toSet ++ items.parameters ++
      localParameters.flatMap(_.parameters)
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
            if (boolDeclaredByName.contains(token.declaration.name)) {
              FrontendException.failAt(
                "MORPH-FRONTEND-PARAMETER-KIND-MISMATCH",
                s"module '$name' uses '${token.declaration.name}' as integer but declares it as Boolean",
                token.origin
              )
            } else {
              FrontendException.failAt(
                "MORPH-FRONTEND-PARAMETER-NOT-DECLARED",
                s"module '$name' uses public parameter '${token.declaration.name}' without declaring it",
                token.origin
              )
            }
        }
      }

    val usedBooleans = ports.flatMap(_.booleanParameters).toSet ++
      items.booleanParameters ++ localParameters.flatMap(_.booleanParameters)
    usedBooleans.toVector
      .sortBy(token => (token.declaration.name, token.origin.file, token.origin.line))
      .foreach { token =>
        boolDeclaredByName.get(token.declaration.name) match {
          case Some(declared) if declared eq token =>
          case Some(declared) =>
            FrontendException.failAt(
              "MORPH-FRONTEND-BOOLEAN-PARAMETER-TOKEN-MISMATCH",
              s"module '$name' declares Boolean '${declared.declaration.name}' from " +
                s"${declared.origin.rendered} but uses a distinct declaration from ${token.origin.rendered}",
              token.origin
            )
          case None =>
            if (declaredByName.contains(token.declaration.name)) {
              FrontendException.failAt(
                "MORPH-FRONTEND-PARAMETER-KIND-MISMATCH",
                s"module '$name' uses '${token.declaration.name}' as Boolean but declares it as integer",
                token.origin
              )
            } else {
              FrontendException.failAt(
                "MORPH-FRONTEND-BOOLEAN-PARAMETER-NOT-DECLARED",
                s"module '$name' uses Boolean parameter '${token.declaration.name}' without declaring it",
                token.origin
              )
            }
        }
      }

    val localDeclaredByName = localDeclarations.map(token => token.declaration.name -> token).toMap
    val usedLocals = ports.flatMap(_.localParameters).toSet ++ items.localParameters ++
      localParameters.flatMap(_.localParameters)
    LocalParameterToken.requireUnclaimed(usedLocals.toVector)
    usedLocals.toVector
      .sortBy(token => (token.declaration.name, token.origin.file, token.origin.line))
      .foreach { token =>
        localDeclaredByName.get(token.declaration.name) match {
          case Some(declared) if declared eq token =>
          case Some(declared) =>
            FrontendException.failAt(
              "MORPH-FRONTEND-LOCAL-PARAMETER-TOKEN-MISMATCH",
              s"module '$name' declares local '${declared.declaration.name}' from " +
                s"${declared.origin.rendered} but uses a distinct declaration from ${token.origin.rendered}",
              token.origin
            )
          case None =>
            FrontendException.failAt(
              "MORPH-FRONTEND-LOCAL-PARAMETER-NOT-DECLARED",
              s"module '$name' uses local parameter '${token.declaration.name}' without declaring it",
              token.origin
            )
        }
      }

    val orderedLocalDeclarations = dependencyFirst(localDeclarations, name)
    LocalParameterToken.claimAll(
      orderedLocalDeclarations,
      new LocalParameterOwner(name, origin)
    )

    ModuleDef(
      name = name,
      parameters = parameters.map(_.raw),
      ports = ports.map(_.raw),
      items = items.raw,
      localParameters = orderedLocalDeclarations.map(_.declaration),
      booleanParameters = booleanParameters.map(_.raw)
    )
  }

  private def dependencyFirst(
      declarations: Vector[LocalParameterToken],
      moduleName: String
  ): Vector[LocalParameterToken] = {
    var remaining = declarations.map(token => token -> token.dependencies).toMap
    val result = Vector.newBuilder[LocalParameterToken]

    while (remaining.nonEmpty) {
      val ready = remaining.iterator
        .collect { case (token, dependencies) if dependencies.forall(!remaining.contains(_)) => token }
        .toVector
        .sortBy(token => (token.declaration.name, token.origin.file, token.origin.line))

      if (ready.isEmpty) {
        val first = remaining.keys.toVector
          .sortBy(token => (token.declaration.name, token.origin.file, token.origin.line))
          .head
        FrontendException.failAt(
          "MORPH-FRONTEND-LOCAL-PARAMETER-CYCLE",
          s"module '$moduleName' has a cycle involving local parameter '${first.declaration.name}'",
          first.origin
        )
      }

      ready.foreach(result += _)
      val emitted = ready.toSet
      remaining = remaining.iterator
        .collect { case (token, dependencies) if !emitted(token) => token -> (dependencies -- emitted) }
        .toMap
    }

    result.result()
  }

  private val Identifier = "[A-Za-z_][A-Za-z0-9_]*".r

  private def requireIdentifier(value: String, role: String, origin: SourceOrigin): Unit =
    if (!Identifier.pattern.matcher(value).matches()) {
      FrontendException.failAt(
        "MORPH-FRONTEND-INVALID-LOCAL-PARAMETER-NAME",
        s"$role '$value' is not a portable identifier",
        origin
      )
    }
}
