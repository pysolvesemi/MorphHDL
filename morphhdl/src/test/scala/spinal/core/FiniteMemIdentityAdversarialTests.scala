package spinal.core

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer

import org.scalatest.funsuite.AnyFunSuite

import morphhdl.MorphVerilog
import morphhdl.frontend.HdlInt
import spinal.core.internals.{BitVectorLiteral, DataAssignmentStatement}

private object FiniteMemIdentityAdversarialFixture {
  private def retainedAddress(
      port: MemReadAsync
  ): (UInt, DataAssignmentStatement, BitVectorLiteral) = {
    val address = port.address match {
      case value: UInt => value
      case other =>
        throw new IllegalStateException(
          s"finite Mem fixture retained unexpected address ${other.getClass.getName}"
        )
    }
    val assignments = ArrayBuffer.empty[DataAssignmentStatement]
    address.foreachStatements {
      case value: DataAssignmentStatement if (value.finalTarget eq address) && (value.target eq address) =>
        assignments += value
      case _ =>
    }
    val matching = assignments.collect {
      case value if value.source.isInstanceOf[BitVectorLiteral] =>
        value -> value.source.asInstanceOf[BitVectorLiteral]
    }
    require(
      assignments.size == 1 && matching.size == 1 &&
        !matching.head._2.hasPoison() && matching.head._2.getValue() == 0,
      "finite Mem fixture did not retain one exact zero-address assignment"
    )
    (address, matching.head._1, matching.head._2)
  }

  final class ReplacedAddressAssignment(depth: ElabInt) extends Component {
    setDefinitionName("FiniteMemReplacedAddressAssignment")
    val keepIn = in(Bool()).setName("keep_in")
    val keepOut = out(Bool()).setName("keep_out")
    keepOut := keepIn
    val memory = Mem(Bits(8 bits), depth).setName("finite_mem")

    ElabFiniteRange.foreach(depth, "replaced finite Mem address") { index =>
      val selected = index(memory).setName("selected_word").dontSimplifyIt()
      val port = memory.dlcLast.asInstanceOf[MemReadAsync]
      val (address, retained, _) = retainedAddress(port)
      retained.removeStatement()

      // Recreate the same target and literal witness with another statement.
      // Emitted spelling is not evidence for the stale retained identity.
      address.compositeAssign = null
      address.allowOverride()
      address.assignFrom(
        spinal.core.internals.UIntLiteral(BigInt(0), null, address.getWidth)
      )
      selected.setAsVital()
    }
  }

  final class NormalizedAddressAssignment(depth: ElabInt) extends Component {
    setDefinitionName("FiniteMemNormalizedAddressAssignment")
    val keepIn = in(Bool()).setName("keep_in")
    val keepOut = out(Bool()).setName("keep_out")
    keepOut := keepIn
    val memory = Mem(Bits(8 bits), depth).setName("finite_mem")

    ElabFiniteRange.foreach(depth, "normalized finite Mem address") { index =>
      val selected = index(memory).setName("selected_word").dontSimplifyIt()
      val port = memory.dlcLast.asInstanceOf[MemReadAsync]
      val (address, retained, witness) = retainedAddress(port)

      // Model the exact native normalization admitted by the backend: the
      // wrapper and its retained assignment disappear together, while the
      // port now references the same literal object retained at capture time.
      retained.removeStatement()
      address.removeStatement()
      port.address = witness
      selected.setAsVital()
    }
  }

  final class MixedStructuralAndNativePorts(
      depth: ElabInt,
      includeUncapturedAsyncRead: Boolean
  ) extends Component {
    setDefinitionName("FiniteMemMixedStructuralAndNativePorts")
    val readEnable = in(Bool()).setName("read_enable")
    val writeEnable = in(Bool()).setName("write_enable")
    val address = in(UInt(depth.addressWidth bits)).setName("address")
    val writeData = in(Bits(8 bits)).setName("write_data")
    val readData = out(Bits(8 bits)).setName("read_data")
    val checks = out(Vec(Bool(), depth)).setName("checks")
    val memory = Mem(Bits(8 bits), depth).setName("mixed_mem")

    readData := memory.readSync(
      address,
      enable = readEnable,
      readUnderWrite = readFirst
    )
    memory.write(address, writeData, enable = writeEnable)
    ElabFiniteRange.foreach(depth, "mixed finite Mem read") { index =>
      index(checks) := index(memory).orR
    }

    if (includeUncapturedAsyncRead) {
      val outsideRead = out(Bits(8 bits)).setName("outside_read")
      outsideRead := memory.readAsync(U(0, memory.nativePortAddressWidth bits))
    }
  }

  final class CopiedAddressAlgebra(depth: ElabInt) extends Component {
    setDefinitionName("FiniteMemCopiedAddressAlgebra")
    val depthDomain = depth.expression.exactDomain.getOrElse(
      throw new IllegalArgumentException(
        "copied address-algebra fixture requires exact depth evidence"
      )
    )
    val depthSchema = depth.expression.parameters match {
      case Vector(value) => value
      case _ =>
        throw new IllegalArgumentException(
          "copied address-algebra fixture requires one depth schema"
        )
    }
    val copiedDomain = ElaborationExactDomain.checked[BigInt](
      depthDomain.root,
      depthSchema,
      depthDomain.evaluations.map { case (rootValue, _) =>
        rootValue -> BigInt(2)
      },
      sourceLocation = None,
      role = "copied memory address algebra"
    )
    require(copiedDomain.root eq depthDomain.root)
    require(copiedDomain.hasCompleteCoverage)

    val copiedWidth = ElabInt.fromTrustedExactExpressionForTest(
      ElaborationIntegerExpression(
        verilog = s"clog2(${depth.expression.verilog}, 1)",
        default = BigInt(2),
        minimum = BigInt(2),
        maximum = BigInt(2),
        parameters = Vector(depthSchema),
        parameterRoots = Vector(depthDomain.root),
        exactDomain = Some(copiedDomain)
      )
    )
    val readEnable = in(Bool()).setName("read_enable")
    val writeEnable = in(Bool()).setName("write_enable")
    val address = in(UInt(copiedWidth bits)).setName("address")
    val writeData = in(Bits(8 bits)).setName("write_data")
    val readData = out(Bits(8 bits)).setName("read_data")
    val memory = Mem(Bits(8 bits), depth).setName("copied_algebra_mem")

    readData := memory.readSync(
      address,
      enable = readEnable,
      readUnderWrite = readFirst
    )
    memory.write(address, writeData, enable = writeEnable)
  }
}

final class FiniteMemIdentityAdversarialTests extends AnyFunSuite {
  import FiniteMemIdentityAdversarialFixture._

  test("external memory registry rejects forged inexact public depth metadata") {
    var attachFailure: ParameterizedVerilogException = null
    var createFailure: ParameterizedVerilogException = null
    withSpinalElaboration {
      val parameter = ElaborationIntegerParameter(
        "FORGED_DEPTH",
        default = 3,
        minimum = 1,
        maximum = 8
      )
      val expression = ElaborationIntegerExpression(
        verilog = "FORGED_DEPTH",
        default = 3,
        minimum = 1,
        maximum = 8,
        parameters = Vector(parameter),
        parameterRoots = Vector(
          ElaborationIntegerParameterRoot.fresh("FORGED_DEPTH")
        )
      )
      val forged = ParameterizedMemoryDepth(3, expression)

      attachFailure = intercept[ParameterizedVerilogException] {
        ExternalParameterizedMemoryRegistry.attach(
          Mem(Bits(8 bits), 3),
          forged
        )
      }
      createFailure = intercept[ParameterizedVerilogException] {
        ExternalParameterizedMemoryRegistry.create(
          HardType(Bits(8 bits)),
          forged
        )
      }
    }

    Vector(attachFailure, createFailure).foreach { failure =>
      assert(failure != null)
      assert(
        failure.code ==
          "SPINAL-PARAMETERIZED-VERILOG-MEMORY-DEPTH-EXACT-DOMAIN-MISSING"
      )
    }
  }

  test("external memory registry accepts only canonical public literal depth metadata") {
    var attachedMemory: Mem[Bits] = null
    var attachFailure: ParameterizedVerilogException = null
    var createFailure: ParameterizedVerilogException = null
    var generateFailure: ParameterizedVerilogException = null
    withSpinalElaboration {
      val noncanonical = ParameterizedMemoryDepth(
        4,
        ElaborationIntegerExpression(
          verilog = "UNDECLARED_MEMORY_DEPTH",
          default = 4,
          minimum = 4,
          maximum = 4,
          parameters = Vector.empty
        )
      )
      attachedMemory = Mem(Bits(8 bits), 4)
      attachFailure = intercept[ParameterizedVerilogException] {
        ExternalParameterizedMemoryRegistry.attach(
          attachedMemory,
          noncanonical
        )
      }
      createFailure = intercept[ParameterizedVerilogException] {
        ExternalParameterizedMemoryRegistry.create(
          HardType(Bits(8 bits)),
          noncanonical
        )
      }

      generateFailure = intercept[ParameterizedVerilogException] {
        ExternalParameterizedMemoryRegistry.create(
          HardType(Bits(8 bits)),
          ParameterizedMemoryDepth(
            4,
            ElaborationIntegerExpression(
              verilog = "4",
              default = 4,
              minimum = 4,
              maximum = 4,
              parameters = Vector.empty,
              generateIndex = Some("forged_generate_index")
            )
          )
        )
      }
    }

    Vector(attachFailure, createFailure, generateFailure).foreach { failure =>
      assert(failure != null)
      assert(
        failure.code ==
          "SPINAL-PARAMETERIZED-VERILOG-MEMORY-DEPTH-DOMAIN-INVALID"
      )
    }
    assert(
      ExternalParameterizedMemoryRegistry.metadataOf(attachedMemory).isEmpty
    )
  }

  test("external memory registry rejects foreign depth roots and inexact element geometry") {
    var depthFailure: ParameterizedVerilogException = null
    var elementFailure: ParameterizedVerilogException = null
    withSpinalElaboration {
      val exactDepth = parameter("DEPTH", default = 3, minimum = 1, maximum = 8)
      val foreignDepth = ElabInt.authenticateExactExpressionForTest(
        exactDepth.expression.copy(
          parameterRoots = Vector(
            ElaborationIntegerParameterRoot.fresh("DEPTH")
          )
        )
      )
      depthFailure = intercept[ParameterizedVerilogException] {
        ExternalParameterizedMemoryRegistry.attach(
          Mem(Bits(8 bits), 3),
          ParameterizedMemoryDepth(3, foreignDepth)
        )
      }

      val widthParameter = ElaborationIntegerParameter(
        "FORGED_WIDTH",
        default = 8,
        minimum = 1,
        maximum = 16
      )
      val inexactWidth = ElaborationIntegerExpression(
        verilog = "FORGED_WIDTH",
        default = 8,
        minimum = 1,
        maximum = 16,
        parameters = Vector(widthParameter),
        parameterRoots = Vector(
          ElaborationIntegerParameterRoot.fresh("FORGED_WIDTH")
        )
      )
      val wordType = HardType {
        ParameterizedWidth.attach(
          Bits(8 bits),
          ParameterizedBitCount(
            value = 8,
            parameter = None,
            expression = Some(inexactWidth)
          )
        )
      }
      elementFailure = intercept[ParameterizedVerilogException] {
        ExternalParameterizedMemoryRegistry.attach(
          Mem(wordType, 4),
          literalDepth(4)
        )
      }
    }

    assert(depthFailure != null)
    assert(
      depthFailure.code == "SPINAL-ELAB-DOMAIN-ROOT-IDENTITY-MISMATCH"
    )
    assert(elementFailure != null)
    assert(
      elementFailure.code ==
        "SPINAL-PARAMETERIZED-VERILOG-WIDTH-EXACT-DOMAIN-REQUIRED"
    )
  }

  test("external memory registry preserves literal and valid exact symbolic construction") {
    withSpinalElaboration {
      val literal = ExternalParameterizedMemoryRegistry.create(
        HardType(Bits(8 bits)),
        literalDepth(4)
      )
      assert(literal.wordCount == 4)
      assert(
        ExternalParameterizedMemoryRegistry
          .metadataOf(literal)
          .exists(_.depth.parameters.isEmpty)
      )

      val width = parameter("WIDTH", default = 8, minimum = 1, maximum = 16)
      val depth = parameter("DEPTH", default = 3, minimum = 1, maximum = 8)
      val symbolic = ExternalParameterizedMemoryRegistry.create(
        HardType(UInt(width bits)),
        ParameterizedMemoryDepth.checked(depth)
      )
      val metadata = ExternalParameterizedMemoryRegistry.metadataOf(symbolic).get
      assert(symbolic.wordCount == 3)
      assert(metadata.depth.exactDomain.exists(_.hasCompleteCoverage))
      assert(metadata.elementWidth.exactDomain.exists(_.hasCompleteCoverage))
    }
  }

  test("public typed Mem rejects bounded symbolic depth without exact evidence") {
    var failure: ParameterizedVerilogException = null
    withSpinalElaboration {
      val parameter = ElaborationIntegerParameter(
        "DEPTH",
        default = 3,
        minimum = 1,
        maximum = 8
      )
      val root = ElaborationIntegerParameterRoot.fresh("DEPTH")
      val inexact = ElabInt.fromExpression(
        ElaborationIntegerExpression(
          verilog = "DEPTH",
          default = 3,
          minimum = 1,
          maximum = 8,
          parameters = Vector(parameter),
          parameterRoots = Vector(root)
        )
      )

      failure = intercept[ParameterizedVerilogException] {
        Mem(Bits(8 bits), inexact)
      }
    }

    assert(failure != null)
    assert(
      failure.code == "SPINAL-ELAB-INT-MEMORY-DEPTH-DOMAIN-INVALID"
    )
  }

  test("public typed Mem rejects an equal-but-foreign raw exact schema") {
    var failure: ParameterizedVerilogException = null
    withSpinalElaboration {
      val authoritativeSchema = ElaborationIntegerParameter(
        "FOREIGN_RAW_MEM_DEPTH",
        default = 2,
        minimum = 1,
        maximum = 3
      )
      val copiedSchema = authoritativeSchema.copy()
      val root =
        ElaborationIntegerParameterRoot.fresh("FOREIGN_RAW_MEM_DEPTH")
      val forged = ElabInt.fromTrustedExactExpressionForTest(
        ElaborationIntegerExpression(
          verilog = "FOREIGN_RAW_MEM_DEPTH",
          default = 2,
          minimum = 1,
          maximum = 3,
          parameters = Vector(copiedSchema),
          parameterRoots = Vector(root),
          exactDomain = Some(
            ElaborationExactDomain.checked[BigInt](
              root,
              authoritativeSchema,
              Vector(
                BigInt(1) -> BigInt(1),
                BigInt(2) -> BigInt(2),
                BigInt(3) -> BigInt(3)
              ),
              sourceLocation = None,
              role = "foreign raw typed Mem depth schema"
            )
          )
        )
      )

      failure = intercept[ParameterizedVerilogException] {
        Mem(Bits(8 bits), forged)
      }
    }

    assert(failure != null)
    assert(
      failure.code == "SPINAL-ELAB-INT-MEMORY-DEPTH-DOMAIN-INVALID"
    )
  }

  test("finite Mem rejects a witness-identical replacement address assignment") {
    withTemporaryDirectory { directory =>
      val rtl = directory.resolve("replaced_finite_mem_address.v")
      val failure = MorphVerilog.tryGenerate(
        morphConfig(directory, rtl.getFileName.toString)
      ) {
        new ReplacedAddressAssignment(parameter("DEPTH", 3, 1, 8))
      } match {
        case Left(value)  => value
        case Right(value) => fail(s"expected stale finite Mem address rejection, received $value")
      }
      assert(
        failure.detail.contains(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-MEM-ADDRESS-LINEAGE-MISMATCH"
        ),
        failure.detail
      )
      assert(!Files.exists(rtl), "stale finite Mem address published partial RTL")
    }
  }

  test("finite Mem accepts exact native literal-address normalization") {
    withTemporaryDirectory { directory =>
      val rtl = directory.resolve("normalized_finite_mem_address.v")
      MorphVerilog(
        morphConfig(directory, rtl.getFileName.toString)
      ) {
        new NormalizedAddressAssignment(parameter("DEPTH", 3, 1, 8))
      }
      val verilog = new String(Files.readAllBytes(rtl), StandardCharsets.UTF_8)
      val accesses =
        "(?m)^\\s*assign\\s+[A-Za-z_][A-Za-z0-9_$]*\\s*=\\s*finite_mem\\s*\\[\\s*([^\\]]+)\\s*\\]\\s*;\\s*$".r
          .findAllMatchIn(verilog)
          .map(_.group(1))
          .toVector
      assert(accesses.size == 1, verilog)
      assert(accesses.head.contains("normalized_finite_Mem_address_index_"), verilog)
    }
  }

  test("finite Mem partitions exact structural reads from one native 1R1W pair") {
    withTemporaryDirectory { directory =>
      val rtl = directory.resolve("mixed_structural_native_mem.v")
      MorphVerilog(
        morphConfig(directory, rtl.getFileName.toString)
      ) {
        new MixedStructuralAndNativePorts(
          parameter("DEPTH", 3, 1, 8),
          includeUncapturedAsyncRead = false
        )
      }
      val verilog = new String(Files.readAllBytes(rtl), StandardCharsets.UTF_8)
      val compact = verilog.replaceAll("\\s+", "")
      assert(compact.contains("mixed_mem[0:DEPTH-1]"), verilog)
      assert(verilog.contains("always @(posedge clk) begin : p_mixed_mem"), verilog)
      assert(compact.contains("mixed_mem[mixed_finite_Mem_read_index_"), verilog)
    }
  }

  test("finite Mem mixed mode rejects an uncaptured asynchronous port") {
    withTemporaryDirectory { directory =>
      val rtl = directory.resolve("unowned_async_mixed_mem.v")
      val failure = MorphVerilog.tryGenerate(
        morphConfig(directory, rtl.getFileName.toString)
      ) {
        new MixedStructuralAndNativePorts(
          parameter("DEPTH", 3, 1, 8),
          includeUncapturedAsyncRead = true
        )
      } match {
        case Left(value)  => value
        case Right(value) => fail(s"expected unowned async-port rejection, received $value")
      }
      assert(
        failure.detail.contains(
          "SPINAL-PARAMETERIZED-VERILOG-MEMORY-PORT-SHAPE-UNSUPPORTED"
        ),
        failure.detail
      )
      assert(!Files.exists(rtl), "unowned async port published partial RTL")
    }
  }

  test("copied address-width algebra cannot replace same-root exhaustive capacity proof") {
    withTemporaryDirectory { directory =>
      val rtl = directory.resolve("copied_address_algebra.v")
      val failure = MorphVerilog.tryGenerate(
        morphConfig(directory, rtl.getFileName.toString)
      ) {
        new CopiedAddressAlgebra(parameter("DEPTH", 3, 1, 8))
      } match {
        case Left(value)  => value
        case Right(value) => fail(s"expected copied address-algebra rejection, received $value")
      }
      assert(
        failure.detail.contains(
          "SPINAL-PARAMETERIZED-VERILOG-MEMORY-ADDRESS-CAPACITY-NOT-PROVEN"
        ),
        failure.detail
      )
      assert(!Files.exists(rtl), "copied address algebra published partial RTL")
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

  private def literalDepth(value: Int): ParameterizedMemoryDepth =
    ParameterizedMemoryDepth(
      value,
      ElaborationIntegerExpression(
        verilog = value.toString,
        default = BigInt(value),
        minimum = BigInt(value),
        maximum = BigInt(value),
        parameters = Vector.empty
      )
    )

  private def morphConfig(directory: Path, filename: String): SpinalConfig = {
    Files.createDirectories(directory)
    val config = SpinalConfig(targetDirectory = directory.toString)
    config.netlistFileName = filename
    config
  }

  private def withSpinalElaboration(body: => Unit): Unit =
    withTemporaryDirectory { directory =>
      SpinalVerilog(
        SpinalConfig(
          targetDirectory = directory.toString,
          headerWithRepoHash = false,
          withTimescale = false,
          printFilelist = false
        )
      ) {
        new Component {
          val keep = out(Bool())
          keep := False
          body
        }
      }
    }

  private def withTemporaryDirectory(body: Path => Unit): Unit = {
    val directory = Files.createTempDirectory("morphhdl-finite-mem-identity-")
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
