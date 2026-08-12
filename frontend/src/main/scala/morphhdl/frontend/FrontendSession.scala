package morphhdl.frontend

import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable

import morphhdl.paramrtl.ModuleItem

private[frontend] object FrontendSession {
  private sealed trait Mode
  private case object Concrete extends Mode
  private case object Parameterized extends Mode

  private final class NameRegistry {
    private val labels = mutable.Set.empty[String]
    private val indices = mutable.Set.empty[String]

    def reserve(names: GenerateNames, origin: SourceOrigin): Unit = {
      val allNames = labels ++ indices
      if (names.label == names.index || allNames(names.label) || allNames(names.index)) {
        FrontendException.failAt(
          "MORPH-FRONTEND-GENERATE-NAME-DUPLICATE",
          s"generate label '${names.label}' or index '${names.index}' is already used in this capture",
          origin
        )
      }
      labels += names.label
      indices += names.index
    }

    def release(names: GenerateNames): Unit = {
      labels -= names.label
      indices -= names.index
    }
  }

  private final case class Context(
      mode: Mode,
      collector: Option[ArrayBuffer[FrontendNode[ModuleItem]]],
      activeScope: Option[ScopeToken],
      names: NameRegistry
  )

  private val current = new ThreadLocal[Context]

  def concrete[A](body: => A)(implicit file: sourcecode.File, line: sourcecode.Line): A = {
    val origin = SourceOrigin.capture
    requireNoSession("concrete elaboration", origin)
    withContext(Context(Concrete, None, None, new NameRegistry))(body)
  }

  def captureItems(body: => Unit, origin: SourceOrigin): FrontendNode[Vector[ModuleItem]] = {
    requireNoSession("parameterized capture", origin)
    val items = ArrayBuffer.empty[FrontendNode[ModuleItem]]
    withContext(Context(Parameterized, Some(items), None, new NameRegistry)) {
      body
      FrontendNode(
        items.map(_.raw).toVector,
        parameters = items.flatMap(_.parameters).toSet,
        localParameters = items.flatMap(_.localParameters).toSet,
        scopes = items.flatMap(_.scopes).toSet,
        origin = origin
      )
    }
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
    if (context.activeScope.nonEmpty) {
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
            localParameters = range.end.localParameters ++ childCollector.flatMap(_.localParameters),
            origin = range.origin
          )
        }
    }
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
      localParameters = item.localParameters,
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
