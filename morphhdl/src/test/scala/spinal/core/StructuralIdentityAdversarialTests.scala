package spinal.core

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.collection.JavaConverters._

import org.scalatest.funsuite.AnyFunSuite

import morphhdl.MorphVerilog
import morphhdl.frontend.{HdlInt, HdlIntRangeStart}
import spinal.core.internals.WhenStatement

private object StructuralIdentityAdversarialFixture {
  final class CapturedCover(control: ElabInt) extends Component {
    setDefinitionName("CapturedCoverMustFailClosed")
    control.elabEq(1).generate {
      cover(True)
    }
  }

  final class CoincidentFiniteVec(depth: ElabInt) extends Component {
    setDefinitionName("CoincidentFiniteVec")
    val values = in(Vec(Bits(8 bits), depth)).setName("values")
    val passthroughIn = in(Bool()).setName("passthrough_in")
    val passthroughOut = out(Bool()).setName("passthrough_out")
    passthroughOut := passthroughIn

    ElabFiniteRange.foreach(depth, "coincident finite Vec") { index =>
      val selected = Bits(8 bits).setName("selected_value")
      selected := index(values)
      selected.dontSimplifyIt()

      // This is a distinct constant Vec operation which selects the same
      // carrier as the representative index zero. It must remain element zero
      // rather than being promoted into the generate index.
      val coincident = Bits(8 bits).setName("coincident_value")
      coincident := values(0)
      coincident.dontSimplifyIt()
    }
  }

  final class UnusedFiniteVecWithRawCarrier(depth: ElabInt) extends Component {
    setDefinitionName("UnusedFiniteVecWithRawCarrier")
    val values = in(Vec(Bits(8 bits), depth)).setName("values")
    val passthroughIn = in(Bool()).setName("passthrough_in")
    val passthroughOut = out(Bool()).setName("passthrough_out")
    passthroughOut := passthroughIn

    ElabFiniteRange.foreach(depth, "unused finite Vec") { index =>
      // The exact symbolic selection is deliberately unused. Its retained
      // evidence must not authorize a separate raw witness carrier below.
      index(values)

      val raw = Bits(8 bits).setName("raw_value")
      raw := values.vec(0)
      raw.dontSimplifyIt()
    }
  }

  final class ProjectedFiniteVecWithRawCarrier(depth: ElabInt)
      extends Component {
    setDefinitionName("ProjectedFiniteVecWithRawCarrierMustFailClosed")
    val passthroughIn = in(Bool()).setName("passthrough_in")
    val passthroughOut = out(Bool()).setName("passthrough_out")
    passthroughOut := passthroughIn

    (depth > 1).generate {
      val values = Vec(Bits(8 bits), depth)
        .setName("projected_values")
        .dontSimplifyIt()

      ElabFiniteRange.foreach(depth, "projected finite Vec") { index =>
        index(values) := B(0x5a, 8 bits)
        val consumed = Bits(8 bits).setName("consumed_projected_value")
        consumed := index(values)
        consumed.dontSimplifyIt()
      }

      // The finite selector's witness access is consumed by structural
      // lowering. It must not authorize this separate raw carrier reference,
      // even though both select the representative element zero.
      val raw = Bits(8 bits).setName("raw_projected_value")
      raw := values.vec(0)
      raw.dontSimplifyIt()
    }
  }

  final class ReusedFiniteVecSelection(depth: ElabInt) extends Component {
    setDefinitionName("ReusedFiniteVecSelection")
    val values = in(Vec(Bits(8 bits), depth)).setName("values")
    val passthroughIn = in(Bool()).setName("passthrough_in")
    val passthroughOut = out(Bool()).setName("passthrough_out")
    passthroughOut := passthroughIn

    ElabFiniteRange.foreach(depth, "reused finite Vec") { index =>
      val selected = index(values)
      val first = Bits(8 bits).setName("first_value")
      val second = Bits(8 bits).setName("second_value")
      first := selected
      second := selected
      first.dontSimplifyIt()
      second.dontSimplifyIt()
    }
  }

  final class PackedPartialFiniteVecSelection(depth: ElabInt) extends Component {
    setDefinitionName("PackedPartialFiniteVecSelectionMustFailClosed")
    val values = in(Vec(Bits(8 bits), depth)).setName("values")
    val passthroughIn = in(Bool()).setName("passthrough_in")
    val passthroughOut = out(Bool()).setName("passthrough_out")
    passthroughOut := passthroughIn

    ElabFiniteRange.foreach(depth, "packed partial finite Vec") { index =>
      val selected = index(values)
      val partial = Bool().setName("partial_value")
      partial := selected(0)
      partial.dontSimplifyIt()
    }
  }

  final class InternalFiniteVecSelection(depth: ElabInt) extends Component {
    setDefinitionName("InternalFiniteVecSelection")
    val source = in(Vec(Bits(8 bits), depth)).setName("source")
    val passthroughIn = in(Bool()).setName("passthrough_in")
    val passthroughOut = out(Bool()).setName("passthrough_out")
    passthroughOut := passthroughIn
    val values = Vec(Bits(8 bits), depth)
      .setName("internal_values")
      .dontSimplifyIt()
    values := source

    ElabFiniteRange.foreach(depth, "internal finite Vec") { index =>
      val observed = Bits(8 bits).setName("internal_observed")
      observed := index(values)
      observed.dontSimplifyIt()
    }
  }

  final class FiniteVecSelectionOnLhs(depth: ElabInt) extends Component {
    setDefinitionName("FiniteVecSelectionOnLhs")
    val data = in(Bits(8 bits)).setName("data")
    val values = out(Vec(Bits(8 bits), depth)).setName("values")

    ElabFiniteRange.foreach(depth, "finite Vec lhs") { index =>
      index(values) := data
    }
  }

  final class OutputOnlyFiniteStructuralVec(depth: ElabInt) extends Component {
    setDefinitionName("OutputOnlyFiniteStructuralVec")
    val values = out(Vec(Bits(8 bits), depth)).setName("values")

    ElabFiniteRange.foreach(depth, "output-only finite structural Vec") { index =>
      index(values) := B(0x5a, 8 bits)
    }
  }

  final class OutputOnlySymbolicScalar(width: ElabInt) extends Component {
    setDefinitionName("OutputOnlySymbolicScalarMustFailClosed")
    val value = out(UInt(width bits)).setName("value")
    value := 0
  }

  final class OutputOnlyFiniteVecWithScalar(depth: ElabInt) extends Component {
    setDefinitionName("OutputOnlyFiniteVecWithScalarMustFailClosed")
    val values = out(Vec(Bits(8 bits), depth)).setName("values")
    val scalar = out(Bool()).setName("scalar")
    scalar := False

    ElabFiniteRange.foreach(depth, "mixed output finite structural Vec") { index =>
      index(values) := B(0x5a, 8 bits)
    }
  }

  final class InputOnlyTypedVec(depth: ElabInt) extends Component {
    setDefinitionName("InputOnlyTypedVecMustFailClosed")
    val values = in(Vec(Bits(8 bits), depth)).setName("values")
  }

  final class InOutOnlySymbolicBits(width: ElabInt) extends Component {
    setDefinitionName("InOutOnlySymbolicBitsMustFailClosed")
    val inoutValue = inout(Analog(Bits(width bits))).setName("inout_value")
  }

  final class DistinctFiniteVecSelections(depth: ElabInt) extends Component {
    setDefinitionName("DistinctFiniteVecSelections")
    val values = in(Vec(Bits(8 bits), depth)).setName("values")
    val passthroughIn = in(Bool()).setName("passthrough_in")
    val passthroughOut = out(Bool()).setName("passthrough_out")
    passthroughOut := passthroughIn

    ElabFiniteRange.foreach(depth, "distinct finite Vec") { index =>
      val firstSelection = index(values)
      val secondSelection = index(values)
      require(
        firstSelection ne secondSelection,
        "distinct finite Vec calls must retain distinct alias identities"
      )
      val first = Bits(8 bits).setName("first_distinct")
      val second = Bits(8 bits).setName("second_distinct")
      first := firstSelection
      second := secondSelection
      first.dontSimplifyIt()
      second.dontSimplifyIt()
    }
  }

  final class SiblingFiniteVecAggregateBridge(depth: ElabInt) extends Component {
    setDefinitionName("SiblingFiniteVecAggregateBridge")
    val source = in(Vec(Bool(), depth)).setName("source")
    val observed = out(Vec(Bool(), depth)).setName("observed")
    val staged = Vec(Bool(), depth).setName("staged")

    ElabFiniteRange.foreach(depth, "sibling Vec aggregate write") { index =>
      index(staged) := index(source)
    }
    ElabFiniteRange.foreach(depth, "sibling Vec aggregate read") { index =>
      index(observed) := index(staged)
    }
  }

  final class ForeignFiniteIndexTokenAggregateBridge(depth: ElabInt)
      extends Component {
    setDefinitionName("ForeignFiniteIndexTokenAggregateBridgeMustFailClosed")
    val source = in(Vec(Bool(), depth)).setName("source")
    val observed = out(Vec(Bool(), depth)).setName("observed")
    val staged = Vec(Bool(), depth).setName("staged")

    ElabFiniteRange.foreach(depth, "foreign-token Vec write") { index =>
      index(staged) := index(source)
    }
    ElabFiniteRange.foreach(depth, "foreign-token Vec read") { index =>
      index(observed) := index(staged)
    }

    val loops = ParameterizedStructure.regionsOf(this).collect {
      case loop: ParameterizedStructure.StructuralFor => loop
    }
    require(loops.size == 2, s"foreign-token fixture retained ${loops.size} loops")
    val writeLoop = loops.head
    val readLoop = loops(1)
    val foreignToken = readLoop.finiteIndexToken.getOrElse {
      throw new IllegalStateException(
        "foreign-token fixture read loop retained no opaque finite identity"
      )
    }
    val writeSelections = writeLoop.body.vecIndices.zipWithIndex.filter {
      case (selection, _) => selection.vector eq staged
    }
    require(
      writeSelections.size == 1,
      s"foreign-token fixture retained ${writeSelections.size} staged write selections"
    )
    val (selection, position) = writeSelections.head
    writeLoop.body.vecIndices = writeLoop.body.vecIndices.updated(
      position,
      new ParameterizedStructure.StructuralVecIndex(
        selection.vector,
        selection.selected,
        selection.result,
        selection.staticAccess,
        selection.index,
        Some(foreignToken),
        selection.sourceLocation
      )
    )
  }

  final class DistinctRootSiblingFiniteVecAggregateBridge(
      writeDepth: ElabInt,
      readDepth: ElabInt
  ) extends Component {
    setDefinitionName("DistinctRootSiblingFiniteVecAggregateBridge")
    val source = in(Vec(Bool(), writeDepth)).setName("source")
    val observed = out(Vec(Bool(), readDepth)).setName("observed")
    val staged = Vec(Bool(), writeDepth).setName("staged")

    ElabFiniteRange.foreach(writeDepth, "distinct-root Vec write") { index =>
      index(staged) := index(source)
    }
    ElabFiniteRange.foreach(readDepth, "distinct-root Vec read") { index =>
      index(observed) := index(staged)
    }
  }

  final class RemovedFiniteVecAliasDeclaration(depth: ElabInt) extends Component {
    setDefinitionName("RemovedFiniteVecAliasDeclaration")
    val values = in(Vec(Bits(8 bits), depth)).setName("values")
    val replacementSource = in(Bits(8 bits)).setName("replacement_source")
    val observed = out(Bits(8 bits)).setName("observed")

    ElabFiniteRange.foreach(depth, "stale finite Vec alias") { index =>
      val retainedAlias = index(values)
      val retainedName = retainedAlias.getName()
      require(
        retainedName != null && retainedName.startsWith(
          "morphhdl_structural_vec_alias_"
        ),
        "stale finite Vec fixture retained no exact named alias"
      )
      retainedAlias.flatten.foreach(_.removeStatement())

      // Recreate the same emitted declaration spelling with a different
      // BaseType identity. Text and names must not authorize the stale
      // StructuralVecIndex result retained by the finite selector.
      val replacement = Bits(8 bits)
        .setName(retainedName)
        .dontSimplifyIt()
      replacement := replacementSource
      observed := replacement
    }
  }

  final class RawStaticVecCarrier(depth: ElabInt) extends Component {
    setDefinitionName("RawStaticVecCarrier")
    val values = in(Vec(Bits(8 bits), depth)).setName("values")
    val selected = out(Bits(8 bits)).setName("selected")
    val bypassed = out(Bits(8 bits)).setName("bypassed")

    selected := values(0)
    // A universally present carrier is intrinsically the same constant Vec
    // element even when accessed through the ordinary internal collection.
    bypassed := values.vec(0)
  }

  final class ReusedStaticVecCarrier(depth: ElabInt) extends Component {
    setDefinitionName("ReusedStaticVecCarrier")
    val values = in(Vec(Bits(8 bits), depth)).setName("values")
    val first = out(Bits(8 bits)).setName("first")
    val second = out(Bits(8 bits)).setName("second")

    // Vec.apply returns the exact carrier element. Reusing that exact element
    // is an ordinary constant Vec read and both uses must remain element zero.
    val selected = values(0)
    first := selected
    second := selected
  }

  final class HighRawStaticVecCarrier(depth: ElabInt) extends Component {
    setDefinitionName("HighRawStaticVecCarrierMustFailClosed")
    val values = in(Vec(Bits(8 bits), depth)).setName("values")
    val observed = out(Bits(8 bits)).setName("observed")

    // Element one is only a carrier-capacity witness when DEPTH may be one.
    // Without an exact structural owner it is not a legal universal slice.
    observed := values.vec(1)
  }

  final class RemovedDynamicReadAssignment(depth: ElabInt) extends Component {
    setDefinitionName("RemovedDynamicReadAssignmentVec")
    val values = in(Vec(Bits(8 bits), depth)).setName("values")
    val index = in(UInt(3 bits)).setName("index")
    val replacement = in(Bits(8 bits)).setName("replacement")
    val observed = out(Bits(8 bits)).setName("observed")
    val internalValues = Vec(Bits(8 bits), depth)
      .setName("internal_values")
      .dontSimplifyIt()
    internalValues := values

    val dynamic = out(internalValues(index))
      .setName("dynamic_result")
      .dontSimplifyIt()
    val access = ParameterizedVec
      .operationsOf(internalValues)
      .collect { case value: ParameterizedVecDynamicAccess => value }
      .last
    require(
      access.assignments.nonEmpty,
      "dynamic stale-lineage fixture retained no native mux assignment"
    )
    access.assignments.foreach(_.removeStatement())

    // A later assignment to the same exact result target must not be mistaken
    // for the removed dynamic-read statement merely because its emitted target
    // name is identical. `Vec.apply(UInt)` normally installs its write-back
    // Assignable on the result; clear that exact native hook so this deliberate
    // adversarial statement is a direct replacement driver, not a Vec write.
    dynamic.flatten.foreach(_.compositeAssign = null)
    dynamic := replacement
    observed := dynamic
  }

  final class RemovedCapturedDynamicReadAssignment(depth: ElabInt)
      extends Component {
    setDefinitionName("RemovedCapturedDynamicReadAssignmentVec")
    val values = in(Vec(Bits(8 bits), depth)).setName("values")
    val index = in(UInt(3 bits)).setName("index")
    val passthroughIn = in(Bool()).setName("passthrough_in")
    val passthroughOut = out(Bool()).setName("passthrough_out")
    passthroughOut := passthroughIn

    (depth > 1).generate {
      val dynamic = values(index)
        .setName("captured_dynamic_result")
        .dontSimplifyIt()
      val consumed = Bits(8 bits)
        .setName("captured_dynamic_consumed")
        .dontSimplifyIt()
      consumed := dynamic
    }

    val access = ParameterizedVec
      .operationsOf(values)
      .collect { case value: ParameterizedVecDynamicAccess => value }
      .last
    require(
      access.assignments.nonEmpty,
      "captured dynamic stale-lineage fixture retained no native mux assignment"
    )
    val owners = ParameterizedStructure.regionsOf(this).flatMap(_.blocks).filter {
      block =>
        access.assignments.forall { assignment =>
          block.assignments.exists(_ eq assignment)
        }
    }
    require(
      owners.size == 1,
      s"captured dynamic stale-lineage fixture retained ${owners.size} structural owners"
    )

    access.assignments.foreach { retained =>
      retained.removeStatement()
      retained.finalTarget.compositeAssign = null
      // Reuse the exact retained Multiplexer expression so the replacement
      // emits the same result bridge and canonical case mapping. Only the
      // removed DataAssignmentStatement identity distinguishes this attack.
      retained.finalTarget.assignFrom(retained.source)
    }
  }

  final class MutatedStandaloneDynamicWriteGuard(depth: ElabInt) extends Component {
    setDefinitionName("MutatedStandaloneDynamicWriteGuardVec")
    val index = in(UInt(3 bits)).setName("index")
    val unrelatedIndex = in(UInt(3 bits)).setName("unrelated_index")
    val writeData = in(Bits(8 bits)).setName("write_data")
    val observed = out(Vec(Bits(8 bits), depth)).setName("observed")
    val storage = Reg(Vec(Bits(8 bits), depth))
      .setName("storage")
      .dontSimplifyIt()

    storage(index) := writeData
    redirectDynamicWriteGuards(storage, unrelatedIndex)
    observed := storage
  }

  final class MutatedConsolidatedDynamicWriteGuard(depth: ElabInt) extends Component {
    setDefinitionName("MutatedConsolidatedDynamicWriteGuardVec")
    val original = in(Vec(Bits(8 bits), depth)).setName("original")
    val index = in(UInt(3 bits)).setName("index")
    val unrelatedIndex = in(UInt(3 bits)).setName("unrelated_index")
    val writeData = in(Bits(8 bits)).setName("write_data")
    val observed = out(Vec(Bits(8 bits), depth)).setName("observed")
    val storage = Vec(Bits(8 bits), depth)
      .setName("storage")
      .dontSimplifyIt()

    storage := original
    storage(index) := writeData
    redirectDynamicWriteGuards(storage, unrelatedIndex)
    observed := storage
  }

  final class ConditionalConsolidatedDynamicWrite(
      depth: ElabInt,
      falseBranch: Boolean,
      nested: Boolean,
      mutate: Boolean
  ) extends Component {
    setDefinitionName("ConditionalConsolidatedDynamicWriteVec")
    val original = in(Vec(Bits(8 bits), depth)).setName("original")
    val index = in(UInt(3 bits)).setName("index")
    val writeData = in(Bits(8 bits)).setName("write_data")
    val enable = in(Bool()).setName("enable")
    val secondEnable = in(Bool()).setName("second_enable")
    val observed = out(Vec(Bits(8 bits), depth)).setName("observed")
    val storage = Vec(Bits(8 bits), depth).setName("storage").dontSimplifyIt()
    storage := original
    def write(): Unit = {
      if (nested) {
        when(secondEnable) { storage(index) := writeData }
      } else storage(index) := writeData
    }
    if (falseBranch) {
      when(enable) { } otherwise { write() }
    } else {
      when(enable) { write() }
    }
    if (mutate) {
      val operation = ParameterizedVec.operationsOf(storage).collect {
        case value: ParameterizedVecDynamicWrite => value
      }.head
      val condition = operation.guards.head.enclosingConditions.head
      require(condition.condition eq enable)
      condition.whenStatement.cond = secondEnable
    }
    observed := storage
  }

  final class CapturedStaticVecWrite(depth: ElabInt, mutation: String) extends Component {
    setDefinitionName("CapturedStaticVecWrite")
    val original = in(Vec(Bits(8 bits), depth)).setName("original")
    val writeData = in(Bits(8 bits)).setName("write_data")
    val unrelatedData = in(Bits(8 bits)).setName("unrelated_data")
    val enable = in(Bool()).setName("enable")
    val observed = out(Vec(Bits(8 bits), depth)).setName("observed")
    val storage = Vec(Bits(8 bits), depth).setName("storage").dontSimplifyIt()
    storage := original
    when(enable) {
      if (mutation == "partial") storage(0)(0) := writeData(0)
      else storage(0) := writeData
    }
    val operation = ParameterizedVec.operationsOf(storage).collect {
      case value: ParameterizedVecStaticWrite => value
    }.head
    mutation match {
      case "source" => operation.assignment.source = unrelatedData
      case "removed" =>
        operation.assignment.removeStatement()
        when(enable) { storage(0) := writeData }
      case _ =>
    }
    observed := storage
  }

  final class StaticDynamicWritePriority(depth: ElabInt, staticAfter: Boolean) extends Component {
    setDefinitionName("StaticDynamicWritePriorityVec")
    val original = in(Vec(Bits(8 bits), depth)).setName("original")
    val index = in(UInt(3 bits)).setName("index")
    val staticData = in(Bits(8 bits)).setName("static_data")
    val dynamicData = in(Bits(8 bits)).setName("dynamic_data")
    val staticEnable = in(Bool()).setName("static_enable")
    val dynamicEnable = in(Bool()).setName("dynamic_enable")
    val observed = out(Vec(Bits(8 bits), depth)).setName("observed")
    val storage = Vec(Bits(8 bits), depth).setName("storage").dontSimplifyIt()
    storage := original
    def staticWrite(): Unit = when(staticEnable) { storage(0) := staticData }
    if (!staticAfter) staticWrite()
    when(dynamicEnable) { storage(index) := dynamicData }
    if (staticAfter) staticWrite()
    observed := storage
  }

  final class DependentStaticVecWrites(depth: ElabInt) extends Component {
    setDefinitionName("DependentStaticVecWrites")
    val original = in(Vec(Bits(8 bits), depth)).setName("original")
    val input = in(Bits(8 bits)).setName("input_value")
    val enable = in(Bool()).setName("enable")
    val observed = out(Vec(Bits(8 bits), depth)).setName("observed")
    val storage = Vec(Bits(8 bits), depth).setName("storage").dontSimplifyIt()
    storage := original
    when(enable) {
      storage(0) := storage(1)
      storage(1) := input
    }
    observed := storage
  }

  final class RetainedStaticVecCopy(width: ElabInt) extends Component {
    setDefinitionName("RetainedStaticVecCopy")
    val input = in(Bits(width bits)).setName("input_value")
    val observed = out(Vec(Bits(width bits), 2)).setName("observed")
    val storage = Vec(Bits(width bits), 2).setName("storage").dontSimplifyIt()
    storage(0) := input
    storage(1) := storage(0)
    observed := storage
  }

  final class RemovedDynamicReadSelect(depth: ElabInt) extends Component {
    setDefinitionName("RemovedDynamicReadSelectVec")
    val original = in(Vec(Bits(8 bits), depth)).setName("original")
    val index = in(UInt(3 bits)).setName("index")
    val observed = out(Bits(8 bits)).setName("observed")
    observed := original(index)
    val access = ParameterizedVec.operationsOf(original).collect {
      case value: ParameterizedVecDynamicAccess => value
    }.head
    val proof = access.readSelect.get
    proof.assignments.foreach(_.removeStatement())
    proof.select := index
  }

  final class NarrowDomainDynamicWrite(
      depth: ElabInt,
      withWholeAssignment: Boolean
  ) extends Component {
    setDefinitionName(
      if (withWholeAssignment) "NarrowDomainConsolidatedDynamicWriteVec"
      else "NarrowDomainStandaloneDynamicWriteVec"
    )
    val original = in(Vec(Bits(8 bits), depth)).setName("original")
    val index = in(UInt(3 bits)).setName("index")
    val writeData = in(Bits(8 bits)).setName("write_data")
    val observed = out(Vec(Bits(8 bits), depth)).setName("observed")
    val storage = (if (withWholeAssignment) Vec(Bits(8 bits), depth)
                   else Reg(Vec(Bits(8 bits), depth)))
      .setName("storage")
      .dontSimplifyIt()

    if (withWholeAssignment) storage := original
    storage(index) := writeData
    observed := storage
  }

  final class MutatedDynamicWriteDecoder(
      depth: ElabInt,
      withWholeAssignment: Boolean
  ) extends Component {
    setDefinitionName(
      if (withWholeAssignment) "MutatedConsolidatedDynamicWriteDecoderVec"
      else "MutatedStandaloneDynamicWriteDecoderVec"
    )
    val original = in(Vec(Bits(8 bits), depth)).setName("original")
    val index = in(UInt(3 bits)).setName("index")
    val unrelatedIndex = in(UInt(3 bits)).setName("unrelated_index")
    val writeData = in(Bits(8 bits)).setName("write_data")
    val observed = out(Vec(Bits(8 bits), depth)).setName("observed")
    val storage = (if (withWholeAssignment) Vec(Bits(8 bits), depth)
                   else Reg(Vec(Bits(8 bits), depth)))
      .setName("storage")
      .dontSimplifyIt()

    if (withWholeAssignment) storage := original
    storage(index) := writeData
    redirectDynamicWriteDecoder(storage, unrelatedIndex)
    observed := storage
  }

  private def redirectDynamicWriteGuards(
      vector: Vec[_],
      unrelatedAddress: UInt
  ): Unit = {
    val unrelatedDecoder = (U(1) << unrelatedAddress)
      .setName("unrelated_decoder")
      .dontSimplifyIt()
    val unrelatedGuards = unrelatedDecoder.asBools
    unrelatedGuards.foreach(_.dontSimplifyIt())

    val writes = ParameterizedVec.operationsOf(vector).collect { case value: ParameterizedVecDynamicWrite =>
      value
    }
    require(writes.nonEmpty, "dynamic guard fixture retained no write operation")
    writes.foreach { write =>
      write.assignments.foreach { assignment =>
        val elementIndex = vector.vec.indexWhere { element =>
          element.asInstanceOf[Data].flatten.exists(_ eq assignment.finalTarget)
        }
        require(
          elementIndex >= 0 && elementIndex < unrelatedGuards.size,
          "dynamic guard fixture could not locate one exact carrier target"
        )
        val owner = Option(assignment.parentScope)
          .flatMap(scope => Option(scope.parentStatement))
          .collect { case value: WhenStatement => value }
          .getOrElse(
            throw new IllegalStateException(
              "dynamic guard fixture assignment has no native When owner"
            )
          )
        owner.cond = unrelatedGuards(elementIndex)
      }
    }
  }

  private def redirectDynamicWriteDecoder(
      vector: Vec[_],
      unrelatedAddress: UInt
  ): Unit = {
    val writes = ParameterizedVec.operationsOf(vector).collect { case value: ParameterizedVecDynamicWrite =>
      value
    }
    val drivers = writes
      .flatMap(_.decoderAssignments)
      .foldLeft(
        Vector.empty[spinal.core.internals.DataAssignmentStatement]
      ) { (known, assignment) =>
        if (known.exists(_ eq assignment)) known else known :+ assignment
      }
    require(
      writes.nonEmpty && drivers.size == 1,
      s"dynamic decoder fixture retained ${writes.size} writes and ${drivers.size} exact decoder drivers"
    )
    drivers.head.source match {
      case shift: spinal.core.internals.Operator.UInt.ShiftLeftByUInt =>
        shift.right = unrelatedAddress
      case other =>
        throw new IllegalStateException(
          s"dynamic decoder fixture retained unexpected source ${other.getClass.getName}"
        )
    }
  }

  final class StaleHierarchyVecChild(depth: ElabInt) extends Component {
    setDefinitionName("StaleHierarchyVecChild")
    val values = in(Vec(Bits(8 bits), depth)).setName("values")
    val observed = out(Bits(8 bits)).setName("observed")
    observed := values(0)
  }

  final class RemovedHierarchyVecAssignment(depth: ElabInt) extends Component {
    setDefinitionName("RemovedHierarchyVecAssignment")
    val values = in(Vec(Bits(8 bits), depth)).setName("values")
    val observed = out(Bits(8 bits)).setName("observed")
    val child = ElabFormalComponent
      .parameter(
        actual = depth,
        name = "DEPTH",
        minimum = BigInt(1),
        maximum = BigInt(8)
      )(childDepth => new StaleHierarchyVecChild(childDepth))
      .setName("child")

    child.values := values
    val retained = ParameterizedVec
      .operationsOf(child.values)
      .collect { case value: ParameterizedVecWholeAssignment => value }
      .last
    require(
      retained.assignments.nonEmpty,
      "hierarchy stale-lineage fixture retained no aggregate assignments"
    )
    retained.assignments.foreach(_.removeStatement())

    // Restore the same native child connections leaf by leaf without creating
    // another aggregate Vec operation. The stale aggregate record must not
    // authorize this unrelated replacement wiring.
    child.values.vec.zip(values.vec).foreach { case (target, source) =>
      target.allowOverride()
      target := source
    }
    observed := child.observed
  }

  final class HarmlessPrunedVec(depth: ElabInt) extends Component {
    setDefinitionName("HarmlessPrunedVec")
    val passthroughIn = in(Bool()).setName("passthrough_in")
    val passthroughOut = out(Bool()).setName("passthrough_out")
    val symbolicIn = in(Bits(depth bits)).setName("symbolic_in")
    val symbolicOut = out(Bits(depth bits)).setName("symbolic_out")
    val unusedValues = Vec(Bits(8 bits), depth).setName("unused_values")
    passthroughOut := passthroughIn
    symbolicOut := symbolicIn

    // Model a genuinely pruned carrier: publication must inventory the
    // retained Vec by exact component ownership even though none of its exact
    // native leaf declarations survives.
    unusedValues.vec.foreach(_.flatten.foreach(_.removeStatement()))
  }

  final class FormallyBoundPrunedVecChild(depth: ElabInt) extends Component {
    setDefinitionName("FormallyBoundPrunedVecChild")
    val passthroughIn = in(Bool()).setName("passthrough_in")
    val passthroughOut = out(Bool()).setName("passthrough_out")
    val unusedValues = Vec(Bits(8 bits), depth).setName("unused_values")
    passthroughOut := passthroughIn

    unusedValues.vec.foreach(_.flatten.foreach(_.removeStatement()))
  }

  final class RequiredPrunedVecHierarchy(depth: ElabInt) extends Component {
    setDefinitionName("RequiredPrunedVecHierarchy")
    val passthroughIn = in(Bool()).setName("passthrough_in")
    val passthroughOut = out(Bool()).setName("passthrough_out")
    val child = ElabFormalComponent
      .parameter(
        actual = depth,
        name = "DEPTH",
        minimum = BigInt(1),
        maximum = BigInt(8)
      )(childDepth => new FormallyBoundPrunedVecChild(childDepth))
      .setName("child")
    child.passthroughIn := passthroughIn
    passthroughOut := child.passthroughOut
  }

  final class CoincidentStructuralSlice(lanes: HdlInt) extends Component {
    setDefinitionName("CoincidentStructuralSlice")
    val din = in(morphhdl.frontend.Bits(64 bits)).setName("din")

    (0 until lanes).named("g_coincident_slice", "slice_index").foreach { index =>
      val byteWidth = HdlInt.literal(BigInt(8))
      val selected = morphhdl.frontend.Bits(8 bits).setName("selected_slice")
      selected := din(index * byteWidth, byteWidth)
      selected.dontSimplifyIt()

      // At the representative index zero this is textually identical to the
      // selected slice above, but it is not that retained slice operation.
      val coincident = Bits(8 bits).setName("coincident_slice")
      coincident := din(7 downto 0)
      coincident.dontSimplifyIt()
    }
  }

  final class EscapingStructuralSlice(lanes: HdlInt) extends Component {
    setDefinitionName("EscapingStructuralSliceMustFailClosed")
    val din = in(morphhdl.frontend.Bits(32 bits)).setName("din")

    (0 until lanes).named("g_escaping_slice", "slice_index").foreach { index =>
      val byteWidth = HdlInt.literal(BigInt(8))
      // The representative index-zero slice fits the concrete source, while
      // admitted high indices require bits beyond its complete width.
      val selected = morphhdl.frontend.Bits(8 bits).setName("selected_slice")
      selected := din(index * byteWidth, byteWidth)
      selected.dontSimplifyIt()
    }
  }

  final class RemovedStructuralSliceAssignment(lanes: HdlInt) extends Component {
    setDefinitionName("RemovedStructuralSliceAssignment")
    val din = in(morphhdl.frontend.Bits(64 bits)).setName("din")
    val keepIn = in(Bool()).setName("keep_in")

    (0 until lanes).named("g_stale_slice", "slice_index").foreach { index =>
      val byteWidth = HdlInt.literal(BigInt(8))
      val selected = din(index * byteWidth, byteWidth)
        .setName("selected_slice")
        .dontSimplifyIt()
      selected.setAsVital()
      val keep = Bool().setName("keep").dontSimplifyIt()
      keep := keepIn
    }

    val retained = ParameterizedStructure
      .regionsOf(this)
      .flatMap(ParameterizedStructure.allBlocks)
      .flatMap(_.slices)
      .lastOption
      .getOrElse {
        throw new IllegalStateException(
          "stale structural-slice fixture retained no exact slice record"
        )
      }
    retained.assignment.removeStatement()

    // Recreate the witness-identical target text with another statement. The
    // retained slice record must not authorize this replacement identity.
    retained.result.flatten.foreach(_.compositeAssign = null)
    retained.result.allowOverride()
    retained.result := din(7 downto 0)
  }

  final class RemovedFiniteMemPort(depth: ElabInt) extends Component {
    setDefinitionName("RemovedFiniteMemPort")
    val memory = Mem(Bits(8 bits), depth).setName("memory")

    ElabFiniteRange.foreach(depth, "stale finite Mem") { index =>
      val selected = index(memory)
      val port = memory.dlcLast match {
        case value: spinal.core.MemReadAsync => value
        case other =>
          throw new IllegalStateException(
            s"finite Mem fixture retained unexpected port ${other.getClass.getName}"
          )
      }
      port.setName("finite_mem_port")
      val readBits = port.elaborationReadBits
        .setName("finite_mem_read_bits")

      val readBridges = Vector.newBuilder[
        spinal.core.internals.DataAssignmentStatement
      ]
      readBits.foreachStatements {
        case assignment: spinal.core.internals.DataAssignmentStatement
            if (assignment.finalTarget eq readBits) &&
              (assignment.source eq port) =>
          readBridges += assignment
        case _ =>
      }
      val selectedAssignments = selected.flatten.flatMap { leaf =>
        val values = Vector.newBuilder[
          spinal.core.internals.DataAssignmentStatement
        ]
        leaf.foreachStatements {
          case assignment: spinal.core.internals.DataAssignmentStatement if assignment.finalTarget eq leaf =>
            values += assignment
          case _ =>
        }
        values.result()
      }.toVector
      val bridges = readBridges.result()
      require(
        bridges.size == 1 && selectedAssignments.nonEmpty,
        "finite Mem fixture did not retain its exact native read lineage"
      )

      selectedAssignments.foreach(_.removeStatement())
      bridges.foreach(_.removeStatement())
      port.removeStatement()

      // Restore the same emitted port/readBits spelling and witness access
      // through a different native MemReadAsync identity. Text and names must
      // not authorize the stale StructuralMemoryIndex record above.
      val replacement = memory
        .readAsync(U(0, memory.nativePortAddressWidth bits))
        .setName("replacement_selected")
        .dontSimplifyIt()
      val replacementPort = memory.dlcLast match {
        case value: spinal.core.MemReadAsync => value
        case other =>
          throw new IllegalStateException(
            s"finite Mem fixture replacement retained unexpected port ${other.getClass.getName}"
          )
      }
      replacementPort.setName("finite_mem_port")
      replacementPort.elaborationReadBits
        .setName("finite_mem_read_bits")
        .dontSimplifyIt()
    }
  }

  final class RemovedRetainedValueAssignment(value: ElabInt) extends Component {
    setDefinitionName("RemovedRetainedValueAssignment")
    val passthroughIn = in(Bool()).setName("passthrough_in")
    val passthroughOut = out(Bool()).setName("passthrough_out")
    val observed = out(UInt(4 bits)).setName("observed")
    passthroughOut := passthroughIn

    val retained = ElabValue
      .uintLike(value, UInt(4 bits), "retained_value")
      .dontSimplifyIt()
    val record = ExternalParameterizedValueRegistry.recordOf(retained).getOrElse {
      throw new IllegalStateException(
        "retained-value stale fixture has no exact registry record"
      )
    }
    val assignment = record.assignment.getOrElse {
      throw new IllegalStateException(
        "retained-value stale fixture lost its exact witness assignment"
      )
    }
    assignment.removeStatement()

    // Restore the same target and literal witness with a distinct assignment
    // and source identity. Emitted spelling is not evidence for the removed
    // typed-value driver. A diagnostic replay may reject this replacement at
    // the adapter's exact source-lineage proof before backend stale validation.
    retained.allowOverride()
    retained := U(record.witness)
    observed := retained
  }

  final class MutatedRetainedValueSource(value: ElabInt) extends Component {
    setDefinitionName("MutatedRetainedValueSource")
    val passthroughIn = in(Bool()).setName("passthrough_in")
    val passthroughOut = out(Bool()).setName("passthrough_out")
    val observed = out(UInt(4 bits)).setName("observed")
    passthroughOut := passthroughIn

    val retained = ElabValue
      .uintLike(value, UInt(4 bits), "retained_value")
      .dontSimplifyIt()
    val record = ExternalParameterizedValueRegistry.recordOf(retained).getOrElse {
      throw new IllegalStateException(
        "retained-value source fixture has no exact registry record"
      )
    }
    val assignment = record.assignment.getOrElse {
      throw new IllegalStateException(
        "retained-value source fixture lost its exact witness assignment"
      )
    }
    assignment.source = U(record.witness)
    observed := retained
  }
}

class StructuralIdentityAdversarialTests extends AnyFunSuite {
  import StructuralIdentityAdversarialFixture._

  test("arbitrary AssertStatement remains forbidden inside structural capture") {
    withTemporaryDirectory { directory =>
      val config = morphConfig(directory, "captured_cover.v")
      config.includeFormal
      val failure = MorphVerilog.tryGenerate(config) {
        new CapturedCover(parameter("DEPTH", 1, 1, 2))
      } match {
        case Left(value)  => value
        case Right(value) => fail(s"expected captured AssertStatement rejection, received $value")
      }

      assert(
        failure.detail.contains(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SCALA-SIDE-EFFECT-UNSUPPORTED"
        ),
        failure.detail
      )
      assert(failure.detail.contains("AssertStatement"), failure.detail)
      assert(!Files.exists(directory.resolve("captured_cover.v")))
    }
  }

  test("coincident constant Vec reference remains fixed beside a finite index") {
    withTemporaryDirectory { directory =>
      val config = morphConfig(directory, "coincident_finite_vec.v")
      MorphVerilog(config) {
        new CoincidentFiniteVec(parameter("DEPTH", 3, 1, 8))
      }
      val verilog = readVerilog(directory, config)

      assert(
        "assign\\s+coincident_value\\s*=\\s*values\\s*\\[\\s*\\(?\\s*0\\s*\\)?\\s*\\+:\\s*8\\s*\\]\\s*;".r
          .findFirstIn(verilog)
          .nonEmpty,
        verilog
      )
      assert(
        generatedVecReadIndex(verilog, "selected_value", "values").nonEmpty,
        "finite selection retained no generated index"
      )
    }
  }

  test("an unused finite Vec selection cannot promote a raw witness carrier") {
    withTemporaryDirectory { directory =>
      val config = morphConfig(directory, "unused_finite_vec_raw.v")
      MorphVerilog(config) {
        new UnusedFiniteVecWithRawCarrier(parameter("DEPTH", 3, 1, 8))
      }
      val verilog = readVerilog(directory, config)
      assert(
        "assign\\s+raw_value\\s*=\\s*values\\s*\\[\\s*\\(?\\s*0\\s*\\)?\\s*\\+:\\s*8\\s*\\]\\s*;".r
          .findFirstIn(verilog)
          .nonEmpty,
        verilog
      )
      assert(
        "assign\\s+raw_value\\s*=.*unused_finite_Vec_index".r
          .findFirstIn(verilog)
          .isEmpty,
        verilog
      )
    }
  }

  test("a consumed projected finite Vec witness cannot promote a raw carrier") {
    withTemporaryDirectory { directory =>
      val rtl = directory.resolve("projected_finite_vec_raw_carrier.v")
      val failure = MorphVerilog.tryGenerate(
        morphConfig(directory, rtl.getFileName.toString)
      ) {
        new ProjectedFiniteVecWithRawCarrier(parameter("DEPTH", 1, 1, 8))
      } match {
        case Left(value)  => value
        case Right(value) =>
          fail(s"expected projected raw-carrier rejection, received $value")
      }
      assert(
        failure.detail.contains(
          "SPINAL-ELAB-DOMAIN-PROJECTION-SCOPE-EXPANSION"
        ),
        failure.detail
      )
      assert(!Files.exists(rtl), "projected raw carrier published partial RTL")
    }
  }

  test("one finite Vec wrapper may be reused without losing its exact selection") {
    withTemporaryDirectory { directory =>
      val config = morphConfig(directory, "reused_finite_vec.v")
      MorphVerilog(config) {
        new ReusedFiniteVecSelection(parameter("DEPTH", 3, 1, 8))
      }
      val verilog = readVerilog(directory, config)
      val firstIndex = generatedVecReadIndex(verilog, "first_value", "values")
      val secondIndex = generatedVecReadIndex(verilog, "second_value", "values")
      assert(
        firstIndex == secondIndex,
        s"reused finite Vec selection diverged across $firstIndex and $secondIndex"
      )
      assert(!verilog.contains("morphhdl_structural_vec_alias"), verilog)
    }
  }

  test("packed symbolic Vec selections reject unretained sub-selections") {
    withTemporaryDirectory { directory =>
      val rtl = directory.resolve("packed_partial_finite_vec.v")
      val failure = MorphVerilog.tryGenerate(
        morphConfig(directory, rtl.getFileName.toString)
      ) {
        new PackedPartialFiniteVecSelection(parameter("DEPTH", 3, 1, 8))
      } match {
        case Left(value) => value
        case Right(value) =>
          fail(s"expected packed partial Vec rejection, received $value")
      }
      assert(
        failure.detail.contains(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-VEC-ALIAS-PARTIAL-USE-UNSUPPORTED"
        ),
        failure.detail
      )
      assert(!Files.exists(rtl), "packed partial Vec published partial RTL")
    }
  }

  test("finite selection keeps an internal typed Vec alive and packed") {
    withTemporaryDirectory { directory =>
      val config = morphConfig(directory, "internal_finite_vec.v")
      MorphVerilog(config) {
        new InternalFiniteVecSelection(parameter("DEPTH", 3, 1, 8))
      }
      val verilog = readVerilog(directory, config)
      assert(
        "(?:wire|reg)\\s*\\[\\s*\\(?\\s*8\\s*\\*\\s*DEPTH\\s*\\)?\\s*-\\s*1\\s*:\\s*0\\s*\\]\\s+internal_values\\s*;".r
          .findFirstIn(verilog)
          .nonEmpty,
        verilog
      )
      generatedVecReadIndex(verilog, "internal_observed", "internal_values")
      assert(!verilog.contains("morphhdl_structural_vec_alias"), verilog)
    }
  }

  test("finite Vec selection rejects a foreign opaque range identity") {
    withTemporaryDirectory { directory =>
      val rtl = directory.resolve("foreign_finite_index_token_vec.v")
      val failure = MorphVerilog.tryGenerate(
        morphConfig(directory, rtl.getFileName.toString)
      ) {
        new ForeignFiniteIndexTokenAggregateBridge(
          parameter("DEPTH", 3, 1, 8)
        )
      } match {
        case Left(value)  => value
        case Right(value) =>
          fail(s"expected foreign finite-index token rejection, received $value")
      }
      assert(
        failure.detail.contains(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-FINITE-INDEX-OWNER-MISMATCH"
        ),
        failure.detail
      )
      assert(!Files.exists(rtl), "foreign finite-index token published partial RTL")
    }
  }

  test("finite Vec alias cloned from a port never becomes another port") {
    withTemporaryDirectory { directory =>
      val config = morphConfig(directory, "finite_vec_port_alias.v")
      MorphVerilog(config) {
        new ReusedFiniteVecSelection(parameter("DEPTH", 3, 1, 8))
      }
      val verilog = readVerilog(directory, config)
      assert(
        "(?m)^\\s*input\\s+wire\\s*\\[[^\\n]*\\]\\s+values\\s*[,;]\\s*$".r
          .findFirstIn(verilog)
          .nonEmpty,
        verilog
      )
      assert(!verilog.contains("morphhdl_structural_vec_alias"), verilog)
    }
  }

  test("finite Vec alias is directionless and supports whole-leaf LHS use") {
    withTemporaryDirectory { directory =>
      val config = morphConfig(directory, "finite_vec_lhs.v")
      MorphVerilog(config) {
        new FiniteVecSelectionOnLhs(parameter("DEPTH", 3, 1, 8))
      }
      val verilog = readVerilog(directory, config)
      generatedVecWriteIndex(verilog, "values", "data")
      assert(!verilog.contains("morphhdl_structural_vec_alias"), verilog)
    }
  }

  test("output-only finite structural typed Vec is admitted by exact identity") {
    withTemporaryDirectory { directory =>
      val config = morphConfig(directory, "output_only_finite_vec.v")
      MorphVerilog(config) {
        new OutputOnlyFiniteStructuralVec(parameter("DEPTH", 3, 1, 8))
      }
      val verilog = readVerilog(directory, config)
      assert(
        "(?m)^\\s*output\\s+wire\\s*\\[[^\\n]*DEPTH[^\\n]*\\]\\s+values\\s*[,;]?\\s*$".r
          .findFirstIn(verilog)
          .nonEmpty,
        verilog
      )
      assert(!"(?m)^\\s*input\\b".r.findFirstIn(verilog).nonEmpty, verilog)
      assert(
        "assign\\s+values\\s*\\[[^\\]]+\\+:\\s*8\\s*\\]\\s*=\\s*8'h5a\\s*;".r
          .findFirstIn(verilog.toLowerCase)
          .nonEmpty,
        verilog
      )
      assert(!verilog.contains("morphhdl_structural_vec_alias"), verilog)
    }
  }

  test("output-only scalar and mixed Vec scalar surfaces remain rejected") {
    withTemporaryDirectory { directory =>
      expectPortDirectionsFailure(directory, "output_only_scalar.v") {
        new OutputOnlySymbolicScalar(parameter("WIDTH", 8, 1, 16))
      }
      expectPortDirectionsFailure(directory, "output_only_vec_scalar.v") {
        new OutputOnlyFiniteVecWithScalar(parameter("DEPTH", 3, 1, 8))
      }
    }
  }

  test("input-only and inout-only symbolic surfaces remain rejected") {
    withTemporaryDirectory { directory =>
      expectPortDirectionsFailure(directory, "input_only_vec.v") {
        new InputOnlyTypedVec(parameter("DEPTH", 3, 1, 8))
      }
      expectPortDirectionsFailure(directory, "inout_only_bits.v") {
        new InOutOnlySymbolicBits(parameter("WIDTH", 8, 1, 16))
      }
    }
  }

  test("distinct same-index Vec calls keep independent aliases") {
    withTemporaryDirectory { directory =>
      val config = morphConfig(directory, "distinct_finite_vec.v")
      MorphVerilog(config) {
        new DistinctFiniteVecSelections(parameter("DEPTH", 3, 1, 8))
      }
      val verilog = readVerilog(directory, config)
      val firstIndex =
        generatedVecReadIndex(verilog, "first_distinct", "values")
      val secondIndex =
        generatedVecReadIndex(verilog, "second_distinct", "values")
      assert(
        firstIndex == secondIndex,
        s"same structural index diverged across $firstIndex and $secondIndex"
      )
      assert(!verilog.contains("morphhdl_structural_vec_alias"), verilog)
    }
  }

  test("exact same-root sibling Vec loops share one packed aggregate") {
    withTemporaryDirectory { directory =>
      val config = morphConfig(directory, "sibling_finite_vec_aggregate.v")
      MorphVerilog(config) {
        new SiblingFiniteVecAggregateBridge(
          parameter("DEPTH", 3, 1, 8)
        )
      }
      val verilog = readVerilog(directory, config)
      assert(verilog.contains("staged["), verilog)
      assert(!verilog.contains("morphhdl_structural_vec_alias"), verilog)
    }
  }

  test("equal-domain distinct-root sibling Vec loops cannot authorize aggregate bridge") {
    withTemporaryDirectory { directory =>
      val rtl = directory.resolve("distinct_root_sibling_finite_vec_aggregate.v")
      val failure = MorphVerilog.tryGenerate(
        morphConfig(directory, rtl.getFileName.toString)
      ) {
        new DistinctRootSiblingFiniteVecAggregateBridge(
          parameter("WRITE_DEPTH", 3, 1, 8),
          parameter("READ_DEPTH", 3, 1, 8)
        )
      } match {
        case Left(value) => value
        case Right(value) =>
          fail(s"expected distinct-root aggregate rejection, received $value")
      }
      assert(
        failure.detail.contains(
          "SPINAL-ELAB-FINITE-RANGE-VEC-DEPTH-MISMATCH"
        ),
        failure.detail
      )
      assert(!Files.exists(rtl), "distinct-root Vec bridge published partial RTL")
    }
  }

  test("removed finite Vec alias declaration cannot be replaced by same-name text") {
    withTemporaryDirectory { directory =>
      val rtl = directory.resolve("removed_finite_vec_alias_declaration.v")
      val failure = MorphVerilog.tryGenerate(
        morphConfig(directory, rtl.getFileName.toString)
      ) {
        new RemovedFiniteVecAliasDeclaration(parameter("DEPTH", 3, 1, 8))
      } match {
        case Left(value) => value
        case Right(value) =>
          fail(s"expected stale finite Vec alias rejection, received $value")
      }
      assert(
        failure.detail.contains(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-VEC-ALIAS-OWNERSHIP-MISMATCH"
        ),
        failure.detail
      )
      assert(!Files.exists(rtl), "stale finite Vec alias published partial RTL")
    }
  }

  test("universally present raw and reused static Vec carriers remain legal") {
    withTemporaryDirectory { directory =>
      val rawConfig = morphConfig(directory, "raw_static_vec_carrier.v")
      MorphVerilog(rawConfig) {
        new RawStaticVecCarrier(parameter("DEPTH", 3, 1, 8))
      }
      val rawVerilog = readVerilog(directory, rawConfig)
      assertConstantVecAssignment(rawVerilog, "selected")
      assertConstantVecAssignment(rawVerilog, "bypassed")

      val reusedConfig = morphConfig(directory, "reused_static_vec_carrier.v")
      MorphVerilog(reusedConfig) {
        new ReusedStaticVecCarrier(parameter("DEPTH", 3, 1, 8))
      }
      val reusedVerilog = readVerilog(directory, reusedConfig)
      assertConstantVecAssignment(reusedVerilog, "first")
      assertConstantVecAssignment(reusedVerilog, "second")
    }
  }

  test("capacity-only raw static Vec carrier fails closed") {
    withTemporaryDirectory { directory =>
      val rtl = directory.resolve("high_raw_static_vec_carrier.v")
      val failure = MorphVerilog.tryGenerate(
        morphConfig(directory, rtl.getFileName.toString)
      ) {
        new HighRawStaticVecCarrier(parameter("DEPTH", 3, 1, 8))
      } match {
        case Left(value) => value
        case Right(value) =>
          fail(s"expected capacity-only carrier rejection, received $value")
      }
      assert(
        failure.detail.contains(
          "SPINAL-PARAMETERIZED-VERILOG-VEC-RESIDUAL-CARRIER-REFERENCE"
        ),
        failure.detail
      )
      assert(!Files.exists(rtl), "capacity-only carrier published partial RTL")
    }
  }

  test("removed dynamic Vec read evidence cannot rewrite a replacement target") {
    withTemporaryDirectory { directory =>
      val rtl = directory.resolve("removed_dynamic_read_assignment.v")
      val failure = MorphVerilog.tryGenerate(
        morphConfig(directory, rtl.getFileName.toString)
      ) {
        new RemovedDynamicReadAssignment(parameter("DEPTH", 3, 1, 8))
      } match {
        case Left(value) => value
        case Right(value) =>
          fail(s"expected stale dynamic-read rejection, received $value")
      }
      assert(
        failure.detail.contains(
          "SPINAL-PARAMETERIZED-VERILOG-VEC-ASSIGNMENT-EVIDENCE-STALE"
        ),
        failure.detail
      )
      assert(!Files.exists(rtl), "stale dynamic Vec read published partial RTL")
    }
  }

  test("captured removed dynamic Vec read evidence cannot authorize an identical mux replacement") {
    withTemporaryDirectory { directory =>
      val rtl = directory.resolve("removed_captured_dynamic_read_assignment.v")
      val failure = MorphVerilog.tryGenerate(
        morphConfig(directory, rtl.getFileName.toString)
      ) {
        new RemovedCapturedDynamicReadAssignment(
          parameter("DEPTH", 3, 1, 8)
        )
      } match {
        case Left(value) => value
        case Right(value) =>
          fail(s"expected captured stale dynamic-read rejection, received $value")
      }
      assert(
        failure.detail.contains(
          "SPINAL-PARAMETERIZED-VERILOG-VEC-ASSIGNMENT-EVIDENCE-STALE"
        ),
        failure.detail
      )
      assert(
        !Files.exists(rtl),
        "captured stale dynamic Vec read published partial RTL"
      )
    }
  }

  test("mutated standalone dynamic-write guards cannot launder exact assignments") {
    withTemporaryDirectory { directory =>
      val rtl = directory.resolve("mutated_standalone_dynamic_guard.v")
      val failure = MorphVerilog.tryGenerate(
        morphConfig(directory, rtl.getFileName.toString)
      ) {
        new MutatedStandaloneDynamicWriteGuard(parameter("DEPTH", 3, 1, 8))
      } match {
        case Left(value) => value
        case Right(value) =>
          fail(s"expected mutated dynamic-write guard rejection, received $value")
      }
      assert(
        Vector(
          "SPINAL-PARAMETERIZED-VERILOG-VEC-DYNAMIC-WRITE-CONTROL-UNSUPPORTED",
          "SPINAL-PARAMETERIZED-VERILOG-VEC-ASSIGNMENT-EVIDENCE-STALE"
        ).exists(failure.detail.contains),
        failure.detail
      )
      assert(!Files.exists(rtl), "mutated standalone guard published partial RTL")
    }
  }

  test("mutated consolidated dynamic-write guards cannot launder exact assignments") {
    withTemporaryDirectory { directory =>
      val rtl = directory.resolve("mutated_consolidated_dynamic_guard.v")
      val failure = MorphVerilog.tryGenerate(
        morphConfig(directory, rtl.getFileName.toString)
      ) {
        new MutatedConsolidatedDynamicWriteGuard(parameter("DEPTH", 3, 1, 8))
      } match {
        case Left(value) => value
        case Right(value) =>
          fail(s"expected consolidated dynamic-write guard rejection, received $value")
      }
      assert(
        Vector(
          "SPINAL-PARAMETERIZED-VERILOG-VEC-DYNAMIC-WRITE-CONTROL-UNSUPPORTED",
          "SPINAL-PARAMETERIZED-VERILOG-VEC-ASSIGNMENT-EVIDENCE-STALE"
        ).exists(failure.detail.contains),
        failure.detail
      )
      assert(!Files.exists(rtl), "mutated consolidated guard published partial RTL")
    }
  }

  test("conditional dynamic Vec overrides preserve exact true false and nested Bool paths") {
    withTemporaryDirectory { directory =>
      Vector(false, true).foreach { falseBranch =>
        Vector(false, true).foreach { nested =>
          val config = morphConfig(directory, s"conditional_dynamic_${falseBranch}_${nested}.v")
          MorphVerilog(config) {
            new ConditionalConsolidatedDynamicWrite(parameter("DEPTH", 3, 1, 8), falseBranch, nested, mutate = false)
          }
          val text = readVerilog(directory, config).filterNot(_.isWhitespace)
          val predicate = if (falseBranch) "(!enable)" else "(enable)"
          assert(text.contains(s"if($predicate&&"), text)
          if (nested) assert(text.contains("&&(second_enable)&&"), text)
        }
      }
    }
  }

  test("mutated enclosing dynamic-write condition cannot replace captured Bool identity") {
    withTemporaryDirectory { directory =>
      val rtl = directory.resolve("mutated_dynamic_condition.v")
      val failure = MorphVerilog.tryGenerate(morphConfig(directory, rtl.getFileName.toString)) {
        new ConditionalConsolidatedDynamicWrite(parameter("DEPTH", 3, 1, 8), falseBranch = false, nested = false, mutate = true)
      } match {
        case Left(value) => value
        case Right(value) => fail(s"expected changed native condition rejection, received $value")
      }
      assert(failure.detail.contains("SPINAL-PARAMETERIZED-VERILOG-VEC-DYNAMIC-WRITE-CONTROL-UNSUPPORTED"), failure.detail)
      assert(!Files.exists(rtl), "changed dynamic-write condition published partial RTL")
    }
  }

  test("static and dynamic indexed Vec overrides preserve native assignment priority") {
    withTemporaryDirectory { directory =>
      Vector(false, true).foreach { staticAfter =>
        val config = morphConfig(directory, s"static_dynamic_priority_$staticAfter.v")
        MorphVerilog(config) {
          new StaticDynamicWritePriority(parameter("DEPTH", 3, 1, 8), staticAfter)
        }
        val lines = readVerilog(directory, config).split("\\r?\\n").toVector
          .filter(line => line.contains("if (") && line.contains("storage["))
        assert(lines.size == 2, lines.mkString("\n"))
        assert(lines.head.contains(if (staticAfter) "dynamic_enable" else "static_enable"), lines.mkString("\n"))
        assert(lines.last.contains(if (staticAfter) "static_enable" else "dynamic_enable"), lines.mkString("\n"))
      }
    }
  }

  test("a static-only Vec override consolidates exact continuous and procedural defaults") {
    withTemporaryDirectory { directory =>
      val config = morphConfig(directory, "static_only_vec_write.v")
      MorphVerilog(config) { new CapturedStaticVecWrite(parameter("DEPTH", 3, 1, 8), "none") }
      val text = readVerilog(directory, config).filterNot(_.isWhitespace)
      assert(text.contains("if((enable))storage["), text)
      assert(text.contains("reg["), text)
    }
  }

  test("static Vec source mutation removed evidence and partial targets fail closed") {
    withTemporaryDirectory { directory =>
      Vector("source", "removed", "partial").foreach { mutation =>
        val rtl = directory.resolve(s"static_write_$mutation.v")
        val failure = MorphVerilog.tryGenerate(morphConfig(directory, rtl.getFileName.toString)) {
          new CapturedStaticVecWrite(parameter("DEPTH", 3, 1, 8), mutation)
        } match {
          case Left(value) => value
          case Right(value) => fail(s"expected static write $mutation rejection, received $value")
        }
        assert(Vector("SPINAL-PARAMETERIZED-VERILOG-VEC-STATIC-WRITE-EVIDENCE-MISMATCH",
          "SPINAL-PARAMETERIZED-VERILOG-VEC-ASSIGNMENT-EVIDENCE-STALE").exists(failure.detail.contains), failure.detail)
        assert(!Files.exists(rtl), s"static write $mutation published partial RTL")
      }
    }
  }

  test("static Vec consolidation rejects target dependencies that change native process ordering") {
    withTemporaryDirectory { directory =>
      val rtl = directory.resolve("dependent_static_writes.v")
      val failure = MorphVerilog.tryGenerate(morphConfig(directory, rtl.getFileName.toString)) {
        new DependentStaticVecWrites(parameter("DEPTH", 3, 2, 8))
      } match {
        case Left(value) => value
        case Right(value) => fail(s"expected native process dependency rejection, received $value")
      }
      assert(failure.detail.contains("SPINAL-PARAMETERIZED-VERILOG-VEC-INDEXED-WRITE-FEEDBACK-UNSUPPORTED"), failure.detail)
      assert(!Files.exists(rtl), "dependent native carrier processes published partial RTL")
    }
  }

  test("a fully retained Vec preserves an exact same-carrier static scalar copy") {
    withTemporaryDirectory { directory =>
      val config = morphConfig(directory, "retained_static_vec_copy.v")
      MorphVerilog(config) {
        new RetainedStaticVecCopy(parameter("WIDTH", 8, 1, 16))
      }
      val text = readVerilog(directory, config)
        .filterNot(character => character.isWhitespace || character == '(' || character == ')')
      assert(text.contains("assignstorage[0+:WIDTH]=input_value;"), text)
      assert(text.contains("assignstorage[1*WIDTH+:WIDTH]=storage[0+:WIDTH];"), text)
      assert(text.contains("assignobserved=storage;"), text)
    }
  }

  test("removed native dynamic-read select cannot be replaced by same-address text") {
    withTemporaryDirectory { directory =>
      val rtl = directory.resolve("removed_dynamic_read_select.v")
      val failure = MorphVerilog.tryGenerate(morphConfig(directory, rtl.getFileName.toString)) {
        new RemovedDynamicReadSelect(parameter("DEPTH", 3, 1, 8))
      } match {
        case Left(value) => value
        case Right(value) => fail(s"expected exact read-select driver rejection, received $value")
      }
      assert(failure.detail.contains("SPINAL-PARAMETERIZED-VERILOG-VEC-ASSIGNMENT-EVIDENCE-STALE"), failure.detail)
      assert(!Files.exists(rtl), "removed native read-select driver published partial RTL")
    }
  }

  test("mutated dynamic-write decoder geometry fails in both lowering paths") {
    withTemporaryDirectory { directory =>
      Vector(false, true).foreach { withWholeAssignment =>
        val mode = if (withWholeAssignment) "consolidated" else "standalone"
        val rtl = directory.resolve(s"mutated_${mode}_dynamic_decoder.v")
        val failure = MorphVerilog.tryGenerate(
          morphConfig(directory, rtl.getFileName.toString)
        ) {
          new MutatedDynamicWriteDecoder(
            parameter("DEPTH", 3, 1, 8),
            withWholeAssignment
          )
        } match {
          case Left(value) => value
          case Right(value) =>
            fail(s"expected mutated $mode decoder rejection, received $value")
        }
        assert(
          failure.detail.contains(
            "SPINAL-PARAMETERIZED-VERILOG-VEC-DYNAMIC-WRITE-GUARD-MISMATCH"
          ),
          failure.detail
        )
        assert(!Files.exists(rtl), s"mutated $mode decoder published partial RTL")
      }
    }
  }

  test("dynamic writes retain non-power-of-two and singleton carrier guards") {
    withTemporaryDirectory { directory =>
      Vector(false, true).foreach { withWholeAssignment =>
        val mode = if (withWholeAssignment) "consolidated" else "standalone"
        Vector(
          ("narrow", parameter("DEPTH", 3, 1, 5)),
          ("singleton", parameter("DEPTH", 1, 1, 1))
        ).foreach { case (domain, depth) =>
          val config = morphConfig(
            directory,
            s"${mode}_${domain}_dynamic_guard.v"
          )
          MorphVerilog(config) {
            new NarrowDomainDynamicWrite(depth, withWholeAssignment)
          }
          val verilog = readVerilog(directory, config)
          assert(verilog.contains("storage["), verilog)
          assert(verilog.contains("< (DEPTH)"), verilog)
        }
      }
    }
  }

  test("removed hierarchy Vec evidence cannot authorize leafwise replacement wiring") {
    withTemporaryDirectory { directory =>
      val rtl = directory.resolve("removed_hierarchy_vec_assignment.v")
      val failure = MorphVerilog.tryGenerate(
        morphConfig(directory, rtl.getFileName.toString)
      ) {
        new RemovedHierarchyVecAssignment(parameter("DEPTH", 3, 1, 8))
      } match {
        case Left(value) => value
        case Right(value) =>
          fail(s"expected stale hierarchy rejection, received $value")
      }
      assert(
        failure.detail.contains(
          "SPINAL-PARAMETERIZED-VERILOG-VEC-ASSIGNMENT-EVIDENCE-STALE"
        ),
        failure.detail
      )
      assert(!Files.exists(rtl), "stale hierarchy Vec evidence published partial RTL")
    }
  }

  test("a fully pruned unused Vec is harmlessly omitted by exact ownership") {
    withTemporaryDirectory { directory =>
      val config = morphConfig(directory, "harmless_pruned_vec.v")
      MorphVerilog(config) {
        new HarmlessPrunedVec(parameter("DEPTH", 3, 1, 8))
      }
      val verilog = readVerilog(directory, config)
      assert(!verilog.contains("unused_values"), verilog)
      assert(verilog.contains("passthrough_out"), verilog)
    }
  }

  test("a fully pruned Vec with an exact hierarchy binding fails closed") {
    withTemporaryDirectory { directory =>
      val rtl = directory.resolve("required_pruned_vec.v")
      val failure = MorphVerilog.tryGenerate(
        morphConfig(directory, rtl.getFileName.toString)
      ) {
        new RequiredPrunedVecHierarchy(parameter("DEPTH", 3, 1, 8))
      } match {
        case Left(value) => value
        case Right(value) =>
          fail(s"expected required pruned Vec rejection, received $value")
      }
      assert(
        failure.detail.contains(
          "SPINAL-PARAMETERIZED-VERILOG-VEC-PRUNED-REQUIRED"
        ),
        failure.detail
      )
      assert(!Files.exists(rtl), "required pruned Vec published partial RTL")
    }
  }

  test("structural slice rewrite leaves a coincident native slice unchanged") {
    withTemporaryDirectory { directory =>
      val config = morphConfig(directory, "coincident_structural_slice.v")
      val lanes = HdlInt.param(
        "LANES",
        default = BigInt(3),
        min = BigInt(1),
        max = BigInt(8)
      )
      MorphVerilog(config)(new CoincidentStructuralSlice(lanes))
      val verilog = readVerilog(directory, config)

      assert(
        "din\\s*\\[\\s*7\\s*:\\s*0\\s*\\]".r
          .findFirstIn(verilog)
          .nonEmpty,
        verilog
      )
      assert(
        "din\\s*\\[[^\\]]*slice_index[^\\]]*\\+:\\s*8\\s*\\]".r
          .findFirstIn(verilog)
          .nonEmpty,
        verilog
      )
    }
  }

  test("structural slice rejects a witness-valid range that escapes its complete domain") {
    withTemporaryDirectory { directory =>
      val rtl = directory.resolve("escaping_structural_slice.v")
      val lanes = HdlInt.param(
        "LANES",
        default = BigInt(3),
        min = BigInt(1),
        max = BigInt(8)
      )
      val failure = MorphVerilog.tryGenerate(
        morphConfig(directory, rtl.getFileName.toString)
      ) {
        new EscapingStructuralSlice(lanes)
      } match {
        case Left(value) => value
        case Right(value) =>
          fail(s"expected complete-domain structural-slice rejection, received $value")
      }
      assert(
        failure.detail.contains(
          "SPINAL-PARAMETERIZED-VERILOG-PROCESS-SLICE-DOMAIN-UNSUPPORTED"
        ),
        failure.detail
      )
      assert(!Files.exists(rtl), "escaping structural slice published partial RTL")
    }
  }

  test("removed structural slice assignment cannot be replaced by same text") {
    withTemporaryDirectory { directory =>
      val rtl = directory.resolve("removed_structural_slice_assignment.v")
      val lanes = HdlInt.param(
        "LANES",
        default = BigInt(3),
        min = BigInt(1),
        max = BigInt(8)
      )
      val failure = MorphVerilog.tryGenerate(
        morphConfig(directory, rtl.getFileName.toString)
      ) {
        new RemovedStructuralSliceAssignment(lanes)
      } match {
        case Left(value) => value
        case Right(value) =>
          fail(s"expected stale structural-slice rejection, received $value")
      }
      assert(
        failure.detail.contains(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SLICE-ANCHOR-MISMATCH"
        ),
        failure.detail
      )
      assert(!Files.exists(rtl), "stale structural slice published partial RTL")
    }
  }

  test("removed finite Mem port identity cannot be replaced by same-name text") {
    withTemporaryDirectory { directory =>
      val rtl = directory.resolve("removed_finite_mem_port.v")
      val failure = MorphVerilog.tryGenerate(
        morphConfig(directory, rtl.getFileName.toString)
      ) {
        new RemovedFiniteMemPort(parameter("DEPTH", 3, 1, 8))
      } match {
        case Left(value) => value
        case Right(value) =>
          fail(s"expected stale finite Mem port rejection, received $value")
      }
      assert(
        failure.detail.contains(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-FOREIGN-MEMORY-PORT-UNSUPPORTED"
        ),
        failure.detail
      )
      assert(!Files.exists(rtl), "stale finite Mem port published partial RTL")
    }
  }

  test("removed retained-value witness assignment cannot be replaced by same text") {
    withTemporaryDirectory { directory =>
      val rtl = directory.resolve("removed_retained_value_assignment.v")
      val failure = MorphVerilog.tryGenerate(
        morphConfig(directory, rtl.getFileName.toString)
      ) {
        new RemovedRetainedValueAssignment(parameter("VALUE", 3, 1, 8))
      } match {
        case Left(value) => value
        case Right(value) =>
          fail(s"expected stale retained-value rejection, received $value")
      }
      assert(
        Vector(
          "SPINAL-PARAMETERIZED-VERILOG-VALUE-ASSIGNMENT-EVIDENCE-STALE",
          "SPINAL-PARAMETERIZED-VERILOG-VALUE-ASSIGNMENT-LINEAGE-MISMATCH"
        ).exists(failure.detail.contains),
        failure.detail
      )
      assert(!Files.exists(rtl), "stale retained value published partial RTL")
    }
  }

  test("mutated retained-value literal source identity fails closed") {
    withTemporaryDirectory { directory =>
      val rtl = directory.resolve("mutated_retained_value_source.v")
      val failure = MorphVerilog.tryGenerate(
        morphConfig(directory, rtl.getFileName.toString)
      ) {
        new MutatedRetainedValueSource(parameter("VALUE", 3, 1, 8))
      } match {
        case Left(value) => value
        case Right(value) =>
          fail(s"expected retained-value source rejection, received $value")
      }
      assert(
        failure.detail.contains(
          "SPINAL-PARAMETERIZED-VERILOG-VALUE-ASSIGNMENT-LINEAGE-MISMATCH"
        ),
        failure.detail
      )
      assert(!Files.exists(rtl), "mutated retained value published partial RTL")
    }
  }

  private def parameter(
      name: String,
      default: Int,
      minimum: Int,
      maximum: Int
  ): ElabInt =
    HdlInt
      .param(
        name,
        default = BigInt(default),
        min = BigInt(minimum),
        max = BigInt(maximum)
      )
      .asElabInt

  private def assertConstantVecAssignment(
      verilog: String,
      target: String
  ): Unit =
    assert(
      ("assign\\s+" + target +
        "\\s*=\\s*values\\s*\\[\\s*\\(?\\s*0\\s*\\)?\\s*\\+:\\s*8\\s*\\]\\s*;").r
        .findFirstIn(verilog)
        .nonEmpty,
      verilog
    )

  private def generatedVecReadIndex(
      verilog: String,
      target: String,
      carrier: String
  ): String = {
    val identifier = "([A-Za-z_][A-Za-z0-9_$]*)"
    val assignment =
      ("assign\\s+" + java.util.regex.Pattern.quote(target) +
        "\\s*=\\s*" + java.util.regex.Pattern.quote(carrier) +
        "\\s*\\[\\s*\\(\\(\\s*" + identifier +
        "\\s*\\)\\s*\\*\\s*8\\s*\\)\\s*\\+:\\s*8\\s*\\]\\s*;").r
        .findFirstMatchIn(verilog)
        .getOrElse(fail(s"$target retained no generated Vec read:\n$verilog"))
    assertGeneratedFiniteIndex(verilog, assignment.group(1))
  }

  private def generatedVecWriteIndex(
      verilog: String,
      carrier: String,
      source: String
  ): String = {
    val identifier = "([A-Za-z_][A-Za-z0-9_$]*)"
    val assignment =
      ("assign\\s+" + java.util.regex.Pattern.quote(carrier) +
        "\\s*\\[\\s*\\(\\(\\s*" + identifier +
        "\\s*\\)\\s*\\*\\s*8\\s*\\)\\s*\\+:\\s*8\\s*\\]\\s*=\\s*" +
        java.util.regex.Pattern.quote(source) + "\\s*;").r
        .findFirstMatchIn(verilog)
        .getOrElse(fail(s"$carrier retained no generated Vec write:\n$verilog"))
    assertGeneratedFiniteIndex(verilog, assignment.group(1))
  }

  private def assertGeneratedFiniteIndex(
      verilog: String,
      index: String
  ): String = {
    val quotedIndex = java.util.regex.Pattern.quote(index)
    assert(
      ("genvar\\s+" + quotedIndex + "\\s*;").r
        .findFirstIn(verilog)
        .nonEmpty,
      s"$index is not a declared structural genvar:\n$verilog"
    )
    assert(
      ("for\\s*\\(\\s*" + quotedIndex + "\\s*=\\s*0\\s*;\\s*" +
        quotedIndex + "\\s*<\\s*DEPTH\\s*;\\s*" + quotedIndex +
        "\\s*=\\s*" + quotedIndex + "\\s*\\+\\s*1\\s*\\)").r
        .findFirstIn(verilog)
        .nonEmpty,
      s"$index is not the retained finite-range index:\n$verilog"
    )
    index
  }

  private def readVerilog(directory: Path, config: SpinalConfig): String =
    new String(
      Files.readAllBytes(directory.resolve(config.netlistFileName)),
      StandardCharsets.UTF_8
    )

  private def expectPortDirectionsFailure(
      directory: Path,
      filename: String
  )(component: => Component): Unit = {
    val rtl = directory.resolve(filename)
    val failure = MorphVerilog.tryGenerate(morphConfig(directory, filename)) {
      component
    } match {
      case Left(value) => value
      case Right(value) =>
        fail(s"expected port-direction rejection, received $value")
    }
    assert(
      failure.detail.contains(
        "SPINAL-PARAMETERIZED-VERILOG-PORT-DIRECTIONS-UNSUPPORTED"
      ),
      failure.detail
    )
    assert(!Files.exists(rtl), s"$filename published partial RTL")
  }

  private def morphConfig(directory: Path, filename: String): SpinalConfig = {
    Files.createDirectories(directory)
    val config = SpinalConfig(targetDirectory = directory.toString)
    config.netlistFileName = filename
    config
  }

  private def withTemporaryDirectory(body: Path => Unit): Unit = {
    val directory = Files.createTempDirectory("morphhdl-structural-identity-")
    try body(directory)
    finally {
      if (Files.exists(directory)) {
        val paths = Files.walk(directory)
        try
          paths
            .iterator()
            .asScala
            .toVector
            .sortBy(_.getNameCount)
            .reverse
            .foreach(Files.deleteIfExists)
        finally paths.close()
      }
    }
  }
}
