package morphhdl.frontend

import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable

import morphhdl.paramrtl.{GenerateBlock, ModuleItem}

private[frontend] final case class GenerateIfNames(whenTrue: String, whenFalse: String)

private[frontend] final class FrontendSessionToken {
  @volatile private var open = true

  def isOpen: Boolean = open
  def close(): Unit = open = false
}

private[frontend] final class GenerateIfToken(
    val condition: HdlBool,
    val names: GenerateIfNames,
    val origin: SourceOrigin,
    val session: FrontendSessionToken,
    val parentCollector: Option[ArrayBuffer[FrontendNode[ModuleItem]]],
    val whenTrueItems: Vector[FrontendNode[ModuleItem]],
    val parameterized: Boolean
) {
  private var state = 0

  def isPending: Boolean = state == 0
  def isCompleted: Boolean = state == 1
  def complete(): Unit = state = 1
  def fail(): Unit = state = 2
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
  }

  private final case class Context(
      mode: Mode,
      collector: Option[ArrayBuffer[FrontendNode[ModuleItem]]],
      activeScope: Option[ScopeToken],
      activeGenerateIf: Boolean,
      names: NameRegistry,
      session: FrontendSessionToken,
      pendingGenerateIfs: mutable.Set[GenerateIfToken],
      capturedGenerateIfs: mutable.Set[GenerateIfToken]
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
      activeGenerateIf = false,
      new NameRegistry,
      session,
      mutable.Set.empty,
      mutable.Set.empty
    )
    try {
      withContext(context) {
        val result = body
        requireNoPendingGenerateIf(context)
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
      activeGenerateIf = false,
      new NameRegistry,
      session,
      mutable.Set.empty,
      mutable.Set.empty
    )
    try {
      withContext(context) {
        body
        requireNoPendingGenerateIf(context)
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
    if (context.activeScope.nonEmpty || context.activeGenerateIf) {
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
    context.capturedGenerateIfs.toVector.headOption.foreach { existing =>
      FrontendException.failAt(
        "MORPH-FRONTEND-GENERATE-IF-MULTIPLE",
        s"one module-item capture supports one generateIf; the existing conditional is at " +
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
            withContext(context.copy(activeGenerateIf = true))(whenTrue)
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
      context.pendingGenerateIfs += token
      context.capturedGenerateIfs += token
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
        context.pendingGenerateIfs(token) && token.isPending
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
        withContext(context.copy(activeGenerateIf = true))(whenFalse)
      }
      completed = true
      token.complete()
      context.pendingGenerateIfs -= token
    } finally {
      if (!completed) {
        token.fail()
        context.pendingGenerateIfs -= token
        context.capturedGenerateIfs -= token
        context.names.release(token.names)
      }
    }
  }

  private def captureConditionalBranch(
      context: Context,
      body: => Unit
  ): Vector[FrontendNode[ModuleItem]] = {
    val childCollector = ArrayBuffer.empty[FrontendNode[ModuleItem]]
    withContext(
      context.copy(
        collector = Some(childCollector),
        activeGenerateIf = true
      )
    )(body)
    childCollector.toVector
  }

  private def requireNoNestedGenerate(context: Context, origin: SourceOrigin): Unit =
    if (context.activeScope.nonEmpty || context.activeGenerateIf) {
      FrontendException.failAt(
        "MORPH-FRONTEND-NESTED-GENERATE-UNSUPPORTED",
        "nested generate-if and generate-for regions are not supported by the current frontend surface",
        origin
      )
    }

  private def requireNoPendingGenerateIf(context: Context): Unit =
    context.pendingGenerateIfs.toVector
      .sortBy(token => (token.origin.file, token.origin.line))
      .headOption
      .foreach { token =>
        FrontendException.failAt(
          "MORPH-FRONTEND-GENERATE-IF-OTHERWISE-MISSING",
          "generateIf must be completed with exactly one otherwise branch",
          token.origin
        )
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
    context.collector.getOrElse {
      FrontendException.failAt(
        "MORPH-FRONTEND-MISSING-COLLECTOR",
        "parameterized capture has no active module-item collector",
        item.origin
      )
    } += FrontendNode(
      item.raw,
      parameters = item.parameters,
      booleanParameters = item.booleanParameters,
      localParameters = item.localParameters,
      booleanLocalParameters = item.booleanLocalParameters,
      origin = item.origin
    )
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
