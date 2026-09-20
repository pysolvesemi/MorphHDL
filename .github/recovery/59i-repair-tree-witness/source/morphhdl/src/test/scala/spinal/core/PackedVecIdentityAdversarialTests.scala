package spinal.core

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.collection.JavaConverters._

import org.scalatest.funsuite.AnyFunSuite

import morphhdl.MorphVerilog
import morphhdl.frontend.HdlInt
import spinal.core.internals.{BitsBitAccessFixed, Expression, Operator, Resize}
import spinal.lib._

/** Adversarial packed-Vec fixtures kept in `spinal.core` so they can corrupt
  * one exact retained native expression without exposing the identity
  * registry to public MorphHDL users. Publication must validate the live AST
  * geometry and fail closed instead of reconstructing the operation that the
  * authoritative native Vec algorithm originally produced.
  */
private object PackedVecIdentityAdversarialFixture {
  final case class IndependentPackedRecord(aWidth: ElabInt, bWidth: ElabInt,
      cWidth: ElabInt, dWidth: ElabInt) extends Bundle {
    val a = UInt(aWidth bits)
    val b = SInt(bWidth bits)
    val c = Bits(cWidth bits)
    val d = UInt(dWidth bits)
  }

  final class IndependentPackedRead(defaultCount: Int, maximumCount: Int,
      mutation: Int = 0, scalarConsumer: Boolean = false) extends Component {
    setDefinitionName("IndependentPackedRead")
    val aWidth = HdlInt.param("A_W", 5, 1, 32).asElabInt
    val bWidth = HdlInt.param("B_W", 5, 1, 32).asElabInt
    val cWidth = HdlInt.param("C_W", 5, 1, 32).asElabInt
    val dWidth = HdlInt.param("D_W", 5, 1, 32).asElabInt
    val count = HdlInt.param("COUNT", defaultCount, 1, maximumCount).asElabInt
    val values = in(Vec(IndependentPackedRecord(aWidth, bWidth, cWidth, dWidth), count)).setName("values")
    val packed = values.asBits.asOutput().setName("copied")
    if (mutation != 0) {
      val operation = ParameterizedVec.operationsOf(values)
        .collect { case value: ParameterizedVecPackedRead => value }.last
      val packing = operation.carrierAssignments.find(_.finalTarget eq operation.carrier).get
      val cat = packing.source.asInstanceOf[Operator.Bits.Cat]
      if (mutation == 1) {
        val left = cat.left
        cat.left = cat.right
        cat.right = left
      } else {
        // The lower prefix is a real private Cat support node. Turning it
        // into state must still invalidate the final exact carrier proof.
        val prefix = cat.right.asInstanceOf[BaseType]
        require(prefix.isComb && !prefix.isIo)
        prefix.setAsReg()
      }
    }
    if (scalarConsumer) {
      val scalar = out(UInt()).setName("scalar")
      scalar := packed.asUInt
    }
  }

  final class IndependentScalarConcat extends Component {
    val a = in(Bits(HdlInt.param("A_W", 5, 1, 32) bits))
    val b = in(Bits(HdlInt.param("B_W", 5, 1, 32) bits))
    val c = in(Bits(HdlInt.param("C_W", 5, 1, 32) bits))
    val d = in(Bits(HdlInt.param("D_W", 5, 1, 32) bits))
    val result = out(Bits())
    result := d ## c ## b ## a
  }

  sealed trait ProjectedCoverageMutation
  case object KeepProjectedCoverage extends ProjectedCoverageMutation
  case object KeepActiveStorageCoverage extends ProjectedCoverageMutation
  case object ReplaceProjectedSchema extends ProjectedCoverageMutation
  case object NarrowProjectedAdmission extends ProjectedCoverageMutation

  final class MutatedPackedReadWrapper(depth: ElabInt) extends Component {
    setDefinitionName("MutatedPackedReadWrapperMustFailClosed")
    val vecIn = in(Vec(Bool(), depth)).setName("vec_in")
    val unrelated = in(Bits(8 bits)).setName("unrelated")
    val bitsOut = out(Bits(depth bits)).setName("bits_out")

    val packedValue = vecIn.asBits.setName("packed_value").dontSimplifyIt()
    val operation = ParameterizedVec
      .operationsOf(vecIn)
      .collect { case value: ParameterizedVecPackedRead => value }
      .last
    val wrapper = operation.resultAssignments.find(statement => statement.finalTarget eq operation.result).getOrElse {
      throw new IllegalStateException(
        "packed-read adversarial fixture retained no logical witness wrapper"
      )
    }
    wrapper.source match {
      case resize: Resize => resize.input = unrelated
      case other =>
        throw new IllegalStateException(
          s"packed-read adversarial fixture expected Resize, found ${other.getClass.getName}"
        )
    }

    bitsOut := packedValue
  }

  final class MutatedPackedAssignmentSlice(depth: ElabInt) extends Component {
    setDefinitionName("MutatedPackedAssignmentSliceMustFailClosed")
    val bitsIn = in(Bits(depth bits)).setName("bits_in")
    val bitsOut = out(Bits(depth bits)).setName("bits_out")

    val values = Vec(Bool(), depth).setName("values").dontSimplifyIt()
    values.assignFromBits(bitsIn)
    val operation = ParameterizedVec
      .operationsOf(values)
      .collect { case value: ParameterizedVecPackedAssignment => value }
      .last
    val firstLeaf = values.vec.head.flatten.head
    val firstAssignment = operation.assignments.find(statement => statement.finalTarget eq firstLeaf).getOrElse {
      throw new IllegalStateException(
        "packed-assignment adversarial fixture retained no first-leaf assignment"
      )
    }

    // Element zero belongs at packed bit zero. Keep the same exact live
    // statement and source carrier, but corrupt its native slice to bit one.
    // A mere `contains carrier` proof would incorrectly accept this graph.
    val wrongSlice = new BitsBitAccessFixed
    wrongSlice.source = operation.carrier
    wrongSlice.bitId = 1
    firstAssignment.source = wrongSlice

    val packedValue = values.asBits.setName("packed_value").dontSimplifyIt()
    bitsOut := packedValue
  }

  final class MutatedPackedReadOrder(depth: ElabInt) extends Component {
    setDefinitionName("MutatedPackedReadOrderMustFailClosed")
    val vecIn = in(Vec(Bool(), depth)).setName("vec_in")
    val bitsOut = out(Bits(depth bits)).setName("bits_out")

    val packedValue = vecIn.asBits.setName("packed_value").dontSimplifyIt()
    val operation = ParameterizedVec
      .operationsOf(vecIn)
      .collect { case value: ParameterizedVecPackedRead => value }
      .last
    val packing = operation.carrierAssignments.find(statement => statement.finalTarget eq operation.carrier).getOrElse {
      throw new IllegalStateException(
        "packed-read ordering fixture retained no carrier assignment"
      )
    }
    packing.source match {
      case cat: Operator.Bits.Cat =>
        val originalLeft = cat.left
        cat.left = cat.right
        cat.right = originalLeft
      case other =>
        throw new IllegalStateException(
          s"packed-read ordering fixture expected Cat, found ${other.getClass.getName}"
        )
    }

    bitsOut := packedValue
  }

  final class MutatedPackedReadSupportRegister(depth: ElabInt) extends Component {
    setDefinitionName("MutatedPackedReadSupportRegisterMustFailClosed")
    val vecIn = in(Vec(Bool(), depth)).setName("vec_in")
    val bitsOut = out(Bits(depth bits)).setName("bits_out")
    val packedValue = vecIn.asBits.setName("packed_value").dontSimplifyIt()
    val operation = ParameterizedVec.operationsOf(vecIn)
      .collect { case value: ParameterizedVecPackedRead => value }.last
    val carrier = operation.carrierAssignments.find(_.finalTarget eq operation.carrier).get
    val expected = vecIn.vec.toVector.flatMap(_.flatten)
    def support(value: Expression): Option[BaseType] = value match {
      case base: BaseType if expected.exists(_ eq base) => None
      case base: BaseType if (base ne operation.carrier) && (base ne operation.result) => Some(base)
      case other =>
        var result = Option.empty[BaseType]
        other.foreachExpression(child => if (result.isEmpty) result = support(child))
        result
    }
    val intermediate = support(carrier.source).getOrElse {
      throw new IllegalStateException("packed-read fixture retained no native intermediate")
    }
    require(intermediate.isComb && !intermediate.isIo)
    // Retain the same declaration, assignment, source and Vec leaf identities,
    // but introduce one real cycle. Packed publication must preserve the
    // distinction and reject instead of deleting the stateful intermediary.
    intermediate.setAsReg()
    bitsOut := packedValue
  }

  /** Exercise the default-inactive StreamFifo storage owner which motivated
    * branch-local Vec write coverage.  The returned `checks` Vec retains one
    * exact whole-Vec assignment from the child-owned structural Vec, so its
    * public packed read reaches the projected-loop correlation proof.
    */
  final class ProjectedFiniteVecWriteCoverage(
      depth: ElabInt,
      mutation: ProjectedCoverageMutation
  ) extends Component {
    setDefinitionName("ProjectedFiniteVecWriteCoverage")

    val push = slave(Stream(Bits(8 bits))).setName("push")
    val pop = master(Stream(Bits(8 bits))).setName("pop")
    val flush = in(Bool()).setName("flush")
    val checksOut = out(Bits(depth bits)).setName("checks_out")

    val fifo = StreamFifo(
      HardType(Bits(8 bits)),
      depth,
      withAsyncRead = false,
      withBypass = false,
      allowExtraMsb = true,
      forFMax = false,
      useVec = false,
      initPayload = None
    )
    fifo.io.push << push
    pop << fifo.io.pop
    fifo.io.flush := flush

    val checks = fifo.formalCheckRam(_.orR)
    checksOut := checks.asBits

    mutateProjectedWriteLoop(fifo, checks, mutation)
  }

  private final case class StructuralLoopSlot(
      block: ParameterizedStructuralBlock,
      loop: ParameterizedStructure.StructuralFor
  )

  /** Locate the tested loop solely through retained object relationships:
    * the caller Vec's exact whole-assignment source, that source Vec's exact
    * structural selector and the selector alias's captured assignment.  No
    * emitted name, label or source position participates in discovery.
    */
  private def mutateProjectedWriteLoop(
      fifo: Component,
      published: Vec[Bool],
      mutation: ProjectedCoverageMutation
  ): Unit = {
    def soleWholeSource(vector: Vec[_], role: String): Vec[_] = {
      val sources = ParameterizedVec
        .operationsOf(vector)
        .collect { case value: ParameterizedVecWholeAssignment => value.source }
      require(
        sources.size == 1,
        s"projected coverage fixture retained ${sources.size} $role whole-Vec sources"
      )
      sources.head
    }

    // The published parent Vec is driven from the pulled child-output Vec;
    // that pulled carrier is in turn driven from the child's exact internal
    // structural Vec. Follow both retained assignment identities.
    val pulled = soleWholeSource(published, "published")
    val source = soleWholeSource(pulled, "pulled-output")

    def slotsOf(
        regions: Vector[ParameterizedStructure.StructuralRegion]
    ): Vector[StructuralLoopSlot] =
      regions.flatMap { region =>
        region.blocks.flatMap { block =>
          block.regions.collect {
            case loop: ParameterizedStructure.StructuralFor =>
              StructuralLoopSlot(block, loop)
          } ++ slotsOf(block.regions)
        }
      }

    val candidates = slotsOf(ParameterizedStructure.regionsOf(fifo)).flatMap {
      slot =>
        val selections = slot.loop.body.vecIndices.filter { selection =>
          (selection.vector eq source) &&
          slot.loop.finiteIndexToken.exists { loopToken =>
            selection.finiteIndexToken.exists(_ eq loopToken)
          }
        }
        val assignments = selections.flatMap { selection =>
          val resultLeaves = selection.result.flatten.toVector
          slot.loop.body.assignments.filter(assignment =>
            resultLeaves.exists(leaf =>
              (assignment.target eq leaf) &&
                (assignment.finalTarget eq leaf)
            )
          )
        }
        (selections, assignments) match {
          case (Vector(selection), Vector(assignment)) =>
            ParameterizedStructure
              .capturedAssignmentDomainOf(fifo, assignment)
              .map(domain => (slot, selection, assignment, domain))
              .toVector
          case _ => Vector.empty
        }
    }
    require(
      candidates.size == 1,
      s"projected coverage fixture retained ${candidates.size} exact structural write loops"
    )
    val (slot, _, _, assignmentDomain) = candidates.head
    val original = slot.loop.count
    val exact = original.exactDomain.getOrElse {
      throw new IllegalStateException(
        "projected coverage loop lost its exact domain"
      )
    }
    val projection = original.projectionProvenance.getOrElse {
      throw new IllegalStateException(
        "projected coverage loop lost its projection provenance"
      )
    }
    require(
      projection.admitted == assignmentDomain.values,
      "projected coverage fixture began with mismatched loop and assignment domains"
    )
    mutation match {
      case KeepActiveStorageCoverage =>
        require(
          assignmentDomain.values(exact.parameter.default) &&
            assignmentDomain.values.size > 1,
          "active-storage coverage fixture must exercise the default and a non-singleton storage branch"
        )
      case _ =>
        require(
          !assignmentDomain.values(exact.parameter.default) &&
            assignmentDomain.values.size > 1,
          "projected coverage fixture must exercise an inactive default and a non-singleton storage branch"
        )
    }

    val replacement = mutation match {
      case KeepProjectedCoverage | KeepActiveStorageCoverage => original

      case ReplaceProjectedSchema =>
        val replacementSchema = exact.parameter.copy()
        require(
          replacementSchema == exact.parameter &&
            (replacementSchema ne exact.parameter),
          "replacement schema must be value-equal but identity-distinct"
        )
        original
          .copy(parameters = Vector(replacementSchema))
          .attachProjection(
            exact,
            projection.admitted,
            projection.representative,
            "adversarial projected Vec schema replacement",
            original.sourceLocation
          )

      case NarrowProjectedAdmission =>
        val narrowed = projection.admitted - projection.admitted.min
        require(
          narrowed.nonEmpty && narrowed != assignmentDomain.values,
          "narrowed projection must differ from the captured assignment domain"
        )
        val representative =
          if (narrowed(exact.parameter.default)) exact.parameter.default
          else narrowed.min
        val results = narrowed.toVector.sorted.map { rootValue =>
          exact.evaluate(rootValue).getOrElse {
            throw new IllegalStateException(
              s"projected coverage domain has no result at root value $rootValue"
            )
          }
        }
        original
          .copy(
            default = exact.evaluate(representative).get,
            minimum = results.min,
            maximum = results.max
          )
          .attachProjection(
            exact,
            narrowed,
            representative,
            "adversarial projected Vec admitted-set narrowing",
            original.sourceLocation
          )
    }

    if (replacement ne original) {
      val position = slot.block.regions.indexWhere(_ eq slot.loop)
      require(
        position >= 0,
        "projected coverage loop lost its exact mutable owner"
      )
      slot.block.regions = slot.block.regions.updated(
        position,
        slot.loop.copy(count = replacement)
      )
    }
  }
}

class PackedVecIdentityAdversarialTests extends AnyFunSuite {
  import PackedVecIdentityAdversarialFixture._

  test("composite packed reads keep factorized geometry beyond the scalar Cartesian limit") {
    // Four independent 32-value roots have 1,048,576 combinations. Both a
    // logical-witness wrapper and a full-size native carrier must publish.
    for (defaultCount <- Vector(1, 3)) withTemporaryDirectory { directory =>
      val fileName = "independent_packed_read.v"
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = fileName
      MorphVerilog(config)(new IndependentPackedRead(defaultCount, 3))
      val text = new String(Files.readAllBytes(directory.resolve(fileName)), StandardCharsets.UTF_8)
      for (name <- Vector("values", "copied")) {
        val port = text.linesIterator.find(line =>
          (line.contains("input") || line.contains("output")) &&
            ("\\b" + name + "\\b").r.findFirstIn(line).nonEmpty).getOrElse(fail(text))
        for (root <- Vector("A_W", "B_W", "C_W", "D_W", "COUNT"))
          assert(port.contains(root), port)
      }
      val copies = "(?m)^\\s*assign\\s+([A-Za-z_][A-Za-z0-9_$]*)\\s*=\\s*([A-Za-z_][A-Za-z0-9_$]*)\\s*;".r
        .findAllMatchIn(text).map(value => value.group(1) -> value.group(2)).toMap
      var source = "copied"
      val visited = scala.collection.mutable.Set.empty[String]
      while (copies.contains(source) && !visited(source)) {
        visited += source
        source = copies(source)
      }
      assert(source == "values", text)
    }
  }

  test("ordinary scalar concatenation still enforces the exhaustive width domain limit") {
    expectFailure("independent_scalar_concat.v", "SPINAL-ELAB-WIDTH-DOMAIN-TOO-LARGE",
      new IndependentScalarConcat)
  }

  test("a factorized packed read cannot confer witness width authority on a scalar cast") {
    // A full carrier is checked by native scalar width retention; a smaller
    // witness wrapper is checked by publication of its factorized source.
    // Neither path may turn the packed width into a scalar witness constant.
    for ((defaultCount, code) <- Vector(
        2 -> "SPINAL-ELAB-WIDTH-DOMAIN-TOO-LARGE",
        1 -> "SPINAL-ELAB-DOMAIN-PROJECTION-ROOT-IDENTITY-MISMATCH"))
      expectFailure(s"independent_packed_scalar_cast_$defaultCount.v", code,
        new IndependentPackedRead(defaultCount, 2, scalarConsumer = true))
  }

  test("private composite packing still rejects reordered leaves and stateful support") {
    for (mutation <- Vector(1, 2))
      expectFailure(s"independent_packed_mutation_$mutation.v",
        Set("SPINAL-PARAMETERIZED-VERILOG-VEC-PACKED-READ-LAYOUT-MISMATCH",
          "SPINAL-ELAB-DOMAIN-PROJECTION-ROOT-IDENTITY-MISMATCH"),
        new IndependentPackedRead(1, 3, mutation = mutation))
  }

  test("packed Vec read rejects a live Resize whose input identity changed") {
    expectFailure(
      "mutated_packed_read_wrapper.v",
      "SPINAL-PARAMETERIZED-VERILOG-VEC-PACKED-READ-EVIDENCE-MISMATCH",
      new MutatedPackedReadWrapper(parameter("DEPTH", 3, 1, 8))
    )
  }

  test("packed Vec assignment rejects a live leaf with the wrong slice") {
    expectFailure(
      "mutated_packed_assignment_slice.v",
      "SPINAL-PARAMETERIZED-VERILOG-VEC-PACKED-ASSIGNMENT-SLICE-MISMATCH",
      new MutatedPackedAssignmentSlice(parameter("DEPTH", 3, 1, 8))
    )
  }

  test("packed Vec read rejects a live Cat with reordered leaves") {
    expectFailure(
      "mutated_packed_read_order.v",
      "SPINAL-PARAMETERIZED-VERILOG-VEC-PACKED-READ-LAYOUT-MISMATCH",
      new MutatedPackedReadOrder(parameter("DEPTH", 3, 1, 8))
    )
  }

  test("packed Vec read rejects a retained support wire changed into a register") {
    expectFailure(
      "mutated_packed_read_support_register.v",
      "SPINAL-PARAMETERIZED-VERILOG-VEC-PACKED-READ-LAYOUT-MISMATCH",
      new MutatedPackedReadSupportRegister(parameter("DEPTH", 3, 1, 8))
    )
  }

  test("projected finite Vec write covers an inactive default storage branch") {
    withTemporaryDirectory { directory =>
      val fileName = "projected_finite_vec_write.v"
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = fileName
      MorphVerilog(config) {
        new ProjectedFiniteVecWriteCoverage(
          parameter("DEPTH", 1, 1, 8),
          KeepProjectedCoverage
        )
      }
      val rtl = directory.resolve(fileName)
      assert(Files.exists(rtl))
      assertProjectedAggregateDominance(rtl)
    }
  }

  test("projected finite Vec write counts an inactive one-stage identity once") {
    withTemporaryDirectory { directory =>
      val fileName = "active_storage_finite_vec_write.v"
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = fileName
      MorphVerilog(config) {
        new ProjectedFiniteVecWriteCoverage(
          parameter("DEPTH", 3, 1, 8),
          KeepActiveStorageCoverage
        )
      }
      val rtl = directory.resolve(fileName)
      assert(Files.exists(rtl))
      assertProjectedAggregateDominance(rtl)
    }
  }

  test("projected finite Vec write rejects a value-equal replacement schema") {
    expectFailure(
      "projected_finite_vec_replacement_schema.v",
      "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-CONTINUOUS-ASSIGNMENT-DOMINANCE-UNPROVEN",
      new ProjectedFiniteVecWriteCoverage(
        parameter("DEPTH", 1, 1, 8),
        ReplaceProjectedSchema
      )
    )
  }

  test("projected finite Vec write rejects a different admitted set") {
    expectFailure(
      "projected_finite_vec_admitted_set.v",
      "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-CONTINUOUS-ASSIGNMENT-DOMINANCE-UNPROVEN",
      new ProjectedFiniteVecWriteCoverage(
        parameter("DEPTH", 1, 1, 8),
        NarrowProjectedAdmission
      )
    )
  }

  private val FormalRamCheckAggregate =
    """(?<![A-Za-z0-9_$])(formal_ram_check(?:_[0-9]+_morphhdl_vec)?)(?![A-Za-z0-9_$])""".r

  private def assertProjectedAggregateDominance(rtl: Path): Unit = {
    val verilog = new String(Files.readAllBytes(rtl), StandardCharsets.UTF_8)
    val streamFifo =
      "(?ms)^\\s*module\\s+StreamFifo\\b.*?^\\s*endmodule\\b".r
        .findFirstIn(verilog)
        .getOrElse(fail(s"projected Vec fixture emitted no StreamFifo module:\n$verilog"))
    val generateStart = "(?m)^\\s*generate\\s*$".r
      .findFirstMatchIn(streamFifo)
      .map(_.start)
      .getOrElse(fail(s"projected Vec fixture emitted no generate region:\n$streamFifo"))
    val names = FormalRamCheckAggregate
      .findAllMatchIn(streamFifo)
      .map(_.group(1))
      .toSet
    assert(names.nonEmpty, s"projected Vec fixture retained no RAM-check aggregate:\n$streamFifo")

    names.foreach { name =>
      val quoted = java.util.regex.Pattern.quote(name)
      val declarations =
        ("(?m)^\\s*wire\\s+\\[[^\\]]*DEPTH[^\\]]*\\]\\s+" +
          quoted + "\\s*;\\s*$").r.findAllMatchIn(streamFifo).toVector
      val allDeclarations =
        ("(?m)^\\s*(?:wire|reg)\\b[^;]*\\b" + quoted +
          "\\s*;\\s*$").r.findAllMatchIn(streamFifo).toVector
      assert(
        declarations.size == 1 &&
          allDeclarations.size == 1 &&
          declarations.head.start == allDeclarations.head.start &&
          declarations.head.start < generateStart,
        s"projected Vec aggregate '$name' is not declared exactly once at module scope:\n$streamFifo"
      )
      val slices =
        ("(?m)^\\s*assign\\s+" + quoted +
          "\\s*\\[([^\\]]+)\\]\\s*=").r
          .findAllMatchIn(streamFifo)
          .map(_.group(1).replaceAll("\\s+", ""))
          .toVector
      assert(
        slices.size == 2 &&
          slices.count(_ == "(0)+:1") == 1 &&
          slices.count(value =>
            value.contains("stream_fifo_formal_ram_mask_index") &&
              value.endsWith("+:1")
          ) == 1,
        s"projected Vec aggregate '$name' lost one exact branch slice driver: ${slices.mkString(", ")}\n$streamFifo"
      )
    }
  }

  private def expectFailure(
      fileName: String,
      code: String,
      component: => Component
  ): Unit = expectFailure(fileName, Set(code), component)

  private def expectFailure(
      fileName: String,
      codes: Set[String],
      component: => Component
  ): Unit =
    withTemporaryDirectory { directory =>
      val rtl = directory.resolve(fileName)
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = fileName
      val failure = MorphVerilog.tryGenerate(config)(component) match {
        case Left(value)  => value
        case Right(value) => fail(s"expected ${codes.mkString(" or ")} rejection, received $value")
      }
      assert(codes.exists(failure.detail.contains), failure.detail)
      assert(!Files.exists(rtl), s"${codes.mkString(" or ")} published partial RTL")
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

  private def withTemporaryDirectory(body: Path => Unit): Unit = {
    val directory = Files.createTempDirectory("morphhdl-packed-vec-identity-")
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
