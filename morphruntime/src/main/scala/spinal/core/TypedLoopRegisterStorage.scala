package spinal.core

import spinal.core.internals._

/** Relocate initialized register storage into an exact finite-loop template.
  * Native readback bridges retain clock provenance until publication consumes
  * them. Original carrier identities remain the Vec's public read interface.
  */
private[core] object TypedLoopRegisterStorage {
  final case class Lane(value: BaseType, initialization: InitAssignmentStatement,
      bridge: DataAssignmentStatement)
  final case class Template(alias: BaseType, clock: ClockDomain,
      initialization: InitAssignmentStatement, reset: Expression, lanes: Vector[Lane])
  private object StorageKey
  private final case class Owner(loop: ParameterizedStructure.StructuralFor,
      selection: ParameterizedStructure.StructuralVecIndex, template: Template)
  private def inventory(component: Component) = component.userCache.getOrElseUpdate(StorageKey,
    scala.collection.mutable.ArrayBuffer.empty[Owner])
    .asInstanceOf[scala.collection.mutable.ArrayBuffer[Owner]]

  private def fail(message: String): Nothing =
    ParameterizedVerilogException.fail("SPINAL-TYPED-LOOP-REGISTER-STORAGE-MISMATCH", message, None)

  private def statements(value: BaseType): Vector[AssignmentStatement] = {
    val result = scala.collection.mutable.ArrayBuffer.empty[AssignmentStatement]
    value.foreachStatements(result += _)
    result.toVector
  }

  private def literal(expression: Expression): Option[Expression] = expression match {
    case value: BitVectorLiteral if !value.hasPoison() => Some(value)
    case value: BoolLiteral if !value.hasPoison() => Some(value)
    case value: BaseType if !value.isReg && value.hasOnlyOneStatement &&
        value.head.isInstanceOf[DataAssignmentStatement] => value.head.source match {
      case lit: BitVectorLiteral if !lit.hasPoison() => Some(lit)
      case lit: BoolLiteral if !lit.hasPoison() => Some(lit)
      case _ => None
    }
    case _ => None
  }

  private def sameLiteral(a: Expression, b: Expression): Boolean = (a, b) match {
    case (x: BitVectorLiteral, y: BitVectorLiteral) =>
      x.getTypeObject == y.getTypeObject && x.getWidth == y.getWidth && x.getValue() == y.getValue()
    case (x: BoolLiteral, y: BoolLiteral) => x.value == y.value
    case _ => false
  }

  def retain(component: Component, loop: ParameterizedStructure.StructuralFor): Unit = {
    val body = loop.body
    // Validate the entire operation before mutating any register or reset edge.
    val candidates = body.vecIndices.flatMap { selection =>
      selection.result.flatten.toVector.zipWithIndex.flatMap { case (alias, leafIndex) =>
        val writes = body.assignments.filter(_.finalTarget eq alias)
        val leaves = selection.vector.vec.toVector.map(_.asInstanceOf[Data].flatten(leafIndex))
        if (writes.isEmpty || !leaves.exists(_.isReg)) Vector.empty
        else {
          if (!writes.forall(_.target eq alias) || !leaves.forall(_.isReg) ||
              selection.index.minimum != 0 || selection.index.maximum != leaves.size - 1 ||
              !selection.index.generateIndex.contains(loop.indexName) ||
              leaves.exists(value => value.isIo || (value.parentScope ne component.dslBody)))
            fail("register relocation requires a complete whole-leaf Vec write in its declaring component")
          val clock = leaves.head.clockDomain
          if (clock == null || !leaves.forall(_.clockDomain eq clock))
            fail("register lanes must retain one exact native clock domain")
          val initializations = leaves.map { value =>
            statements(value) match {
              case Vector(init: InitAssignmentStatement) if init.target eq value => init
              case _ => fail("register relocation requires initialized lanes without other data writers")
            }
          }
          val resets = initializations.map(init => literal(init.source).getOrElse(
            fail("register relocation requires exact literal reset values")))
          if (!resets.forall(sameLiteral(_, resets.head)))
            fail("register relocation requires the same reset value in every generated lane")
          Vector((selection, alias, leaves, initializations, clock, resets.head))
        }
      }
    }
    val claimed = new java.util.IdentityHashMap[BaseType, java.lang.Boolean]()
    candidates.foreach { case (_, _, leaves, _, _, _) => leaves.foreach { value =>
      if (claimed.put(value, java.lang.Boolean.TRUE) != null)
        fail("two captured aliases cannot independently own the same register storage")
    }}
    candidates.foreach { case (selection, alias, leaves, initializations, clock, reset) =>
      alias.setAsReg()
      alias.clockDomain = clock
      alias.removeTag(allowFloating)
      alias.removeTag(noBackendCombMerge)
      alias.addTag(noBackendSyncMerge)
      // Declarations inside an enable scope would discard that enclosing
      // enable from native register process ownership. Keep the declaration
      // at the original registers' root while retaining the authored writes.
      alias.removeStatementFromScope()
      component.dslBody.append(alias)
      val initialization = InitAssignmentStatement(alias, reset)
      component.dslBody.append(initialization)
      val lanes = leaves.zip(initializations).map { case (value, init) =>
        init.removeStatement()
        value.setAsComb()
        val bridge = DataAssignmentStatement(value, alias)
        component.dslBody.append(bridge)
        Lane(value, init, bridge)
      }
      if (!body.statements.exists(_ eq alias)) body.statements :+= alias
      body.statements ++= Vector(initialization) ++ lanes.map(_.bridge)
      body.assignments ++= lanes.map(_.bridge)
      val template = Template(alias, clock, initialization, reset, lanes)
      selection.registerStorage :+= template
      inventory(component) += Owner(loop, selection, template)
    }
  }

  def validateInventory(component: Component,
      regions: Vector[ParameterizedStructure.StructuralRegion]): Unit = {
    def nested(region: ParameterizedStructure.StructuralRegion): Vector[ParameterizedStructure.StructuralRegion] =
      Vector(region) ++ region.blocks.flatMap(_.regions).flatMap(nested)
    val all = regions.flatMap(nested)
    val blocks = all.flatMap(_.blocks)
    val owners = inventory(component).toVector
    owners.foreach { owner =>
      if (!all.exists(_ eq owner.loop) ||
          !owner.loop.body.vecIndices.exists(_ eq owner.selection) ||
          owner.selection.registerStorage.count(_ eq owner.template) != 1)
        fail("register storage lost its exact registered loop, selection or template")
      blocks.foreach { body => body.vecIndices.foreach { selection =>
        selection.result.flatten.zipWithIndex.foreach { case (alias, ordinal) =>
          if ((alias ne owner.template.alias) && body.assignments.exists(_.finalTarget eq alias) &&
              selection.vector.vec.exists(element => element.asInstanceOf[Data].flatten.lift(ordinal)
                .exists(leaf => owner.template.lanes.exists(_.value eq leaf))))
            fail("another structural alias also writes relocated register storage")
        }
      }}
    }
    blocks.foreach { body => body.vecIndices.foreach { selection =>
      selection.registerStorage.foreach { template =>
        if (owners.count(owner => (owner.selection eq selection) && (owner.template eq template)) != 1)
          fail("register template has no unique compiler-issued storage owner")
      }
    }}
  }

  def validate(template: Template, body: ParameterizedStructuralBlock,
      live: java.util.IdentityHashMap[Statement, java.lang.Boolean]): Unit = {
    if (!template.alias.isReg || (template.alias.clockDomain ne template.clock) ||
        !live.containsKey(template.alias) || !live.containsKey(template.initialization) ||
        (template.initialization.target ne template.alias) ||
        (template.initialization.source ne template.reset) ||
        !body.declarations.exists(_ eq template.alias))
      fail("generated register lost its exact declaration, clock or reset assignment")
    template.lanes.foreach { lane =>
      if (lane.value.isReg || !live.containsKey(lane.value) || !live.containsKey(lane.bridge) ||
          live.containsKey(lane.initialization) ||
          (lane.bridge.target ne lane.value) || (lane.bridge.source ne template.alias) ||
          statements(lane.value) != Vector(lane.bridge) || !body.assignments.exists(_ eq lane.bridge))
        fail("register readback carrier lost its sole exact native bridge")
    }
  }
}
