package spinal.core.internals

import spinal.core.{BaseType, ParameterizedWidth, NativeLocalParameters, ExternalFormalParameterRegistry}

object MorphHdlEmitterParameterNames {
  /** Parameter publication is independent of optional wire cleanup. The older
    * pipeline may already install this phase; never prepare an owner twice.
    */
  def install(phases: scala.collection.mutable.ArrayBuffer[Phase]): Unit = {
    val existing = phases.count(_.isInstanceOf[MorphHdlEmitterParameterNames])
    require(existing <= 1, "parameter publication needs one name preparation phase")
    if (existing == 0 && phases.exists(_.isInstanceOf[PhaseVerilog])) {
      val allocation = phases.indexWhere(_.isInstanceOf[PhaseAllocateNames])
      require(allocation >= 0, "parameter publication needs native name allocation")
      phases.insert(allocation + 1, new MorphHdlEmitterParameterNames)
    }
    val names = phases.collectFirst { case phase: MorphHdlEmitterParameterNames => phase }
    phases.collect { case emitter: PhaseVerilog => emitter }.foreach { emitter =>
        emitter.bindAssignmentPublication(new VerilogBase.AssignmentPublicationPolicy {
          override def wrapperRange(printer: ComponentEmitterVerilog, expression: Expression): Option[String] =
            names.flatMap(_.wrapperRange(printer, expression))
          override def binaryOperand(printer: ComponentEmitterVerilog, expression: BinaryOperator, slot: Int): Option[String] =
            names.flatMap(_.binaryOperand(printer, expression, slot))
          override def target(printer: ComponentEmitterVerilog, assignment: AssignmentStatement): Option[String] =
            NativeConditionalProcessEmitter.target(printer, assignment)
          override def scope(printer: ComponentEmitterVerilog, tree: TreeStatement, scope: ScopeStatement,
              output: StringBuilder, indentation: String, body: String => Int): Option[Int] =
            NativeConditionalProcessEmitter.scope(printer, tree, scope, output, indentation, body)
          def source(printer: ComponentEmitterVerilog, assignment: AssignmentStatement): Option[String] =
            ExternalParameterizedNativeResize.emitNative(printer, assignment)
              .orElse(ExternalParameterizedVerilogNativeFallback.emitNativeValue(printer, assignment))
        })
      }
  }
}

/** Parameter declarations are published after native emission. Reserve their
  * complete retained module inventory before the native emitter allocates
  * expression functions, formal arguments or late wrappers. This changes no
  * declaration identity and does not infer parameters from emitted names.
  */
final class MorphHdlEmitterParameterNames extends PhaseMisc {
  private var context: PhaseContext = null
  private val wrapperRanges = new java.util.IdentityHashMap[spinal.core.Component, Expression => Option[String]]()
  private val binaryOperands = new java.util.IdentityHashMap[spinal.core.Component, (ComponentEmitterVerilog, BinaryOperator, Int) => Option[String]]()
  private[internals] def binaryOperand(printer: ComponentEmitterVerilog, expression: BinaryOperator, slot: Int): Option[String] = expression match {
    case _: Operator.UInt.Add | _: Operator.UInt.Sub | _: Operator.UInt.And | _: Operator.UInt.Or | _: Operator.UInt.Xor |
        _: Operator.UInt.Equal | _: Operator.UInt.EqualSim | _: Operator.UInt.NotEqual |
        _: Operator.UInt.Smaller | _: Operator.UInt.SmallerOrEqual =>
      require(context != null, "binary publication precedes name preparation")
      val resolver = binaryOperands.synchronized {
        var value = binaryOperands.get(printer.component)
        if (value == null) {
          value = ExternalParameterizedVerilogNativeFallback.binaryOperandResolver(printer.component, context)
          binaryOperands.put(printer.component, value)
        }
        value
      }
      resolver(printer, expression, slot)
    case _ => None
  }
  private[internals] def wrapperRange(printer: ComponentEmitterVerilog, expression: Expression): Option[String] = expression match {
    // These native unsigned wrappers are outside signedness publication's
    // intentional scope. Reuse the ordinary width authority, not a witness.
    case _: Operator.UInt.Add | _: Operator.UInt.Sub | _: Operator.UInt.And | _: Operator.UInt.Or | _: Operator.UInt.Xor =>
      require(context != null, "wrapper width publication precedes name preparation")
      val resolver = wrapperRanges.synchronized {
        var value = wrapperRanges.get(printer.component)
        if (value == null) {
          value = ExternalParameterizedVerilogNativeFallback.expressionRangeResolver(printer.component, context)
          wrapperRanges.put(printer.component, value)
        }
        value
      }
      resolver(expression)
    case _ => None
  }
  override def impl(pc: PhaseContext): Unit = {
    context = pc
    pc.walkComponents { component =>
    if (ExternalFormalParameterRegistry.supportsMultipleTypedFormals(component) ||
        component.children.exists(ExternalFormalParameterRegistry.supportsMultipleTypedFormals)) {
      MorphHdlExternalParameterizedVerilog.validateComponentParameterRootInventory(component, includeChildActuals = true)
      val domains = MorphHdlExternalParameterizedVerilog.componentParameters(component) ++
        component.children.toVector.flatMap(child => ExternalFormalParameterRegistry.bindingsOf(child)
          .flatMap(_.actual.parameters))
      spinal.core.NativeSymbolicLegality.retainDeclarationDomains(component, domains.distinct.sortBy(_.name))
    }
    val names = scala.collection.mutable.HashSet.empty[String]
    MorphHdlExternalParameterizedVerilog.componentParameters(component)
      .foreach(parameter => names += parameter.name)
    // Reserving spellings is not a parameter-root equality proof. In
    // particular, distinct child-formal roots can legitimately share a name
    // before the hierarchy publisher resolves their exact owner/binding.
    component.dslBody.walkDeclarations {
      case value: BaseType => ParameterizedWidth.expressionOf(value)
        .foreach(_.parameters.foreach(parameter => names += parameter.name))
      case _ =>
    }
    names.toVector.sorted.foreach(component.localNamingScope.lockName)
    val uses = scala.collection.mutable.ArrayBuffer.empty[spinal.core.ElaborationIntegerExpression]
    component.dslBody.walkDeclarations {
      case value: BaseType => ParameterizedWidth.expressionOf(value).foreach(uses += _)
      case _ =>
    }
    component.children.foreach(child =>
      ExternalFormalParameterRegistry.bindingsOf(child).foreach(binding => uses += binding.actual))
    component.children.collect { case child: spinal.core.BlackBox => child }.foreach { child =>
      ParameterizedBlackBoxGenericRegistry.recordsOf(child).foreach {
        case binding: ParameterizedBlackBoxIntegerGeneric => uses += binding.expression
        case _ =>
      }
    }
    spinal.core.ExternalParameterizedValueRegistry.valuesOf(component)
      .foreach { case (_, record) => uses += record.expression }
    NativeLocalParameters.prepare(component, uses.toVector)
  }
  }
}
