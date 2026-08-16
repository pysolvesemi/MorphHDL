package morphhdl.frontend

import scala.collection.mutable.ArrayBuffer

import spinal.core.{
  Component,
  ParameterizedProcess,
  ParameterizedStructuralBlock,
  ParameterizedStructuralPending,
  ParameterizedStructure
}

/**
  * Native SpinalHDL structural bridge used when no explicit ParamRTL capture
  * session is active.
  */
private[frontend] object NativeStructuralFrontend {
  private val activeIndices =
    new ThreadLocal[Map[String, StructuralExpressionBridge.GenerateIndexFacts]]

  private[frontend] def currentGenerateIndices
      : Map[String, StructuralExpressionBridge.GenerateIndexFacts] =
    Option(activeIndices.get()).getOrElse(Map.empty)

  def runRange(range: HdlRange, body: GenIndex => Unit): Unit = {
    val component = requireComponent("HdlInt loop", range.origin)
    if (range.start != 0) {
      FrontendException.failAt(
        "MORPH-FRONTEND-GENERATE-START-UNSUPPORTED",
        s"generate loops must start at zero, received ${range.start}",
        range.origin
      )
    }
    val names = range.names.getOrElse(HdlRange.generatedNames(range.origin))

    if (ParameterizedStructure.captureEnabled) {
      val count = StructuralExpressionBridge.integer(
        range.end,
        "native parameterized loop count",
        Map.empty
      )
      if (count.default <= 0 || !count.default.isValidInt) {
        FrontendException.failAt(
          if (count.default <= 0)
            "MORPH-FRONTEND-GENERATE-COUNT-NONPOSITIVE"
          else "MORPH-FRONTEND-GENERATE-COUNT-TOO-LARGE",
          s"generate-loop concrete witness must be a positive Scala Int, received ${count.default}",
          range.origin
        )
      }
      val facts = StructuralExpressionBridge.GenerateIndexFacts(
        default = BigInt(0),
        minimum = BigInt(0),
        maximum = count.maximum - 1
      )
      ParameterizedProcess.captureRange(
        component,
        names.label,
        names.index,
        count,
        Some(range.origin.rendered)
      ) {
        withGenerateIndices(Map(names.index -> facts)) {
          val one = HdlInt.literalAt(BigInt(1), range.origin)
          val representative = new HdlRange(
            start = 0,
            end = one,
            names = Some(names),
            origin = range.origin
          )
          FrontendSession.concrete {
            representative.foreach(body)
          }
        }
      }
    } else {
      FrontendSession.concrete {
        range.foreach(body)
      }
    }
  }

  def startGenerateIf(
      condition: HdlBool,
      names: Option[GenerateIfNames],
      whenTrue: => Unit,
      origin: SourceOrigin
  ): GenerateIfBuilder = {
    val component = requireComponent("generateIf", origin)
    if (condition eq null) {
      FrontendException.failAt(
        "MORPH-FRONTEND-BOOLEAN-CONDITION-NULL",
        "generateIf condition must not be null",
        origin
      )
    }
    val resolved = names.getOrElse(generatedIfNames(origin))
    if (ParameterizedStructure.captureEnabled) {
      val expression = StructuralExpressionBridge.boolean(
        condition,
        "native structural generate-if condition"
      )
      val whenTrueBlock = ParameterizedStructure.captureBlock(
        component,
        Some(origin.rendered)
      )(whenTrue)
      val pending = ParameterizedStructure.beginPending(
        component,
        "generate-if",
        Some(origin.rendered)
      )
      new GenerateIfBuilder(
        new NativeGenerateIfToken(
          component,
          pending,
          condition,
          expression,
          resolved,
          whenTrueBlock,
          origin,
          parameterized = true
        )
      )
    } else {
      if (condition.witness) whenTrue
      new GenerateIfBuilder(
        new NativeGenerateIfToken(
          component = component,
          pending = null,
          condition = condition,
          expression = null,
          names = resolved,
          whenTrueBlock = null,
          origin = origin,
          parameterized = false
        )
      )
    }
  }

  def startGenerateCase(
      selector: HdlInt,
      origin: SourceOrigin
  ): GenerateCaseBuilder = {
    val component = requireComponent("generateCase", origin)
    if (selector eq null) {
      FrontendException.failAt(
        "MORPH-FRONTEND-GENERATE-CASE-SELECTOR-NULL",
        "generateCase selector must not be null",
        origin
      )
    }
    if (ParameterizedStructure.captureEnabled) {
      val expression = StructuralExpressionBridge.integer(
        selector,
        "native structural generate-case selector",
        Map.empty
      )
      val pending = ParameterizedStructure.beginPending(
        component,
        "generate-case",
        Some(origin.rendered)
      )
      new GenerateCaseBuilder(
        new NativeGenerateCaseToken(
          component,
          pending,
          selector,
          expression,
          origin,
          parameterized = true
        )
      )
    } else {
      new GenerateCaseBuilder(
        new NativeGenerateCaseToken(
          component = component,
          pending = null,
          selector = selector,
          expression = null,
          origin = origin,
          parameterized = false
        )
      )
    }
  }

  private def requireComponent(
      operation: String,
      origin: SourceOrigin
  ): Component = {
    val component = Component.current
    if (component eq null) {
      FrontendException.failAt(
        "MORPH-FRONTEND-SESSION-MISSING",
        s"$operation requires either an explicit frontend session or ordinary Component construction",
        origin
      )
    }
    component
  }

  private def withGenerateIndices[A](
      values: Map[String, StructuralExpressionBridge.GenerateIndexFacts]
  )(body: => A): A = {
    val previous = activeIndices.get()
    activeIndices.set(values)
    try body
    finally {
      if (previous eq null) activeIndices.remove()
      else activeIndices.set(previous)
    }
  }

  private def generatedIfNames(origin: SourceOrigin): GenerateIfNames = {
    val normalized = origin.file.replace('\\', '/')
    val fileName = normalized.substring(normalized.lastIndexOf('/') + 1)
    val stem = fileName.lastIndexOf('.') match {
      case index if index > 0 => fileName.substring(0, index)
      case _                  => fileName
    }
    val safeStem = stem.replaceAll("[^A-Za-z0-9_]", "_") match {
      case value if value.nonEmpty && value.charAt(0).isDigit => s"_$value"
      case value if value.nonEmpty                            => value
      case _                                                  => "source"
    }
    val base = s"g_if_${safeStem}_l${origin.line}"
    GenerateIfNames(s"${base}_true", s"${base}_false")
  }
}

private[frontend] final class NativeGenerateIfToken(
    val component: Component,
    val pending: ParameterizedStructuralPending,
    val condition: HdlBool,
    val expression: spinal.core.ElaborationBooleanExpression,
    val names: GenerateIfNames,
    val whenTrueBlock: ParameterizedStructuralBlock,
    val origin: SourceOrigin,
    val parameterized: Boolean
) {
  private var completed = false

  def otherwise(body: => Unit, callOrigin: SourceOrigin): Unit = {
    if (completed) {
      FrontendException.failAt(
        "MORPH-FRONTEND-GENERATE-IF-OTHERWISE-DUPLICATE",
        "otherwise was already supplied for this generateIf",
        callOrigin
      )
    }
    if (parameterized) {
      val whenFalse = ParameterizedStructure.captureBlock(
        component,
        Some(callOrigin.rendered)
      )(body)
      ParameterizedStructure.registerIf(
        pending,
        expression,
        names.whenTrue,
        names.whenFalse,
        whenTrueBlock,
        whenFalse,
        Some(origin.rendered)
      )
    } else if (!condition.witness) {
      body
    }
    completed = true
  }
}

private[frontend] final class NativeGenerateCaseToken(
    val component: Component,
    val pending: ParameterizedStructuralPending,
    val selector: HdlInt,
    val expression: spinal.core.ElaborationIntegerExpression,
    val origin: SourceOrigin,
    val parameterized: Boolean
) {
  private val choices = ArrayBuffer.empty[(BigInt, String, ParameterizedStructuralBlock)]
  private val values = scala.collection.mutable.Set.empty[BigInt]
  private var matched = false
  private var completed = false

  def choice(
      value: BigInt,
      label: String,
      body: => Unit,
      callOrigin: SourceOrigin
  ): Unit = {
    requireOpen(callOrigin)
    if (value == null) {
      FrontendException.failAt(
        "MORPH-FRONTEND-GENERATE-CASE-CHOICE-NULL",
        "generateCase choice value must be a non-null integer literal",
        callOrigin
      )
    }
    if (!values.add(value)) {
      FrontendException.failAt(
        "MORPH-FRONTEND-GENERATE-CASE-CHOICE-DUPLICATE",
        s"generateCase choice value '$value' is already present",
        callOrigin
      )
    }
    if (parameterized) {
      val block = ParameterizedStructure.captureBlock(
        component,
        Some(callOrigin.rendered)
      )(body)
      choices += ((value, label, block))
    } else if (!matched && selector.witness == value) {
      body
      matched = true
    }
  }

  def default(
      label: String,
      body: => Unit,
      callOrigin: SourceOrigin
  ): Unit = {
    requireOpen(callOrigin)
    if (values.isEmpty) {
      FrontendException.failAt(
        "MORPH-FRONTEND-GENERATE-CASE-CHOICE-MISSING",
        "generateCase requires at least one explicit literal choice before its default branch",
        origin
      )
    }
    if (parameterized) {
      val defaultBlock = ParameterizedStructure.captureBlock(
        component,
        Some(callOrigin.rendered)
      )(body)
      ParameterizedStructure.registerCase(
        pending,
        expression,
        choices.toVector.sortBy(_._1),
        label,
        defaultBlock,
        Some(origin.rendered)
      )
    } else if (!matched) {
      body
    }
    completed = true
  }

  private def requireOpen(callOrigin: SourceOrigin): Unit =
    if (completed) {
      FrontendException.failAt(
        "MORPH-FRONTEND-GENERATE-CASE-COMPLETED",
        "generateCase was already completed with its default branch",
        callOrigin
      )
    }
}
