package morphhdl.frontend

import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable

import morphhdl.paramrtl.{GenerateBlock, GenerateCaseChoice, ModuleItem}

private[frontend] final case class GenerateIfNames(whenTrue: String, whenFalse: String)

private[frontend] sealed trait RuntimeProcessKind {
  def description: String
}

private[frontend] case object CombinationalProcessKind extends RuntimeProcessKind {
  val description = "combinational process"
}

private[frontend] case object SynchronousRegisterKind extends RuntimeProcessKind {
  val description = "synchronous register process"
}

private[frontend] case object AsynchronousRegisterKind extends RuntimeProcessKind {
  val description = "asynchronous-reset register process"
}

private[frontend] case object SynchronousEnabledRegisterKind extends RuntimeProcessKind {
  val description = "synchronous enabled-register process"
}

private[frontend] case object AsynchronousEnabledRegisterKind extends RuntimeProcessKind {
  val description = "asynchronous-reset enabled-register process"
}

private[frontend] case object SynchronousReadFirstSinglePortMemoryKind
    extends RuntimeProcessKind {
  val description = "synchronous read-first single-port memory process"
}

private[frontend] case object SynchronousCounterKind extends RuntimeProcessKind {
  val description = "synchronous counter process"
}

private[frontend] final case class CapturedRuntimeProcess(
    kind: RuntimeProcessKind,
    origin: SourceOrigin
)

private[frontend] final class FrontendSessionToken {
  @volatile private var open = true

  def isOpen: Boolean = open
  def close(): Unit = open = false
}

private[frontend] sealed trait ConditionalGenerateToken {
  def origin: SourceOrigin
  def isPending: Boolean
  def isCompleted: Boolean
  def complete(): Unit
  def fail(): Unit
}

private[frontend] final class GenerateIfToken(
    val condition: HdlBool,
    val names: GenerateIfNames,
    val origin: SourceOrigin,
    val session: FrontendSessionToken,
    val parentCollector: Option[ArrayBuffer[FrontendNode[ModuleItem]]],
    val whenTrueItems: Vector[FrontendNode[ModuleItem]],
    val parameterized: Boolean
) extends ConditionalGenerateToken {
  private var state = 0

  def isPending: Boolean = state == 0
  def isCompleted: Boolean = state == 1
  def complete(): Unit = state = 1
  def fail(): Unit = state = 2
}

private[frontend] final case class CapturedGenerateCaseChoice(
    value: BigInt,
    label: String,
    items: Vector[FrontendNode[ModuleItem]]
)

private[frontend] final class GenerateCaseToken(
    val selector: HdlInt,
    val origin: SourceOrigin,
    val session: FrontendSessionToken,
    val parentCollector: Option[ArrayBuffer[FrontendNode[ModuleItem]]],
    val parameterized: Boolean
) extends ConditionalGenerateToken {
  private var state = 0
  private var capturedChoices = Vector.empty[CapturedGenerateCaseChoice]
  private var labels = Vector.empty[String]
  private var concreteChoiceMatched = false

  def isPending: Boolean = state == 0
  def isCompleted: Boolean = state == 1
  def complete(): Unit = state = 1
  def fail(): Unit = state = 2

  def choices: Vector[CapturedGenerateCaseChoice] = capturedChoices
  def containsChoice(value: BigInt): Boolean = capturedChoices.exists(_.value == value)
  def addChoice(choice: CapturedGenerateCaseChoice): Unit = capturedChoices :+= choice

  def reservedLabels: Vector[String] = labels
  def addReservedLabel(label: String): Unit = labels :+= label

  def hasConcreteMatch: Boolean = concreteChoiceMatched
  def markConcreteMatch(): Unit = concreteChoiceMatched = true
}

private[frontend] object FrontendSession {
  private sealed trait Mode
  private case object Concrete extends Mode
  private case object Parameterized extends Mode

  private final class NameRegistry {
    private val labels = mutable.Set.empty[String]
    private val indices = mutable.Set.empty[String]

    def reserve(names: GenerateNames, origin: SourceOrigin): Unit = {
      reserveAll(Vector(names.label), Vector(names.index), origin)
    }

    def reserve(names: GenerateIfNames, origin: SourceOrigin): Unit =
      reserveAll(Vector(names.whenTrue, names.whenFalse), Vector.empty, origin)

    def reserveLabel(label: String, origin: SourceOrigin): Unit =
      reserveAll(Vector(label), Vector.empty, origin)

    private def reserveAll(
        newLabels: Vector[String],
        newIndices: Vector[String],
        origin: SourceOrigin
    ): Unit = {
      val requested = newLabels ++ newIndices
      val allNames = labels ++ indices
      if (requested.distinct.size != requested.size || requested.exists(allNames)) {
        FrontendException.failAt(
          "MORPH-FRONTEND-GENERATE-NAME-DUPLICATE",
          s"generate identifier '${requested.find(allNames).getOrElse(requested.head)}' " +
            "is already used in this capture",
          origin
        )
      }
      labels ++= newLabels
      indices ++= newIndices
    }

    def release(names: GenerateNames): Unit = {
      labels -= names.label
      indices -= names.index
    }

    def release(names: GenerateIfNames): Unit = {
      labels -= names.whenTrue
      labels -= names.whenFalse
    }

    def releaseLabels(values: Vector[String]): Unit = labels --= values
  }

  private final case class Context(
      mode: Mode,
      collector: Option[ArrayBuffer[FrontendNode[ModuleItem]]],
      activeScope: Option[ScopeToken],
      activeConditional: Boolean,
      names: NameRegistry,
      session: FrontendSessionToken,
      pendingConditionals: mutable.Set[ConditionalGenerateToken],
      capturedConditionals: mutable.Set[ConditionalGenerateToken],
      capturedGenerateFors: ArrayBuffer[SourceOrigin],
      capturedProcesses: ArrayBuffer[CapturedRuntimeProcess],
      capturedOrdinaryItems: ArrayBuffer[(SourceOrigin, String)]
  )

  private val current = new ThreadLocal[Context]

  def concrete[A](body: => A)(implicit file: sourcecode.File, line: sourcecode.Line): A = {
    val origin = SourceOrigin.capture
    requireNoSession("concrete elaboration", origin)
    val session = new FrontendSessionToken
    val context = Context(
      Concrete,
      None,
      None,
      activeConditional = false,
      new NameRegistry,
      session,
      mutable.Set.empty,
      mutable.Set.empty,
      ArrayBuffer.empty,
      ArrayBuffer.empty,
      ArrayBuffer.empty
    )
    try {
      withContext(context) {
        val result = body
        requireNoPendingConditional(context)
        result
      }
    } finally session.close()
  }

  def captureItems(body: => Unit, origin: SourceOrigin): FrontendNode[Vector[ModuleItem]] = {
    requireNoSession("parameterized capture", origin)
    val items = ArrayBuffer.empty[FrontendNode[ModuleItem]]
    val session = new FrontendSessionToken
    val context = Context(
      Parameterized,
      Some(items),
      None,
      activeConditional = false,
      new NameRegistry,
      session,
      mutable.Set.empty,
      mutable.Set.empty,
      ArrayBuffer.empty,
      ArrayBuffer.empty,
      ArrayBuffer.empty
    )
    try {
      withContext(context) {
        body
        requireNoPendingConditional(context)
        FrontendNode(
          items.map(_.raw).toVector,
          parameters = items.flatMap(_.parameters).toSet,
          booleanParameters = items.flatMap(_.booleanParameters).toSet,
          localParameters = items.flatMap(_.localParameters).toSet,
          booleanLocalParameters = items.flatMap(_.booleanLocalParameters).toSet,
          scopes = items.flatMap(_.scopes).toSet,
          origin = origin
        )
      }
    } finally session.close()
  }

  private[frontend] def runRange(
      range: HdlRange,
      body: GenIndex => Unit
  ): Unit = {
    val existing = current.get()
    if (existing == null) {
      FrontendException.failAt(
        "MORPH-FRONTEND-SESSION-MISSING",
        "an HdlInt loop requires an explicit concrete or parameterized frontend session",
        range.origin
      )
    } else {
      runRangeInContext(range, body)
    }
  }

  private def runRangeInContext(range: HdlRange, body: GenIndex => Unit): Unit = {
    val context = current.get()
    requireNoRuntimeProcessMix(context, "generate-for", range.origin)
    if (context.activeScope.nonEmpty || context.activeConditional) {
      FrontendException.failAt(
        "MORPH-FRONTEND-NESTED-GENERATE-UNSUPPORTED",
        "nested HdlInt generate loops are not supported by the current frontend surface",
        range.origin
      )
    }
    if (range.start != 0) {
      FrontendException.failAt(
        "MORPH-FRONTEND-GENERATE-START-UNSUPPORTED",
        s"generate loops must start at zero, received ${range.start}",
        range.origin
      )
    }
    range.end.requireUsable("generate-loop count")
    val count = checkedIterationCount(range.end.witness, range.origin)
    val names = range.names.getOrElse(HdlRange.generatedNames(range.origin))

    context.mode match {
      case Concrete =>
        var index = 0
        while (index < count) {
          val token = new ScopeToken(names.index)
          try {
            withContext(context.copy(activeScope = Some(token))) {
              body(new GenIndex(BigInt(index), token, range.origin))
            }
          } finally {
            token.close()
          }
          index += 1
        }

      case Parameterized =>
        context.names.reserve(names, range.origin)
        val parentCollector = context.collector.getOrElse {
          FrontendException.failAt(
            "MORPH-FRONTEND-MISSING-COLLECTOR",
            "parameterized capture has no active module-item collector",
            range.origin
          )
        }
        val childCollector = ArrayBuffer.empty[FrontendNode[ModuleItem]]
        val token = new ScopeToken(names.index)
        var completed = false
        try {
          withContext(context.copy(collector = Some(childCollector), activeScope = Some(token))) {
            body(new GenIndex(BigInt(0), token, range.origin))
          }
          completed = true
        } finally {
          token.close()
          if (!completed) context.names.release(names)
        }
        if (completed) {
          parentCollector += FrontendNode(
            ModuleItem.GenerateFor(
              label = names.label,
              indexName = names.index,
              count = range.end.expression,
              body = childCollector.map(_.raw).toVector
            ),
            parameters = range.end.parameters ++ childCollector.flatMap(_.parameters),
            booleanParameters = range.end.booleanParameters ++
              childCollector.flatMap(_.booleanParameters),
            localParameters = range.end.localParameters ++ childCollector.flatMap(_.localParameters),
            booleanLocalParameters = range.end.booleanLocalParameters ++
              childCollector.flatMap(_.booleanLocalParameters),
            origin = range.origin
          )
          context.capturedGenerateFors += range.origin
        }
    }
  }

  private[frontend] def startGenerateIf(
      condition: HdlBool,
      names: Option[GenerateIfNames],
      whenTrue: => Unit,
      origin: SourceOrigin
  ): GenerateIfBuilder = {
    val context = current.get()
    if (context == null) {
      FrontendException.failAt(
        "MORPH-FRONTEND-SESSION-MISSING",
        "generateIf requires an explicit concrete or parameterized frontend session",
        origin
      )
    }
    if (condition eq null) {
      FrontendException.failAt(
        "MORPH-FRONTEND-BOOLEAN-CONDITION-NULL",
        "generateIf condition must not be null",
        origin
      )
    }
    requireNoNestedGenerate(context, origin)
    requireNoRuntimeProcessMix(context, "generate-if", origin)
    context.capturedConditionals.toVector.headOption.foreach { existing =>
      FrontendException.failAt(
        "MORPH-FRONTEND-GENERATE-IF-MULTIPLE",
        s"one module-item capture supports one conditional generate region; the existing region is at " +
          existing.origin.rendered,
        origin
      )
    }

    val resolvedNames = names.getOrElse(generatedIfNames(origin))
    context.names.reserve(resolvedNames, origin)
    var captured = false
    try {
      val trueItems = context.mode match {
        case Concrete =>
          if (condition.witness) {
            withContext(context.copy(activeConditional = true))(whenTrue)
          }
          Vector.empty
        case Parameterized => captureConditionalBranch(context, whenTrue)
      }
      val token = new GenerateIfToken(
        condition,
        resolvedNames,
        origin,
        context.session,
        context.collector,
        trueItems,
        parameterized = context.mode == Parameterized
      )
      context.pendingConditionals += token
      context.capturedConditionals += token
      captured = true
      new GenerateIfBuilder(token)
    } finally {
      if (!captured) context.names.release(resolvedNames)
    }
  }

  private[frontend] def completeGenerateIf(
      token: GenerateIfToken,
      whenFalse: => Unit,
      callOrigin: SourceOrigin
  ): Unit = {
    if (token.isCompleted) {
      FrontendException.failAt(
        "MORPH-FRONTEND-GENERATE-IF-OTHERWISE-DUPLICATE",
        "otherwise was already supplied for this generateIf",
        callOrigin
      )
    }
    val context = current.get()
    val active =
      context != null && token.session.isOpen && (context.session eq token.session) &&
        context.pendingConditionals(token) && token.isPending
    if (!active) {
      FrontendException.failAt(
        "MORPH-FRONTEND-GENERATE-IF-ESCAPED",
        "generateIf builder escaped the frontend session which created it",
        token.origin
      )
    }
    requireNoNestedGenerate(context, callOrigin)

    var completed = false
    try {
      if (token.parameterized) {
        val falseItems = captureConditionalBranch(context, whenFalse)
        val parent = token.parentCollector.getOrElse {
          FrontendException.failAt(
            "MORPH-FRONTEND-MISSING-COLLECTOR",
            "parameterized generateIf has no parent module-item collector",
            token.origin
          )
        }
        val currentCollector = context.collector.getOrElse {
          FrontendException.failAt(
            "MORPH-FRONTEND-MISSING-COLLECTOR",
            "parameterized capture has no active module-item collector",
            callOrigin
          )
        }
        if (!(parent eq currentCollector)) {
          FrontendException.failAt(
            "MORPH-FRONTEND-GENERATE-IF-ESCAPED",
            "otherwise was invoked from a foreign module-item collector",
            callOrigin
          )
        }
        val allItems = token.whenTrueItems ++ falseItems
        parent += FrontendNode(
          ModuleItem.GenerateIf(
            condition = token.condition.expression,
            whenTrue = GenerateBlock(
              token.names.whenTrue,
              token.whenTrueItems.map(_.raw)
            ),
            whenFalse = GenerateBlock(token.names.whenFalse, falseItems.map(_.raw))
          ),
          parameters = token.condition.integerParameters ++
            allItems.flatMap(_.parameters),
          booleanParameters = token.condition.parameters ++
            allItems.flatMap(_.booleanParameters),
          localParameters = token.condition.localParameters ++
            allItems.flatMap(_.localParameters),
          booleanLocalParameters = token.condition.booleanLocalParameters ++
            allItems.flatMap(_.booleanLocalParameters),
          scopes = allItems.flatMap(_.scopes).toSet,
          origin = token.origin
        )
      } else if (!token.condition.witness) {
        withContext(context.copy(activeConditional = true))(whenFalse)
      }
      completed = true
      token.complete()
      context.pendingConditionals -= token
    } finally {
      if (!completed) {
        token.fail()
        context.pendingConditionals -= token
        context.capturedConditionals -= token
        context.names.release(token.names)
      }
    }
  }

  private[frontend] def startGenerateCase(
      selector: HdlInt,
      origin: SourceOrigin
  ): GenerateCaseBuilder = {
    val context = current.get()
    if (context == null) {
      FrontendException.failAt(
        "MORPH-FRONTEND-SESSION-MISSING",
        "generateCase requires an explicit concrete or parameterized frontend session",
        origin
      )
    }
    if (selector eq null) {
      FrontendException.failAt(
        "MORPH-FRONTEND-GENERATE-CASE-SELECTOR-NULL",
        "generateCase selector must not be null",
        origin
      )
    }
    selector.requireLoopInvariant("generate-case selector")
    requireNoNestedGenerate(context, origin)
    requireNoRuntimeProcessMix(context, "generate-case", origin)
    context.capturedConditionals.toVector.headOption.foreach { existing =>
      FrontendException.failAt(
        "MORPH-FRONTEND-GENERATE-CASE-MULTIPLE",
        s"one module-item capture supports one conditional generate region; the existing region is at " +
          existing.origin.rendered,
        origin
      )
    }

    val token = new GenerateCaseToken(
      selector,
      origin,
      context.session,
      context.collector,
      parameterized = context.mode == Parameterized
    )
    context.pendingConditionals += token
    context.capturedConditionals += token
    new GenerateCaseBuilder(token)
  }

  private[frontend] def addGenerateCaseChoice(
      token: GenerateCaseToken,
      value: BigInt,
      label: String,
      body: => Unit,
      callOrigin: SourceOrigin
  ): Unit = {
    if (token.isCompleted) {
      FrontendException.failAt(
        "MORPH-FRONTEND-GENERATE-CASE-COMPLETED",
        "a choice cannot be appended after the generateCase default branch",
        callOrigin
      )
    }
    val context = requireActiveGenerateCase(token, callOrigin)
    if (value == null) {
      FrontendException.failAt(
        "MORPH-FRONTEND-GENERATE-CASE-CHOICE-NULL",
        "generateCase choice value must be a non-null integer literal",
        callOrigin
      )
    }
    if (token.containsChoice(value)) {
      FrontendException.failAt(
        "MORPH-FRONTEND-GENERATE-CASE-CHOICE-DUPLICATE",
        s"generateCase choice value '$value' is already present",
        callOrigin
      )
    }
    requireCaseParentCollector(token, context, callOrigin)
    context.names.reserveLabel(label, callOrigin)
    token.addReservedLabel(label)

    var completed = false
    try {
      val items = context.mode match {
        case Parameterized => captureConditionalBranch(context, body)
        case Concrete =>
          if (!token.hasConcreteMatch && token.selector.witness == value) {
            token.markConcreteMatch()
            withContext(context.copy(activeConditional = true))(body)
          }
          Vector.empty
      }
      token.addChoice(CapturedGenerateCaseChoice(value, label, items))
      completed = true
    } finally {
      if (!completed) failGenerateCase(token, context)
    }
  }

  private[frontend] def completeGenerateCase(
      token: GenerateCaseToken,
      defaultLabel: String,
      defaultBody: => Unit,
      callOrigin: SourceOrigin
  ): Unit = {
    if (token.isCompleted) {
      FrontendException.failAt(
        "MORPH-FRONTEND-GENERATE-CASE-DEFAULT-DUPLICATE",
        "the default branch was already supplied for this generateCase",
        callOrigin
      )
    }
    val context = requireActiveGenerateCase(token, callOrigin)
    if (token.choices.isEmpty) {
      FrontendException.failAt(
        "MORPH-FRONTEND-GENERATE-CASE-CHOICE-MISSING",
        "generateCase requires at least one explicit literal choice before its default branch",
        token.origin
      )
    }
    val parent = requireCaseParentCollector(token, context, callOrigin)
    context.names.reserveLabel(defaultLabel, callOrigin)
    token.addReservedLabel(defaultLabel)

    var completed = false
    try {
      context.mode match {
        case Parameterized =>
          val defaultItems = captureConditionalBranch(context, defaultBody)
          val sortedChoices = token.choices.sortBy(_.value)
          val allItems = sortedChoices.flatMap(_.items) ++ defaultItems
          parent.getOrElse {
            FrontendException.failAt(
              "MORPH-FRONTEND-MISSING-COLLECTOR",
              "parameterized generateCase has no parent module-item collector",
              token.origin
            )
          } += FrontendNode(
            ModuleItem.GenerateCase(
              selector = token.selector.expression,
              choices = sortedChoices.map { choice =>
                GenerateCaseChoice(
                  choice.value,
                  GenerateBlock(choice.label, choice.items.map(_.raw))
                )
              },
              default = GenerateBlock(defaultLabel, defaultItems.map(_.raw))
            ),
            parameters = token.selector.parameters ++ allItems.flatMap(_.parameters),
            booleanParameters = token.selector.booleanParameters ++
              allItems.flatMap(_.booleanParameters),
            localParameters = token.selector.localParameters ++ allItems.flatMap(_.localParameters),
            booleanLocalParameters = token.selector.booleanLocalParameters ++
              allItems.flatMap(_.booleanLocalParameters),
            scopes = allItems.flatMap(_.scopes).toSet,
            origin = token.origin
          )
        case Concrete =>
          if (!token.hasConcreteMatch) {
            withContext(context.copy(activeConditional = true))(defaultBody)
          }
      }
      token.complete()
      context.pendingConditionals -= token
      completed = true
    } finally {
      if (!completed) failGenerateCase(token, context)
    }
  }

  private def requireActiveGenerateCase(
      token: GenerateCaseToken,
      callOrigin: SourceOrigin
  ): Context = {
    val context = current.get()
    val active =
      context != null && token.session.isOpen && (context.session eq token.session) &&
        context.pendingConditionals(token) && token.isPending
    if (!active) {
      FrontendException.failAt(
        "MORPH-FRONTEND-GENERATE-CASE-ESCAPED",
        "generateCase builder escaped the frontend session which created it",
        token.origin
      )
    }
    requireNoNestedGenerate(context, callOrigin)
    context
  }

  private def requireCaseParentCollector(
      token: GenerateCaseToken,
      context: Context,
      callOrigin: SourceOrigin
  ): Option[ArrayBuffer[FrontendNode[ModuleItem]]] = {
    if (token.parameterized) {
      val parent = token.parentCollector.getOrElse {
        FrontendException.failAt(
          "MORPH-FRONTEND-MISSING-COLLECTOR",
          "parameterized generateCase has no parent module-item collector",
          token.origin
        )
      }
      val currentCollector = context.collector.getOrElse {
        FrontendException.failAt(
          "MORPH-FRONTEND-MISSING-COLLECTOR",
          "parameterized capture has no active module-item collector",
          callOrigin
        )
      }
      if (!(parent eq currentCollector)) {
        FrontendException.failAt(
          "MORPH-FRONTEND-GENERATE-CASE-ESCAPED",
          "generateCase builder was continued from a foreign module-item collector",
          callOrigin
        )
      }
      Some(parent)
    } else None
  }

  private def failGenerateCase(token: GenerateCaseToken, context: Context): Unit = {
    token.fail()
    context.pendingConditionals -= token
    context.capturedConditionals -= token
    context.names.releaseLabels(token.reservedLabels)
  }

  private def captureConditionalBranch(
      context: Context,
      body: => Unit
  ): Vector[FrontendNode[ModuleItem]] = {
    val childCollector = ArrayBuffer.empty[FrontendNode[ModuleItem]]
    withContext(
      context.copy(
        collector = Some(childCollector),
        activeConditional = true
      )
    )(body)
    childCollector.toVector
  }

  private def requireNoNestedGenerate(context: Context, origin: SourceOrigin): Unit =
    if (context.activeScope.nonEmpty || context.activeConditional) {
      FrontendException.failAt(
        "MORPH-FRONTEND-NESTED-GENERATE-UNSUPPORTED",
        "nested generate-if, generate-case and generate-for regions are not supported by the current frontend surface",
        origin
      )
    }

  private def requireNoRuntimeProcessMix(
      context: Context,
      operation: String,
      origin: SourceOrigin
  ): Unit =
    context.capturedProcesses.headOption.foreach { existing =>
      FrontendException.failAt(
        mixedCode(existing.kind),
        s"$operation cannot share one module-item capture with the ${existing.kind.description} at " +
          existing.origin.rendered,
        origin
      )
    }

  private def requireNoPendingConditional(context: Context): Unit =
    context.pendingConditionals.toVector
      .sortBy(token => (token.origin.file, token.origin.line))
      .headOption
      .foreach { token =>
        token match {
          case _: GenerateIfToken =>
            FrontendException.failAt(
              "MORPH-FRONTEND-GENERATE-IF-OTHERWISE-MISSING",
              "generateIf must be completed with exactly one otherwise branch",
              token.origin
            )
          case _: GenerateCaseToken =>
            FrontendException.failAt(
              "MORPH-FRONTEND-GENERATE-CASE-DEFAULT-MISSING",
              "generateCase must be completed with exactly one default branch",
              token.origin
            )
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

  private[frontend] def emit(item: FrontendNode[ModuleItem]): Unit = {
    val context = current.get()
    if (context == null || context.mode != Parameterized) {
      FrontendException.failAt(
        "MORPH-FRONTEND-EMIT-OUTSIDE-CAPTURE",
        "ParamRTL module items may be emitted only during parameterized capture",
        item.origin
      )
    }
    item.requireUsable("module-item emission")
    val topLevel = context.activeScope.isEmpty && !context.activeConditional
    if (topLevel) {
      context.capturedProcesses.headOption.foreach { existing =>
        FrontendException.failAt(
          mixedCode(existing.kind),
          s"${ordinaryItemKind(item.raw)} cannot share one module-item capture with the " +
            s"${existing.kind.description} at ${existing.origin.rendered}",
          item.origin
        )
      }
    }
    val collector = context.collector.getOrElse {
      FrontendException.failAt(
        "MORPH-FRONTEND-MISSING-COLLECTOR",
        "parameterized capture has no active module-item collector",
        item.origin
      )
    }
    collector += FrontendNode(
      item.raw,
      parameters = item.parameters,
      booleanParameters = item.booleanParameters,
      localParameters = item.localParameters,
      booleanLocalParameters = item.booleanLocalParameters,
      origin = item.origin
    )
    if (topLevel) context.capturedOrdinaryItems += item.origin -> ordinaryItemKind(item.raw)
  }

  private[frontend] def emitCombinationalIf(item: FrontendNode[ModuleItem]): Unit =
    emitRuntimeProcess(item, CombinationalProcessKind)

  private[frontend] def emitSynchronousRegister(item: FrontendNode[ModuleItem]): Unit =
    emitRuntimeProcess(item, SynchronousRegisterKind)

  private[frontend] def emitAsynchronousRegister(item: FrontendNode[ModuleItem]): Unit =
    emitRuntimeProcess(item, AsynchronousRegisterKind)

  private[frontend] def emitSynchronousEnabledRegister(
      item: FrontendNode[ModuleItem]
  ): Unit =
    emitRuntimeProcess(item, SynchronousEnabledRegisterKind)

  private[frontend] def emitAsynchronousEnabledRegister(
      item: FrontendNode[ModuleItem]
  ): Unit =
    emitRuntimeProcess(item, AsynchronousEnabledRegisterKind)

  private[frontend] def emitSynchronousReadFirstSinglePortMemory(
      item: FrontendNode[ModuleItem]
  ): Unit =
    emitRuntimeProcess(item, SynchronousReadFirstSinglePortMemoryKind)

  private[frontend] def emitSynchronousCounter(item: FrontendNode[ModuleItem]): Unit =
    emitRuntimeProcess(item, SynchronousCounterKind)

  private def emitRuntimeProcess(
      item: FrontendNode[ModuleItem],
      kind: RuntimeProcessKind
  ): Unit = {
    val context = current.get()
    if (context == null || context.mode != Parameterized) {
      FrontendException.failAt(
        "MORPH-FRONTEND-EMIT-OUTSIDE-CAPTURE",
        s"a ${kind.description} may be emitted only during parameterized capture",
        item.origin
      )
    }
    if (context.activeScope.nonEmpty || context.activeConditional) {
      FrontendException.failAt(
        nestedCode(kind),
        s"a ${kind.description} cannot be nested in generate-if, generate-case or generate-for",
        item.origin
      )
    }
    context.capturedProcesses.headOption.foreach { existing =>
      val code =
        if (existing.kind == kind) multipleCode(kind)
        else "MORPH-FRONTEND-RUNTIME-PROCESS-MIXED"
      val detail =
        if (existing.kind == kind)
          s"one module-item capture supports one ${kind.description}; the existing process is at " +
            existing.origin.rendered
        else
          s"a ${kind.description} cannot share one module-item capture with the " +
            s"${existing.kind.description} at ${existing.origin.rendered}"
      FrontendException.failAt(code, detail, item.origin)
    }
    context.capturedOrdinaryItems.headOption.foreach { case (existing, itemKind) =>
      FrontendException.failAt(
        mixedCode(kind),
        s"a ${kind.description} cannot share one module-item capture with $itemKind at " +
          existing.rendered,
        item.origin
      )
    }
    val existingGenerate = context.capturedConditionals.toVector
      .map(_.origin)
      .sortBy(origin => (origin.file, origin.line))
      .headOption
      .orElse(context.capturedGenerateFors.headOption)
    existingGenerate.foreach { existing =>
      FrontendException.failAt(
        mixedCode(kind),
        s"a ${kind.description} cannot share one module-item capture with the generate region at " +
          existing.rendered,
        item.origin
      )
    }

    item.requireUsable(s"${kind.description} emission")
    val collector = context.collector.getOrElse {
      FrontendException.failAt(
        "MORPH-FRONTEND-MISSING-COLLECTOR",
        "parameterized capture has no active module-item collector",
        item.origin
      )
    }
    collector += FrontendNode(
      item.raw,
      parameters = item.parameters,
      booleanParameters = item.booleanParameters,
      localParameters = item.localParameters,
      booleanLocalParameters = item.booleanLocalParameters,
      origin = item.origin
    )
    context.capturedProcesses += CapturedRuntimeProcess(kind, item.origin)
  }

  private def mixedCode(kind: RuntimeProcessKind): String = kind match {
    case CombinationalProcessKind => "MORPH-FRONTEND-COMBINATIONAL-PROCESS-MIXED"
    case SynchronousRegisterKind  => "MORPH-FRONTEND-SYNCHRONOUS-REGISTER-MIXED"
    case AsynchronousRegisterKind => "MORPH-FRONTEND-ASYNCHRONOUS-REGISTER-MIXED"
    case SynchronousEnabledRegisterKind =>
      "MORPH-FRONTEND-SYNCHRONOUS-ENABLED-REGISTER-MIXED"
    case AsynchronousEnabledRegisterKind =>
      "MORPH-FRONTEND-ASYNCHRONOUS-ENABLED-REGISTER-MIXED"
    case SynchronousReadFirstSinglePortMemoryKind =>
      "MORPH-FRONTEND-SINGLE-PORT-MEMORY-MIXED"
    case SynchronousCounterKind =>
      "MORPH-FRONTEND-SYNCHRONOUS-COUNTER-MIXED"
  }

  private def nestedCode(kind: RuntimeProcessKind): String = kind match {
    case CombinationalProcessKind => "MORPH-FRONTEND-COMBINATIONAL-PROCESS-NESTED"
    case SynchronousRegisterKind  => "MORPH-FRONTEND-SYNCHRONOUS-REGISTER-NESTED"
    case AsynchronousRegisterKind => "MORPH-FRONTEND-ASYNCHRONOUS-REGISTER-NESTED"
    case SynchronousEnabledRegisterKind =>
      "MORPH-FRONTEND-SYNCHRONOUS-ENABLED-REGISTER-NESTED"
    case AsynchronousEnabledRegisterKind =>
      "MORPH-FRONTEND-ASYNCHRONOUS-ENABLED-REGISTER-NESTED"
    case SynchronousReadFirstSinglePortMemoryKind =>
      "MORPH-FRONTEND-SINGLE-PORT-MEMORY-NESTED"
    case SynchronousCounterKind =>
      "MORPH-FRONTEND-SYNCHRONOUS-COUNTER-NESTED"
  }

  private def multipleCode(kind: RuntimeProcessKind): String = kind match {
    case CombinationalProcessKind => "MORPH-FRONTEND-COMBINATIONAL-PROCESS-MULTIPLE"
    case SynchronousRegisterKind  => "MORPH-FRONTEND-SYNCHRONOUS-REGISTER-MULTIPLE"
    case AsynchronousRegisterKind => "MORPH-FRONTEND-ASYNCHRONOUS-REGISTER-MULTIPLE"
    case SynchronousEnabledRegisterKind =>
      "MORPH-FRONTEND-SYNCHRONOUS-ENABLED-REGISTER-MULTIPLE"
    case AsynchronousEnabledRegisterKind =>
      "MORPH-FRONTEND-ASYNCHRONOUS-ENABLED-REGISTER-MULTIPLE"
    case SynchronousReadFirstSinglePortMemoryKind =>
      "MORPH-FRONTEND-SINGLE-PORT-MEMORY-MULTIPLE"
    case SynchronousCounterKind =>
      "MORPH-FRONTEND-SYNCHRONOUS-COUNTER-MULTIPLE"
  }

  private def ordinaryItemKind(item: ModuleItem): String = item match {
    case _: ModuleItem.ContinuousAssign => "a continuous assignment"
    case _: ModuleItem.ModuleInstance   => "a module instance"
    case _                              => "another module item"
  }

  private[frontend] def requireActiveScope(
      token: ScopeToken,
      consumer: String,
      origin: SourceOrigin
  ): Unit = {
    val context = current.get()
    val active = context != null && context.activeScope.exists(_ eq token)
    if (!token.isOpen || !active) {
      FrontendException.failAt(
        "MORPH-FRONTEND-GENINDEX-ESCAPED",
        s"generate index '${token.indexName}' escaped its lexical scope before $consumer",
        origin
      )
    }
  }

  private def checkedIterationCount(value: BigInt, origin: SourceOrigin): Int = {
    if (value <= 0) {
      FrontendException.failAt(
        "MORPH-FRONTEND-GENERATE-COUNT-NONPOSITIVE",
        s"generate-loop concrete witness must be positive, received $value",
        origin
      )
    }
    if (!value.isValidInt) {
      FrontendException.failAt(
        "MORPH-FRONTEND-GENERATE-COUNT-TOO-LARGE",
        s"generate-loop concrete witness does not fit a Scala Int: $value",
        origin
      )
    }
    value.toInt
  }

  private def requireNoSession(operation: String, origin: SourceOrigin): Unit =
    if (current.get() != null) {
      FrontendException.failAt(
        "MORPH-FRONTEND-SESSION-NESTED",
        s"cannot start $operation while another frontend session is active",
        origin
      )
    }

  private def withContext[A](context: Context)(body: => A): A = {
    val previous = current.get()
    current.set(context)
    try body
    finally {
      if (previous == null) current.remove()
      else current.set(previous)
    }
  }
}
