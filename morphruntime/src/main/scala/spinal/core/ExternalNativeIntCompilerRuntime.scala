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

  private[core] def parameterizedUIntShapeSource(
      prototype: UInt,
      sourceLocation: Option[String]
  ): UInt =
    ParameterizedWidth.expressionOf(prototype) match {
      case Some(expression) if expression.parameters.nonEmpty => prototype
      case _ =>
        ParameterizedVerilogException.fail(
          "SPINAL-PARAMETERIZED-VERILOG-UINT-SHAPE-PROVENANCE-MISSING",
          "native UInt carrier prototype has no exact retained symbolic-width metadata; " +
            "refusing concrete-width or expression-traversal shape inference",
          sourceLocation
        )
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
    val shapeSource =
      parameterizedUIntShapeSource(prototype, Some(rendered(file, line)))
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
    ExternalNativeIntShadowRegistry.rejectTracked(
      reference,
      code,
      detail,
      rendered(file, line)
    )
    nativeValue
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

  def selectSymbolic[T](
      condition: Boolean,
      predicateReference: String,
      sourceFile: String,
      sourceLine: Int
  )(ifTrue: => T)(ifFalse: => T): T = {
    if (!boundaryActive || !ParameterizedStructure.captureEnabled) {
      if (condition) ifTrue else ifFalse
    } else withCapture(sourceFile, sourceLine) {
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

  /**
    * Preserve the native `BooleanPimped.generate` contract while retaining its
    * body as a structural generate-if alternative. Unlike a source-level `if`,
    * `generate` has an implicit empty false continuation and returns null when
    * its concrete witness is false. The empty continuation is compiler-owned
    * and must not pass through the user-body empty check.
    */
  def selectSymbolicGenerate[T](
      condition: Boolean,
      predicateReference: String,
      sourceFile: String,
      sourceLine: Int
  )(body: => T): T = {
    if (!boundaryActive || !ParameterizedStructure.captureEnabled) {
      if (condition) body else null.asInstanceOf[T]
    } else withCapture(sourceFile, sourceLine) {
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
      if (predicateDomain.exists(_.whenTrue.isEmpty)) {
        null.asInstanceOf[T]
      } else {
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
    } else withCapture(sourceFile, sourceLine) {
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
        )
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
        val continuation = index + 1 < ordered.size
        val falseFile = if (continuation) ordered(index + 1)._4 else otherwiseFile
        val falseLine = if (continuation) ordered(index + 1)._5 else otherwiseLine
        captureOne(
          conditionThunk(),
          reference,
          file,
          line,
          falseFile,
          falseLine,
          body(),
          if (continuation) capture(index + 1) else otherwise()
        )
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
        if (index + 1 < ordered.size) {
          val next = ordered(index + 1)
          captureOne[Any](
            conditionThunk(),
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
        } else {
          captureOneUnit(
            conditionThunk(),
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
