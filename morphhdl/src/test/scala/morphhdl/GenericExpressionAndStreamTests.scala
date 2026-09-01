package morphhdl

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer
import scala.util.matching.Regex

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.internals.{
  DataAssignmentStatement,
  ExternalParameterizedAutoResize,
  Phase,
  PhaseContext,
  PhaseMisc,
  PhaseNormalizeNodeInputs,
  PhaseRemoveIntermediateUnnameds
}
import spinal.lib._

import morphhdl.frontend.HdlInt

class GenericExpressionAndStreamTests extends AnyFunSuite {
  private final class GenericExpressions(width: HdlInt) extends Component {
    setDefinitionName("NativeGenericExpressions")

    val left = in(morphhdl.frontend.Bits(width bits))
    val right = in(morphhdl.frontend.Bits(width bits))
    val unsignedLeft = in(morphhdl.frontend.UInt(width bits))
    val unsignedRight = in(morphhdl.frontend.UInt(width bits))
    val select = in(Bool())

    val connected = out(morphhdl.frontend.Bits(width bits))
    val muxed = out(morphhdl.frontend.Bits(width bits))
    val xored = out(morphhdl.frontend.Bits(width bits))
    val sum = out(morphhdl.frontend.UInt(width bits))
    val expandedSum = out(UInt())
    val concatenated = out(Bits())
    val sliced = out(morphhdl.frontend.Bits(4 bits))
    val resized = out(morphhdl.frontend.Bits(4 bits))

    connected := left
    muxed := Mux(select, left, right)
    xored := left ^ right
    sum := unsignedLeft + unsignedRight
    expandedSum := unsignedLeft +^ unsignedRight
    concatenated := left ## right
    sliced := left(3 downto 0)
    resized := right.resize(4)
  }

  private final class NativeStreamM2sPipe(width: HdlInt) extends Component {
    setDefinitionName("NativeStreamM2sPipe")

    val push_valid = in(Bool())
    val push_ready = out(Bool())
    val push_data = in(morphhdl.frontend.Bits(width bits))
    val pop_valid = out(Bool())
    val pop_ready = in(Bool())
    val pop_data = out(morphhdl.frontend.Bits(width bits))

    val push = Stream(morphhdl.frontend.Bits(width bits))
    push.valid := push_valid
    push.payload := push_data
    push_ready := push.ready

    val piped = push.m2sPipe()
    pop_valid := piped.valid
    piped.ready := pop_ready
    pop_data := piped.payload
  }

  private final class NativeAutoResizedIncrement(width: HdlInt) extends Component {
    setDefinitionName("NativeAutoResizedIncrement")

    val value = in(morphhdl.frontend.UInt(width bits))
    val nextValue = out(morphhdl.frontend.UInt(width bits))

    nextValue := (value + 1).resized
  }

  private final class NativeUnresizedFixedIncrement(width: HdlInt) extends Component {
    setDefinitionName("NativeUnresizedFixedIncrement")

    val value = in(morphhdl.frontend.UInt(width bits))
    val next = out(morphhdl.frontend.UInt(width bits))

    next := value + U(1, 3 bits)
  }

  private final class NativeUnsizedNestedIncrement(width: HdlInt) extends Component {
    setDefinitionName("NativeUnsizedNestedIncrement")

    val prefix = in(Bool())
    val value = in(morphhdl.frontend.UInt(width bits))
    val packed = out(morphhdl.frontend.UInt((width + 1) bits))

    packed := (prefix.asBits ## (value + 1).asBits).asUInt
  }

  private final class NativeReusedAutoResize(width: HdlInt) extends Component {
    setDefinitionName("NativeReusedAutoResize")

    val value = in(morphhdl.frontend.UInt(width bits))
    val first = out(morphhdl.frontend.UInt(width bits))
    val second = out(morphhdl.frontend.UInt(width bits))
    val shared = (value + 1).resized

    first := shared
    second := shared ^ value
  }

  private final class WitnessInactiveAutoResize(
      depth: HdlInt,
      reuse: Boolean
  ) extends Component {
    setDefinitionName(
      if (reuse) "WitnessInactiveReusedAutoResize"
      else "WitnessInactiveAutoResize"
    )

    private val elabDepth = depth.asElabInt
    val source = in UInt (1 bits)
    val observed = out Bool ()
    observed := source.orR

    val storage = ElabControl.generateSymbolic(
      elabDepth > 1,
      "GenericExpressionAndStreamTests.scala",
      120
    ) {
      new Area {
        val normalized = UInt(elabDepth.addressWidth bits)
          .setName("inactive_normalized_index")
          .dontSimplifyIt()
        val shared = source.resized
        normalized := shared
        if (reuse) {
          val duplicate = UInt(1 bits)
            .setName("inactive_duplicate_consumer")
            .dontSimplifyIt()
          duplicate := shared
        }
      }
    }
  }

  /** The source and target are equal at WIDTH=3, while WIDTH=4 requires one
    * bit of unsigned narrowing.  Keeping the native `.resized` clone named and
    * non-simplifiable forces the exact materialized boundary used by Stream's
    * symbolic index carriers.
    */
  private final class NativeWitnessEqualNarrowingAutoResize(
      width: HdlInt,
      reuse: Boolean
  ) extends Component {
    setDefinitionName(
      if (reuse) "NativeWitnessEqualNarrowingReusedAutoResize"
      else "NativeWitnessEqualNarrowingAutoResize"
    )

    private val elabWidth = width.asElabInt
    private val sourceWidth =
      elabWidth + elabWidth.elabEq(4).toElabInt
    val source = in UInt (sourceWidth bits)
    val observed = out UInt (elabWidth bits)
    val duplicateObserved = out(UInt(sourceWidth bits))
      .setName("duplicate_observed")
    val carrier = source.resized
      .setName("native_narrowing_resize")
      .dontSimplifyIt()

    observed := carrier
    if (reuse) duplicateObserved := carrier
    else duplicateObserved := 0

    val retainedSourceDriver = carrier.head match {
      case assignment: DataAssignmentStatement => assignment
      case other =>
        throw new IllegalStateException(
          s"native narrowing carrier retained unexpected driver $other"
        )
    }
    val retainedOuter = observed.head match {
      case assignment: DataAssignmentStatement => assignment
      case other =>
        throw new IllegalStateException(
          s"native narrowing target retained unexpected driver $other"
        )
    }
    val retainedDuplicateDriver = duplicateObserved.head match {
      case assignment: DataAssignmentStatement => assignment
      case other =>
        throw new IllegalStateException(
          s"native narrowing duplicate target retained unexpected driver $other"
        )
    }
  }

  test("ordinary assignments muxes arithmetic concatenation slicing and resize reuse native Verilog emission") {
    withTemporaryDirectory { directory =>
      val parameterizedDirectory = directory.resolve("parameterized")
      val concreteDirectory = directory.resolve("concrete")
      Files.createDirectories(parameterizedDirectory)
      Files.createDirectories(concreteDirectory)

      val width = HdlInt.param("WIDTH", default = 8, min = 4, max = 32)
      val parameterized = emitMorph(
        parameterizedDirectory,
        "native_generic_expressions.v",
        new GenericExpressions(width)
      )
      val concrete = emitConcrete(
        concreteDirectory,
        "native_generic_expressions.v",
        new GenericExpressions(width)
      )

      assert(
        nativeModule(concretize(parameterized, "NativeGenericExpressions", width = 8)) ==
          nativeModule(concrete)
      )
      assert(parameterized.contains("parameter integer WIDTH = 8"))
      assert(hasDeclarationWidth(parameterized, "connected", "[WIDTH-1:0]"))
      assert(
        hasDeclarationWidth(
          parameterized,
          "concatenated",
          "[(WIDTH + WIDTH)-1:0]"
        )
      )
      assert(
        hasDeclarationWidth(parameterized, "expandedSum", "[(WIDTH + 1)-1:0]")
      )
      assert(parameterized.contains("[3:0]"))
      assert(parameterized.contains("left ^ right"))
      assert(parameterized.contains("unsignedLeft + unsignedRight"))
      assert(parameterized.contains("select"))
      assert(parameterized.contains(" ? "))
      assert(parameterized.contains("{left"))
      assert(parameterized.contains("right}"))
      assert(!parameterized.contains("parameterizedDesign"))
      assert(!parameterized.contains("ParamRTL"))
    }
  }

  test("the real Stream.m2sPipe path emits one parameterized module and matches its concrete native witness") {
    withTemporaryDirectory { directory =>
      val parameterizedDirectory = directory.resolve("parameterized")
      val concreteDirectory = directory.resolve("concrete")
      Files.createDirectories(parameterizedDirectory)
      Files.createDirectories(concreteDirectory)

      val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 32)
      val parameterized = emitMorph(
        parameterizedDirectory,
        "native_stream_m2s_pipe.v",
        new NativeStreamM2sPipe(width),
        synchronousResetConfig(parameterizedDirectory)
      )
      val concrete = emitConcrete(
        concreteDirectory,
        "native_stream_m2s_pipe.v",
        new NativeStreamM2sPipe(width),
        synchronousResetConfig(concreteDirectory)
      )

      assert(
        nativeModule(concretize(parameterized, "NativeStreamM2sPipe", width = 8)) ==
          nativeModule(concrete)
      )
      assert(parameterized.contains("module NativeStreamM2sPipe #("))
      assert(parameterized.contains("parameter integer WIDTH = 8"))
      assert(hasDeclarationWidth(parameterized, "push_data", "[WIDTH-1:0]"))
      assert(hasDeclarationWidth(parameterized, "pop_data", "[WIDTH-1:0]"))
      assert(parameterized.contains("always @(posedge clk)"))
      assert(parameterized.contains("push_ready"))
      assert(parameterized.contains("pop_ready"))
      assert(parameterized.contains("push_valid"))
      assert(parameterized.contains("pop_valid"))
      assert(parameterized.contains("!"))
      assert(parameterized.contains("<= push_valid"))

      val oracle = read(contractGolden("synchronous_stream_m2s_pipe.v"))
      Vector(
        "always @(posedge clk)",
        "push_ready",
        "pop_ready",
        "push_valid",
        "pop_valid",
        "push_data",
        "pop_data",
        "!"
      ).foreach { token =>
        assert(parameterized.contains(token), s"native m2sPipe output lost '$token'")
        assert(oracle.contains(token), s"Increment 28 oracle lost '$token'")
      }
    }
  }

  test("native UInt auto-resize provenance is exact and generation-local") {
    withTemporaryDirectory { directory =>
      val parameterizedDirectory = directory.resolve("parameterized")
      val concreteDirectory = directory.resolve("concrete")
      Files.createDirectories(parameterizedDirectory)
      Files.createDirectories(concreteDirectory)

      val width = HdlInt.param("WIDTH", default = 3, min = 1, max = 8)
      val parameterized = emitMorph(
        parameterizedDirectory,
        "native_auto_resized_increment.v",
        new NativeAutoResizedIncrement(width)
      )
      val concrete = emitConcrete(
        concreteDirectory,
        "native_auto_resized_increment.v",
        new NativeAutoResizedIncrement(width)
      )

      assert(parameterized.contains("parameter integer WIDTH = 3"))
      assert(hasDeclarationWidth(parameterized, "value", "[WIDTH-1:0]"))
      assert(
        nativeModule(
          concretize(parameterized, "NativeAutoResizedIncrement", width = 3)
        ) == nativeModule(concrete)
      )

      val unsafeConfig = SpinalConfig(targetDirectory = directory.toString)
      unsafeConfig.netlistFileName = "native_unresized_fixed_increment.v"
      val unsafe = MorphVerilog.tryGenerate(unsafeConfig) {
        new NativeUnresizedFixedIncrement(width)
      }
      unsafe match {
        case Left(failure) =>
          assert(
            failure.detail.contains(
              "SPINAL-PARAMETERIZED-VERILOG-ASSIGNMENT-WIDTH-MISMATCH"
            )
          )
        case Right(report) =>
          fail(s"Expected explicit fixed-width crossing failure, received $report")
      }

      val nestedConfig = SpinalConfig(targetDirectory = directory.toString)
      nestedConfig.netlistFileName = "native_unsized_nested_increment.v"
      val nestedUnsafe = MorphVerilog.tryGenerate(nestedConfig) {
        new NativeUnsizedNestedIncrement(width)
      }
      nestedUnsafe match {
        case Left(failure) =>
          assert(
            failure.detail.contains(
              "SPINAL-PARAMETERIZED-VERILOG-ASSIGNMENT-WIDTH-MISMATCH"
            )
          )
        case Right(report) =>
          fail(s"Expected nested witness-width crossing failure, received $report")
      }

      val reusedConfig = SpinalConfig(targetDirectory = directory.toString)
      reusedConfig.netlistFileName = "native_reused_auto_resize.v"
      val reusedUnsafe = MorphVerilog.tryGenerate(reusedConfig) {
        new NativeReusedAutoResize(width)
      }
      reusedUnsafe match {
        case Left(failure) =>
          assert(
            failure.detail.contains(
              "SPINAL-PARAMETERIZED-VERILOG-ASSIGNMENT-WIDTH-MISMATCH"
            )
          )
        case Right(report) =>
          fail(s"Expected reused auto-resize provenance failure, received $report")
      }
    }
  }

  test("native auto-resize provenance is captured before unnamed intermediates are removed") {
    val firstRemoval = new PhaseRemoveIntermediateUnnameds(true)
    val laterRemoval = new PhaseRemoveIntermediateUnnameds(false)
    val phases = ArrayBuffer[Phase](firstRemoval, laterRemoval)

    ExternalParameterizedAutoResize.install(phases)

    assert(phases.size == 3)
    assert(phases(1) eq firstRemoval)
    assert(phases(2) eq laterRemoval)
    assert(!phases.head.isInstanceOf[PhaseRemoveIntermediateUnnameds])
  }

  test("witness-inactive auto-resize provenance retains exact one-use ownership") {
    withTemporaryDirectory { directory =>
      val depth = HdlInt.param("DEPTH", default = 1, min = 1, max = 8)
      val safeConfig = SpinalConfig(targetDirectory = directory.toString)
      safeConfig.netlistFileName = "witness_inactive_auto_resize.v"

      MorphVerilog(safeConfig) {
        new WitnessInactiveAutoResize(depth, reuse = false)
      }
      val safe = new String(
        Files.readAllBytes(directory.resolve(safeConfig.netlistFileName)),
        StandardCharsets.UTF_8
      )
      assert(safe.contains("inactive_normalized_index"), safe)

      val reusedConfig = SpinalConfig(targetDirectory = directory.toString)
      reusedConfig.netlistFileName = "witness_inactive_reused_auto_resize.v"
      MorphVerilog.tryGenerate(reusedConfig) {
        new WitnessInactiveAutoResize(depth, reuse = true)
      } match {
        case Left(failure) =>
          assert(
            failure.detail.contains(
              "SPINAL-PARAMETERIZED-VERILOG-ASSIGNMENT-WIDTH-MISMATCH"
            ),
            failure.detail
          )
        case Right(report) =>
          fail(s"Expected inactive reused auto-resize rejection, received $report")
      }
    }
  }

  test("materialized native auto-resize proves witness-equal narrowing over the complete domain") {
    withTemporaryDirectory { directory =>
      Vector(3, 4).foreach { default =>
        val width = HdlInt.param("WIDTH", default = default, min = 3, max = 4)
        val safeConfig = SpinalConfig(targetDirectory = directory.toString)
        safeConfig.netlistFileName =
          s"native_witness_equal_narrowing_$default.v"

        MorphVerilog(safeConfig) {
          new NativeWitnessEqualNarrowingAutoResize(width, reuse = false)
        }
        val verilog = read(directory.resolve(safeConfig.netlistFileName))
        val compact = verilog.replaceAll("\\s+", "")
        assert(
          verilog.contains(s"parameter integer WIDTH = $default"),
          verilog
        )
        assert(hasDeclarationWidth(verilog, "observed", "[WIDTH-1:0]"), verilog)
        val emittedSourceWidth = declarationWidth(verilog, "source")
        assert(emittedSourceWidth.nonEmpty, verilog)
        assert(
          declarationWidth(verilog, "native_narrowing_resize") == emittedSourceWidth,
          verilog
        )
        assert(emittedSourceWidth.get != "[WIDTH-1:0]", verilog)
        assert(compact.contains("WIDTH)==(4"), verilog)
        assert(compact.contains("assignnative_narrowing_resize=source;"), verilog)
        val observedAssignment =
          if (default == 3) "assignobserved=native_narrowing_resize;"
          else "assignobserved=native_narrowing_resize[WIDTH-1:0];"
        assert(compact.contains(observedAssignment), verilog)
      }

      val width = HdlInt.param("WIDTH", default = 3, min = 3, max = 4)
      val reusedConfig = SpinalConfig(targetDirectory = directory.toString)
      reusedConfig.netlistFileName = "native_witness_equal_narrowing_reused.v"
      MorphVerilog.tryGenerate(reusedConfig) {
        new NativeWitnessEqualNarrowingAutoResize(width, reuse = true)
      } match {
        case Left(failure) =>
          assert(
            failure.detail.contains(
              "SPINAL-PARAMETERIZED-VERILOG-ASSIGNMENT-WIDTH-MISMATCH"
            ),
            failure.detail
          )
        case Right(report) =>
          fail(s"Expected narrowing carrier reuse rejection, received $report")
      }
      assert(!Files.exists(directory.resolve(reusedConfig.netlistFileName)))
    }
  }

  test("materialized native auto-resize rejects stale edges and post-capture reuse") {
    withTemporaryDirectory { directory =>
      Vector("outer", "source_driver", "reuse").foreach { role =>
        val width = HdlInt.param("WIDTH", default = 4, min = 3, max = 4)
        val config = SpinalConfig(targetDirectory = directory.toString)
        config.netlistFileName = s"stale_native_narrowing_$role.v"
        var fixture: NativeWitnessEqualNarrowingAutoResize = null

        config.phasesInserters += { phases: ArrayBuffer[Phase] =>
          val normalized = phases.indexWhere(_.isInstanceOf[PhaseNormalizeNodeInputs])
          require(
            normalized >= 0,
            "stale native auto-resize fixture found no input-normalization phase"
          )
          phases.insert(
            normalized + 1,
            new PhaseMisc {
              override def impl(pc: PhaseContext): Unit = {
                if (pc.config.parameterizedVerilog) {
                  require(fixture != null, "stale native auto-resize fixture was not elaborated")
                  val retained =
                    role match {
                      case "source_driver" => fixture.retainedSourceDriver
                      case "reuse"         => fixture.retainedDuplicateDriver
                      case _               => fixture.retainedOuter
                    }
                  val target = retained.target
                  val source =
                    if (role == "reuse") fixture.carrier
                    else retained.source
                  val parent = retained.parentScope
                  require(
                    parent != null,
                    s"retained native $role edge lost its scope before adversarial replacement"
                  )
                  retained.removeStatement()
                  parent.append(DataAssignmentStatement(target, source))
                }
              }
            }
          )
        }

        MorphVerilog.tryGenerate(config) {
          fixture = new NativeWitnessEqualNarrowingAutoResize(
            width,
            reuse = false
          )
          fixture
        } match {
          case Left(failure) =>
            assert(
              failure.detail.contains(
                "SPINAL-PARAMETERIZED-VERILOG-ASSIGNMENT-WIDTH-MISMATCH"
              ) || failure.detail.contains(
                "SPINAL-PARAMETERIZED-VERILOG-RESIZE-DOMAIN-UNSUPPORTED"
              ),
              failure.detail
            )
          case Right(report) =>
            fail(s"Expected stale native $role rejection, received $report")
        }
        assert(!Files.exists(directory.resolve(config.netlistFileName)))
      }
    }
  }

  test("derived packed widths are proven over the complete parameter domain") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "too_wide_expression.v"
      val result = MorphVerilog.tryGenerate(config) {
        val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 3000)
        new Component {
          setDefinitionName("TooWideExpression")
          val left = in(morphhdl.frontend.Bits(width bits))
          val right = in(morphhdl.frontend.Bits(width bits))
          val concatenated = out(Bits())
          concatenated := left ## right
        }
      }

      result match {
        case Left(failure) =>
          assert(failure.detail.contains("SPINAL-PARAMETERIZED-VERILOG-EXPRESSION-DOMAIN-TOO-LARGE"))
        case Right(report) => fail(s"Expected full-domain width failure, received $report")
      }
      assert(!Files.exists(directory.resolve("too_wide_expression.v")))
    }
  }

  test("fixed slices and resize must be valid for every legal symbolic width") {
    withTemporaryDirectory { directory =>
      val sliceConfig = SpinalConfig(targetDirectory = directory.toString)
      sliceConfig.netlistFileName = "unsafe_slice.v"
      val unsafeSlice = MorphVerilog.tryGenerate(sliceConfig) {
        val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 16)
        new Component {
          setDefinitionName("UnsafeSlice")
          val input = in(morphhdl.frontend.Bits(width bits))
          val output = out(morphhdl.frontend.Bits(4 bits))
          output := input(3 downto 0)
        }
      }
      unsafeSlice match {
        case Left(failure) =>
          assert(failure.detail.contains("SPINAL-PARAMETERIZED-VERILOG-SLICE-DOMAIN-UNSUPPORTED"))
        case Right(report) => fail(s"Expected full-domain slice failure, received $report")
      }

      val resizeConfig = SpinalConfig(targetDirectory = directory.toString)
      resizeConfig.netlistFileName = "unsafe_resize.v"
      val unsafeResize = MorphVerilog.tryGenerate(resizeConfig) {
        val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 16)
        new Component {
          setDefinitionName("UnsafeResize")
          val input = in(morphhdl.frontend.Bits(width bits))
          val output = out(morphhdl.frontend.Bits(12 bits))
          output := input.resize(12)
        }
      }
      unsafeResize match {
        case Left(failure) =>
          assert(failure.detail.contains("SPINAL-PARAMETERIZED-VERILOG-RESIZE-DOMAIN-UNSUPPORTED"))
        case Right(report) => fail(s"Expected domain-crossing resize failure, received $report")
      }
    }
  }

  private def emitMorph(
      directory: Path,
      filename: String,
      component: => Component,
      config: SpinalConfig = null
  ): String = {
    val useConfig =
      if (config == null) SpinalConfig(targetDirectory = directory.toString)
      else config
    useConfig.netlistFileName = filename
    MorphVerilog(useConfig)(component)
    read(directory.resolve(filename))
  }

  private def emitConcrete(
      directory: Path,
      filename: String,
      component: => Component,
      config: SpinalConfig = null
  ): String = {
    val useConfig =
      if (config == null) SpinalConfig(targetDirectory = directory.toString)
      else config
    useConfig.netlistFileName = filename
    SpinalVerilog(useConfig)(component)
    read(directory.resolve(filename))
  }

  private def synchronousResetConfig(directory: Path): SpinalConfig =
    SpinalConfig(
      targetDirectory = directory.toString,
      defaultConfigForClockDomains = ClockDomainConfig(
        clockEdge = RISING,
        resetKind = SYNC,
        resetActiveLevel = HIGH
      )
    )

  private def hasDeclarationWidth(
      verilog: String,
      name: String,
      range: String
  ): Boolean = {
    val pattern =
      (java.util.regex.Pattern.quote(range) + "\\s+" +
        java.util.regex.Pattern.quote(name) + "(?=\\s*[,;])").r
    pattern.findFirstIn(verilog).nonEmpty
  }

  private def declarationWidth(verilog: String, name: String): Option[String] = {
    val pattern =
      ("(?m)^\\s*(?:(?:input|output)\\s+wire|wire|reg)\\s+" +
        "(\\[[^\\r\\n]+?\\])\\s+" +
        java.util.regex.Pattern.quote(name) + "(?=\\s*[,;])").r
    pattern
      .findFirstMatchIn(verilog)
      .map(_.group(1).replaceAll("\\s+", ""))
  }

  private def nativeModule(verilog: String): String =
    "(?m)^module\\s".r
      .findFirstMatchIn(verilog)
      .map(module => verilog.substring(module.start))
      .getOrElse(verilog)

  private def concretize(verilog: String, moduleName: String, width: Int): String = {
    val header: Regex =
      ("(?s)module " + java.util.regex.Pattern.quote(moduleName) + " #\\(\\n.*?\\n\\) \\(").r
    header
      .replaceFirstIn(verilog, s"module $moduleName (")
      .replace("[(WIDTH + WIDTH)-1:0]", s"[${width * 2 - 1}:0]")
      .replace("[(WIDTH + 1)-1:0]", s"[$width:0]")
      .replace("[WIDTH-1:0]", s"[${width - 1}:0]")
  }

  private def read(path: Path): String =
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

  private def contractGolden(fileName: String): Path = {
    val relativePath = Paths.get("morphhdl", "examples", "contracts", fileName)
    val codeSource =
      Option(getClass.getProtectionDomain)
        .flatMap(domain => Option(domain.getCodeSource))
        .flatMap(source => Option(source.getLocation))
        .filter(_.getProtocol == "file")
        .map(location => Paths.get(location.toURI))
        .toVector
    val classPath =
      sys.props
        .get("java.class.path")
        .toVector
        .flatMap(_.split(File.pathSeparator).toVector)
        .filter(_.nonEmpty)
        .map(Paths.get(_))
    val searchRoots = codeSource ++ Vector(Paths.get("")) ++ classPath
    searchRoots.iterator
      .flatMap(pathAndAncestors)
      .map(_.toAbsolutePath.normalize.resolve(relativePath))
      .find(path => Files.isRegularFile(path))
      .getOrElse(throw new java.nio.file.NoSuchFileException(relativePath.toString))
  }

  private def pathAndAncestors(path: Path): Iterator[Path] =
    Iterator
      .iterate(Option(path.toAbsolutePath.normalize))(
        _.flatMap(current => Option(current.getParent))
      )
      .takeWhile(_.nonEmpty)
      .map(_.get)

  private def withTemporaryDirectory(body: Path => Unit): Unit = {
    val directory = Files.createTempDirectory("morphhdl-generic-expression-test-")
    try body(directory)
    finally deleteTree(directory)
  }

  private def deleteTree(root: Path): Unit =
    if (Files.exists(root)) {
      val stream = Files.walk(root)
      try {
        stream.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach { path =>
          Files.deleteIfExists(path)
        }
      } finally stream.close()
    }
}
