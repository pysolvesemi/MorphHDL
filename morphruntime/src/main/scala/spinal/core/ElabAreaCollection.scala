package spinal.core

/** A retained Area template with explicit, statically selected signal exports. */
final class ElabAreaCollection[T <: Area] private[core] (
    private val count: ElabInt,
    private val values: Vector[T],
    private val index: Option[ElabFiniteIndex],
    private val component: Component
) {
  private val exports = new java.util.IdentityHashMap[BaseType, scala.collection.mutable.Map[Int, BaseType]]()
  private var exportOrdinal = 0
  /** Documentation callbacks may label the template, but cannot construct RTL. */
  def documentation: ElabAreaDocumentation[T] = new ElabAreaDocumentation(values, index.nonEmpty, component)
  def length: Int = {
    if (index.nonEmpty && count.expression.parameters.nonEmpty)
      ParameterizedVerilogException.fail("SPINAL-ELAB-AREA-SYMBOLIC-LENGTH",
        "a retained parameter-sized Area collection has no Scala length", count.sourceLocation)
    values.size match {
      case _ if index.nonEmpty => count.expression.default.toInt
      case size => size
    }
  }
  def apply(position: Int): T = index match {
    case None => values(position)
    case Some(_) => ParameterizedVerilogException.fail("SPINAL-ELAB-AREA-OBJECT-ESCAPE",
      "export an Area signal through member(position)(_.signal); the template object cannot escape", count.sourceLocation)
  }
  def member[D <: BaseType](position: Int)(select: T => D): D = {
    if (position < 0 || BigInt(position) >= count.expression.minimum)
      ParameterizedVerilogException.fail("SPINAL-ELAB-AREA-STATIC-INDEX-OUT-OF-RANGE",
        s"Area member index $position is not valid throughout the collection count domain",
        count.sourceLocation)
    if (Component.current ne component)
      ParameterizedVerilogException.fail("SPINAL-ELAB-AREA-OWNER-MISMATCH",
        "Area collection members must be exported in their declaring component", count.sourceLocation)
    index match {
      case None => select(values(position))
      case Some(retained) =>
        val source = select(values.head)
        ParameterizedStructure.requireFiniteMember(retained, source)
        var selected = exports.get(source)
        if (selected == null) {
          selected = scala.collection.mutable.Map.empty[Int, BaseType]
          exports.put(source, selected)
        }
        selected.get(position).foreach(value => return value.asInstanceOf[D])
        val exported = cloneOf(source)
        exported.setWeakName("area_member")
        exported.dontSimplifyIt()
        exported.setAsVital()
        exportOrdinal += 1
        val (_, assignments) = ParameterizedVec.captureAssignments(exported) {
          ParameterizedStructure.extendFiniteIndex(retained) {
            retained.onlyEqual(position, s"member_$exportOrdinal") { exported := source }
          }
        }
        require(assignments.size == 1, "Area export requires one exact driver")
        ElabAreaExport.retain(retained, position, source, exported, assignments.head)
        selected(position) = exported
        exported
    }
  }
}

private[spinal] object ElabAreaExport {
  import spinal.core.internals._
  private object Key
  private final case class Entry(index: ElabFiniteIndex, position: Int, source: BaseType,
      result: BaseType, assignment: DataAssignmentStatement)
  private def entries(component: Component) = component.userCache.getOrElseUpdate(Key,
    scala.collection.mutable.ArrayBuffer.empty[Entry]).asInstanceOf[scala.collection.mutable.ArrayBuffer[Entry]]
  private[core] def retain(index: ElabFiniteIndex, position: Int, source: BaseType,
      result: BaseType, assignment: DataAssignmentStatement): Unit =
    entries(result.component) += Entry(index, position, source, result, assignment)

  def moduleDeclarations(component: Component, blocks: Vector[ParameterizedStructuralBlock]): Vector[BaseType] =
    entries(component).toVector.map { entry =>
      val owners = blocks.filter(_.assignments.exists(_ eq entry.assignment))
      require(owners.size == 1 && totalDriver(component, entry.result.getName(), owners.head),
        "Area export requires one authenticated singleton driver before declaration publication")
      entry.result
    }

  def totalDriver(component: Component, name: String, owner: ParameterizedStructuralBlock): Boolean =
    entries(component).find(entry => entry.result.getName() == name).exists { entry =>
      val result = entry.result
      val drivers = scala.collection.mutable.ArrayBuffer.empty[DataAssignmentStatement]
      result.foreachStatements { case a: DataAssignmentStatement => drivers += a; case _ => }
      require(result.isComb && (result.component eq component) && (result.parentScope eq component.dslBody) &&
        drivers.size == 1 && (drivers.head eq entry.assignment) &&
        (entry.assignment.target eq result) && (entry.assignment.source eq entry.source) &&
        owner.assignments.exists(_ eq entry.assignment) && entry.index.count.minimum > entry.position,
        "Area export lost its sole native driver, module declaration or total index domain")
      ParameterizedStructure.requireFiniteValueOwner(entry.index, component, owner)
      def guarded(regions: Vector[ParameterizedStructure.StructuralRegion]): Boolean = regions.exists {
        case branch: ParameterizedStructure.StructuralIf if branch.whenTrue eq owner =>
          branch.condition.verilog == s"(${entry.index.expression.verilog} == ${entry.position})" &&
            branch.predicateDomain.exists(domain =>
              ParameterizedStructure.finitePredicateRoot(component, entry.index.token).exists(_ eq domain.root) &&
                domain.whenTrue == Set(BigInt(entry.position))) &&
            ParameterizedStructuralSynthetic.isSyntheticEmpty(branch.whenFalse)
        case region => region.blocks.exists(block => guarded(block.regions))
      }
      require(guarded(ParameterizedStructure.regionsOf(component)),
        "Area export lost its exact singleton generate guard")
      true
    }
}

final class ElabAreaDocumentation[T <: Area] private[core] (values: Vector[T], retained: Boolean, component: Component) {
  def foreach(body: ((T, String)) => Unit): Unit = {
    import spinal.core.internals._
    def snapshot(): Vector[(Statement, Any)] = {
      val result = scala.collection.mutable.ArrayBuffer.empty[(Statement, Any)]
      def visit(owner: Component): Unit = {
      owner.dslBody.walkStatements { statement =>
        val shape: Any = statement match {
          case assignment: AssignmentStatement => (assignment.target, assignment.source)
          case signal: BaseType => (signal.getBitsWidth, signal.getTypeObject, signal.isReg,
            signal.getTags().filterNot(_.isInstanceOf[RtlDocTag]).toVector)
          case _ => statement.parentScope
        }
        result += statement -> shape
      }
      owner.children.foreach(visit)
      }
      visit(component)
      result.toVector
    }
    val before = snapshot()
    val children = component.children.toVector
    values.zipWithIndex.foreach { case (value, n) => body(value -> (if (retained) "generated lane" else n.toString)) }
    if (snapshot() != before || component.children.toVector != children)
      ParameterizedVerilogException.fail("SPINAL-ELAB-AREA-DOCUMENTATION-HARDWARE-EFFECT",
        "Area documentation iteration must not change native hardware")
  }
}

object ElabAreaCollection {
  def tabulate[T <: Area](count: ElabInt, name: String)(body: ElabFiniteIndex => T): ElabAreaCollection[T] = {
    val component = Component.current
    var values = Vector.empty[T]
    var retained = Option.empty[ElabFiniteIndex]
    ElabFiniteRange.foreach(count, name) { index =>
      if (index.expression.generateIndex.nonEmpty) retained = Some(index)
      val area = body(index)
      area.setName(if (retained.nonEmpty) name else name + "_" + values.size)
      values :+= area
    }
    new ElabAreaCollection(count, values, retained, component)
  }
}
