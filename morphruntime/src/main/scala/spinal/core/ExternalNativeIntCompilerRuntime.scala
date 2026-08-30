package spinal.core

/**
  * Runtime target of MorphHDL's parser-phase native-Int instrumentation.
  *
  * All methods preserve the ordinary Scala/native SpinalHDL witness when no
  * exact formalization boundary is active. Symbolic metadata is attached only
  * while constructing the selected native component for MorphVerilog.
  */
object ExternalNativeIntCompilerRuntime {
  private val MaximumCaptureDepth = 64
  private val activeCaptureDepth = new ThreadLocal[java.lang.Integer]

  private def rendered(file: String, line: Int): String = s"$file:$line"

  def boundaryActive: Boolean = ExternalNativeIntShadowRegistry.boundaryActive

  def compilerTrackArgument(
      value: Int,
      name: String,
      reference: String,
      file: String,
      line: Int
  ): Int =
    ExternalNativeIntShadowRegistry.captureArgumentTracked(
      value,
      name,
      reference,
      rendered(file, line)
    )

  def compilerTrackLocal(
      value: Int,
      name: String,
      sourceReference: String,
      resultReference: String,
      file: String,
      line: Int
  ): Int =
    ExternalNativeIntShadowRegistry.captureLocalTracked(
      value,
      name,
      sourceReference,
      resultReference,
      rendered(file, line),
      requireBoundary = true
    )

  def compilerAlias(
      value: Int,
      name: String,
      sourceReference: String,
      resultReference: String,
      file: String,
      line: Int
  ): Int =
    ExternalNativeIntShadowRegistry.aliasTracked(
      value,
      name,
      sourceReference,
      resultReference,
      rendered(file, line)
    )

  def compilerBinary(
      operation: String,
      left: Int,
      leftReference: String,
      leftLiteral: Boolean,
      right: Int,
      rightReference: String,
      rightLiteral: Boolean,
      resultReference: String,
      name: String,
      file: String,
      line: Int
  ): Int =
    ExternalNativeIntShadowRegistry.binaryTracked(
      operation,
      left,
      leftReference,
      leftLiteral,
      right,
      rightReference,
      rightLiteral,
      resultReference,
      name,
      rendered(file, line)
    )

  def compilerUnary(
      operation: String,
      value: Int,
      valueReference: String,
      resultReference: String,
      name: String,
      file: String,
      line: Int
  ): Int =
    ExternalNativeIntShadowRegistry.unaryTracked(
      operation,
      value,
      valueReference,
      resultReference,
      name,
      rendered(file, line)
    )

  def compilerComparison(
      operation: String,
      left: Int,
      leftReference: String,
      leftLiteral: Boolean,
      right: Int,
      rightReference: String,
      rightLiteral: Boolean,
      resultReference: String,
      name: String,
      file: String,
      line: Int
  ): Boolean =
    ExternalNativeIntShadowRegistry.comparisonTracked(
      operation,
      left,
      leftReference,
      leftLiteral,
      right,
      rightReference,
      rightLiteral,
      resultReference,
      name,
      rendered(file, line)
    )

  def compilerPowerOfTwo(
      value: Int,
      valueReference: String,
      resultReference: String,
      name: String,
      file: String,
      line: Int
  ): Boolean =
    ExternalNativeIntShadowRegistry.powerOfTwoTracked(
      value,
      valueReference,
      resultReference,
      name,
      rendered(file, line)
    )

  def compilerBooleanBinary(
      operation: String,
      left: Boolean,
      leftReference: String,
      leftConcrete: Boolean,
      right: Boolean,
      rightReference: String,
      rightConcrete: Boolean,
      resultReference: String,
      name: String,
      file: String,
      line: Int
  ): Boolean =
    ExternalNativeIntShadowRegistry.booleanBinaryTracked(
      operation,
      left,
      leftReference,
      leftConcrete,
      right,
      rightReference,
      rightConcrete,
      resultReference,
      name,
      rendered(file, line)
    )

  def compilerBooleanNot(
      value: Boolean,
      valueReference: String,
      valueConcrete: Boolean,
      resultReference: String,
      name: String,
      file: String,
      line: Int
  ): Boolean =
    ExternalNativeIntShadowRegistry.booleanNotTracked(
      value,
      valueReference,
      valueConcrete,
      resultReference,
      name,
      rendered(file, line)
    )

  def compilerBooleanToInt(
      value: Boolean,
      valueReference: String,
      valueConcrete: Boolean,
      resultReference: String,
      name: String,
      file: String,
      line: Int
  ): Int =
    ExternalNativeIntShadowRegistry.booleanToIntTracked(
      value,
      valueReference,
      valueConcrete,
      resultReference,
      name,
      rendered(file, line)
    )

  private def literalWidth(value: Int): ElaborationIntegerExpression =
    ElaborationIntegerExpression(
      verilog = value.toString,
      default = BigInt(value),
      minimum = BigInt(value),
      maximum = BigInt(value),
      parameters = Vector.empty
    )

  private def addWidths(
      left: ElaborationIntegerExpression,
      right: ElaborationIntegerExpression
  ): ElaborationIntegerExpression =
    ElaborationIntegerExpression(
      verilog = s"(${left.verilog} + ${right.verilog})",
      default = left.default + right.default,
      minimum = left.minimum + right.minimum,
      maximum = left.maximum + right.maximum,
      parameters = (left.parameters ++ right.parameters).distinct.sortBy(_.name),
      sourceLocation = left.sourceLocation.orElse(right.sourceLocation),
      parameterRoots = ElabInt.mergeParameterRoots(
        left.completedParameterRoots,
        right.completedParameterRoots
      )
    )

  private def packedWidthExpression(data: Data): ElaborationIntegerExpression = {
    if (data == null) {
      fail(
        "MORPH-FRONTEND-NATIVE-WIDTH-FUNCTION-DATA-NULL",
        "native width function received a null Data root",
        "<native-width-function>",
        1
      )
    }
    val leaves = data.flatten.toVector
    if (leaves.isEmpty) literalWidth(0)
    else leaves.map { leaf =>
      ParameterizedWidth.expressionOf(leaf).getOrElse(literalWidth(leaf.getBitsWidth))
    }.reduce(addWidths)
  }

  /**
    * Open a temporary generic native-width method scope. The real native method
    * remains authoritative; this scope only supplies symbolic provenance to
    * compiler-instrumented `widthOf` results and expressions derived from them.
    */
  def withWidthFunctionBoundary[A](
      roots: Seq[Data],
      file: String,
      line: Int
  )(body: => A): A = {
    val expressions = Option(roots).getOrElse(Seq.empty).map(packedWidthExpression)
    val symbolic = expressions.filter(_.parameters.nonEmpty).foldLeft(Vector.empty[ElaborationIntegerExpression]) {
      case (known, expression)
          if known.exists(ExternalFormalParameterRegistry.equivalentExpression(_, expression)) => known
      case (known, expression) => known :+ expression
    }
    symbolic match {
      case Vector() => body
      case Vector(root) =>
        val location = rendered(file, line)
        val token = ExternalNativeIntFormalizationToken(
          callSite = location,
          valueOrigin = root.sourceLocation.getOrElse(location),
          role = "nativeWidthFunction"
        )
        ExternalNativeIntShadowRegistry.withDefinitionExpressionBoundary(root, token)(body)
      case roots =>
        fail(
          "MORPH-FRONTEND-NATIVE-WIDTH-FUNCTION-ROOT-AMBIGUOUS",
          s"native width function received ${roots.size} independent symbolic packed-width roots: ${roots.map(_.verilog).mkString(", ")}",
          file,
          line
        )
    }
  }

  def compilerWidthOf(
      data: Data,
      nativeWidth: Int,
      reference: String,
      name: String,
      file: String,
      line: Int
  ): Int = {
    val expression = packedWidthExpression(data)
    ExternalNativeIntShadowRegistry.widthQueryTracked(
      witness = nativeWidth,
      expression = if (expression.parameters.nonEmpty) Some(expression) else None,
      reference = reference,
      name = name,
      sourceLocation = rendered(file, line)
    )
  }

  def compilerUInt(
      reference: String,
      file: String,
      line: Int
  )(native: => UInt): UInt = {
    val value = native
    retainWidth(value, reference, file, line)
  }

  def compilerBits(
      reference: String,
      file: String,
      line: Int
  )(native: => Bits): Bits = {
    val value = native
    retainWidth(value, reference, file, line)
  }

  def compilerSInt(
      reference: String,
      file: String,
      line: Int
  )(native: => SInt): SInt = {
    val value = native
    retainWidth(value, reference, file, line)
  }

  private def retainWidth[T <: BitVector](
      value: T,
      reference: String,
      file: String,
      line: Int
  ): T = {
    ExternalNativeIntShadowRegistry
      .definitionExpressionTracked(
        reference,
        value.getBitsWidth,
        rendered(file, line),
        positiveWidth = true
      )
      .foreach { expression =>
        val direct = expression.parameters match {
          case Vector(parameter) if expression.verilog.trim == parameter.name =>
            Some(parameter)
          case _ => None
        }
        ParameterizedWidth.attach(
          value,
          ParameterizedBitCount(
            value = value.getBitsWidth,
            parameter = direct,
            sourceLocation = Some(rendered(file, line)),
            expression = Some(expression)
          )
        )
      }
    value
  }

  /**
    * Retain the target width of the untouched native BitVector.resize result
    * and bind it to the exact internal Resize expression before native
    * normalization can remove the weak-clone result object.
    */
  def compilerResize[T <: BitVector](
      reference: String,
      file: String,
      line: Int
  )(native: => T): T = {
    val value = retainWidth(native, reference, file, line)
    ParameterizedWidth
      .expressionOf(value)
      .filter(_.parameters.nonEmpty)
      .foreach { expression =>
        if (value.hasOnlyOneStatement) {
          value.head match {
            case assignment: spinal.core.internals.DataAssignmentStatement
                if (assignment.target eq value) &&
                  (assignment.finalTarget eq value) =>
              assignment.source match {
                case resize: spinal.core.internals.Resize
                    if resize.size == value.getBitsWidth =>
                  ExternalParameterizedResizeRegistry.attach(resize, expression)
                case _ =>
              }
            case _ =>
          }
        }
      }
    value
  }

  def compilerReg[T <: Data](dataType: => T)(native: => T): T =
    if (boundaryActive) ParameterizedWidth.Reg(dataType) else native

  def compilerRegHardType[T <: Data](
      dataType: => HardType[T]
  )(native: => T): T =
    if (boundaryActive) ParameterizedWidth.Reg(dataType()) else native

  def compilerCloneOf[T <: Data](data: T)(native: => T): T =
    if (boundaryActive) ParameterizedWidth.cloneOf(data) else native

  def compilerCopyShape[T <: Data](source: T)(native: => T): T = {
    val value = native
    if (boundaryActive) ParameterizedWidth.copyShape(source, value) else value
  }

  def compilerHardType[T <: Data](dataType: => T)(native: => HardType[T]): HardType[T] =
    if (boundaryActive) ParameterizedWidth.HardType(dataType) else native

  def compilerMem[T <: Data](
      depth: Int,
      reference: String,
      file: String,
      line: Int
  )(native: => Mem[T]): Mem[T] = {
    val memory = native
    ExternalNativeIntShadowRegistry
      .definitionExpressionTracked(
        reference,
        depth,
        rendered(file, line),
        positiveWidth = false
      )
      .foreach { expression =>
        ExternalParameterizedMemoryRegistry.attach(
          memory,
          ParameterizedMemoryDepth(
            value = depth,
            expression = expression,
            sourceLocation = Some(rendered(file, line))
          )
        )
      }
    memory
  }

  private def parameterizedUIntShapeSource(prototype: UInt): UInt = {
    if (ParameterizedWidth.expressionOf(prototype).exists(_.parameters.nonEmpty)) prototype
    else {
      var selected: UInt = null

      def visit(expression: spinal.core.internals.Expression): Unit = {
        if (selected == null) {
          expression match {
            case value: UInt
                if value.getBitsWidth == prototype.getBitsWidth &&
                  ParameterizedWidth.expressionOf(value).exists(_.parameters.nonEmpty) =>
              selected = value
            case _ =>
          }
          if (selected == null) expression.foreachExpression(visit)
        }
      }

      prototype.foreachStatements {
        case spinal.core.internals.AssignmentStatement(_, source)
            if selected == null =>
          visit(source)
        case _ =>
      }
      Option(selected).getOrElse(prototype)
    }
  }

  /**
    * Build an exact, named UInt carrier for one symbolic Scala integer used by
    * an ordinary hardware operator. This method is called only from the active
    * side of a compiler-generated boundary check; concrete SpinalHDL evaluates
    * the original source expression instead.
    */
  def compilerUIntValueLike(
      value: Int,
      reference: String,
      prototype: UInt,
      stableName: String,
      file: String,
      line: Int
  ): UInt = {
    val carrierWidth = prototype.getBitsWidth
    if (!boundaryActive) return U(BigInt(value), carrierWidth bits)
    val expression = ExternalNativeIntShadowRegistry
      .definitionExpressionTracked(
        reference,
        value,
        rendered(file, line),
        positiveWidth = false
      )
      .getOrElse {
        throw new IllegalStateException(
          "active native formalization boundary lost a tracked UInt value"
        )
      }
    val shapeSource = parameterizedUIntShapeSource(prototype)
    val result = UInt(carrierWidth bits)
    ParameterizedWidth.copyShape(shapeSource, result)
    result.setName(stableName)
    result := U(BigInt(value))
    ExternalParameterizedValueRegistry.attach(
      result,
      expression,
      BigInt(value),
      Some(rendered(file, line))
    )
  }

  def compilerUnsupportedInt(
    reference: String,
    code: String,
    detail: String,
    file: String,
    line: Int
)(nativeValue: => Int): Int = {
  // The rewritten pure native expression may contain the compiler hook
  // which materializes a derived receiver reference. Evaluate it before
  // validating that exact reference, then fail closed with the requested
  // unsupported-operation diagnostic before the value can escape.
  val value = nativeValue
  ExternalNativeIntShadowRegistry.rejectTracked(
    reference,
    code,
    detail,
    rendered(file, line)
  )
  value
}

  def compilerUnsupportedValue[A](
      reference: String,
      code: String,
      detail: String,
      file: String,
      line: Int
  )(nativeValue: => A): A = {
    ExternalNativeIntShadowRegistry.rejectTracked(
      reference,
      code,
      detail,
      rendered(file, line)
    )
    nativeValue
  }

  def compilerUnsupportedBoolean(
      reference: String,
      code: String,
      detail: String,
      file: String,
      line: Int
  )(nativeValue: => Boolean): Boolean = {
    ExternalNativeIntShadowRegistry.rejectTracked(
      reference,
      code,
      detail,
      rendered(file, line)
    )
    nativeValue
  }

  def compilerBoxing[A](
      reference: String,
      detail: String,
      file: String,
      line: Int
  )(nativeValue: => A): A = {
    ExternalNativeIntShadowRegistry.rejectTracked(
      reference,
      "MORPH-FRONTEND-NATIVE-INT-EXPRESSION-BOXING-UNSUPPORTED",
      detail,
      rendered(file, line)
    )
    nativeValue
  }

  def compilerMutableInt(
      reference: String,
      detail: String,
      file: String,
      line: Int
  )(nativeValue: => Int): Int = {
    ExternalNativeIntShadowRegistry.rejectTracked(
      reference,
      "MORPH-FRONTEND-NATIVE-INT-EXPRESSION-MUTABLE-ESCAPE",
      detail,
      rendered(file, line)
    )
    nativeValue
  }

  private def constantPredicate(
      condition: Boolean,
      predicateReference: String,
      sourceFile: String,
      sourceLine: Int
  ): Option[Boolean] = {
    if (!ExternalNativeIntShadowRegistry.nativeWidthFunctionBoundaryActive)
      return None

    val (_, domain) =
      ExternalNativeIntShadowRegistry.definitionPredicateEvidenceTracked(
        predicateReference,
        condition,
        rendered(sourceFile, sourceLine)
      )
    domain.flatMap { value =>
      if (value.whenTrue.isEmpty) Some(false)
      else if (value.whenTrue == value.universe) Some(true)
      else None
    }
  }

  def selectSymbolic[T](
      condition: Boolean,
      predicateReference: String,
      sourceFile: String,
      sourceLine: Int
  )(ifTrue: => T)(ifFalse: => T): T = {
    if (!boundaryActive || !ParameterizedStructure.captureEnabled) {
      if (condition) ifTrue else ifFalse
    } else constantPredicate(
      condition,
      predicateReference,
      sourceFile,
      sourceLine
    ) match {
      case Some(true)  => ifTrue
      case Some(false) => ifFalse
      case None => withCapture(sourceFile, sourceLine) {
        captureOne(
          condition,
          predicateReference,
          sourceFile,
          sourceLine,
          sourceFile,
          sourceLine,
          ifTrue,
          ifFalse
        )
      }
    }
  }

  /**
    * Preserve the native `BooleanPimped.generate` contract while retaining its
    * body as a structural generate-if alternative.  Unlike a source-level
    * `if`, `generate` has an implicit empty false continuation and returns null
    * when its concrete witness is false.  The empty continuation is therefore
    * compiler-owned and must not pass through the user-body empty check.
    */
  def selectSymbolicGenerate[T](
      condition: Boolean,
      predicateReference: String,
      sourceFile: String,
      sourceLine: Int
  )(body: => T): T = {
    if (!boundaryActive || !ParameterizedStructure.captureEnabled) {
      if (condition) body else null.asInstanceOf[T]
    } else constantPredicate(
      condition,
      predicateReference,
      sourceFile,
      sourceLine
    ) match {
      case Some(true)  => body
      case Some(false) => null.asInstanceOf[T]
      case None => withCapture(sourceFile, sourceLine) {
        val component = Option(Component.current).getOrElse {
          fail(
            "MORPH-FRONTEND-SESSION-MISSING",
            "native symbolic generate requires an active Component",
            sourceFile,
            sourceLine
          )
        }
        val (expression, predicateDomain) =
          ExternalNativeIntShadowRegistry.definitionPredicateEvidenceTracked(
            predicateReference,
            condition,
            rendered(sourceFile, sourceLine)
          )
        var capturedValue: Option[T] = None
        val trueBlock = ParameterizedStructure.captureBlock(
          component,
          Some(rendered(sourceFile, sourceLine))
        ) {
          capturedValue = Some(body)
        }
        val pending = ParameterizedStructure.beginPending(
          component,
          "generate-if",
          Some(rendered(sourceFile, sourceLine))
        )
        val falseBlock = ParameterizedStructuralSynthetic.emptyBlock(
          Some(rendered(sourceFile, sourceLine))
        )
        val base = generatedIfBase(sourceFile, sourceLine)
        ParameterizedStructure.registerIf(
          pending,
          expression,
          base + "_true",
          base + "_false",
          trueBlock,
          falseBlock,
          Some(rendered(sourceFile, sourceLine)),
          predicateDomain
        )
        if (condition) capturedValue.get else null.asInstanceOf[T]
      }
    }
  }

  /**
    * Preserve Scala's `if (condition) statement` result type. The true branch
    * may return a fluent hardware context while the source-level conditional
    * still has Unit type because it has no explicit else branch.
    */
  def selectSymbolicUnit(
      condition: Boolean,
      predicateReference: String,
      sourceFile: String,
      sourceLine: Int
  )(ifTrue: => Any)(ifFalse: => Any): Unit = {
    if (!boundaryActive || !ParameterizedStructure.captureEnabled) {
      if (condition) ifTrue else ifFalse
      ()
    } else constantPredicate(
      condition,
      predicateReference,
      sourceFile,
      sourceLine
    ) match {
      case Some(true) =>
        ifTrue
        ()
      case Some(false) =>
        ifFalse
        ()
      case None => withCapture(sourceFile, sourceLine) {
        val component = Option(Component.current).getOrElse {
          fail(
            "MORPH-FRONTEND-SESSION-MISSING",
            "native symbolic conditional requires an active Component",
            sourceFile,
            sourceLine
          )
        }
        val expression = ExternalNativeIntShadowRegistry.definitionPredicateTracked(
          predicateReference,
          condition,
          rendered(sourceFile, sourceLine)
        )
        val witness = if (condition) BigInt(1) else BigInt(0)
        val retained = ElaborationIntegerExpression(
          verilog = expression.verilog,
          default = witness,
          minimum = BigInt(0),
          maximum = BigInt(1),
          parameters = expression.parameters,
          sourceLocation = expression.sourceLocation.orElse(
            Some(rendered(sourceFile, sourceLine))
          ),
          parameterRoots = expression.completedParameterRoots
        )
        val carrier = component.rework {
          val value = UInt(1 bits)
          value := U(witness, 1 bits)
          ExternalParameterizedValueRegistry.attach(
            value,
            retained,
            witness,
            retained.sourceLocation
          )
        }
        when(carrier.asBool) {
          ifTrue
          ()
        }
        ()
      }
    }
  }

  def selectSymbolicChain[T](
      alternatives: Seq[(() => Boolean, String, () => T, String, Int)],
      otherwise: () => T,
      otherwiseFile: String,
      otherwiseLine: Int
  ): T = {
    val ordered = alternatives.toVector
    if (ordered.isEmpty) {
      fail(
        "MORPH-FRONTEND-NATIVE-INT-SYMBOLIC-CONDITIONAL-CHAIN-EMPTY",
        "a native symbolic else-if chain requires at least one proven predicate",
        otherwiseFile,
        otherwiseLine
      )
    }
    if (!boundaryActive || !ParameterizedStructure.captureEnabled) {
      def select(index: Int): T = {
        val (condition, _, body, _, _) = ordered(index)
        if (condition()) body()
        else if (index + 1 < ordered.size) select(index + 1)
        else otherwise()
      }
      select(0)
    } else withCapture(ordered.head._4, ordered.head._5) {
      def capture(index: Int): T = {
        val (conditionThunk, reference, body, file, line) = ordered(index)
        val condition = conditionThunk()
        val continuation = index + 1 < ordered.size
        def fallback(): T = if (continuation) capture(index + 1) else otherwise()
        constantPredicate(condition, reference, file, line) match {
          case Some(true)  => body()
          case Some(false) => fallback()
          case None =>
            val falseFile = if (continuation) ordered(index + 1)._4 else otherwiseFile
            val falseLine = if (continuation) ordered(index + 1)._5 else otherwiseLine
            captureOne(
              condition,
              reference,
              file,
              line,
              falseFile,
              falseLine,
              body(),
              fallback()
            )
        }
      }
      capture(0)
    }
  }

  /** Unit-preserving counterpart for an else-if chain with no final else. */
  def selectSymbolicChainUnit(
      alternatives: Seq[(() => Boolean, String, () => Any, String, Int)],
      otherwise: () => Any,
      otherwiseFile: String,
      otherwiseLine: Int
  ): Unit = {
    val ordered = alternatives.toVector
    if (ordered.isEmpty) {
      fail(
        "MORPH-FRONTEND-NATIVE-INT-SYMBOLIC-CONDITIONAL-CHAIN-EMPTY",
        "a native symbolic else-if chain requires at least one proven predicate",
        otherwiseFile,
        otherwiseLine
      )
    }
    if (!boundaryActive || !ParameterizedStructure.captureEnabled) {
      selectSymbolicChain[Any](ordered, otherwise, otherwiseFile, otherwiseLine)
      ()
    } else withCapture(ordered.head._4, ordered.head._5) {
      def capture(index: Int): Unit = {
        val (conditionThunk, reference, body, file, line) = ordered(index)
        val condition = conditionThunk()
        constantPredicate(condition, reference, file, line) match {
          case Some(true) =>
            body()
            ()
          case Some(false) =>
            if (index + 1 < ordered.size) capture(index + 1)
            else { otherwise(); () }
          case None if index + 1 < ordered.size =>
            val next = ordered(index + 1)
            captureOne[Any](
              condition,
              reference,
              file,
              line,
              next._4,
              next._5,
              body(),
              {
                capture(index + 1)
                ()
              }
            )
            ()
          case None =>
            captureOneUnit(
              condition,
              reference,
              file,
              line,
              otherwiseFile,
              otherwiseLine,
              body()
            )
        }
      }
      capture(0)
    }
  }

  /**
    * Capture a compiler-proven no-else conditional. The omitted continuation is
    * an explicit synthetic empty structural block, not a user-authored body;
    * ordinary empty bodies and Scala-only side effects remain rejected by
    * ParameterizedStructure.captureBlock.
    */
  private def captureOneUnit(
      condition: Boolean,
      predicateReference: String,
      sourceFile: String,
      sourceLine: Int,
      falseFile: String,
      falseLine: Int,
      ifTrue: => Any
  ): Unit = {
    val component = Option(Component.current).getOrElse {
      fail(
        "MORPH-FRONTEND-SESSION-MISSING",
        "native symbolic conditional requires an active Component",
        sourceFile,
        sourceLine
      )
    }
    val (expression, predicateDomain) =
      ExternalNativeIntShadowRegistry.definitionPredicateEvidenceTracked(
        predicateReference,
        condition,
        rendered(sourceFile, sourceLine)
      )
    val trueBlock = ParameterizedStructure.captureBlock(
      component,
      Some(rendered(sourceFile, sourceLine))
    ) {
      ifTrue
      ()
    }
    val pending = ParameterizedStructure.beginPending(
      component,
      "generate-if",
      Some(rendered(sourceFile, sourceLine))
    )
    val falseBlock = ParameterizedStructuralSynthetic.emptyBlock(
      Some(rendered(falseFile, falseLine))
    )
    val base = generatedIfBase(sourceFile, sourceLine)
    ParameterizedStructure.registerIf(
      pending,
      expression,
      base + "_true",
      base + "_false",
      trueBlock,
      falseBlock,
      Some(rendered(sourceFile, sourceLine)),
      predicateDomain
    )
  }

  private def captureOne[T](
      condition: Boolean,
      predicateReference: String,
      sourceFile: String,
      sourceLine: Int,
      falseFile: String,
      falseLine: Int,
      ifTrue: => T,
      ifFalse: => T
  ): T = {
    val component = Option(Component.current).getOrElse {
      fail(
        "MORPH-FRONTEND-SESSION-MISSING",
        "native symbolic conditional requires an active Component",
        sourceFile,
        sourceLine
      )
    }
    val (expression, predicateDomain) =
      ExternalNativeIntShadowRegistry.definitionPredicateEvidenceTracked(
        predicateReference,
        condition,
        rendered(sourceFile, sourceLine)
      )
    var trueValue: Option[T] = None
    var falseValue: Option[T] = None
    val trueBlock = ParameterizedStructure.captureBlock(
      component,
      Some(rendered(sourceFile, sourceLine))
    ) {
      trueValue = Some(ifTrue)
    }
    val pending = ParameterizedStructure.beginPending(
      component,
      "generate-if",
      Some(rendered(sourceFile, sourceLine))
    )
    val falseBlock = ParameterizedStructure.captureBlock(
      component,
      Some(rendered(falseFile, falseLine))
    ) {
      falseValue = Some(ifFalse)
    }
    val base = generatedIfBase(sourceFile, sourceLine)
    ParameterizedStructure.registerIf(
      pending,
      expression,
      base + "_true",
      base + "_false",
      trueBlock,
      falseBlock,
      Some(rendered(sourceFile, sourceLine)),
      predicateDomain
    )
    if (condition) trueValue.get else falseValue.get
  }

  def guardAlternative[T](
      code: String,
      detail: String,
      sourceFile: String,
      sourceLine: Int
  )(body: => T): T = {
    if (boundaryActive && ParameterizedStructure.captureEnabled)
      fail(code, detail, sourceFile, sourceLine)
    body
  }

  private def withCapture[T](file: String, line: Int)(body: => T): T = {
    val current = activeCaptureDepth.get()
    val previous = if (current == null) 0 else current.intValue()
    if (previous >= MaximumCaptureDepth) {
      fail(
        "MORPH-FRONTEND-NATIVE-INT-SYMBOLIC-CONDITIONAL-DEPTH-EXCEEDED",
        s"native symbolic control-flow nesting exceeds the bounded depth $MaximumCaptureDepth",
        file,
        line
      )
    }
    activeCaptureDepth.set(java.lang.Integer.valueOf(previous + 1))
    try body
    finally {
      if (previous == 0) activeCaptureDepth.remove()
      else activeCaptureDepth.set(java.lang.Integer.valueOf(previous))
    }
  }

  private def generatedIfBase(file: String, line: Int): String = {
    val normalized = file.replace('\\', '/')
    val name = normalized.substring(normalized.lastIndexOf('/') + 1)
    val stem = name.lastIndexOf('.') match {
      case index if index > 0 => name.substring(0, index)
      case _                  => name
    }
    val safe = stem.replaceAll("[^A-Za-z0-9_]", "_") match {
      case value if value.nonEmpty && value.charAt(0).isDigit => "_" + value
      case value if value.nonEmpty                            => value
      case _                                                  => "source"
    }
    s"g_if_${safe}_l$line"
  }

  private def fail(
      code: String,
      detail: String,
      file: String,
      line: Int
  ): Nothing =
    throw new ParameterizedVerilogException(
      code,
      detail,
      Some(rendered(file, line))
    )
}
