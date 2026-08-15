package morphhdl

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import scala.collection.JavaConverters._
import scala.util.matching.Regex

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib._

import morphhdl.frontend.HdlInt

class GenericExpressionAndStreamTests extends AnyFunSuite {
  private final class GenericExpressions(width: HdlInt) extends Component {
    setDefinitionName("NativeGenericExpressions")

    val left = in(Bits(width bits))
    val right = in(Bits(width bits))
    val unsignedLeft = in(UInt(width bits))
    val unsignedRight = in(UInt(width bits))
    val select = in(Bool())

    val connected = out(Bits(width bits))
    val muxed = out(Bits(width bits))
    val xored = out(Bits(width bits))
    val sum = out(UInt(width bits))
    val expandedSum = out(UInt())
    val concatenated = out(Bits())
    val sliced = out(Bits(4 bits))
    val resized = out(Bits(4 bits))

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
    val push_data = in(Bits(width bits))
    val pop_valid = out(Bool())
    val pop_ready = in(Bool())
    val pop_data = out(Bits(width bits))

    val push = Stream(Bits(width bits))
    push.valid := push_valid
    push.payload := push_data
    push_ready := push.ready

    val piped = push.m2sPipe()
    pop_valid := piped.valid
    piped.ready := pop_ready
    pop_data := piped.payload
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
      assert(parameterized.contains("assign push_payload = push_data"))
      assert(parameterized.contains("<= push_payload"))

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

  test("derived packed widths are proven over the complete parameter domain") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "too_wide_expression.v"
      val result = MorphVerilog.tryGenerate(config) {
        val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 3000)
        new Component {
          setDefinitionName("TooWideExpression")
          val left = in(Bits(width bits))
          val right = in(Bits(width bits))
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
          val input = in(Bits(width bits))
          val output = out(Bits(4 bits))
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
          val input = in(Bits(width bits))
          val output = out(Bits(12 bits))
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