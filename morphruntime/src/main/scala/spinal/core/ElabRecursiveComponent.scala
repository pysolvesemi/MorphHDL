package spinal.core

import spinal.core.internals._

/** A typed direct-recursion boundary. The interface constructor is authored once;
  * recursive calls construct only that interface, never the Component body.
  * Publication still proves every guarded edge and substituted port geometry.
  */
object ElabRecursiveComponent {
  def guardConstructor(owner: Component): Unit = {
    require(owner != null && (owner eq Component.current), "direct recursive constructor lost its current owner")
    var parent = owner.parent
    while (parent != null) {
      if (parent.getClass == owner.getClass)
        ParameterizedVerilogException.fail("SPINAL-DIRECT-RECURSION-NONLOCAL",
          "recursive construction through a helper or another component is unsupported; use a guarded direct self-construction", None)
      parent = parent.parent
    }
  }

  def inferred[P <: Data](bindings: Seq[(ElabInt, ElabInt)])(
      interface: Vector[ElabInt] => P): InferredDeclaration[P] = {
    val owner = Component.current
    require(owner != null && bindings != null && bindings.nonEmpty,
      "inferred recursion requires a current component and constructor bindings")
    bindings.foreach { case (formal, actual) =>
      require(RtlParam.authenticates(owner, formal, actual),
        "inferred recursive parameter must retain its exact declaration and constructor actual")
    }
    if (owner.definitionName == null) owner.setDefinitionName(spinal.core.internals.classNameOf(owner))
    val slots = bindings.toVector
    val enabled = slots.map { case (formal, actual) => RtlParam.active(owner, formal, actual) }
    val nativeFormals = slots.zip(enabled).collect { case ((formal, _), true) => formal }
    val native = apply(nativeFormals) { actuals =>
      val byIdentity = nativeFormals.zip(actuals)
      interface(slots.zip(enabled).map {
        case ((formal, _), true) => byIdentity.find(_._1 eq formal).get._2
        case ((formal, _), false) => formal
      })
    }
    owner.afterElaboration { inferClockPorts(owner) }
    new InferredDeclaration(native, slots.map(_._1), enabled)
  }

  final class InferredDeclaration[P <: Data] private[ElabRecursiveComponent](
      native: Declaration[P], slots: Vector[ElabInt], enabled: Vector[Boolean]) {
    val ports: P = native.ports
    def apply(overrides: (ElabInt, ElabInt)*): Reference[P] = {
      require(overrides.map(_._1).distinct.size == overrides.size,
        "inferred recursive bindings must be unique")
      overrides.foreach { case (key, value) =>
        val slot = slots.indexWhere(_ eq key)
        require(slot >= 0, "inferred recursive binding has no exact declaration")
        if (!enabled(slot)) require(value == null || (value eq key),
          "an omitted recursive parameter must retain its omission identity")
      }
      native(overrides.filter { case (key, _) => enabled(slots.indexWhere(_ eq key)) }: _*)
    }
  }

  private object Key
  private final case class Edge(owner: Component, reference: BlackBox, name: String,
      formals: Vector[ElabInt], definitionPorts: Vector[BaseType], referencePorts: Vector[BaseType]) {
    var clocks = Vector.empty[ClockPort]
  }
  private final case class ClockPort(domain: ClockDomain, source: Bool, definition: Bool,
      reference: Bool, assignment: DataAssignmentStatement)

  /** Only compiler-inferred boundaries gain native single-domain clock ports.
    * Explicit interface ports continue through the original exact inventory.
    */
  private def inferClockPorts(owner: Component): Unit = {
    val edges = owner.children.toVector.flatMap(_.userCache.get(Key)).map(_.asInstanceOf[Edge])
    if (edges.isEmpty) return
    val domains = scala.collection.mutable.LinkedHashSet.empty[ClockDomain]
    owner.dslBody.walkLeafStatements {
      case value: BaseType if value.isReg => domains += value.clockDomain
      case statement => statement.foreachClockDomain(domains += _)
    }
    val current = owner.clockDomain.get
    val external = domains.toVector.filter(cd =>
      Vector(cd.clock, cd.reset, cd.softReset, cd.clockEnable).exists(b => b != null && (b.component ne owner)))
    if (external.isEmpty) return
    if (external.size != 1 || (external.head ne current))
      ParameterizedVerilogException.fail("SPINAL-DIRECT-RECURSION-CLOCK-DOMAIN-UNSUPPORTED",
        "implicit recursive clocks require the component's single current clock domain; declare other domains explicitly", None)
    val cd = external.head
    val sources = Vector(cd.clock, cd.reset, cd.softReset, cd.clockEnable).filter(_ != null).distinct
    val ports = owner.rework {
      sources.map(source => source -> Data.doPull(source, owner, useCache = true, propagateName = true))
    }
    edges.foreach { edge =>
      require(edge.clocks.isEmpty, "recursive clock ports may be inferred only once")
      edge.clocks = ports.filterNot { case (_,port) => edge.definitionPorts.exists(_ eq port) }.map {
        case (source, definition) =>
          require(definition.component == owner && definition.isInput,
            "implicit recursive clock must be an exact input boundary")
          val reference = Data.doPull(definition, edge.reference, useCache = true, propagateName = true)
          require(reference.component == edge.reference && reference.isInput && reference.hasOnlyOneStatement,
            "implicit recursive clock lost its sole input binding")
          val assignment = reference.head match {
            case value: DataAssignmentStatement if (value.target eq reference) && (value.source eq definition) => value
            case _ => throw new IllegalArgumentException("implicit recursive clock requires an exact direct binding")
          }
          ClockPort(cd, source, definition, reference, assignment)
      }
    }
  }

  final class Declaration[P <: Data] private[ElabRecursiveComponent] (
      val owner: Component, formals: Vector[ElabInt], interface: Vector[ElabInt] => P) {
    private val name = owner.definitionName
    val ports: P = interface(formals)
    ports.setName("")
    require(ports.flatten.nonEmpty && ports.flatten.forall(p =>
      (p.component eq owner) && (p.isInput || p.isOutput)),
      "recursive interface must contain only ports owned by its definition")

    /** Omitted slots retain their definition formal. Keys are exact declared
      * ElabInt identities, not strings or equivalent-looking expressions.
      */
    def apply(overrides: (ElabInt, ElabInt)*): Reference[P] = {
      require(Component.current eq owner, "recursive call escaped its declaring Component")
      require(owner.definitionName == name, "recursive definition name changed after declaration")
      require(overrides.forall { case (key, value) =>
        value != null && formals.exists(_ eq key)
      } && overrides.map(_._1).distinct.size == overrides.size,
        "recursive bindings require unique exact formal identities")
      val actuals = formals.map(f => overrides.find(_._1 eq f).map(_._2).getOrElse(f))
      // morphruntime deliberately has no IDSL compiler plugin. Complete this
      // compiler-owned shell's native construction exactly once here.
      val reference = new Reference[P](owner, name, formals, actuals, ports.flatten.toVector, interface)
      reference.postInitCallback()
      reference
    }
  }

  final class Reference[P <: Data] private[ElabRecursiveComponent] (
      owner: Component, name: String, formals: Vector[ElabInt], actuals: Vector[ElabInt],
      definitionPorts: Vector[BaseType],
      interface: Vector[ElabInt] => P) extends BlackBox {
    setBlackBoxName(name)
    formals.zip(actuals).foreach { case (formal, actual) =>
      addGeneric(formal.parameters.head.name, actual)
    }
    val ports: P = interface(actuals)
    ports.setName("")
    def io: P = ports
    /** Frontend-owned declaration spelling; never used as identity evidence. */
    def authoredName(name: String): this.type = {
      setName(name, Nameable.DATAMODEL_WEAK)
      this
    }
    require(parent eq owner, "recursive reference lost its declaring Component")
    userCache(Key) = Edge(owner, this, name, formals, definitionPorts, ports.flatten.toVector)
  }

  def apply[P <: Data](formals: Seq[ElabInt])(
      interface: Vector[ElabInt] => P): Declaration[P] = {
    require(ParameterizedStructure.captureEnabled, "typed recursion requires MorphVerilog")
    val owner = Component.current
    require(owner != null && !owner.isInstanceOf[BlackBox] && interface != null &&
      formals != null && formals.nonEmpty, "typed recursion requires a real active definition")
    val slots = formals.toVector
    require(slots.forall(f => f != null && f.parameters.size == 1 &&
      ElabInt.equivalentExpression(f.expression,
        ElabInt.directParameter(f.parameters.head, f.sourceLocation).expression)) &&
      slots.map(_.parameters.head).distinct.size == slots.size,
      "recursive slots must be distinct direct definition formals")
    new Declaration(owner, slots, interface)
  }

  private[spinal] def validateIdentity(reference: BlackBox,
      parameters: => Vector[ElaborationIntegerParameter]): Unit =
    reference.userCache.get(Key).foreach { value =>
      val edge = value.asInstanceOf[Edge]
      require((edge.reference eq reference) && (reference.parent eq edge.owner) &&
        edge.owner.children.exists(_ eq reference) &&
        ElabModuleReference.definitionOf(reference).isEmpty &&
        reference.definitionName == edge.name && edge.owner.definitionName == edge.name,
        "typed recursive reference lost its exact definition identity")
      val retained = parameters
      require(retained.size == edge.formals.size && edge.formals.forall(f =>
        retained.exists(_ eq f.parameters.head)),
        "typed recursion lost its complete exact definition formal inventory")
      edge.clocks.foreach { clock =>
        require((edge.owner.clockDomain.get eq clock.domain) &&
          Vector(clock.domain.clock, clock.domain.reset, clock.domain.softReset, clock.domain.clockEnable)
            .exists(_ eq clock.source) &&
          edge.owner.pulledDataCache.get(clock.source).exists(_ eq clock.definition) &&
          reference.pulledDataCache.get(clock.definition).exists(_ eq clock.reference) &&
          clock.definition.isInput && clock.reference.isInput &&
          (clock.definition.component eq edge.owner) && (clock.reference.component eq reference) &&
          clock.reference.hasOnlyOneStatement && (clock.reference.head eq clock.assignment) &&
          (clock.assignment.target eq clock.reference) && (clock.assignment.source eq clock.definition),
          "typed recursive clock lost its exact domain, ports or binding")
      }
      require((edge.definitionPorts ++ edge.clocks.map(_.definition)).toSet == edge.owner.getAllIo.toSet &&
        (edge.referencePorts ++ edge.clocks.map(_.reference)).toSet == reference.getAllIo.toSet,
        "typed recursion must retain the complete declared interface; declare clock/reset ports explicitly")
    }
}
