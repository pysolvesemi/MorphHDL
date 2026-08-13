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

  /** Creates one identity-bearing module-local Boolean parameter handle. */
  def localParam(name: String, value: HdlBool)(implicit
      file: sourcecode.File,
      line: sourcecode.Line
  ): HdlBool = {
    val origin = SourceOrigin.capture
    if (value eq null) {
      FrontendException.failAt(
        "MORPH-FRONTEND-BOOLEAN-LOCAL-PARAMETER-VALUE-NULL",
        s"Boolean local parameter '$name' must not have a null value",
        origin
      )
    }
    requireIdentifier(name, "Boolean local parameter", origin)
    HdlBool.local(name, value, origin)
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
      booleanLocalParameters = token.booleanDependencies,
      localDeclaration = Some(token),
      origin = SourceOrigin.capture
    )
  }

  /** Converts the exact handle returned by the Boolean localParam overload into a declaration. */
  def booleanLocalParameter(value: HdlBool)(implicit
      file: sourcecode.File,
      line: sourcecode.Line
  ): FrontendNode[BooleanLocalParameter] = {
    val token = value.localDeclaration.getOrElse {
      FrontendException.fail(
        "MORPH-FRONTEND-NOT-A-BOOLEAN-LOCAL-PARAMETER",
        "only the exact HdlBool returned by localParam can declare a module-local Boolean parameter"
      )
    }
    FrontendNode(
      token.declaration,
      parameters = token.parameters,
      booleanParameters = token.booleanParameters,
      localParameters = token.integerDependencies,
      booleanLocalParameters = token.dependencies + token,
      booleanLocalDeclaration = Some(token),
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
      booleanLocalParameters = width.booleanLocalParameters,
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
      booleanLocalParameters = dataType.booleanLocalParameters,
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
      booleanLocalParameters = value.booleanLocalParameters,
      origin = SourceOrigin.capture
    )
  }

  /**
    * Binds a child Boolean parameter without leaving the symbolic Boolean domain.
    *
    * Integer, Boolean and local-parameter identities used by comparisons are
    * deliberately retained until the enclosing module discharges them.
    */
  def parameterBinding(parameterName: String, value: HdlBool)(implicit
      file: sourcecode.File,
      line: sourcecode.Line
  ): FrontendNode[BooleanParameterBinding] = {
    val origin = SourceOrigin.capture
    if (value eq null) {
      FrontendException.failAt(
        "MORPH-FRONTEND-BOOLEAN-PARAMETER-BINDING-NULL",
        s"Boolean parameter binding '$parameterName' must not be null",
        origin
      )
    }
    FrontendNode(
      BooleanParameterBinding(parameterName, value.expression),
      parameters = value.integerParameters,
      booleanParameters = value.parameters,
      localParameters = value.localParameters,
      booleanLocalParameters = value.booleanLocalParameters,
      origin = origin
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
      booleanLocalParameters = offset.booleanLocalParameters ++ width.booleanLocalParameters,
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
      booleanLocalParameters = actual.booleanLocalParameters,
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
        booleanLocalParameters = value.booleanLocalParameters,
        origin = SourceOrigin.capture
      )
    )
  }

  def emitInstance(
      name: String,
      moduleName: String,
      parameterBindings: Vector[FrontendNode[ParameterBinding]] = Vector.empty,
      portConnections: Vector[FrontendNode[PortConnection]] = Vector.empty,
      booleanParameterBindings: Vector[FrontendNode[BooleanParameterBinding]] = Vector.empty
  )(implicit file: sourcecode.File, line: sourcecode.Line): Unit = {
    parameterBindings.foreach(_.requireUsable(s"module instance '$name' parameter binding"))
    portConnections.foreach(_.requireUsable(s"module instance '$name' port connection"))
    booleanParameterBindings.foreach(
      _.requireUsable(s"module instance '$name' Boolean-parameter binding")
    )
    FrontendSession.emit(
      FrontendNode(
        ModuleItem.ModuleInstance(
          name,
          moduleName,
          parameterBindings.map(_.raw),
          portConnections.map(_.raw),
          booleanParameterBindings.map(_.raw)
        ),
        parameters = parameterBindings.flatMap(_.parameters).toSet ++
          portConnections.flatMap(_.parameters) ++
          booleanParameterBindings.flatMap(_.parameters),
        booleanParameters = parameterBindings.flatMap(_.booleanParameters).toSet ++
          portConnections.flatMap(_.booleanParameters) ++
          booleanParameterBindings.flatMap(_.booleanParameters),
        localParameters = parameterBindings.flatMap(_.localParameters).toSet ++
          portConnections.flatMap(_.localParameters) ++
          booleanParameterBindings.flatMap(_.localParameters),
        booleanLocalParameters = parameterBindings.flatMap(_.booleanLocalParameters).toSet ++
          portConnections.flatMap(_.booleanLocalParameters) ++
          booleanParameterBindings.flatMap(_.booleanLocalParameters),
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
      booleanParameters: Vector[FrontendNode[BooleanParameter]] = Vector.empty,
      booleanLocalParameters: Vector[FrontendNode[BooleanLocalParameter]] = Vector.empty
  )(implicit file: sourcecode.File, line: sourcecode.Line): ModuleDef = {
    val origin = SourceOrigin.capture
    parameters.foreach(_.requireUsable(s"module '$name' parameter declaration"))
    booleanParameters.foreach(_.requireUsable(s"module '$name' Boolean-parameter declaration"))
    localParameters.foreach(_.requireUsable(s"module '$name' local-parameter declaration"))
    booleanLocalParameters.foreach(
      _.requireUsable(s"module '$name' Boolean-local-parameter declaration")
    )
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

    val booleanLocalDeclarations = booleanLocalParameters.map { node =>
      node.booleanLocalDeclaration match {
        case Some(token) if token.declaration == node.raw => token
        case _ =>
          FrontendException.failAt(
            "MORPH-FRONTEND-BOOLEAN-LOCAL-PARAMETER-IDENTITY-UNRESOLVED",
            s"module '$name' received a Boolean local declaration not produced by booleanLocalParameter",
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

    booleanLocalDeclarations
      .groupBy(identity)
      .values
      .filter(_.size > 1)
      .flatten
      .toVector
      .sortBy(token => (token.declaration.name, token.origin.file, token.origin.line))
      .headOption
      .foreach { duplicate =>
        FrontendException.failAt(
          "MORPH-FRONTEND-BOOLEAN-LOCAL-PARAMETER-DECLARATION-DUPLICATE",
          s"module '$name' declares the same Boolean local parameter handle " +
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

    booleanLocalDeclarations
      .groupBy(_.declaration.name)
      .toVector
      .sortBy(_._1)
      .collectFirst { case (_, values) if values.size > 1 =>
        values.sortBy(token => (token.origin.file, token.origin.line)).tail.head
      }
      .foreach { duplicate =>
        FrontendException.failAt(
          "MORPH-FRONTEND-BOOLEAN-LOCAL-PARAMETER-NAME-DUPLICATE",
          s"module '$name' declares Boolean local parameter " +
            s"'${duplicate.declaration.name}' more than once",
          duplicate.origin
        )
      }

    val integerLocalNames = localDeclarations.map(_.declaration.name).toSet
    val booleanLocalNames = booleanLocalDeclarations.map(_.declaration.name).toSet
    integerLocalNames.intersect(booleanLocalNames).toVector.sorted.headOption.foreach { collision =>
      val token = booleanLocalDeclarations.find(_.declaration.name == collision).get
      FrontendException.failAt(
        "MORPH-FRONTEND-LOCAL-PARAMETER-KIND-COLLISION",
        s"module '$name' declares local '$collision' as both integer and Boolean",
        token.origin
      )
    }

    val publicNames = integerParameterNames ++ booleanParameterNames
    val allLocalDeclarations: Vector[ModuleLocalParameterToken] =
      localDeclarations.map(token => token: ModuleLocalParameterToken) ++
        booleanLocalDeclarations.map(token => token: ModuleLocalParameterToken)
    allLocalDeclarations
      .filter(token => publicNames(token.parameterName))
      .sortBy(token => (token.parameterName, token.origin.file, token.origin.line))
      .headOption
      .foreach { collision =>
        FrontendException.failAt(
          "MORPH-FRONTEND-LOCAL-PARAMETER-NAME-COLLISION",
          s"module '$name' declares '${collision.parameterName}' as both a public and local parameter",
          collision.origin
        )
      }
    ModuleLocalParameterToken.requireUnclaimed(allLocalDeclarations)

    val declaredByName = declarations.map(token => token.declaration.name -> token).toMap
    val boolDeclaredByName = boolDeclarations.map(token => token.declaration.name -> token).toMap
    val used = ports.flatMap(_.parameters).toSet ++ items.parameters ++
      localParameters.flatMap(_.parameters) ++ booleanLocalParameters.flatMap(_.parameters)
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
      items.booleanParameters ++ localParameters.flatMap(_.booleanParameters) ++
      booleanLocalParameters.flatMap(_.booleanParameters)
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
    val booleanLocalDeclaredByName =
      booleanLocalDeclarations.map(token => token.declaration.name -> token).toMap
    val usedLocals = ports.flatMap(_.localParameters).toSet ++ items.localParameters ++
      localParameters.flatMap(_.localParameters) ++
      booleanLocalParameters.flatMap(_.localParameters)
    val usedBooleanLocals = ports.flatMap(_.booleanLocalParameters).toSet ++
      items.booleanLocalParameters ++ localParameters.flatMap(_.booleanLocalParameters) ++
      booleanLocalParameters.flatMap(_.booleanLocalParameters)
    val usedLocalIdentities: Vector[ModuleLocalParameterToken] =
      usedLocals.toVector.map(token => token: ModuleLocalParameterToken) ++
        usedBooleanLocals.toVector.map(token => token: ModuleLocalParameterToken)
    ModuleLocalParameterToken.requireUnclaimed(usedLocalIdentities)
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
            if (booleanLocalDeclaredByName.contains(token.declaration.name)) {
              FrontendException.failAt(
                "MORPH-FRONTEND-LOCAL-PARAMETER-KIND-MISMATCH",
                s"module '$name' uses local '${token.declaration.name}' as integer but declares it as Boolean",
                token.origin
              )
            } else {
              FrontendException.failAt(
                "MORPH-FRONTEND-LOCAL-PARAMETER-NOT-DECLARED",
                s"module '$name' uses local parameter '${token.declaration.name}' without declaring it",
                token.origin
              )
            }
        }
      }

    usedBooleanLocals.toVector
      .sortBy(token => (token.declaration.name, token.origin.file, token.origin.line))
      .foreach { token =>
        booleanLocalDeclaredByName.get(token.declaration.name) match {
          case Some(declared) if declared eq token =>
          case Some(declared) =>
            FrontendException.failAt(
              "MORPH-FRONTEND-BOOLEAN-LOCAL-PARAMETER-TOKEN-MISMATCH",
              s"module '$name' declares Boolean local '${declared.declaration.name}' from " +
                s"${declared.origin.rendered} but uses a distinct declaration from ${token.origin.rendered}",
              token.origin
            )
          case None =>
            if (localDeclaredByName.contains(token.declaration.name)) {
              FrontendException.failAt(
                "MORPH-FRONTEND-LOCAL-PARAMETER-KIND-MISMATCH",
                s"module '$name' uses local '${token.declaration.name}' as Boolean but declares it as integer",
                token.origin
              )
            } else {
              FrontendException.failAt(
                "MORPH-FRONTEND-BOOLEAN-LOCAL-PARAMETER-NOT-DECLARED",
                s"module '$name' uses Boolean local parameter " +
                  s"'${token.declaration.name}' without declaring it",
                token.origin
              )
            }
        }
      }

    val orderedDeclarations = dependencyFirst(allLocalDeclarations, name)
    ModuleLocalParameterToken.claimAll(
      orderedDeclarations,
      new LocalParameterOwner(name, origin)
    )
    val orderedLocalDeclarations = orderedDeclarations.collect { case token: LocalParameterToken => token }
    val orderedBooleanLocalDeclarations =
      orderedDeclarations.collect { case token: BooleanLocalParameterToken => token }

    ModuleDef(
      name = name,
      parameters = parameters.map(_.raw),
      ports = ports.map(_.raw),
      items = items.raw,
      localParameters = orderedLocalDeclarations.map(_.declaration),
      booleanParameters = booleanParameters.map(_.raw),
      booleanLocalParameters = orderedBooleanLocalDeclarations.map(_.declaration)
    )
  }

  private def dependencyFirst(
      declarations: Vector[ModuleLocalParameterToken],
      moduleName: String
  ): Vector[ModuleLocalParameterToken] = {
    var remaining = declarations.map(token => token -> token.allDependencies).toMap
    val result = Vector.newBuilder[ModuleLocalParameterToken]

    while (remaining.nonEmpty) {
      val ready = remaining.iterator
        .collect { case (token, dependencies) if dependencies.forall(!remaining.contains(_)) => token }
        .toVector
        .sortBy(token => (token.parameterName, token.origin.file, token.origin.line))

      if (ready.isEmpty) {
        val first = remaining.keys.toVector
          .sortBy(token => (token.parameterName, token.origin.file, token.origin.line))
          .head
        FrontendException.failAt(
          "MORPH-FRONTEND-LOCAL-PARAMETER-CYCLE",
          s"module '$moduleName' has a cycle involving local parameter '${first.parameterName}'",
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
