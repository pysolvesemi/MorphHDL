package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.collection.JavaConverters._

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

import morphhdl.frontend.{formalParam, formalRegion, HdlInt}

object FormalParameterClonePropagationSmoke {
  final class Leaf(actualWidth: HdlInt) extends Component {
    setDefinitionName("FormalCloneLeaf")

    @dontName
    private val width = formalParam(actualWidth, "WIDTH")

    @dontName
    private val prototype = morphhdl.frontend.Bits(width bits)

    val clk = in(Bool())
    val din = in(morphhdl.frontend.cloneOf(prototype))
    val dout = out(morphhdl.frontend.cloneOf(prototype))

    @dontName
    private val hardType =
      morphhdl.frontend.HardType(morphhdl.frontend.Bits(width bits))

    @dontName
    private val hardValue = hardType()

    @dontName
    private val registerClockDomain = ClockDomain(clock = clk)

    private val registerArea = new ClockingArea(registerClockDomain) {
      @dontName
      val state = morphhdl.frontend.Reg(morphhdl.frontend.Bits(width bits))
      state := hardValue
    }

    @dontName
    private val values =
      morphhdl.frontend.Vec(morphhdl.frontend.Bits(width bits), 2)

    prototype := din
    hardValue := prototype
    values(0) := registerArea.state
    values(1) := values(0)
    dout := values(1)

    requireFormal(hardValue, "HardType instance")
    requireFormal(registerArea.state, "Reg result")
    requireFormal(values(0), "Vec element 0")
    requireFormal(values(1), "Vec element 1")
  }

  final class Top(leftWidth: HdlInt, rightWidth: HdlInt) extends Component {
    setDefinitionName("FormalCloneTop")

    val clk = in(Bool())
    val leftIn = in(morphhdl.frontend.Bits(leftWidth bits))
    val leftOut = out(morphhdl.frontend.Bits(leftWidth bits))
    val rightIn = in(morphhdl.frontend.Bits(rightWidth bits))
    val rightOut = out(morphhdl.frontend.Bits(rightWidth bits))

    val left = new Leaf(leftWidth)
    left.setName("left")
    val right = new Leaf(rightWidth)
    right.setName("right")

    left.clk := clk
    left.din := leftIn
    leftOut := left.dout
    right.clk := clk
    right.din := rightIn
    rightOut := right.dout
  }

  final class MismatchedTop(
      formalActual: HdlInt,
      connectedWidth: HdlInt
  ) extends Component {
    setDefinitionName("FormalCloneMismatchTop")

    val clk = in(Bool())
    val din = in(morphhdl.frontend.Bits(connectedWidth bits))
    val dout = out(morphhdl.frontend.Bits(connectedWidth bits))

    val leaf = new Leaf(formalActual)
    leaf.setName("leaf")
    leaf.clk := clk
    leaf.din := din
    dout := leaf.dout
  }

  def component(): Component = {
    val leftWidth = HdlInt.param("LEFT_WIDTH", default = 8, min = 1, max = 16)
    val rightWidth = HdlInt.param("RIGHT_WIDTH", default = 8, min = 2, max = 32)
    new Top(leftWidth, rightWidth)
  }

  private def requireFormal(data: Data, role: String): Unit = {
    val leaves = data.flatten.toVector
    require(leaves.nonEmpty, s"$role has no flattened leaves")
    leaves.foreach { leaf =>
      require(
        ExternalFormalParameterRegistry
          .bindingOf(leaf)
          .exists(_.formal.name == "WIDTH"),
        s"$role lost explicit formal binding through a shape-copy path"
      )
    }
  }
}

class FormalParameterClonePropagationTests extends AnyFunSuite {
  import FormalParameterClonePropagationSmoke._

  private final class RootlessRecoveryProbe extends Component {
    setDefinitionName("FormalRootlessRecoveryProbe")

    private val schema =
      ElaborationIntegerParameter("WIDTH", default = 8, minimum = 1, maximum = 16)
    private val binding = ExternalFormalParameterBinding(
      formal = schema,
      actual = ElaborationIntegerExpression(
        verilog = "8",
        default = 8,
        minimum = 8,
        maximum = 8,
        parameters = Vector.empty
      ),
      declarationKey = "rootless-recovery-probe::WIDTH",
      ownerClassName = getClass.getName,
      sourceLocation = None
    )
    ExternalFormalParameterRegistry.retainComponent(this, binding)

    private val independentRoot =
      ElaborationIntegerParameterRoot.fresh("WIDTH")
    private val independentExpression = ElaborationIntegerExpression(
      verilog = "WIDTH",
      default = 8,
      minimum = 1,
      maximum = 16,
      parameters = Vector(schema),
      parameterRoots = Vector(independentRoot)
    )
    val unbound = out(
      ParameterizedWidth.Bits(
        ParameterizedBitCount(
          value = 8,
          parameter = Some(schema),
          expression = Some(independentExpression)
        )
      )
    )

    require(
      ExternalFormalParameterRegistry.bindingOf(unbound).isEmpty,
      "a rootless component formal must not recover an independently rooted leaf"
    )
    unbound := 0
  }

  private final class IndependentActualRootsProbe extends Component {
    setDefinitionName("FormalIndependentActualRootsProbe")

    private val formal =
      ElaborationIntegerParameter("WIDTH", default = 8, minimum = 1, maximum = 16)
    private val actualSchema = ElaborationIntegerParameter(
      "ACTUAL_WIDTH",
      default = 8,
      minimum = 1,
      maximum = 16
    )
    private def actual(root: ElaborationIntegerParameterRoot) =
      ElaborationIntegerExpression(
        verilog = "ACTUAL_WIDTH",
        default = 8,
        minimum = 1,
        maximum = 16,
        parameters = Vector(actualSchema),
        parameterRoots = Vector(root)
      )
    private def binding(root: ElaborationIntegerParameterRoot) =
      ExternalFormalParameterBinding(
        formal = formal,
        actual = actual(root),
        declarationKey = "independent-actual-roots-probe::WIDTH",
        ownerClassName = getClass.getName,
        sourceLocation = None
      )

    ExternalFormalParameterRegistry.retainComponent(
      this,
      binding(ElaborationIntegerParameterRoot.fresh("ACTUAL_WIDTH"))
    )
    ExternalFormalParameterRegistry.retainComponent(
      this,
      binding(ElaborationIntegerParameterRoot.fresh("ACTUAL_WIDTH"))
    )
  }

  private final class FormalRegistryPreflightProbe extends Component {
    setDefinitionName("FormalRegistryPreflightProbe")

    val attached = out(Bits(8 bits))
    attached := 0

    private val retainFormal = ElaborationIntegerParameter(
      "RETAIN_WIDTH",
      default = 8,
      minimum = 1,
      maximum = 16
    )
    private val wrongRetainFormal = ElaborationIntegerParameter(
      "WRONG_RETAIN_WIDTH",
      default = 8,
      minimum = 1,
      maximum = 16
    )
    private val invalidRetainBinding = binding(
      retainFormal,
      "formal-registry-preflight::invalid-retain",
      getClass.getName
    )
    val invalidRetainCode = captureCode {
      ExternalFormalParameterRegistry.retain(
        ParameterizedBitCount(8, wrongRetainFormal),
        invalidRetainBinding
      )
    }
    ExternalFormalParameterRegistry.retain(
      ParameterizedBitCount(8, retainFormal),
      binding(
        retainFormal,
        "formal-registry-preflight::valid-retain",
        getClass.getName
      )
    )
    val retainRetrySucceeded = true

    private val attachFormal = ElaborationIntegerParameter(
      "ATTACH_WIDTH",
      default = 8,
      minimum = 1,
      maximum = 16
    )
    private val wrongAttachFormal = ElaborationIntegerParameter(
      "WRONG_ATTACH_WIDTH",
      default = 8,
      minimum = 1,
      maximum = 16
    )
    private val invalidAttachBinding = binding(
      attachFormal,
      "formal-registry-preflight::invalid-attach",
      getClass.getName
    )
    val invalidAttachCode = captureCode {
      ExternalFormalParameterRegistry.attach(
        this,
        attached,
        ParameterizedBitCount(8, wrongAttachFormal),
        invalidAttachBinding
      )
    }
    private val validAttachBinding = binding(
      attachFormal,
      "formal-registry-preflight::valid-attach",
      getClass.getName
    )
    ExternalFormalParameterRegistry.attach(
      this,
      attached,
      ParameterizedBitCount(8, attachFormal),
      validAttachBinding
    )
    val attachRetrySucceeded =
      ExternalFormalParameterRegistry
        .bindingOf(attached)
        .exists(_.declarationKey == validAttachBinding.declarationKey)
  }

  private final class InvalidFormalActualPreflightProbe extends Component {
    setDefinitionName("InvalidFormalActualPreflightProbe")

    val keepAlive = out(Bool())
    keepAlive := False

    private val malformedFormal = ElaborationIntegerParameter(
      "MALFORMED_ACTUAL_WIDTH",
      default = 8,
      minimum = 1,
      maximum = 16
    )
    private val actualSchema = ElaborationIntegerParameter(
      "ACTUAL_WIDTH",
      default = 8,
      minimum = 1,
      maximum = 16
    )
    private val malformedActual = ElaborationIntegerExpression(
      verilog = "ACTUAL_WIDTH",
      default = 8,
      minimum = 1,
      maximum = 16,
      parameters = Vector(actualSchema),
      parameterRoots = null
    )
    val malformedActualCode = captureCode {
      ExternalFormalParameterRegistry.retainComponent(
        this,
        binding(
          malformedFormal,
          "formal-actual-preflight::invalid-roots",
          getClass.getName,
          malformedActual
        )
      )
    }
    ExternalFormalParameterRegistry.retainComponent(
      this,
      binding(
        malformedFormal,
        "formal-actual-preflight::valid-roots",
        getClass.getName
      )
    )
    val malformedRetrySucceeded = true

    private val nullFormal = ElaborationIntegerParameter(
      "NULL_ACTUAL_WIDTH",
      default = 8,
      minimum = 1,
      maximum = 16
    )
    val nullActualCode = captureCode {
      ExternalFormalParameterRegistry.retainComponent(
        this,
        binding(
          nullFormal,
          "formal-actual-preflight::invalid-null",
          getClass.getName,
          null
        )
      )
    }
    ExternalFormalParameterRegistry.retainComponent(
      this,
      binding(
        nullFormal,
        "formal-actual-preflight::valid-null",
        getClass.getName
      )
    )
    val nullRetrySucceeded = true
  }

  private final class FormalBatchLeaf extends Component {
    setDefinitionName("FormalBatchPreflightLeaf")

    val first = in(Bits(8 bits))
    val second = out(Bits(8 bits))
    second := first
  }

  private final class FormalBatchPreflightProbe extends Component {
    setDefinitionName("FormalBatchPreflightProbe")

    val child = new FormalBatchLeaf
    child.first := 0
    val observed = out(Bits(8 bits))
    observed := child.second

    private val seedFormal = ElaborationIntegerParameter(
      "SEED_WIDTH",
      default = 8,
      minimum = 1,
      maximum = 16
    )
    private val seedBinding = binding(
      seedFormal,
      "formal-batch-preflight::seed",
      child.getClass.getName
    )
    ExternalFormalParameterRegistry.attach(
      child,
      child.second,
      ParameterizedBitCount(8, seedFormal),
      seedBinding
    )

    private val incomingFormal = ElaborationIntegerParameter(
      "WIDTH",
      default = 8,
      minimum = 1,
      maximum = 16
    )
    private val invalidIncoming = binding(
      incomingFormal,
      "formal-batch-preflight::invalid",
      child.getClass.getName
    )
    private val token = ExternalNativeIntFormalizationToken(
      callSite = "formal-batch-preflight",
      valueOrigin = "formal-batch-preflight",
      role = "formal batch preflight"
    )
    val batchConflictCode = captureCode {
      ExternalNativeIntFormalizationRegistry.attachComponent(
        this,
        child,
        Vector(child.first, child.second),
        invalidIncoming,
        token
      )
    }
    val firstLeafUnchanged =
      ExternalFormalParameterRegistry.bindingOf(child.first).isEmpty &&
        ParameterizedWidth.expressionOf(child.first).isEmpty
    val conflictingLeafPreserved =
      ExternalFormalParameterRegistry
        .bindingOf(child.second)
        .exists(_.declarationKey == seedBinding.declarationKey) &&
        ParameterizedWidth
          .expressionOf(child.second)
          .exists(_.verilog == seedFormal.name)
    val componentRecordUnchanged =
      ExternalNativeIntFormalizationRegistry.componentRecordsOf(child).isEmpty
    val formalBindingsUnchanged =
      ExternalFormalParameterRegistry
        .bindingsOf(child)
        .map(_.declarationKey) == Vector(seedBinding.declarationKey)
    val regionRecordsUnchanged =
      ExternalNativeIntFormalizationRegistry.regionOf(child.first).isEmpty &&
        ExternalNativeIntFormalizationRegistry.regionOf(child.second).isEmpty

    ExternalFormalParameterRegistry.retainComponent(
      child,
      binding(
        incomingFormal,
        "formal-batch-preflight::valid",
        child.getClass.getName
      )
    )
    val declarationRetrySucceeded = true
  }

  private final class PendingOwnerA(
      sharedWidth: ParameterizedBitCount,
      sharedFormal: ElaborationIntegerParameter
  ) extends Component {
    setDefinitionName("FormalPendingOwnerA")

    val keepAlive = out(Bool())
    keepAlive := False
    ExternalFormalParameterRegistry.retain(
      sharedWidth,
      binding(
        sharedFormal,
        "formal-pending-preflight::owner-a",
        getClass.getName
      )
    )
  }

  private final class PendingOwnerB(
      sharedWidth: ParameterizedBitCount,
      sharedFormal: ElaborationIntegerParameter
  ) extends Component {
    setDefinitionName("FormalPendingOwnerB")

    val keepAlive = out(Bool())
    keepAlive := False
    val pendingConflictCode = captureCode {
      ExternalFormalParameterRegistry.retain(
        sharedWidth,
        binding(
          sharedFormal,
          "formal-pending-preflight::failed-owner-b",
          getClass.getName
        )
      )
    }
    ExternalFormalParameterRegistry.retainComponent(
      this,
      binding(
        sharedFormal,
        "formal-pending-preflight::retry-owner-b",
        getClass.getName
      )
    )
    val declarationRetrySucceeded = true
  }

  private final class PendingDeclarationPreflightProbe extends Component {
    setDefinitionName("PendingDeclarationPreflightProbe")

    private val sharedFormal = ElaborationIntegerParameter(
      "WIDTH",
      default = 8,
      minimum = 1,
      maximum = 16
    )
    private val sharedWidth = ParameterizedBitCount(8, sharedFormal)
    val first = new PendingOwnerA(sharedWidth, sharedFormal)
    val second = new PendingOwnerB(sharedWidth, sharedFormal)
  }

  private final class FormalRegionOnFormalParamProbe(actual: HdlInt)
      extends Component {
    setDefinitionName("FormalRegionOnFormalParamProbe")

    @dontName
    private val width = formalParam(actual, "WIDTH")
    val data = out(formalRegion(width)(value => Bits(value bits)))
    data := 0

    private val retainedBinding =
      ExternalFormalParameterRegistry.bindingOf(data).getOrElse {
        throw new IllegalStateException("formalRegion lost its formal binding")
      }
    val exactSchemaRetained = ParameterizedWidth
      .expressionOf(data)
      .exists(_.parameters match {
        case Vector(parameter) => parameter eq retainedBinding.formal
        case _                 => false
      })
  }

  private final class MalformedNativeRegionPreflightProbe extends Component {
    setDefinitionName("MalformedNativeRegionPreflightProbe")

    val data = out(Bits(8 bits))
    data := 0

    private val formal = ElaborationIntegerParameter(
      "WIDTH",
      default = 8,
      minimum = 1,
      maximum = 16
    )
    private val malformedExpression = ElaborationIntegerExpression(
      verilog = "WIDTH",
      default = 8,
      minimum = 1,
      maximum = 16,
      parameters = Vector(formal),
      parameterRoots = null
    )
    private val formalBinding = binding(
      formal,
      "malformed-native-region-preflight::WIDTH",
      getClass.getName
    )
    private val token = ExternalNativeIntFormalizationToken(
      callSite = "malformed-native-region-preflight",
      valueOrigin = "malformed-native-region-preflight",
      role = "malformed native region preflight"
    )
    val malformedExpressionCode = captureCode {
      ExternalNativeIntFormalizationRegistry.attachRegion(
        this,
        data,
        malformedExpression,
        token,
        Some(formalBinding)
      )
    }
    val metadataUnchanged =
      ExternalNativeIntFormalizationRegistry.regionOf(data).isEmpty &&
        ExternalFormalParameterRegistry.bindingOf(data).isEmpty &&
        ParameterizedWidth.expressionOf(data).isEmpty
  }

  private def literalActual: ElaborationIntegerExpression =
    ElaborationIntegerExpression(
      verilog = "8",
      default = 8,
      minimum = 8,
      maximum = 8,
      parameters = Vector.empty
    )

  private def binding(
      formal: ElaborationIntegerParameter,
      declarationKey: String,
      ownerClassName: String,
      actual: ElaborationIntegerExpression = literalActual
  ): ExternalFormalParameterBinding =
    ExternalFormalParameterBinding(
      formal = formal,
      actual = actual,
      declarationKey = declarationKey,
      ownerClassName = ownerClassName,
      sourceLocation = None
    )

  private def captureCode(body: => Unit): String =
    try {
      body
      "<accepted>"
    } catch {
      case error: ParameterizedVerilogException => error.code
    }

  test("clone-derived ports and native shape copies retain per-instance actuals") {
    withTemporaryDirectory { directory =>
      val verilog = emitComponent(
        directory,
        "formal_parameter_clone_propagation.v",
        component()
      )

      assert(
        "(?m)^module FormalCloneLeaf\\b".r.findAllMatchIn(verilog).size == 1
      )
      assert(
        "(?m)^  FormalCloneLeaf #\\(".r.findAllMatchIn(verilog).size == 2
      )
      assert(verilog.contains("module FormalCloneLeaf #("))
      assert(verilog.contains("parameter integer WIDTH = 8"))
      assert(verilog.contains("module FormalCloneTop #("))
      assert(verilog.contains("parameter integer LEFT_WIDTH = 8"))
      assert(verilog.contains("parameter integer RIGHT_WIDTH = 8"))
      assert(verilog.contains(".WIDTH(LEFT_WIDTH)"))
      assert(verilog.contains(".WIDTH(RIGHT_WIDTH)"))
      assert(hasDeclarationWidth(verilog, "din", "[WIDTH-1:0]"))
      assert(hasDeclarationWidth(verilog, "dout", "[WIDTH-1:0]"))
      assert(hasDeclarationWidth(verilog, "leftIn", "[LEFT_WIDTH-1:0]"))
      assert(hasDeclarationWidth(verilog, "leftOut", "[LEFT_WIDTH-1:0]"))
      assert(hasDeclarationWidth(verilog, "rightIn", "[RIGHT_WIDTH-1:0]"))
      assert(hasDeclarationWidth(verilog, "rightOut", "[RIGHT_WIDTH-1:0]"))
      assert(!verilog.contains("FormalCloneLeaf_1"))
    }
  }

  test("clone-derived formal ports keep constructor-actual conflict checks") {
    withTemporaryDirectory { directory =>
      val formalActual =
        HdlInt.param("FORMAL_ACTUAL", default = 8, min = 1, max = 16)
      val connected =
        HdlInt.param("CONNECTED_WIDTH", default = 8, min = 1, max = 16)

      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "formal_clone_connection_conflict.v"
      MorphVerilog.tryGenerate(config)(new MismatchedTop(formalActual, connected)) match {
        case Left(failure) =>
          assert(
            failure.detail.contains(
              "SPINAL-PARAMETERIZED-VERILOG-FORMAL-ACTUAL-CONNECTION-CONFLICT"
            ),
            failure.detail
          )
        case Right(report) =>
          fail(
            "Expected clone-derived formal connection conflict, received " + report
          )
      }
    }
  }

  test("clone-derived formal ports reject independently rooted same-name connections") {
    withTemporaryDirectory { directory =>
      val formalActual =
        HdlInt.param("SHARED_WIDTH", default = 8, min = 1, max = 16)
      val connected =
        HdlInt.param("SHARED_WIDTH", default = 8, min = 1, max = 16)

      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "formal_clone_same_name_root_conflict.v"
      MorphVerilog.tryGenerate(config)(new MismatchedTop(formalActual, connected)) match {
        case Left(failure) =>
          assert(
            failure.detail.contains(
              "SPINAL-PARAMETERIZED-VERILOG-FORMAL-ACTUAL-CONNECTION-CONFLICT"
            ),
            failure.detail
          )
        case Right(report) =>
          fail(
            "Expected independently rooted formal connection conflict, received " +
              report
          )
      }
    }
  }

  test("rootless component formals do not wildcard-recover rooted leaves") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "formal_rootless_recovery_probe.v"
      // The assertion runs during construction. Ordinary publication then
      // proves the intentionally unbound symbolic metadata is not consumed as
      // a MorphHDL formal hierarchy binding.
      SpinalVerilog(config)(new RootlessRecoveryProbe)
    }
  }

  test("one formal slot rejects independently rooted same-schema actuals") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "formal_independent_actual_roots_probe.v"
      MorphVerilog.tryGenerate(config)(new IndependentActualRootsProbe) match {
        case Left(failure) =>
          assert(
            failure.detail.contains(
              "SPINAL-PARAMETERIZED-VERILOG-FORMAL-SLOT-AMBIGUOUS"
            ),
            failure.detail
          )
        case Right(report) =>
          fail(
            "Expected independent formal-slot root ambiguity, received " + report
          )
      }
    }
  }

  test("invalid formal widths do not reserve retain or attach declarations") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "formal_registry_preflight_probe.v"
      val probe = SpinalVerilog(config)(new FormalRegistryPreflightProbe).toplevel

      assert(
        probe.invalidRetainCode ==
          "SPINAL-PARAMETERIZED-VERILOG-FORMAL-WIDTH-SCHEMA-MISMATCH"
      )
      assert(probe.retainRetrySucceeded)
      assert(
        probe.invalidAttachCode ==
          "SPINAL-PARAMETERIZED-VERILOG-FORMAL-WIDTH-SCHEMA-MISMATCH"
      )
      assert(probe.attachRetrySucceeded)
    }
  }

  test("malformed formal actuals fail typed preflight without reserving declarations") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "formal_actual_preflight_probe.v"
      val probe =
        SpinalVerilog(config)(new InvalidFormalActualPreflightProbe).toplevel

      assert(
        probe.malformedActualCode ==
          "SPINAL-ELAB-INT-PARAMETER-ROOT-NULL"
      )
      assert(probe.malformedRetrySucceeded)
      assert(
        probe.nullActualCode ==
          "SPINAL-PARAMETERIZED-VERILOG-FORMAL-ACTUAL-NULL"
      )
      assert(probe.nullRetrySucceeded)
    }
  }

  test("formal region batches preflight every leaf before committing metadata") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "formal_batch_preflight_probe.v"
      val probe = SpinalVerilog(config)(new FormalBatchPreflightProbe).toplevel

      assert(
        probe.batchConflictCode ==
          "SPINAL-PARAMETERIZED-VERILOG-FORMAL-METADATA-CONFLICT"
      )
      assert(probe.firstLeafUnchanged)
      assert(probe.conflictingLeafPreserved)
      assert(probe.componentRecordUnchanged)
      assert(probe.formalBindingsUnchanged)
      assert(probe.regionRecordsUnchanged)
      assert(probe.declarationRetrySucceeded)
    }
  }

  test("pending conflicts do not reserve declarations for the losing owner") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "formal_pending_preflight_probe.v"
      val probe =
        SpinalVerilog(config)(new PendingDeclarationPreflightProbe).toplevel

      assert(
        probe.second.pendingConflictCode ==
          "SPINAL-PARAMETERIZED-VERILOG-FORMAL-METADATA-CONFLICT"
      )
      assert(probe.second.declarationRetrySucceeded)
    }
  }

  test("formalRegion reuses the exact formal schema object") {
    withTemporaryDirectory { directory =>
      val actual =
        HdlInt.param("ACTUAL_WIDTH", default = 8, min = 1, max = 16)
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "formal_region_exact_schema_probe.v"
      val probe =
        SpinalVerilog(config)(new FormalRegionOnFormalParamProbe(actual)).toplevel

      assert(probe.exactSchemaRetained)
    }
  }

  test("malformed native region expressions fail typed preflight before attachment") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "malformed_native_region_preflight_probe.v"
      val probe =
        SpinalVerilog(config)(new MalformedNativeRegionPreflightProbe).toplevel

      assert(
        probe.malformedExpressionCode ==
          "SPINAL-ELAB-INT-PARAMETER-ROOT-NULL"
      )
      assert(probe.metadataUnchanged)
    }
  }

  private def emitComponent(
      directory: Path,
      filename: String,
      component: => Component
  ): String = {
    val config = SpinalConfig(targetDirectory = directory.toString)
    config.netlistFileName = filename
    MorphVerilog(config)(component)
    new String(
      Files.readAllBytes(directory.resolve(filename)),
      StandardCharsets.UTF_8
    )
  }

  private def hasDeclarationWidth(
      verilog: String,
      name: String,
      range: String
  ): Boolean = {
    val pattern =
      (java.util.regex.Pattern.quote(range) + "\\s+" +
        java.util.regex.Pattern.quote(name) + "(?=\\s*(?:[,;]|\\)))").r
    pattern.findFirstIn(verilog).nonEmpty
  }

  private def withTemporaryDirectory(body: Path => Unit): Unit = {
    val directory = Files.createTempDirectory("morphhdl-formal-clone-test-")
    try body(directory)
    finally {
      val stream = Files.walk(directory)
      try {
        stream.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach {
          path => Files.deleteIfExists(path)
        }
      } finally stream.close()
    }
  }
}
