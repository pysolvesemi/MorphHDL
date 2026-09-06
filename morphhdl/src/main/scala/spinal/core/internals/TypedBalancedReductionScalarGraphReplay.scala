package spinal.core.internals

import java.util.IdentityHashMap
import scala.collection.mutable.ArrayBuffer
import spinal.core._
import TypedBalancedReductionValueEvidence.Evidence

/** Exact native scalar graph transfer. This certificate does not certify host
  * code, execute a callback, or change the native balanced-tree topology.
  * In particular ordered subtraction is replayed in its original operand order.
  */
private[spinal] object TypedBalancedReductionScalarGraphReplay {
  private def fail(code: String, detail: String): Nothing =
    throw new IllegalArgumentException(s"MORPH-REDUCE-BALANCED-GRAPH-REPLAY-$code: $detail")

  private val scalarClasses = Set[Class[_]](classOf[Bool], classOf[Bits], classOf[UInt], classOf[SInt])
  private val binaries: Map[Class[_], () => BinaryOperator] = Map(
    classOf[Operator.Bool.And] -> (() => new Operator.Bool.And),
    classOf[Operator.Bool.Or] -> (() => new Operator.Bool.Or),
    classOf[Operator.Bool.Xor] -> (() => new Operator.Bool.Xor),
    classOf[Operator.Bool.Equal] -> (() => new Operator.Bool.Equal),
    classOf[Operator.Bool.NotEqual] -> (() => new Operator.Bool.NotEqual),
    classOf[Operator.Bits.And] -> (() => new Operator.Bits.And),
    classOf[Operator.Bits.Or] -> (() => new Operator.Bits.Or),
    classOf[Operator.Bits.Xor] -> (() => new Operator.Bits.Xor),
    classOf[Operator.Bits.Equal] -> (() => new Operator.Bits.Equal),
    classOf[Operator.Bits.NotEqual] -> (() => new Operator.Bits.NotEqual),
    classOf[Operator.Bits.Cat] -> (() => new Operator.Bits.Cat),
    classOf[Operator.UInt.And] -> (() => new Operator.UInt.And),
    classOf[Operator.UInt.Or] -> (() => new Operator.UInt.Or),
    classOf[Operator.UInt.Xor] -> (() => new Operator.UInt.Xor),
    classOf[Operator.UInt.Add] -> (() => new Operator.UInt.Add),
    classOf[Operator.UInt.Sub] -> (() => new Operator.UInt.Sub),
    classOf[Operator.UInt.Mul] -> (() => new Operator.UInt.Mul),
    classOf[Operator.UInt.Smaller] -> (() => new Operator.UInt.Smaller),
    classOf[Operator.UInt.SmallerOrEqual] -> (() => new Operator.UInt.SmallerOrEqual),
    classOf[Operator.UInt.Equal] -> (() => new Operator.UInt.Equal),
    classOf[Operator.UInt.NotEqual] -> (() => new Operator.UInt.NotEqual),
    classOf[Operator.SInt.And] -> (() => new Operator.SInt.And),
    classOf[Operator.SInt.Or] -> (() => new Operator.SInt.Or),
    classOf[Operator.SInt.Xor] -> (() => new Operator.SInt.Xor),
    classOf[Operator.SInt.Add] -> (() => new Operator.SInt.Add),
    classOf[Operator.SInt.Sub] -> (() => new Operator.SInt.Sub),
    classOf[Operator.SInt.Mul] -> (() => new Operator.SInt.Mul),
    classOf[Operator.SInt.Smaller] -> (() => new Operator.SInt.Smaller),
    classOf[Operator.SInt.SmallerOrEqual] -> (() => new Operator.SInt.SmallerOrEqual),
    classOf[Operator.SInt.Equal] -> (() => new Operator.SInt.Equal),
    classOf[Operator.SInt.NotEqual] -> (() => new Operator.SInt.NotEqual)
  )
  private val unaries: Map[Class[_], () => UnaryOperator] = Map(
    classOf[Operator.Bool.Not] -> (() => new Operator.Bool.Not),
    classOf[Operator.Bits.Not] -> (() => new Operator.Bits.Not),
    classOf[Operator.UInt.Not] -> (() => new Operator.UInt.Not),
    classOf[Operator.SInt.Not] -> (() => new Operator.SInt.Not),
    classOf[Operator.BitVector.orR] -> (() => new Operator.BitVector.orR),
    classOf[Operator.BitVector.andR] -> (() => new Operator.BitVector.andR),
    classOf[Operator.BitVector.xorR] -> (() => new Operator.BitVector.xorR)
  )
  private val casts: Map[Class[_], () => Cast] = Map(
    classOf[CastUIntToBits] -> (() => new CastUIntToBits),
    classOf[CastSIntToBits] -> (() => new CastSIntToBits),
    classOf[CastBitsToUInt] -> (() => new CastBitsToUInt),
    classOf[CastBitsToSInt] -> (() => new CastBitsToSInt),
    classOf[CastUIntToSInt] -> (() => new CastUIntToSInt),
    classOf[CastSIntToUInt] -> (() => new CastSIntToUInt),
    classOf[CastBoolToBits] -> (() => new CastBoolToBits)
  )
  private val resizes: Map[Class[_], () => Resize] = Map(
    classOf[ResizeBits] -> (() => new ResizeBits),
    classOf[ResizeUInt] -> (() => new ResizeUInt),
    classOf[ResizeSInt] -> (() => new ResizeSInt)
  )
  private val muxes: Map[Class[_], () => BinaryMultiplexer] = Map(
    classOf[BinaryMultiplexerBool] -> (() => new BinaryMultiplexerBool),
    classOf[BinaryMultiplexerBits] -> (() => new BinaryMultiplexerBits),
    classOf[BinaryMultiplexerUInt] -> (() => new BinaryMultiplexerUInt),
    classOf[BinaryMultiplexerSInt] -> (() => new BinaryMultiplexerSInt)
  )
  private val bitAccesses: Map[Class[_], () => BitVectorBitAccessFixed] = Map(
    classOf[BitsBitAccessFixed] -> (() => new BitsBitAccessFixed),
    classOf[UIntBitAccessFixed] -> (() => new UIntBitAccessFixed),
    classOf[SIntBitAccessFixed] -> (() => new SIntBitAccessFixed)
  )
  private val ranges: Map[Class[_], () => BitVectorRangedAccessFixed] = Map(
    classOf[BitsRangedAccessFixed] -> (() => new BitsRangedAccessFixed),
    classOf[UIntRangedAccessFixed] -> (() => new UIntRangedAccessFixed),
    classOf[SIntRangedAccessFixed] -> (() => new SIntRangedAccessFixed)
  )
  private val comparisonKinds: Map[Class[_], AnyRef] = Map(
    classOf[Operator.Bool.Equal] -> TypeBool, classOf[Operator.Bool.NotEqual] -> TypeBool,
    classOf[Operator.Bits.Equal] -> TypeBits, classOf[Operator.Bits.NotEqual] -> TypeBits,
    classOf[Operator.UInt.Smaller] -> TypeUInt, classOf[Operator.UInt.SmallerOrEqual] -> TypeUInt,
    classOf[Operator.UInt.Equal] -> TypeUInt, classOf[Operator.UInt.NotEqual] -> TypeUInt,
    classOf[Operator.SInt.Smaller] -> TypeSInt, classOf[Operator.SInt.SmallerOrEqual] -> TypeSInt,
    classOf[Operator.SInt.Equal] -> TypeSInt, classOf[Operator.SInt.NotEqual] -> TypeSInt)
  private val castInputKinds: Map[Class[_], AnyRef] = Map(
    classOf[CastUIntToBits] -> TypeUInt, classOf[CastSIntToBits] -> TypeSInt,
    classOf[CastBitsToUInt] -> TypeBits, classOf[CastBitsToSInt] -> TypeBits,
    classOf[CastUIntToSInt] -> TypeUInt, classOf[CastSIntToUInt] -> TypeSInt,
    classOf[CastBoolToBits] -> TypeBool)
  private val selectInputKinds: Map[Class[_], AnyRef] = Map(
    classOf[BitsBitAccessFixed] -> TypeBits, classOf[UIntBitAccessFixed] -> TypeUInt,
    classOf[SIntBitAccessFixed] -> TypeSInt, classOf[BitsRangedAccessFixed] -> TypeBits,
    classOf[UIntRangedAccessFixed] -> TypeUInt, classOf[SIntRangedAccessFixed] -> TypeSInt)

  private final class Identity(val value: AnyRef) {
    override def equals(other: Any): Boolean = other match {
      case that: Identity => value eq that.value
      case _ => false
    }
    override def hashCode(): Int = System.identityHashCode(value)
  }
  private final class WidthKey(val width: ElaborationIntegerExpression) {
    override def equals(other: Any): Boolean = other match {
      case that: WidthKey => ElabInt.equivalentExactFunction(width, that.width)
      case _ => false
    }
    // Width authority compares exact functions and root identities, never text.
    override def hashCode(): Int = 1
  }
  private final case class Key(kind: Any, width: WidthKey, properties: Vector[Any], children: Vector[Key])
  private final class Node(val kind: AnyRef, val width: ElaborationIntegerExpression,
      val key: Key, val children: Vector[Node], val build: Vector[BaseType] => BaseType)

  private def lit(value: Int): ElaborationIntegerExpression = ElabInt.literal(value).expression
  private def add(a: ElaborationIntegerExpression, b: ElaborationIntegerExpression): ElaborationIntegerExpression =
    (ElabInt.fromExpression(a) + ElabInt.fromExpression(b)).expression
  private def maximum(a: ElaborationIntegerExpression, b: ElaborationIntegerExpression): ElaborationIntegerExpression = {
    if (ElabInt.equivalentExactFunction(a, b)) a
    else if ((ElabInt.fromExpression(a) - ElabInt.fromExpression(b)).expression.minimum >= 0) a
    else if ((ElabInt.fromExpression(b) - ElabInt.fromExpression(a)).expression.minimum >= 0) b
    else fail("WIDTH-ORDER", "native maximum width requires a proven ordering over the entire typed domain")
  }
  private def scalar(kind: AnyRef): BaseType = {
    if (kind eq TypeBool) Bool()
    else if (kind eq TypeBits) Bits()
    else if (kind eq TypeUInt) UInt()
    else if (kind eq TypeSInt) SInt()
    else fail("TYPE", "only exact Bool, Bits, UInt and SInt kinds are replayable")
  }
  private def wrap(expression: Expression, width: ElaborationIntegerExpression): BaseType = {
    val result = scalar(expression.getTypeObject.asInstanceOf[AnyRef]).setAsTypeNode()
    result.assignFrom(expression)
    result match {
      case bits: BitVector if width.parameters.isEmpty && width.default == 0 => bits.setWidth(0)
      case bits: BitVector => expression match {
        case _: Resize => ParameterizedWidth.attachResize(bits, ElabInt.fromExpression(width))
        case _ => ParameterizedWidth.attach(bits, ElabInt.fromExpression(width).bits)
      }
      case _ =>
    }
    // Keep each proved symbolic transfer on its exact native carrier through
    // normalization. Inlining a typed resize or its intermediate source would
    // erase the identity needed by the ordinary structural width publisher.
    if (width.parameters.nonEmpty) {
      result.dontSimplifyIt()
      result.noBackendCombMerge()
    }
    result
  }
  private def same[A <: AnyRef](a: Vector[A], b: Vector[A]): Boolean =
    a.size == b.size && a.zip(b).forall { case (left, right) => left eq right }
  private def assignmentsOf(owner: Component, target: BaseType): Vector[AssignmentStatement] = {
    val out = ArrayBuffer.empty[AssignmentStatement]
    owner.dslBody.walkStatements {
      case value: AssignmentStatement if value.finalTarget eq target => out += value
      case _ =>
    }
    out.toVector
  }

  final class Proof private[TypedBalancedReductionScalarGraphReplay] (
      val nativeResult: BaseType,
      val resultWidth: ElaborationIntegerExpression,
      val operatorClass: Class[_],
      private val root: Node,
      private val owner: Component,
      private val inputs: Vector[Evidence],
      private val captureEvidence: Vector[Evidence],
      private val guards: Vector[() => Unit]
  ) extends TypedBalancedReductionOperatorCertificate {
    val operationKey: Any = root.key
    def validateFreshness(): Unit = guards.foreach(_.apply())
    def replay(left: BaseType, right: BaseType): BaseType = {
      validateFreshness()
      if (Component.current ne owner) fail("OWNER", "replay must remain in its exact owning component")
      inputs(0).requireReplacement(left)
      inputs(1).requireReplacement(right)
      val cache = new IdentityHashMap[Node, BaseType]()
      def emit(node: Node): BaseType = {
        val existing = cache.get(node)
        if (existing != null) existing
        else {
          val value = node.key.kind match {
            case "left" => left
            case "right" => right
            case "capture" => captureEvidence(node.key.properties.head.asInstanceOf[Int]).value
            case _ => node.build(node.children.map(emit))
          }
          cache.put(node, value)
          value
        }
      }
      emit(root)
    }
  }

  def certify(callback: UnvalidatedBalancedCallback, inputEvidence: Vector[Evidence],
      captures: Vector[BaseType] = Vector.empty): Proof = {
    if (callback == null || callback.operands == null || callback.operands.size != 2 ||
        callback.operands.exists(_ == null) || callback.result == null || inputEvidence == null ||
        inputEvidence.size != 2 || inputEvidence.exists(_ == null) || captures == null || captures.exists(_ == null))
      fail("EVIDENCE", "two exact scalar operands, input certificates and explicit captures are required")
    val operands = callback.operands.map {
      case value: BaseType if scalarClasses.contains(value.getClass) => value
      case _ => fail("TYPE", "graph operators require exact native scalar operands")
    }
    val result = callback.result match {
      case value: BaseType if scalarClasses.contains(value.getClass) => value
      case _ => fail("TYPE", "graph operators require an exact native scalar result")
    }
    operands.zip(inputEvidence).foreach { case (value, evidence) => evidence.requireValue(value) }
    val owner = operands.head.component
    if (owner == null || (Component.current ne owner) || (operands(0) eq operands(1)))
      fail("OWNER", "two distinct operands must belong to the active native component")
    val inputWidth = inputEvidence.head.width
    val inputKind = inputEvidence.head.kind
    inputEvidence.foreach { evidence =>
      if ((evidence.owner ne owner) || (evidence.kind ne inputKind) ||
          !ElabInt.equivalentExactFunction(evidence.width, inputWidth))
        fail("INPUT-SHAPE", "fixed-result graph inputs must retain one exact scalar shape")
    }
    val captureEvidence = captures.map(TypedBalancedReductionValueEvidence.input)
    val captureIds = new IdentityHashMap[BaseType, java.lang.Integer]()
    captureEvidence.zipWithIndex.foreach { case (evidence, index) =>
      if ((evidence.owner ne owner) || operands.exists(_ eq evidence.value))
        fail("CAPTURE", "captures must be exact read-only hardware identities separate from the pair and in the same owner")
      // JVM capture slots may alias one signal. Keep every slot's binding
      // certificate while the native graph binds that signal by exact identity.
      if (!captureIds.containsKey(evidence.value))
        captureIds.put(evidence.value, java.lang.Integer.valueOf(index))
    }
    if (callback.declarations == null || callback.assignments == null || callback.statements == null)
      fail("INVENTORY", "callback declaration and assignment inventories are required")
    val declared = new IdentityHashMap[BaseType, java.lang.Boolean]()
    callback.declarations.foreach { value =>
      if (value == null || declared.put(value, java.lang.Boolean.TRUE) != null ||
          operands.exists(_ eq value) || captureIds.containsKey(value))
        fail("INVENTORY", "local declarations cannot repeat or relabel an input or capture")
    }
    val recorded = new IdentityHashMap[AssignmentStatement, java.lang.Boolean]()
    callback.assignments.foreach { value =>
      if (value == null || recorded.put(value, java.lang.Boolean.TRUE) != null ||
          !declared.containsKey(value.finalTarget) || (value.target ne value.finalTarget) ||
          value.getClass != classOf[DataAssignmentStatement])
        fail("DRIVER", "only captured full-object combinational local writes are permitted")
    }
    val consumed = new IdentityHashMap[AssignmentStatement, java.lang.Boolean]()
    val consumedWhens = new IdentityHashMap[WhenStatement, java.lang.Boolean]()
    val nodes = new IdentityHashMap[Expression, Node]()
    val visiting = new IdentityHashMap[Expression, java.lang.Boolean]()
    val guards = ArrayBuffer.empty[() => Unit]
    (inputEvidence ++ captureEvidence).foreach(e => guards += (() => e.requireFreshness()))
    captureEvidence.foreach { evidence =>
      guards += (() => {
        val scope = evidence.value.parentScope
        if (scope == null || !scope.statementIterable.exists(statement => statement eq evidence.value))
          fail("CAPTURE-LIFETIME", "captured hardware must retain its live native declaration lifetime")
      })
    }
    // Added statement identity and traversal order also cover removal/reparenting
    // of conditionals, which cannot be detected from expression pointers alone.
    val statementIds = new IdentityHashMap[Statement, java.lang.Boolean]()
    callback.statements.foreach { statement =>
      if (statement == null || statementIds.put(statement, java.lang.Boolean.TRUE) != null ||
          !(statement.isInstanceOf[BaseType] || statement.getClass == classOf[DataAssignmentStatement] ||
            statement.getClass == classOf[WhenStatement]))
        fail("STATEMENT", "callback statements must be exact reviewed declarations, assignments or native when nodes")
    }
    def liveInventory(): Vector[Statement] = {
      val live = ArrayBuffer.empty[Statement]
      owner.dslBody.walkStatements { statement =>
        if (statementIds.containsKey(statement)) live += statement
      }
      live.toVector
    }
    if (callback.statements.nonEmpty) {
      val inventory = liveInventory()
      if (!same(inventory, callback.statements) ||
          callback.declarations.exists(value => !statementIds.containsKey(value)) ||
          callback.assignments.exists(value => !statementIds.containsKey(value)))
        fail("INVENTORY", "callback statement inventory must be complete and live in exact native order")
      guards += (() => {
        if (!same(liveInventory(), inventory)) fail("STALE", "native callback statements were removed or reordered")
      })
    }
    def leafNode(label: String, evidence: Evidence, properties: Vector[Any]): Node =
      new Node(evidence.kind, evidence.width, Key(label, new WidthKey(evidence.width), properties, Vector.empty),
        Vector.empty, _ => fail("INTERNAL", "input nodes require exact replay bindings"))
    operands.zip(inputEvidence).zipWithIndex.foreach { case ((value, evidence), index) =>
      nodes.put(value, leafNode(if (index == 0) "left" else "right", evidence, Vector.empty))
    }
    captureEvidence.zipWithIndex.foreach { case (evidence, index) =>
      if (!nodes.containsKey(evidence.value))
        nodes.put(evidence.value, leafNode("capture", evidence, Vector(index, new Identity(evidence.value))))
    }

    def make(kind: AnyRef, width: ElaborationIntegerExpression, cls: Class[_], properties: Vector[Any],
        children: Vector[Node])(construct: Vector[BaseType] => Expression): Node = {
      ElabInt.requireAuthoritativeIntegerDomain(width, "balanced graph width",
        "MORPH-REDUCE-BALANCED-GRAPH-REPLAY-WIDTH-AUTHORITY", requireExactExtrema = false)
      if (width.minimum < 0 || width.maximum > Int.MaxValue)
        fail("WIDTH-DOMAIN", "every native intermediate requires a finite nonnegative typed width")
      new Node(kind, width, Key(cls, new WidthKey(width), properties, children.map(_.key)), children,
        values => {
          val expression = construct(values)
          if (expression.getClass != cls || (expression.getTypeObject.asInstanceOf[AnyRef] ne kind))
            fail("CONSTRUCTOR", "the native constructor changed exact expression class or kind")
          wrap(expression, width)
        })
    }
    def mux(condition: Node, yes: Node, no: Node): Node = {
      if ((condition.kind ne TypeBool) || (yes.kind ne no.kind))
        fail("TYPE", "mux requires a native Bool condition and equal arm kinds")
      val width = maximum(yes.width, no.width)
      val factory = if (yes.kind eq TypeBool) muxes(classOf[BinaryMultiplexerBool])
        else if (yes.kind eq TypeBits) muxes(classOf[BinaryMultiplexerBits])
        else if (yes.kind eq TypeUInt) muxes(classOf[BinaryMultiplexerUInt])
        else muxes(classOf[BinaryMultiplexerSInt])
      val cls = factory().getClass
      make(yes.kind, width, cls, Vector.empty, Vector(condition, yes, no)) { values =>
        val out = factory()
        out.cond = values(0)
        out.whenTrue = values(1).asInstanceOf[out.T]
        out.whenFalse = values(2).asInstanceOf[out.T]
        out
      }
    }
    def freeze(expression: Expression, properties: () => Vector[Any]): Unit = {
      val children = ArrayBuffer.empty[Expression]
      expression.foreachExpression(children += _)
      val frozenChildren = children.toVector
      val frozenProperties = properties()
      val kind = expression.getTypeObject.asInstanceOf[AnyRef]
      guards += (() => {
        val now = ArrayBuffer.empty[Expression]
        expression.foreachExpression(now += _)
        if (!same(now.toVector, frozenChildren) || properties() != frozenProperties ||
            (expression.getTypeObject.asInstanceOf[AnyRef] ne kind))
          fail("STALE", "a certified expression changed children, type or retained properties")
      })
    }

    def expand(expression: Expression, depth: Int = 0,
        literalWidth: Option[ElaborationIntegerExpression] = None): Node = {
      if (expression == null || depth > 512 || nodes.size() > 8192)
        fail("LIMIT", "graph contains null or exceeds reviewed depth/node limits")
      val cached = nodes.get(expression)
      if (cached != null) return cached
      if (visiting.put(expression, java.lang.Boolean.TRUE) != null)
        fail("CYCLE", "callback local graph contains a combinational cycle")
      def child(value: Expression): Node = expand(value, depth + 1)
      val kind = expression.getTypeObject.asInstanceOf[AnyRef]
      val built: Node = expression match {
        case leaf: BaseType =>
          if (!declared.containsKey(leaf)) fail("EXTERNAL-READ", "hardware reads require an explicit exact-identity capture")
          if (!scalarClasses.contains(leaf.getClass) || (leaf.component ne owner) || leaf.isReg ||
              leaf.isAnalog || !leaf.isDirectionLess || leaf.parentScope == null || leaf.hasTag(tagAutoResize))
            fail("LOCAL-STATE", "local temporaries must be digital, combinational exact scalar declarations")
          var enclosing = leaf.parentScope
          var nesting = 0
          while (enclosing ne owner.dslBody) {
            nesting += 1
            if (nesting > 512 || enclosing == null || enclosing.parentStatement == null ||
                enclosing.parentStatement.getClass != classOf[WhenStatement])
              fail("LOCAL-SCOPE", "local temporary lifetime must stay inside exact native when scopes")
            val statement = enclosing.parentStatement.asInstanceOf[WhenStatement]
            consumedWhens.put(statement, java.lang.Boolean.TRUE)
            if ((enclosing ne statement.whenTrue) && (enclosing ne statement.whenFalse))
              fail("LOCAL-SCOPE", "conditional scope lost its exact parent identity")
            val parent = statement.parentScope
            val nested = enclosing
            guards += (() => {
              if ((nested.parentStatement ne statement) || (statement.parentScope ne parent))
                fail("STALE", "local temporary conditional lifetime changed")
            })
            enclosing = parent
          }
          val retained = ParameterizedWidth.expressionOf(leaf)
          val fixed = leaf match { case bits: BitVector => bits.fixedWidth; case _ => -1 }
          val all = assignmentsOf(owner, leaf)
          if (all.isEmpty || all.exists(value => !recorded.containsKey(value)))
            fail("DRIVER", "every local driver must belong to this exact callback")
          val declarationScope = leaf.parentScope
          val clock = leaf.clockDomain
          val sourceNodes = new IdentityHashMap[AssignmentStatement, Node]()
          all.foreach { assignment =>
            val source = assignment.source
            val scope = assignment.parentScope
            val parent = if (scope == null) null else scope.parentStatement
            guards += (() => {
              if ((assignment.source ne source) || (assignment.target ne leaf) ||
                  (assignment.parentScope ne scope) || scope == null || (scope.parentStatement ne parent))
                fail("STALE", "a local driver changed source, target or conditional scope")
            })
            val declaredWidth = retained.orElse(if (fixed >= 0) Some(lit(fixed)) else None)
            sourceNodes.put(assignment, expand(source, depth + 1, declaredWidth))
          }
          def scopeDriver(scope: ScopeStatement, previous: Option[Node], scopeDepth: Int): Option[Node] = {
            if (scopeDepth > 512) fail("LIMIT", "local conditional nesting exceeds reviewed depth")
            var current = previous
            scope.foreachStatements {
              case assignment: AssignmentStatement if assignment.finalTarget eq leaf =>
                if (!recorded.containsKey(assignment)) fail("DRIVER", "unrecorded local assignment")
                consumed.put(assignment, java.lang.Boolean.TRUE)
                current = Some(sourceNodes.get(assignment))
              case statement: WhenStatement =>
                val yes = scopeDriver(statement.whenTrue, current, scopeDepth + 1)
                val no = scopeDriver(statement.whenFalse, current, scopeDepth + 1)
                if (yes != current || no != current) {
                  consumedWhens.put(statement, java.lang.Boolean.TRUE)
                  if (yes.isEmpty || no.isEmpty)
                    fail("INCOMPLETE-WHEN", "local when requires an unconditional default or exhaustive alternatives")
                  val condition = statement.cond
                  val parent = statement.parentScope
                  guards += (() => {
                    if ((statement.cond ne condition) || (statement.parentScope ne parent) ||
                        (statement.whenTrue.parentStatement ne statement) || (statement.whenFalse.parentStatement ne statement))
                      fail("STALE", "a certified conditional changed condition or ownership")
                  })
                  current = Some(mux(child(condition), yes.get, no.get))
                }
              case tree: TreeStatement =>
                var writes = false
                tree.walkStatements {
                  case assignment: AssignmentStatement if assignment.finalTarget eq leaf => writes = true
                  case _ =>
                }
                if (writes) fail("STATEMENT", "only native when alternatives have a reviewed statement transfer")
              case _ =>
            }
            current
          }
          val value = scopeDriver(leaf.parentScope, None, 0).getOrElse {
            fail("DRIVER", "local temporary has no complete combinational driver")
          }
          if ((value.kind ne kind) || retained.exists(width => !ElabInt.equivalentExactFunction(width, value.width)) ||
              (fixed >= 0 && (BigInt(fixed) != value.width.default ||
                (value.width.parameters.nonEmpty && retained.isEmpty))))
            fail("LOCAL-WIDTH", "local width must follow exact native transfer or retain its typed width; witness freezing is forbidden")
          // A later native callback (notably getMuxType) may inspect this value
          // before the next stage proof exists. Publish only a width established
          // by this complete generic node transfer, never an equal witness.
          leaf match {
            case bits: BitVector if fixed == -1 && retained.isEmpty && value.width.parameters.nonEmpty =>
              ParameterizedWidth.attach(bits, ElabInt.fromExpression(value.width).bits)
            case _ =>
          }
          val certifiedRetained = ParameterizedWidth.expressionOf(leaf)
          val certifiedFixed = leaf match { case bits: BitVector => bits.fixedWidth; case _ => -1 }
          val retainedCarrier = leaf.dontSimplify
          val separateCarrier = leaf.hasTag(noBackendCombMerge)
          guards += (() => {
            val nowFixed = leaf match { case bits: BitVector => bits.fixedWidth; case _ => -1 }
            if ((leaf.component ne owner) || (leaf.parentScope ne declarationScope) || (leaf.clockDomain ne clock) ||
                leaf.isReg || leaf.isAnalog || !leaf.isDirectionLess || leaf.hasTag(tagAutoResize) ||
                (retainedCarrier && !leaf.dontSimplify) ||
                (separateCarrier && !leaf.hasTag(noBackendCombMerge)) ||
                (leaf.getTypeObject.asInstanceOf[AnyRef] ne kind) ||
                !TypedBalancedReductionValueEvidence.preservesFixedWidth(certifiedFixed, nowFixed, value.width) ||
                ParameterizedWidth.expressionOf(leaf).map(new Identity(_)) != certifiedRetained.map(new Identity(_)) ||
                !same(assignmentsOf(owner, leaf), all))
              fail("STALE", "a certified local changed identity, state, width or complete driver order")
          })
          value
        case native: BinaryOperator if binaries.contains(native.getClass) =>
          freeze(native, () => Vector.empty)
          val a = child(native.left)
          val b = child(native.right)
          val expectedKind = comparisonKinds.getOrElse(native.getClass, kind)
          if ((a.kind ne b.kind) || (a.kind ne expectedKind))
            fail("TYPE", "binary operands must retain the exact native class's operand kind")
          val width = if (kind eq TypeBool) lit(1)
            else if (native.getClass == classOf[Operator.Bits.Cat] ||
                native.getClass == classOf[Operator.UInt.Mul] || native.getClass == classOf[Operator.SInt.Mul]) add(a.width, b.width)
            else maximum(a.width, b.width)
          val factory = binaries(native.getClass)
          make(kind, width, native.getClass, Vector.empty, Vector(a, b)) { values =>
            val out = factory(); out.left = values(0).asInstanceOf[out.T]; out.right = values(1).asInstanceOf[out.T]; out
          }
        case native: UnaryOperator if unaries.contains(native.getClass) =>
          freeze(native, () => Vector.empty)
          val source = child(native.source)
          val reduction = native.getClass == classOf[Operator.BitVector.orR] ||
            native.getClass == classOf[Operator.BitVector.andR] || native.getClass == classOf[Operator.BitVector.xorR]
          if ((!reduction && (source.kind ne kind)) || (reduction && (source.kind eq TypeBool)))
            fail("TYPE", "unary operand does not match the exact native primitive kind")
          val width = if (kind eq TypeBool) lit(1) else source.width
          val factory = unaries(native.getClass)
          make(kind, width, native.getClass, Vector.empty, Vector(source)) { values =>
            val out = factory(); out.source = values.head.asInstanceOf[out.T]; out
          }
        case native: Cast if casts.contains(native.getClass) =>
          freeze(native, () => Vector.empty)
          val source = child(native.input)
          if (source.kind ne castInputKinds(native.getClass))
            fail("TYPE", "cast source kind must match its exact native cast class")
          val factory = casts(native.getClass)
          make(kind, source.width, native.getClass, Vector.empty, Vector(source)) { values =>
            val out = factory(); out.input = values.head.asInstanceOf[out.T]; out
          }
        case native: Resize if resizes.contains(native.getClass) =>
          val retained = ParameterizedWidth.resizeExpressionOf(native)
          val size = native.size
          freeze(native, () => Vector(native.size, ParameterizedWidth.resizeExpressionOf(native).map(new Identity(_))))
          val width = retained.getOrElse(lit(size))
          if (width.minimum < 1 || width.default != BigInt(size)) fail("RESIZE", "resize requires a positive exact target width")
          val source = child(native.input)
          if (source.kind ne kind) fail("TYPE", "resize cannot change the native scalar kind")
          val factory = resizes(native.getClass)
          make(kind, width, native.getClass, Vector.empty, Vector(source)) { values =>
            val out = factory(); out.input = values.head.asInstanceOf[Expression with WidthProvider]; out.size = size; out
          }
        case native: BinaryMultiplexer if muxes.contains(native.getClass) =>
          freeze(native, () => Vector.empty)
          val out = mux(child(native.cond), child(native.whenTrue), child(native.whenFalse))
          if (out.kind ne kind) fail("TYPE", "mux arms must retain the exact native mux kind")
          out
        case native: BitVectorBitAccessFixed if bitAccesses.contains(native.getClass) =>
          val bit = native.bitId
          freeze(native, () => Vector(native.bitId))
          val source = child(native.source)
          if (source.kind ne selectInputKinds(native.getClass)) fail("TYPE", "bit access source kind mismatch")
          if (bit < 0 || BigInt(bit) >= source.width.minimum)
            fail("SELECT-DOMAIN", "fixed bit index must be in range over the entire symbolic width domain")
          val factory = bitAccesses(native.getClass)
          make(TypeBool, lit(1), native.getClass, Vector(bit), Vector(source)) { values =>
            val out = factory(); out.source = values.head.asInstanceOf[Expression with WidthProvider]; out.bitId = bit; out
          }
        case native: BitVectorRangedAccessFixed if ranges.contains(native.getClass) =>
          val hi = native.hi; val lo = native.lo
          freeze(native, () => Vector(native.hi, native.lo))
          val source = child(native.source)
          if (source.kind ne selectInputKinds(native.getClass)) fail("TYPE", "part access source kind mismatch")
          if (lo < 0 || hi < lo || BigInt(hi) >= source.width.minimum)
            fail("SELECT-DOMAIN", "fixed part selection must stay nonempty and in range for the entire symbolic domain")
          val factory = ranges(native.getClass)
          make(kind, lit(hi - lo + 1), native.getClass, Vector(hi, lo), Vector(source)) { values =>
            val out = factory(); out.source = values.head.asInstanceOf[Expression with WidthProvider]; out.hi = hi; out.lo = lo; out
          }
        case native: BitVectorLiteral if native.getClass == classOf[BitsLiteral] ||
            native.getClass == classOf[UIntLiteral] || native.getClass == classOf[SIntLiteral] =>
          if (native.hasPoison || native.value == null) fail("LITERAL", "only exact non-poisoned native constants are permitted")
          val value = native.value
          val bits = native.bitCount
          val specified = native.hasSpecifiedBitCount
          freeze(native, () => Vector(native.value, native.poisonMask, native.bitCount, native.hasSpecifiedBitCount))
          val width = literalWidth.getOrElse(lit(bits))
          val needed = value.bitLength + (if ((kind eq TypeSInt) && value != 0) 1 else 0)
          if ((value < 0 && (kind ne TypeSInt)) || width.minimum < needed)
            fail("LITERAL-DOMAIN", "constant value must fit its exact native kind and width for every allowed parameter")
          make(kind, width, native.getClass, Vector(value, specified), Vector.empty) { _ =>
            if (kind eq TypeBits) BitsLiteral(value, null, width.default.toInt, true)
            else if (kind eq TypeUInt) UIntLiteral(value, null, width.default.toInt, true)
            else SIntLiteral(value, null, width.default.toInt, true)
          }
        case native: BoolLiteral if native.getClass == classOf[BoolLiteral] =>
          val value = native.value
          freeze(native, () => Vector(native.value))
          make(TypeBool, lit(1), native.getClass, Vector(value), Vector.empty)(_ => new BoolLiteral(value))
        case _ => fail("UNSUPPORTED", s"native class '${expression.getClass.getName}' has no exact graph transfer rule")
      }
      visiting.remove(expression)
      nodes.put(expression, built)
      built
    }

    val root = expand(result)
    // Width queries are deliberately deferred until the complete graph has
    // passed the cycle check. They are freshness witnesses, not width transfer.
    val nodeIterator = nodes.keySet().iterator()
    while (nodeIterator.hasNext) {
      val expression = nodeIterator.next()
      expression match {
        case sized: WidthProvider =>
          val width = sized.getWidth
          guards += (() => {
            if (sized.getWidth != width) fail("STALE", "a native expression's width policy changed")
          })
        case _ =>
      }
    }
    if ((root.kind ne inputKind) || !ElabInt.equivalentExactFunction(root.width, inputWidth) || root.width.minimum < 1)
      fail("RESULT-SHAPE", "59f graph callbacks must preserve the exact input scalar kind and result width")
    if (callback.declarations.exists(value => !nodes.containsKey(value)) || consumed.size() != callback.assignments.size)
      fail("UNCONSUMED-EFFECT", "all callback declarations and assignments must belong to the certified result graph")
    if (callback.statements.exists {
        case statement: WhenStatement => !consumedWhens.containsKey(statement)
        case _ => false
      }) fail("UNCONSUMED-EFFECT", "conditional statements outside the result graph are not certified")
    val proof = new Proof(result, root.width, root.key.kind match {
      case cls: Class[_] => cls
      case _ => result.getClass
    }, root, owner, inputEvidence, captureEvidence, guards.toVector)
    proof.validateFreshness()
    proof
  }
}
