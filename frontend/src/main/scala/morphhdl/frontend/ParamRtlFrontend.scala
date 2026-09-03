package morphhdl.frontend

import morphhdl.paramrtl.RtlExpr.{IndexedPartSelect, Ref}
import morphhdl.paramrtl._

/** Guarded atomic lowering retained for dual-factory compatibility and mutation oracles. */
@deprecated(
  "ParamRTL frontend lowering is compatibility/mutation-only; use typed native APIs",
  "Increment 58"
)
private[morphhdl] object ParamRtlFrontend {
  private val PortableIdentifier = "[A-Za-z_][A-Za-z0-9_]*".r

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

  /**
    * Starts one generate-case whose literal choices and mandatory default are
    * supplied through the returned builder.
    */
  def generateCase(selector: HdlInt)(implicit
      file: sourcecode.File,
      line: sourcecode.Line
  ): GenerateCaseBuilder =
    FrontendSession.startGenerateCase(selector, SourceOrigin.capture)

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
        "MORPH-FRONTEND-BOOLEAN-LOCAL-PARAMETER-NULL",
        s"Boolean local parameter '$name' must not be null",
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
      localParameters = token.dependencies.collect { case value: LocalParameterToken => value } + token,
      booleanLocalParameters = token.dependencies.collect {
        case value: BooleanLocalParameterToken => value
      },
      localDeclaration = Some(token),
      origin = SourceOrigin.capture
    )
  }

  /** Converts the exact Boolean handle returned by localParam into a declaration node. */
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
      localParameters = token.dependencies.collect { case item: LocalParameterToken => item },
      booleanLocalParameters = token.dependencies.collect {
        case item: BooleanLocalParameterToken => item
      } + token,
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

  /**
    * Builds one direct ref-only procedural assignment intent. Its enclosing
    * process determines blocking versus nonblocking legalization.
    */
  def proceduralAssign(target: String, value: FrontendNode[RtlExpr])(implicit
      file: sourcecode.File,
      line: sourcecode.Line
  ): FrontendNode[ProceduralAssign] = {
    val origin = SourceOrigin.capture
    requirePortableIdentifier(
      target,
      "combinational-process assignment target",
      "MORPH-FRONTEND-COMBINATIONAL-TARGET-INVALID",
      origin
    )
    if (value eq null) {
      FrontendException.failAt(
        "MORPH-FRONTEND-COMBINATIONAL-VALUE-NULL",
        s"runtime-process assignment '$target' requires a non-null value reference",
        origin
      )
    }
    value.requireUsable(s"runtime-process assignment '$target'")
    val valueRef = requireRef(
      value,
      "runtime-process assignment value",
      "MORPH-FRONTEND-COMBINATIONAL-VALUE-NOT-REF",
      origin
    )
    requirePortableIdentifier(
      valueRef.name,
      "runtime-process assignment value",
      "MORPH-FRONTEND-COMBINATIONAL-VALUE-INVALID",
      value.origin
    )
    FrontendNode(
      ProceduralAssign(Ref(target), valueRef),
      parameters = value.parameters,
      booleanParameters = value.booleanParameters,
      localParameters = value.localParameters,
      booleanLocalParameters = value.booleanLocalParameters,
      scopes = value.scopes,
      origin = origin
    )
  }

  /**
    * Atomically emits one named runtime combinational if/else process.
    *
    * ParamRTL remains responsible for complete-driver, duplicate-target and
    * port-direction validation across the two mandatory branch vectors.
    */
  def emitCombinationalIf(
      label: String,
      condition: FrontendNode[RtlExpr],
      whenTrue: Vector[FrontendNode[ProceduralAssign]],
      whenFalse: Vector[FrontendNode[ProceduralAssign]]
  )(implicit file: sourcecode.File, line: sourcecode.Line): Unit = {
    val origin = SourceOrigin.capture
    requirePortableIdentifier(
      label,
      "combinational-process label",
      "MORPH-FRONTEND-COMBINATIONAL-LABEL-INVALID",
      origin
    )
    if (condition eq null) {
      FrontendException.failAt(
        "MORPH-FRONTEND-COMBINATIONAL-CONDITION-NULL",
        s"combinational process '$label' requires a non-null condition reference",
        origin
      )
    }
    condition.requireUsable(s"combinational process '$label' condition")
    val conditionRef = requireRef(
      condition,
      "combinational-process condition",
      "MORPH-FRONTEND-COMBINATIONAL-CONDITION-NOT-REF",
      origin
    )
    requirePortableIdentifier(
      conditionRef.name,
      "combinational-process condition",
      "MORPH-FRONTEND-COMBINATIONAL-CONDITION-INVALID",
      condition.origin
    )
    val trueAssignments = requireAssignments(label, "true", whenTrue, origin)
    val falseAssignments = requireAssignments(label, "false", whenFalse, origin)
    val assignments = whenTrue ++ whenFalse

    FrontendSession.emitCombinationalIf(
      FrontendNode(
        ModuleItem.CombinationalIf(
          label,
          conditionRef,
          trueAssignments,
          falseAssignments
        ),
        parameters = condition.parameters ++ assignments.flatMap(_.parameters),
        booleanParameters = condition.booleanParameters ++
          assignments.flatMap(_.booleanParameters),
        localParameters = condition.localParameters ++ assignments.flatMap(_.localParameters),
        booleanLocalParameters = condition.booleanLocalParameters ++
          assignments.flatMap(_.booleanLocalParameters),
        scopes = condition.scopes ++ assignments.flatMap(_.scopes),
        origin = origin
      )
    )
  }

  /**
    * Atomically emits one posedge register with active-high synchronous
    * reset-to-zero semantics. The assignment supplies the registered output
    * target and direct data-input reference.
    */
  def emitSynchronousRegister(
      label: String,
      clock: FrontendNode[RtlExpr],
      reset: FrontendNode[RtlExpr],
      assignment: FrontendNode[ProceduralAssign]
  )(implicit file: sourcecode.File, line: sourcecode.Line): Unit = {
    val origin = SourceOrigin.capture
    requirePortableIdentifier(
      label,
      "synchronous-register label",
      "MORPH-FRONTEND-SYNCHRONOUS-REGISTER-LABEL-INVALID",
      origin
    )
    val clockRef = requireSynchronousRegisterRef(
      label,
      "clock",
      clock,
      "MORPH-FRONTEND-SYNCHRONOUS-REGISTER-CLOCK-NULL",
      "MORPH-FRONTEND-SYNCHRONOUS-REGISTER-CLOCK-NOT-REF",
      "MORPH-FRONTEND-SYNCHRONOUS-REGISTER-CLOCK-INVALID",
      origin
    )
    val resetRef = requireSynchronousRegisterRef(
      label,
      "reset",
      reset,
      "MORPH-FRONTEND-SYNCHRONOUS-REGISTER-RESET-NULL",
      "MORPH-FRONTEND-SYNCHRONOUS-REGISTER-RESET-NOT-REF",
      "MORPH-FRONTEND-SYNCHRONOUS-REGISTER-RESET-INVALID",
      origin
    )
    if (assignment eq null) {
      FrontendException.failAt(
        "MORPH-FRONTEND-SYNCHRONOUS-REGISTER-ASSIGNMENT-NULL",
        s"synchronous register '$label' requires one non-null data assignment",
        origin
      )
    }
    assignment.requireUsable(s"synchronous register '$label' assignment")
    requirePortableIdentifier(
      assignment.raw.target.name,
      "synchronous-register assignment target",
      "MORPH-FRONTEND-SYNCHRONOUS-REGISTER-TARGET-INVALID",
      assignment.origin
    )
    requirePortableIdentifier(
      assignment.raw.value.name,
      "synchronous-register assignment value",
      "MORPH-FRONTEND-SYNCHRONOUS-REGISTER-VALUE-INVALID",
      assignment.origin
    )

    FrontendSession.emitSynchronousRegister(
      FrontendNode(
        ModuleItem.SynchronousRegister(label, clockRef, resetRef, assignment.raw),
        parameters = clock.parameters ++ reset.parameters ++ assignment.parameters,
        booleanParameters = clock.booleanParameters ++ reset.booleanParameters ++
          assignment.booleanParameters,
        localParameters = clock.localParameters ++ reset.localParameters ++
          assignment.localParameters,
        booleanLocalParameters = clock.booleanLocalParameters ++
          reset.booleanLocalParameters ++ assignment.booleanLocalParameters,
        scopes = clock.scopes ++ reset.scopes ++ assignment.scopes,
        origin = origin
      )
    )
  }

  /**
    * Atomically emits one posedge register with active-high asynchronous
    * reset-to-zero semantics. The assignment supplies the registered output
    * target and direct data-input reference.
    */
  def emitAsynchronousRegister(
      label: String,
      clock: FrontendNode[RtlExpr],
      reset: FrontendNode[RtlExpr],
      assignment: FrontendNode[ProceduralAssign]
  )(implicit file: sourcecode.File, line: sourcecode.Line): Unit = {
    val origin = SourceOrigin.capture
    requirePortableIdentifier(
      label,
      "asynchronous-register label",
      "MORPH-FRONTEND-ASYNCHRONOUS-REGISTER-LABEL-INVALID",
      origin
    )
    val clockRef = requireAsynchronousRegisterRef(
      label,
      "clock",
      clock,
      "MORPH-FRONTEND-ASYNCHRONOUS-REGISTER-CLOCK-NULL",
      "MORPH-FRONTEND-ASYNCHRONOUS-REGISTER-CLOCK-NOT-REF",
      "MORPH-FRONTEND-ASYNCHRONOUS-REGISTER-CLOCK-INVALID",
      origin
    )
    val resetRef = requireAsynchronousRegisterRef(
      label,
      "reset",
      reset,
      "MORPH-FRONTEND-ASYNCHRONOUS-REGISTER-RESET-NULL",
      "MORPH-FRONTEND-ASYNCHRONOUS-REGISTER-RESET-NOT-REF",
      "MORPH-FRONTEND-ASYNCHRONOUS-REGISTER-RESET-INVALID",
      origin
    )
    if (assignment eq null) {
      FrontendException.failAt(
        "MORPH-FRONTEND-ASYNCHRONOUS-REGISTER-ASSIGNMENT-NULL",
        s"asynchronous-reset register '$label' requires one non-null data assignment",
        origin
      )
    }
    assignment.requireUsable(s"asynchronous-reset register '$label' assignment")
    requirePortableIdentifier(
      assignment.raw.target.name,
      "asynchronous-register assignment target",
      "MORPH-FRONTEND-ASYNCHRONOUS-REGISTER-TARGET-INVALID",
      assignment.origin
    )
    requirePortableIdentifier(
      assignment.raw.value.name,
      "asynchronous-register assignment value",
      "MORPH-FRONTEND-ASYNCHRONOUS-REGISTER-VALUE-INVALID",
      assignment.origin
    )

    FrontendSession.emitAsynchronousRegister(
      FrontendNode(
        ModuleItem.AsynchronousRegister(label, clockRef, resetRef, assignment.raw),
        parameters = clock.parameters ++ reset.parameters ++ assignment.parameters,
        booleanParameters = clock.booleanParameters ++ reset.booleanParameters ++
          assignment.booleanParameters,
        localParameters = clock.localParameters ++ reset.localParameters ++
          assignment.localParameters,
        booleanLocalParameters = clock.booleanLocalParameters ++
          reset.booleanLocalParameters ++ assignment.booleanLocalParameters,
        scopes = clock.scopes ++ reset.scopes ++ assignment.scopes,
        origin = origin
      )
    )
  }

  /**
    * Atomically emits one posedge register with active-high synchronous
    * reset-to-zero and active-high clock-enable semantics. The assignment
    * supplies the registered output target and direct data-input reference;
    * when enable is low the register retains its previous value.
    */
  def emitSynchronousEnabledRegister(
      label: String,
      clock: FrontendNode[RtlExpr],
      reset: FrontendNode[RtlExpr],
      enable: FrontendNode[RtlExpr],
      assignment: FrontendNode[ProceduralAssign]
  )(implicit file: sourcecode.File, line: sourcecode.Line): Unit = {
    val origin = SourceOrigin.capture
    requirePortableIdentifier(
      label,
      "synchronous-enabled-register label",
      "MORPH-FRONTEND-SYNCHRONOUS-ENABLED-REGISTER-LABEL-INVALID",
      origin
    )
    val clockRef = requireSynchronousEnabledRegisterRef(
      label,
      "clock",
      clock,
      "MORPH-FRONTEND-SYNCHRONOUS-ENABLED-REGISTER-CLOCK-NULL",
      "MORPH-FRONTEND-SYNCHRONOUS-ENABLED-REGISTER-CLOCK-NOT-REF",
      "MORPH-FRONTEND-SYNCHRONOUS-ENABLED-REGISTER-CLOCK-INVALID",
      origin
    )
    val resetRef = requireSynchronousEnabledRegisterRef(
      label,
      "reset",
      reset,
      "MORPH-FRONTEND-SYNCHRONOUS-ENABLED-REGISTER-RESET-NULL",
      "MORPH-FRONTEND-SYNCHRONOUS-ENABLED-REGISTER-RESET-NOT-REF",
      "MORPH-FRONTEND-SYNCHRONOUS-ENABLED-REGISTER-RESET-INVALID",
      origin
    )
    val enableRef = requireSynchronousEnabledRegisterRef(
      label,
      "enable",
      enable,
      "MORPH-FRONTEND-SYNCHRONOUS-ENABLED-REGISTER-ENABLE-NULL",
      "MORPH-FRONTEND-SYNCHRONOUS-ENABLED-REGISTER-ENABLE-NOT-REF",
      "MORPH-FRONTEND-SYNCHRONOUS-ENABLED-REGISTER-ENABLE-INVALID",
      origin
    )
    if (assignment eq null) {
      FrontendException.failAt(
        "MORPH-FRONTEND-SYNCHRONOUS-ENABLED-REGISTER-ASSIGNMENT-NULL",
        s"synchronous enabled register '$label' requires one non-null data assignment",
        origin
      )
    }
    assignment.requireUsable(s"synchronous enabled register '$label' assignment")
    requirePortableIdentifier(
      assignment.raw.target.name,
      "synchronous-enabled-register assignment target",
      "MORPH-FRONTEND-SYNCHRONOUS-ENABLED-REGISTER-TARGET-INVALID",
      assignment.origin
    )
    requirePortableIdentifier(
      assignment.raw.value.name,
      "synchronous-enabled-register assignment value",
      "MORPH-FRONTEND-SYNCHRONOUS-ENABLED-REGISTER-VALUE-INVALID",
      assignment.origin
    )

    FrontendSession.emitSynchronousEnabledRegister(
      FrontendNode(
        ModuleItem.SynchronousEnabledRegister(
          label,
          clockRef,
          resetRef,
          enableRef,
          assignment.raw
        ),
        parameters = clock.parameters ++ reset.parameters ++ enable.parameters ++
          assignment.parameters,
        booleanParameters = clock.booleanParameters ++ reset.booleanParameters ++
          enable.booleanParameters ++ assignment.booleanParameters,
        localParameters = clock.localParameters ++ reset.localParameters ++
          enable.localParameters ++ assignment.localParameters,
        booleanLocalParameters = clock.booleanLocalParameters ++
          reset.booleanLocalParameters ++ enable.booleanLocalParameters ++
          assignment.booleanLocalParameters,
        scopes = clock.scopes ++ reset.scopes ++ enable.scopes ++ assignment.scopes,
        origin = origin
      )
    )
  }

  /**
    * Atomically emits one posedge register with active-high asynchronous
    * reset-to-zero and active-high clock-enable semantics. Reset takes
    * priority over enable. The assignment supplies the registered output
    * target and direct data-input reference; when enable is low the register
    * retains its previous value.
    */
  def emitAsynchronousEnabledRegister(
      label: String,
      clock: FrontendNode[RtlExpr],
      reset: FrontendNode[RtlExpr],
      enable: FrontendNode[RtlExpr],
      assignment: FrontendNode[ProceduralAssign]
  )(implicit file: sourcecode.File, line: sourcecode.Line): Unit = {
    val origin = SourceOrigin.capture
    requirePortableIdentifier(
      label,
      "asynchronous-enabled-register label",
      "MORPH-FRONTEND-ASYNCHRONOUS-ENABLED-REGISTER-LABEL-INVALID",
      origin
    )
    val clockRef = requireAsynchronousEnabledRegisterRef(
      label,
      "clock",
      clock,
      "MORPH-FRONTEND-ASYNCHRONOUS-ENABLED-REGISTER-CLOCK-NULL",
      "MORPH-FRONTEND-ASYNCHRONOUS-ENABLED-REGISTER-CLOCK-NOT-REF",
      "MORPH-FRONTEND-ASYNCHRONOUS-ENABLED-REGISTER-CLOCK-INVALID",
      origin
    )
    val resetRef = requireAsynchronousEnabledRegisterRef(
      label,
      "reset",
      reset,
      "MORPH-FRONTEND-ASYNCHRONOUS-ENABLED-REGISTER-RESET-NULL",
      "MORPH-FRONTEND-ASYNCHRONOUS-ENABLED-REGISTER-RESET-NOT-REF",
      "MORPH-FRONTEND-ASYNCHRONOUS-ENABLED-REGISTER-RESET-INVALID",
      origin
    )
    val enableRef = requireAsynchronousEnabledRegisterRef(
      label,
      "enable",
      enable,
      "MORPH-FRONTEND-ASYNCHRONOUS-ENABLED-REGISTER-ENABLE-NULL",
      "MORPH-FRONTEND-ASYNCHRONOUS-ENABLED-REGISTER-ENABLE-NOT-REF",
      "MORPH-FRONTEND-ASYNCHRONOUS-ENABLED-REGISTER-ENABLE-INVALID",
      origin
    )
    if (assignment eq null) {
      FrontendException.failAt(
        "MORPH-FRONTEND-ASYNCHRONOUS-ENABLED-REGISTER-ASSIGNMENT-NULL",
        s"asynchronous-reset enabled register '$label' requires one non-null data assignment",
        origin
      )
    }
    assignment.requireUsable(
      s"asynchronous-reset enabled register '$label' assignment"
    )
    requirePortableIdentifier(
      assignment.raw.target.name,
      "asynchronous-enabled-register assignment target",
      "MORPH-FRONTEND-ASYNCHRONOUS-ENABLED-REGISTER-TARGET-INVALID",
      assignment.origin
    )
    requirePortableIdentifier(
      assignment.raw.value.name,
      "asynchronous-enabled-register assignment value",
      "MORPH-FRONTEND-ASYNCHRONOUS-ENABLED-REGISTER-VALUE-INVALID",
      assignment.origin
    )

    FrontendSession.emitAsynchronousEnabledRegister(
      FrontendNode(
        ModuleItem.AsynchronousEnabledRegister(
          label,
          clockRef,
          resetRef,
          enableRef,
          assignment.raw
        ),
        parameters = clock.parameters ++ reset.parameters ++ enable.parameters ++
          assignment.parameters,
        booleanParameters = clock.booleanParameters ++ reset.booleanParameters ++
          enable.booleanParameters ++ assignment.booleanParameters,
        localParameters = clock.localParameters ++ reset.localParameters ++
          enable.localParameters ++ assignment.localParameters,
        booleanLocalParameters = clock.booleanLocalParameters ++
          reset.booleanLocalParameters ++ enable.booleanLocalParameters ++
          assignment.booleanLocalParameters,
        scopes = clock.scopes ++ reset.scopes ++ enable.scopes ++ assignment.scopes,
        origin = origin
      )
    )
  }

  /**
    * Atomically emits one positive-edge single-port memory with a one-cycle
    * synchronous read. A same-address read/write collision returns the old
    * element value. Addresses outside the configured depth read as zero and
    * suppress writes. Read enable low holds the read output, while writes
    * remain independently controlled. Reset, initialization and write masks
    * are deliberately outside this bounded frontend surface.
    */
  def emitSynchronousReadFirstSinglePortMemory(
      label: String,
      memoryName: String,
      clock: FrontendNode[RtlExpr],
      readEnable: FrontendNode[RtlExpr],
      writeEnable: FrontendNode[RtlExpr],
      address: FrontendNode[RtlExpr],
      writeData: FrontendNode[RtlExpr],
      readData: FrontendNode[RtlExpr],
      elementType: FrontendNode[PackedBits],
      depth: HdlInt
  )(implicit file: sourcecode.File, line: sourcecode.Line): Unit = {
    val origin = SourceOrigin.capture
    requirePortableIdentifier(
      label,
      "synchronous read-first single-port memory label",
      "MORPH-FRONTEND-SINGLE-PORT-MEMORY-LABEL-INVALID",
      origin
    )
    requirePortableIdentifier(
      memoryName,
      "synchronous read-first single-port memory name",
      "MORPH-FRONTEND-SINGLE-PORT-MEMORY-NAME-INVALID",
      origin
    )
    val clockRef = requireSynchronousReadFirstSinglePortMemoryRef(
      label,
      "clock",
      clock,
      "MORPH-FRONTEND-SINGLE-PORT-MEMORY-CLOCK-NULL",
      "MORPH-FRONTEND-SINGLE-PORT-MEMORY-CLOCK-NOT-REF",
      "MORPH-FRONTEND-SINGLE-PORT-MEMORY-CLOCK-INVALID",
      origin
    )
    val readEnableRef = requireSynchronousReadFirstSinglePortMemoryRef(
      label,
      "read-enable",
      readEnable,
      "MORPH-FRONTEND-SINGLE-PORT-MEMORY-READ-ENABLE-NULL",
      "MORPH-FRONTEND-SINGLE-PORT-MEMORY-READ-ENABLE-NOT-REF",
      "MORPH-FRONTEND-SINGLE-PORT-MEMORY-READ-ENABLE-INVALID",
      origin
    )
    val writeEnableRef = requireSynchronousReadFirstSinglePortMemoryRef(
      label,
      "write-enable",
      writeEnable,
      "MORPH-FRONTEND-SINGLE-PORT-MEMORY-WRITE-ENABLE-NULL",
      "MORPH-FRONTEND-SINGLE-PORT-MEMORY-WRITE-ENABLE-NOT-REF",
      "MORPH-FRONTEND-SINGLE-PORT-MEMORY-WRITE-ENABLE-INVALID",
      origin
    )
    val addressRef = requireSynchronousReadFirstSinglePortMemoryRef(
      label,
      "address",
      address,
      "MORPH-FRONTEND-SINGLE-PORT-MEMORY-ADDRESS-NULL",
      "MORPH-FRONTEND-SINGLE-PORT-MEMORY-ADDRESS-NOT-REF",
      "MORPH-FRONTEND-SINGLE-PORT-MEMORY-ADDRESS-INVALID",
      origin
    )
    val writeDataRef = requireSynchronousReadFirstSinglePortMemoryRef(
      label,
      "write-data",
      writeData,
      "MORPH-FRONTEND-SINGLE-PORT-MEMORY-WRITE-DATA-NULL",
      "MORPH-FRONTEND-SINGLE-PORT-MEMORY-WRITE-DATA-NOT-REF",
      "MORPH-FRONTEND-SINGLE-PORT-MEMORY-WRITE-DATA-INVALID",
      origin
    )
    val readDataRef = requireSynchronousReadFirstSinglePortMemoryRef(
      label,
      "read-data",
      readData,
      "MORPH-FRONTEND-SINGLE-PORT-MEMORY-READ-DATA-NULL",
      "MORPH-FRONTEND-SINGLE-PORT-MEMORY-READ-DATA-NOT-REF",
      "MORPH-FRONTEND-SINGLE-PORT-MEMORY-READ-DATA-INVALID",
      origin
    )
    if (elementType eq null) {
      FrontendException.failAt(
        "MORPH-FRONTEND-SINGLE-PORT-MEMORY-ELEMENT-TYPE-NULL",
        s"synchronous read-first single-port memory '$label' requires a non-null element type",
        origin
      )
    }
    elementType.requireUsable(
      s"synchronous read-first single-port memory '$label' element type"
    )
    if (depth eq null) {
      FrontendException.failAt(
        "MORPH-FRONTEND-SINGLE-PORT-MEMORY-DEPTH-NULL",
        s"synchronous read-first single-port memory '$label' requires a non-null depth",
        origin
      )
    }
    depth.requireLoopInvariant(
      s"synchronous read-first single-port memory '$label' depth"
    )

    val refs = Vector(clock, readEnable, writeEnable, address, writeData, readData)
    FrontendSession.emitSynchronousReadFirstSinglePortMemory(
      FrontendNode(
        ModuleItem.SynchronousReadFirstSinglePortMemory(
          label,
          memoryName,
          clockRef,
          readEnableRef,
          writeEnableRef,
          addressRef,
          writeDataRef,
          readDataRef,
          elementType.raw,
          depth.expression
        ),
        parameters = refs.flatMap(_.parameters).toSet ++ elementType.parameters ++
          depth.parameters,
        booleanParameters = refs.flatMap(_.booleanParameters).toSet ++
          elementType.booleanParameters ++ depth.booleanParameters,
        localParameters = refs.flatMap(_.localParameters).toSet ++
          elementType.localParameters ++ depth.localParameters,
        booleanLocalParameters = refs.flatMap(_.booleanLocalParameters).toSet ++
          elementType.booleanLocalParameters ++ depth.booleanLocalParameters,
        scopes = refs.flatMap(_.scopes).toSet ++ elementType.scopes,
        origin = origin
      )
    )
  }

  /**
    * Atomically emits one positive-edge simple dual-port memory with an
    * independently enabled synchronous read port and write port. Same-address
    * collisions are read-first: the read output captures the pre-edge element
    * while the write stores the new element. Surplus reads return zero and
    * surplus writes are suppressed. Read enable low holds the read output.
    */
  def emitSynchronousReadFirstSimpleDualPortMemory(
      label: String,
      memoryName: String,
      clock: FrontendNode[RtlExpr],
      readEnable: FrontendNode[RtlExpr],
      writeEnable: FrontendNode[RtlExpr],
      readAddress: FrontendNode[RtlExpr],
      writeAddress: FrontendNode[RtlExpr],
      writeData: FrontendNode[RtlExpr],
      readData: FrontendNode[RtlExpr],
      elementType: FrontendNode[PackedBits],
      depth: HdlInt
  )(implicit file: sourcecode.File, line: sourcecode.Line): Unit = {
    val origin = SourceOrigin.capture
    requirePortableIdentifier(
      label,
      "synchronous read-first simple dual-port memory label",
      "MORPH-FRONTEND-SIMPLE-DUAL-PORT-MEMORY-LABEL-INVALID",
      origin
    )
    requirePortableIdentifier(
      memoryName,
      "synchronous read-first simple dual-port memory name",
      "MORPH-FRONTEND-SIMPLE-DUAL-PORT-MEMORY-NAME-INVALID",
      origin
    )
    val clockRef = requireSynchronousReadFirstSimpleDualPortMemoryRef(
      label,
      "clock",
      clock,
      "MORPH-FRONTEND-SIMPLE-DUAL-PORT-MEMORY-CLOCK-NULL",
      "MORPH-FRONTEND-SIMPLE-DUAL-PORT-MEMORY-CLOCK-NOT-REF",
      "MORPH-FRONTEND-SIMPLE-DUAL-PORT-MEMORY-CLOCK-INVALID",
      origin
    )
    val readEnableRef = requireSynchronousReadFirstSimpleDualPortMemoryRef(
      label,
      "read-enable",
      readEnable,
      "MORPH-FRONTEND-SIMPLE-DUAL-PORT-MEMORY-READ-ENABLE-NULL",
      "MORPH-FRONTEND-SIMPLE-DUAL-PORT-MEMORY-READ-ENABLE-NOT-REF",
      "MORPH-FRONTEND-SIMPLE-DUAL-PORT-MEMORY-READ-ENABLE-INVALID",
      origin
    )
    val writeEnableRef = requireSynchronousReadFirstSimpleDualPortMemoryRef(
      label,
      "write-enable",
      writeEnable,
      "MORPH-FRONTEND-SIMPLE-DUAL-PORT-MEMORY-WRITE-ENABLE-NULL",
      "MORPH-FRONTEND-SIMPLE-DUAL-PORT-MEMORY-WRITE-ENABLE-NOT-REF",
      "MORPH-FRONTEND-SIMPLE-DUAL-PORT-MEMORY-WRITE-ENABLE-INVALID",
      origin
    )
    val readAddressRef = requireSynchronousReadFirstSimpleDualPortMemoryRef(
      label,
      "read-address",
      readAddress,
      "MORPH-FRONTEND-SIMPLE-DUAL-PORT-MEMORY-READ-ADDRESS-NULL",
      "MORPH-FRONTEND-SIMPLE-DUAL-PORT-MEMORY-READ-ADDRESS-NOT-REF",
      "MORPH-FRONTEND-SIMPLE-DUAL-PORT-MEMORY-READ-ADDRESS-INVALID",
      origin
    )
    val writeAddressRef = requireSynchronousReadFirstSimpleDualPortMemoryRef(
      label,
      "write-address",
      writeAddress,
      "MORPH-FRONTEND-SIMPLE-DUAL-PORT-MEMORY-WRITE-ADDRESS-NULL",
      "MORPH-FRONTEND-SIMPLE-DUAL-PORT-MEMORY-WRITE-ADDRESS-NOT-REF",
      "MORPH-FRONTEND-SIMPLE-DUAL-PORT-MEMORY-WRITE-ADDRESS-INVALID",
      origin
    )
    val writeDataRef = requireSynchronousReadFirstSimpleDualPortMemoryRef(
      label,
      "write-data",
      writeData,
      "MORPH-FRONTEND-SIMPLE-DUAL-PORT-MEMORY-WRITE-DATA-NULL",
      "MORPH-FRONTEND-SIMPLE-DUAL-PORT-MEMORY-WRITE-DATA-NOT-REF",
      "MORPH-FRONTEND-SIMPLE-DUAL-PORT-MEMORY-WRITE-DATA-INVALID",
      origin
    )
    val readDataRef = requireSynchronousReadFirstSimpleDualPortMemoryRef(
      label,
      "read-data",
      readData,
      "MORPH-FRONTEND-SIMPLE-DUAL-PORT-MEMORY-READ-DATA-NULL",
      "MORPH-FRONTEND-SIMPLE-DUAL-PORT-MEMORY-READ-DATA-NOT-REF",
      "MORPH-FRONTEND-SIMPLE-DUAL-PORT-MEMORY-READ-DATA-INVALID",
      origin
    )
    if (elementType eq null) {
      FrontendException.failAt(
        "MORPH-FRONTEND-SIMPLE-DUAL-PORT-MEMORY-ELEMENT-TYPE-NULL",
        s"synchronous read-first simple dual-port memory '$label' requires a non-null element type",
        origin
      )
    }
    elementType.requireUsable(
      s"synchronous read-first simple dual-port memory '$label' element type"
    )
    if (depth eq null) {
      FrontendException.failAt(
        "MORPH-FRONTEND-SIMPLE-DUAL-PORT-MEMORY-DEPTH-NULL",
        s"synchronous read-first simple dual-port memory '$label' requires a non-null depth",
        origin
      )
    }
    depth.requireLoopInvariant(
      s"synchronous read-first simple dual-port memory '$label' depth"
    )

    val refs = Vector(
      clock,
      readEnable,
      writeEnable,
      readAddress,
      writeAddress,
      writeData,
      readData
    )
    FrontendSession.emitSynchronousReadFirstSimpleDualPortMemory(
      FrontendNode(
        ModuleItem.SynchronousReadFirstSimpleDualPortMemory(
          label,
          memoryName,
          clockRef,
          readEnableRef,
          writeEnableRef,
          readAddressRef,
          writeAddressRef,
          writeDataRef,
          readDataRef,
          elementType.raw,
          depth.expression
        ),
        parameters = refs.flatMap(_.parameters).toSet ++ elementType.parameters ++
          depth.parameters,
        booleanParameters = refs.flatMap(_.booleanParameters).toSet ++
          elementType.booleanParameters ++ depth.booleanParameters,
        localParameters = refs.flatMap(_.localParameters).toSet ++
          elementType.localParameters ++ depth.localParameters,
        booleanLocalParameters = refs.flatMap(_.booleanLocalParameters).toSet ++
          elementType.booleanLocalParameters ++ depth.booleanLocalParameters,
        scopes = refs.flatMap(_.scopes).toSet ++ elementType.scopes ++ depth.scope.toSet,
        origin = origin
      )
    )
  }

  /**
    * Atomically emits one bounded single-clock ready/valid FIFO backed by a synchronous-read
    * memory and a registered pop stage. Public depth is the total externally observable
    * capacity. Empty pushes are not bypassed and a pop from a full FIFO does not accept a push
    * on the same edge. Reset is active-high and synchronous.
    */
  def emitSynchronousStreamFifo(
      label: String,
      memoryName: String,
      clock: FrontendNode[RtlExpr],
      reset: FrontendNode[RtlExpr],
      pushValid: FrontendNode[RtlExpr],
      pushReady: FrontendNode[RtlExpr],
      pushData: FrontendNode[RtlExpr],
      popValid: FrontendNode[RtlExpr],
      popReady: FrontendNode[RtlExpr],
      popData: FrontendNode[RtlExpr],
      elementType: FrontendNode[PackedBits],
      depth: HdlInt
  )(implicit file: sourcecode.File, line: sourcecode.Line): Unit = {
    val origin = SourceOrigin.capture
    val prefix = "MORPH-FRONTEND-SYNCHRONOUS-STREAM-FIFO"
    requirePortableIdentifier(
      label,
      "synchronous stream FIFO label",
      s"$prefix-LABEL-INVALID",
      origin
    )
    requirePortableIdentifier(
      memoryName,
      "synchronous stream FIFO memory name",
      s"$prefix-NAME-INVALID",
      origin
    )

    val clockRef = requireSynchronousStreamFifoRef(label, "clock", "CLOCK", clock, origin)
    val resetRef = requireSynchronousStreamFifoRef(label, "reset", "RESET", reset, origin)
    val pushValidRef =
      requireSynchronousStreamFifoRef(label, "push-valid", "PUSH-VALID", pushValid, origin)
    val pushReadyRef =
      requireSynchronousStreamFifoRef(label, "push-ready", "PUSH-READY", pushReady, origin)
    val pushDataRef =
      requireSynchronousStreamFifoRef(label, "push-data", "PUSH-DATA", pushData, origin)
    val popValidRef =
      requireSynchronousStreamFifoRef(label, "pop-valid", "POP-VALID", popValid, origin)
    val popReadyRef =
      requireSynchronousStreamFifoRef(label, "pop-ready", "POP-READY", popReady, origin)
    val popDataRef =
      requireSynchronousStreamFifoRef(label, "pop-data", "POP-DATA", popData, origin)

    if (elementType eq null) {
      FrontendException.failAt(
        s"$prefix-ELEMENT-TYPE-NULL",
        s"synchronous stream FIFO '$label' requires a non-null element type",
        origin
      )
    }
    elementType.requireUsable(s"synchronous stream FIFO '$label' element type")
    if (depth eq null) {
      FrontendException.failAt(
        s"$prefix-DEPTH-NULL",
        s"synchronous stream FIFO '$label' requires a non-null depth",
        origin
      )
    }
    depth.requireLoopInvariant(s"synchronous stream FIFO '$label' depth")
    val directPublicParameter = depth.declaration.exists { token =>
      depth.expression == IntExpr.ParameterRef(token.declaration.name) &&
      depth.parameters == Set(token) &&
      depth.booleanParameters.isEmpty &&
      depth.localDeclaration.isEmpty &&
      depth.localParameters.isEmpty &&
      depth.booleanLocalParameters.isEmpty &&
      depth.scope.isEmpty
    }
    if (!directPublicParameter) {
      FrontendException.failAt(
        s"$prefix-DEPTH-NOT-PUBLIC-PARAMETER",
        s"synchronous stream FIFO '$label' depth must be the exact unmodified HdlInt.param handle",
        depth.origin
      )
    }
    if (depth.witness <= 0) {
      FrontendException.failAt(
        s"$prefix-DEPTH-WITNESS-NONPOSITIVE",
        s"synchronous stream FIFO '$label' depth witness must be positive, received ${depth.witness}",
        depth.origin
      )
    }

    val refs = Vector(
      clock,
      reset,
      pushValid,
      pushReady,
      pushData,
      popValid,
      popReady,
      popData
    )
    FrontendSession.emitSynchronousStreamFifo(
      FrontendNode(
        ModuleItem.SynchronousStreamFifo(
          label,
          memoryName,
          clockRef,
          resetRef,
          pushValidRef,
          pushReadyRef,
          pushDataRef,
          popValidRef,
          popReadyRef,
          popDataRef,
          elementType.raw,
          depth.expression
        ),
        parameters = refs.flatMap(_.parameters).toSet ++ elementType.parameters ++
          depth.parameters,
        booleanParameters = refs.flatMap(_.booleanParameters).toSet ++
          elementType.booleanParameters ++ depth.booleanParameters,
        localParameters = refs.flatMap(_.localParameters).toSet ++
          elementType.localParameters ++ depth.localParameters,
        booleanLocalParameters = refs.flatMap(_.booleanLocalParameters).toSet ++
          elementType.booleanLocalParameters ++ depth.booleanLocalParameters,
        scopes = refs.flatMap(_.scopes).toSet ++ elementType.scopes ++ depth.scope.toSet,
        origin = origin
      )
    )
  }

  /**
    * Atomically emits one positive-edge one-entry ready/valid pipeline stage matching the
    * default Spinal Stream `m2sPipe` contract. The stage accepts a replacement on the same
    * edge that its current element is consumed, holds valid payload while stalled, and has
    * one edge of forward latency. Reset is active-high and synchronous and clears only valid.
    */
  def emitSynchronousStreamM2sPipe(
      label: String,
      clock: FrontendNode[RtlExpr],
      reset: FrontendNode[RtlExpr],
      pushValid: FrontendNode[RtlExpr],
      pushReady: FrontendNode[RtlExpr],
      pushData: FrontendNode[RtlExpr],
      popValid: FrontendNode[RtlExpr],
      popReady: FrontendNode[RtlExpr],
      popData: FrontendNode[RtlExpr],
      elementType: FrontendNode[PackedBits]
  )(implicit file: sourcecode.File, line: sourcecode.Line): Unit = {
    val origin = SourceOrigin.capture
    val prefix = "MORPH-FRONTEND-SYNCHRONOUS-STREAM-M2S-PIPE"
    requirePortableIdentifier(
      label,
      "synchronous stream m2s pipe label",
      s"$prefix-LABEL-INVALID",
      origin
    )

    val clockRef =
      requireSynchronousStreamM2sPipeRef(label, "clock", "CLOCK", clock, origin)
    val resetRef =
      requireSynchronousStreamM2sPipeRef(label, "reset", "RESET", reset, origin)
    val pushValidRef =
      requireSynchronousStreamM2sPipeRef(label, "push-valid", "PUSH-VALID", pushValid, origin)
    val pushReadyRef =
      requireSynchronousStreamM2sPipeRef(label, "push-ready", "PUSH-READY", pushReady, origin)
    val pushDataRef =
      requireSynchronousStreamM2sPipeRef(label, "push-data", "PUSH-DATA", pushData, origin)
    val popValidRef =
      requireSynchronousStreamM2sPipeRef(label, "pop-valid", "POP-VALID", popValid, origin)
    val popReadyRef =
      requireSynchronousStreamM2sPipeRef(label, "pop-ready", "POP-READY", popReady, origin)
    val popDataRef =
      requireSynchronousStreamM2sPipeRef(label, "pop-data", "POP-DATA", popData, origin)

    if (elementType eq null) {
      FrontendException.failAt(
        s"$prefix-ELEMENT-TYPE-NULL",
        s"synchronous stream m2s pipe '$label' requires a non-null element type",
        origin
      )
    }
    elementType.requireUsable(s"synchronous stream m2s pipe '$label' element type")

    val refs = Vector(
      clock,
      reset,
      pushValid,
      pushReady,
      pushData,
      popValid,
      popReady,
      popData
    )
    FrontendSession.emitSynchronousStreamM2sPipe(
      FrontendNode(
        ModuleItem.SynchronousStreamM2sPipe(
          label,
          clockRef,
          resetRef,
          pushValidRef,
          pushReadyRef,
          pushDataRef,
          popValidRef,
          popReadyRef,
          popDataRef,
          elementType.raw
        ),
        parameters = refs.flatMap(_.parameters).toSet ++ elementType.parameters,
        booleanParameters = refs.flatMap(_.booleanParameters).toSet ++
          elementType.booleanParameters,
        localParameters = refs.flatMap(_.localParameters).toSet ++ elementType.localParameters,
        booleanLocalParameters = refs.flatMap(_.booleanLocalParameters).toSet ++
          elementType.booleanLocalParameters,
        scopes = refs.flatMap(_.scopes).toSet ++ elementType.scopes,
        origin = origin
      )
    )
  }

  /**
    * Atomically emits one positive-edge up-counter. Reset is active-high and
    * synchronous, has priority over enable, and clears count to zero. Enable
    * is active-high; when low the count holds. An enabled count equal to
    * `limit - 1` wraps to zero, otherwise it increments by one. The limit must
    * be the exact unmodified handle returned by HdlInt.param.
    */
  def emitSynchronousCounter(
      label: String,
      clock: FrontendNode[RtlExpr],
      reset: FrontendNode[RtlExpr],
      enable: FrontendNode[RtlExpr],
      count: FrontendNode[RtlExpr],
      limit: HdlInt
  )(implicit file: sourcecode.File, line: sourcecode.Line): Unit = {
    val origin = SourceOrigin.capture
    requirePortableIdentifier(
      label,
      "synchronous-counter label",
      "MORPH-FRONTEND-SYNCHRONOUS-COUNTER-LABEL-INVALID",
      origin
    )
    val clockRef = requireSynchronousCounterRef(
      label,
      "clock",
      clock,
      "MORPH-FRONTEND-SYNCHRONOUS-COUNTER-CLOCK-NULL",
      "MORPH-FRONTEND-SYNCHRONOUS-COUNTER-CLOCK-NOT-REF",
      "MORPH-FRONTEND-SYNCHRONOUS-COUNTER-CLOCK-INVALID",
      origin
    )
    val resetRef = requireSynchronousCounterRef(
      label,
      "reset",
      reset,
      "MORPH-FRONTEND-SYNCHRONOUS-COUNTER-RESET-NULL",
      "MORPH-FRONTEND-SYNCHRONOUS-COUNTER-RESET-NOT-REF",
      "MORPH-FRONTEND-SYNCHRONOUS-COUNTER-RESET-INVALID",
      origin
    )
    val enableRef = requireSynchronousCounterRef(
      label,
      "enable",
      enable,
      "MORPH-FRONTEND-SYNCHRONOUS-COUNTER-ENABLE-NULL",
      "MORPH-FRONTEND-SYNCHRONOUS-COUNTER-ENABLE-NOT-REF",
      "MORPH-FRONTEND-SYNCHRONOUS-COUNTER-ENABLE-INVALID",
      origin
    )
    val countRef = requireSynchronousCounterRef(
      label,
      "count",
      count,
      "MORPH-FRONTEND-SYNCHRONOUS-COUNTER-COUNT-NULL",
      "MORPH-FRONTEND-SYNCHRONOUS-COUNTER-COUNT-NOT-REF",
      "MORPH-FRONTEND-SYNCHRONOUS-COUNTER-COUNT-INVALID",
      origin
    )
    if (limit eq null) {
      FrontendException.failAt(
        "MORPH-FRONTEND-SYNCHRONOUS-COUNTER-LIMIT-NULL",
        s"synchronous counter '$label' requires a non-null public limit parameter",
        origin
      )
    }
    limit.requireLoopInvariant(s"synchronous counter '$label' limit")
    val directPublicParameter = limit.declaration.exists { token =>
      limit.expression == IntExpr.ParameterRef(token.declaration.name) &&
      limit.parameters == Set(token) &&
      limit.booleanParameters.isEmpty &&
      limit.localDeclaration.isEmpty &&
      limit.localParameters.isEmpty &&
      limit.booleanLocalParameters.isEmpty &&
      limit.scope.isEmpty
    }
    if (!directPublicParameter) {
      FrontendException.failAt(
        "MORPH-FRONTEND-SYNCHRONOUS-COUNTER-LIMIT-NOT-PUBLIC-PARAMETER",
        s"synchronous counter '$label' limit must be the exact unmodified HdlInt.param handle",
        limit.origin
      )
    }
    if (limit.witness <= 0) {
      FrontendException.failAt(
        "MORPH-FRONTEND-SYNCHRONOUS-COUNTER-LIMIT-WITNESS-NONPOSITIVE",
        s"synchronous counter '$label' limit witness must be positive, received ${limit.witness}",
        limit.origin
      )
    }

    val refs = Vector(clock, reset, enable, count)
    FrontendSession.emitSynchronousCounter(
      FrontendNode(
        ModuleItem.SynchronousCounter(
          label,
          clockRef,
          resetRef,
          enableRef,
          countRef,
          limit.expression
        ),
        parameters = refs.flatMap(_.parameters).toSet ++ limit.parameters,
        booleanParameters = refs.flatMap(_.booleanParameters).toSet,
        localParameters = refs.flatMap(_.localParameters).toSet,
        booleanLocalParameters = refs.flatMap(_.booleanLocalParameters).toSet,
        scopes = refs.flatMap(_.scopes).toSet,
        origin = origin
      )
    )
  }

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
      _.requireUsable(s"module '$name' Boolean local-parameter declaration")
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
    val allLocalDeclarations: Vector[LocalParameterIdentity] =
      localDeclarations ++ booleanLocalDeclarations
    allLocalDeclarations
      .filter(token => publicNames(token.name))
      .sortBy(token => (token.name, token.origin.file, token.origin.line))
      .headOption
      .foreach { collision =>
        FrontendException.failAt(
          "MORPH-FRONTEND-LOCAL-PARAMETER-NAME-COLLISION",
          s"module '$name' declares '${collision.name}' as both a public and local parameter",
          collision.origin
        )
      }
    LocalParameterIdentity.requireUnclaimed(allLocalDeclarations)

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
    val usedLocals = ports.flatMap(_.localParameters).toSet ++ items.localParameters ++
      localParameters.flatMap(_.localParameters) ++
      booleanLocalParameters.flatMap(_.localParameters)
    LocalParameterIdentity.requireUnclaimed(usedLocals.toVector)
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
            if (booleanLocalNames(token.declaration.name)) {
              FrontendException.failAt(
                "MORPH-FRONTEND-LOCAL-PARAMETER-KIND-MISMATCH",
                s"module '$name' uses local '${token.declaration.name}' as integer " +
                  "but declares it as Boolean",
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

    val booleanLocalDeclaredByName =
      booleanLocalDeclarations.map(token => token.declaration.name -> token).toMap
    val usedBooleanLocals = ports.flatMap(_.booleanLocalParameters).toSet ++
      items.booleanLocalParameters ++ localParameters.flatMap(_.booleanLocalParameters) ++
      booleanLocalParameters.flatMap(_.booleanLocalParameters)
    LocalParameterIdentity.requireUnclaimed(usedBooleanLocals.toVector)
    usedBooleanLocals.toVector
      .sortBy(token => (token.declaration.name, token.origin.file, token.origin.line))
      .foreach { token =>
        booleanLocalDeclaredByName.get(token.declaration.name) match {
          case Some(declared) if declared eq token =>
          case Some(declared) =>
            FrontendException.failAt(
              "MORPH-FRONTEND-BOOLEAN-LOCAL-PARAMETER-TOKEN-MISMATCH",
              s"module '$name' declares Boolean local '${declared.declaration.name}' from " +
                s"${declared.origin.rendered} but uses a distinct declaration from " +
                token.origin.rendered,
              token.origin
            )
          case None =>
            if (integerLocalNames(token.declaration.name)) {
              FrontendException.failAt(
                "MORPH-FRONTEND-LOCAL-PARAMETER-KIND-MISMATCH",
                s"module '$name' uses local '${token.declaration.name}' as Boolean " +
                  "but declares it as integer",
                token.origin
              )
            } else {
              FrontendException.failAt(
                "MORPH-FRONTEND-BOOLEAN-LOCAL-PARAMETER-NOT-DECLARED",
                s"module '$name' uses Boolean local parameter '${token.declaration.name}' " +
                  "without declaring it",
                token.origin
              )
            }
        }
      }

    val orderedLocalDeclarations = dependencyFirst(allLocalDeclarations, name)
    LocalParameterIdentity.claimAll(
      orderedLocalDeclarations,
      new LocalParameterOwner(name, origin)
    )

    ModuleDef(
      name = name,
      parameters = parameters.map(_.raw),
      ports = ports.map(_.raw),
      items = items.raw,
      localParameters = orderedLocalDeclarations.collect {
        case token: LocalParameterToken => token.declaration
      },
      booleanParameters = booleanParameters.map(_.raw),
      booleanLocalParameters = orderedLocalDeclarations.collect {
        case token: BooleanLocalParameterToken => token.declaration
      }
    )
  }

  private def dependencyFirst(
      declarations: Vector[LocalParameterIdentity],
      moduleName: String
  ): Vector[LocalParameterIdentity] = {
    var remaining = declarations.map(token => token -> token.dependencies).toMap
    val result = Vector.newBuilder[LocalParameterIdentity]

    while (remaining.nonEmpty) {
      val ready = remaining.iterator
        .collect { case (token, dependencies) if dependencies.forall(!remaining.contains(_)) => token }
        .toVector
        .sortBy(token => (token.name, localKindRank(token), token.origin.file, token.origin.line))

      if (ready.isEmpty) {
        val first = remaining.keys.toVector
          .sortBy(token => (token.name, localKindRank(token), token.origin.file, token.origin.line))
          .head
        FrontendException.failAt(
          "MORPH-FRONTEND-LOCAL-PARAMETER-CYCLE",
          s"module '$moduleName' has a cycle involving local parameter '${first.name}'",
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

  private def requireAssignments(
      label: String,
      branch: String,
      assignments: Vector[FrontendNode[ProceduralAssign]],
      origin: SourceOrigin
  ): Vector[ProceduralAssign] = {
    if (assignments eq null) {
      FrontendException.failAt(
        "MORPH-FRONTEND-COMBINATIONAL-BRANCH-NULL",
        s"combinational process '$label' requires a non-null $branch branch vector",
        origin
      )
    }
    assignments.zipWithIndex.map { case (assignment, index) =>
      if (assignment eq null) {
        FrontendException.failAt(
          "MORPH-FRONTEND-COMBINATIONAL-ASSIGNMENT-NULL",
          s"combinational process '$label' $branch branch assignment $index is null",
          origin
        )
      }
      assignment.requireUsable(s"combinational process '$label' $branch branch")
      assignment.raw
    }
  }

  private def requireRef(
      value: FrontendNode[RtlExpr],
      role: String,
      code: String,
      origin: SourceOrigin
  ): Ref =
    value.raw match {
      case reference: Ref => reference
      case _ =>
        FrontendException.failAt(
          code,
          s"$role must be an exact ref(...) value",
          origin
        )
    }

  private def requireSynchronousRegisterRef(
      label: String,
      role: String,
      value: FrontendNode[RtlExpr],
      nullCode: String,
      notRefCode: String,
      invalidCode: String,
      origin: SourceOrigin
  ): Ref = {
    if (value eq null) {
      FrontendException.failAt(
        nullCode,
        s"synchronous register '$label' requires a non-null $role reference",
        origin
      )
    }
    value.requireUsable(s"synchronous register '$label' $role")
    val reference = requireRef(
      value,
      s"synchronous-register $role",
      notRefCode,
      origin
    )
    requirePortableIdentifier(
      reference.name,
      s"synchronous-register $role",
      invalidCode,
      value.origin
    )
    reference
  }

  private def requireAsynchronousRegisterRef(
      label: String,
      role: String,
      value: FrontendNode[RtlExpr],
      nullCode: String,
      notRefCode: String,
      invalidCode: String,
      origin: SourceOrigin
  ): Ref = {
    if (value eq null) {
      FrontendException.failAt(
        nullCode,
        s"asynchronous-reset register '$label' requires a non-null $role reference",
        origin
      )
    }
    value.requireUsable(s"asynchronous-reset register '$label' $role")
    val reference = requireRef(
      value,
      s"asynchronous-register $role",
      notRefCode,
      origin
    )
    requirePortableIdentifier(
      reference.name,
      s"asynchronous-register $role",
      invalidCode,
      value.origin
    )
    reference
  }

  private def requireSynchronousEnabledRegisterRef(
      label: String,
      role: String,
      value: FrontendNode[RtlExpr],
      nullCode: String,
      notRefCode: String,
      invalidCode: String,
      origin: SourceOrigin
  ): Ref = {
    if (value eq null) {
      FrontendException.failAt(
        nullCode,
        s"synchronous enabled register '$label' requires a non-null $role reference",
        origin
      )
    }
    value.requireUsable(s"synchronous enabled register '$label' $role")
    val reference = requireRef(
      value,
      s"synchronous-enabled-register $role",
      notRefCode,
      origin
    )
    requirePortableIdentifier(
      reference.name,
      s"synchronous-enabled-register $role",
      invalidCode,
      value.origin
    )
    reference
  }

  private def requireAsynchronousEnabledRegisterRef(
      label: String,
      role: String,
      value: FrontendNode[RtlExpr],
      nullCode: String,
      notRefCode: String,
      invalidCode: String,
      origin: SourceOrigin
  ): Ref = {
    if (value eq null) {
      FrontendException.failAt(
        nullCode,
        s"asynchronous-reset enabled register '$label' requires a non-null $role reference",
        origin
      )
    }
    value.requireUsable(s"asynchronous-reset enabled register '$label' $role")
    val reference = requireRef(
      value,
      s"asynchronous-enabled-register $role",
      notRefCode,
      origin
    )
    requirePortableIdentifier(
      reference.name,
      s"asynchronous-enabled-register $role",
      invalidCode,
      value.origin
    )
    reference
  }

  private def requireSynchronousReadFirstSinglePortMemoryRef(
      label: String,
      role: String,
      value: FrontendNode[RtlExpr],
      nullCode: String,
      notRefCode: String,
      invalidCode: String,
      origin: SourceOrigin
  ): Ref = {
    if (value eq null) {
      FrontendException.failAt(
        nullCode,
        s"synchronous read-first single-port memory '$label' requires a non-null $role reference",
        origin
      )
    }
    value.requireUsable(
      s"synchronous read-first single-port memory '$label' $role"
    )
    val reference = requireRef(
      value,
      s"synchronous read-first single-port memory $role",
      notRefCode,
      origin
    )
    requirePortableIdentifier(
      reference.name,
      s"synchronous read-first single-port memory $role",
      invalidCode,
      value.origin
    )
    reference
  }

  private def requireSynchronousReadFirstSimpleDualPortMemoryRef(
      label: String,
      role: String,
      value: FrontendNode[RtlExpr],
      nullCode: String,
      notRefCode: String,
      invalidCode: String,
      origin: SourceOrigin
  ): Ref = {
    if (value eq null) {
      FrontendException.failAt(
        nullCode,
        s"synchronous read-first simple dual-port memory '$label' requires a non-null $role reference",
        origin
      )
    }
    value.requireUsable(
      s"synchronous read-first simple dual-port memory '$label' $role"
    )
    val reference = requireRef(
      value,
      s"synchronous read-first simple dual-port memory $role",
      notRefCode,
      origin
    )
    requirePortableIdentifier(
      reference.name,
      s"synchronous read-first simple dual-port memory $role",
      invalidCode,
      value.origin
    )
    reference
  }

  private def requireSynchronousStreamFifoRef(
      label: String,
      role: String,
      codeRole: String,
      value: FrontendNode[RtlExpr],
      origin: SourceOrigin
  ): Ref = {
    val prefix = "MORPH-FRONTEND-SYNCHRONOUS-STREAM-FIFO"
    if (value eq null) {
      FrontendException.failAt(
        s"$prefix-$codeRole-NULL",
        s"synchronous stream FIFO '$label' requires a non-null $role reference",
        origin
      )
    }
    value.requireUsable(s"synchronous stream FIFO '$label' $role")
    val reference = requireRef(
      value,
      s"synchronous stream FIFO $role",
      s"$prefix-$codeRole-NOT-REF",
      origin
    )
    requirePortableIdentifier(
      reference.name,
      s"synchronous stream FIFO $role",
      s"$prefix-$codeRole-INVALID",
      value.origin
    )
    reference
  }

  private def requireSynchronousStreamM2sPipeRef(
      label: String,
      role: String,
      codeRole: String,
      value: FrontendNode[RtlExpr],
      origin: SourceOrigin
  ): Ref = {
    val prefix = "MORPH-FRONTEND-SYNCHRONOUS-STREAM-M2S-PIPE"
    if (value eq null) {
      FrontendException.failAt(
        s"$prefix-$codeRole-NULL",
        s"synchronous stream m2s pipe '$label' requires a non-null $role reference",
        origin
      )
    }
    value.requireUsable(s"synchronous stream m2s pipe '$label' $role")
    val reference = requireRef(
      value,
      s"synchronous stream m2s pipe $role",
      s"$prefix-$codeRole-NOT-REF",
      origin
    )
    requirePortableIdentifier(
      reference.name,
      s"synchronous stream m2s pipe $role",
      s"$prefix-$codeRole-INVALID",
      value.origin
    )
    reference
  }

  private def requireSynchronousCounterRef(
      label: String,
      role: String,
      value: FrontendNode[RtlExpr],
      nullCode: String,
      notRefCode: String,
      invalidCode: String,
      origin: SourceOrigin
  ): Ref = {
    if (value eq null) {
      FrontendException.failAt(
        nullCode,
        s"synchronous counter '$label' requires a non-null $role reference",
        origin
      )
    }
    value.requireUsable(s"synchronous counter '$label' $role")
    val reference = requireRef(
      value,
      s"synchronous-counter $role",
      notRefCode,
      origin
    )
    requirePortableIdentifier(
      reference.name,
      s"synchronous-counter $role",
      invalidCode,
      value.origin
    )
    reference
  }

  private def requirePortableIdentifier(
      value: String,
      role: String,
      code: String,
      origin: SourceOrigin
  ): Unit =
    if (value == null || !PortableIdentifier.pattern.matcher(value).matches()) {
      FrontendException.failAt(
        code,
        s"$role '$value' is not a portable identifier",
        origin
      )
    }

  private def localKindRank(token: LocalParameterIdentity): Int = token match {
    case _: LocalParameterToken        => 0
    case _: BooleanLocalParameterToken => 1
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
