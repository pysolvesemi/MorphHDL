package spinal.core.internals

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer
import morphhdl.MorphVerilog
import morphhdl.frontend.HdlInt
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib._

final case class BalancedBridgeZeroNested(inner: ElabInt) extends Bundle {
  val lanes = Vec(Bool(), inner)
}

private object TypedBalancedReductionBridgeZeroFixture {
  final class RegisteredNested extends Component {
    val inner = HdlInt.param("INNER", 1, 1, 2).asElabInt
    val count = HdlInt.param("COUNT", 1, 1, 3).asElabInt
    val source = in(Vec(BalancedBridgeZeroNested(inner), count))
    val result = out(BalancedBridgeZeroNested(inner))
    result := source.reduceBalancedTree((left, right) => left, (value, level) => {
      val registered = cloneOf(value)
      registered.setAsReg()
      registered := value
      registered.init(registered.getZero)
      registered
    })
  }
}

class TypedBalancedReductionBridgeZeroTests extends AnyFunSuite {
  private def currentDeclarations(owner: Component): Vector[BaseType] = {
    val result = ArrayBuffer.empty[BaseType]
    owner.dslBody.walkStatements {
      case value: BaseType => result += value
      case _ =>
    }
    result.toVector
  }

  private def build(body: (Vec[UInt], Component) => Unit): Unit = {
    val directory = Files.createTempDirectory("bridge-zero-construction-")
    try SpinalConfig(targetDirectory = directory.toString, headerWithDate = false).generateVerilog {
      new Component {
        val input = in(Bool())
        val output = out(Bool())
        output := input
        val width = HdlInt.param("WIDTH", 5, 1, 8).asElabInt
        val count = HdlInt.param("INNER", 1, 1, 3).asElabInt
        val source = Vec(UInt(width bits), count)
        body(source, this)
      }
    } finally {
      val stream = Files.walk(directory)
      try stream.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists(_))
      finally stream.close()
    }
  }

  private def authorized[A](source: Data, owner: Component)(body: => A): A =
    TypedBalancedReductionBridgeZero.withConstruction(source, owner, currentDeclarations(owner))(body)

  private def rejects(body: => Any): Unit = {
    val error = intercept[IllegalArgumentException](body)
    assert(error.getMessage.contains("MORPH-REDUCE-BALANCED-BRIDGE-ZERO-AUTHORITY"), error.getMessage)
  }

  test("native clone zero construction retains the exact symbolic Vec shape") {
    build { (source, owner) =>
      authorized(source, owner) {
        val receiver = cloneOf(source)
        val result = receiver.getZero
        assert(result ne receiver)
        assert((result: Data).flatten.forall(leaf => !(receiver: Data).flatten.exists(_ eq leaf)))
        assert(ParameterizedVec.shapeOf(result).get.depth eq ParameterizedVec.shapeOf(source).get.depth)
      }
    }
  }

  test("public symbolic Vec getZero remains unsupported outside the bridge scope") {
    build { (source, _) =>
      val error = intercept[ParameterizedVerilogException](cloneOf(source).getZero)
      assert(error.code == "SPINAL-ELAB-VEC-OPERATION-UNSUPPORTED")
    }
  }

  test("a receiver created before bridge entry cannot reuse native clone authority") {
    build { (source, owner) =>
      val receiver = cloneOf(source)
      authorized(source, owner) { rejects(receiver.getZero) }
    }
  }

  test("a same-shaped clone from a foreign source has no bridge operand ancestry") {
    build { (source, owner) =>
      val foreign = cloneOf(source)
      authorized(source, owner) { rejects(cloneOf(foreign).getZero) }
    }
  }

  test("copying retained shape metadata does not mint a native clone event") {
    build { (source, owner) =>
      authorized(source, owner) {
        val depth = ElabInt.fromExpression(ParameterizedVec.shapeOf(source).get.depth)
        val width = ElabInt.fromExpression(ParameterizedWidth.expressionOf(source.vec.head).get)
        val receiver = Vec(UInt(width bits), depth)
        ParameterizedVec.copyShape(source, receiver)
        rejects(receiver.getZero)
      }
    }
  }

  test("a changed same-default width function invalidates retained clone lineage") {
    build { (source, owner) =>
      authorized(source, owner) {
        val receiver = cloneOf(source)
        val leaf = receiver.vec.head
        val original = ParameterizedWidth.expressionOf(leaf).get
        val changed = ElaborationWidthAuthority.subtract(ElabInt.literal(10).expression, original)
        assert(changed.default == original.default)
        ParameterizedWidth.retainNativeMuxWidth(leaf, Some(changed))
        try rejects(receiver.getZero)
        finally ParameterizedWidth.retainNativeMuxWidth(leaf, Some(original))
      }
    }
  }

  test("one bridge invocation cannot reuse a zero receiver") {
    build { (source, owner) =>
      authorized(source, owner) {
        val receiver = cloneOf(source)
        receiver.getZero
        rejects(receiver.getZero)
      }
    }
  }

  test("rejected bridge construction restores the original public guard") {
    build { (source, owner) =>
      val marker = new IllegalStateException("stop this exact bridge")
      assert(intercept[IllegalStateException] {
        authorized(source, owner) { cloneOf(source); throw marker }
      } eq marker)
      val error = intercept[ParameterizedVerilogException](cloneOf(source).getZero)
      assert(error.code == "SPINAL-ELAB-VEC-OPERATION-UNSUPPORTED")
    }
  }

  test("zero construction is not admitted in an operator callback") {
    val operator: (Vec[UInt], Vec[UInt]) => Vec[UInt] = (left, right) => cloneOf(left).getZero
    val error = intercept[IllegalArgumentException] {
      TypedBalancedReductionCallbackPolicy.requireSupportedOperator(operator)
    }
    assert(error.getMessage.contains("MORPH-REDUCE-BALANCED-CALLBACK-UNSUPPORTED"), error.getMessage)
  }

  test("a callback cannot manufacture clone observations or install a zero backend") {
    val observe: (Vec[UInt], Int) => Vec[UInt] = (value, level) =>
      NativeWidthProvenance.retainCloneShape(value, value)
    val install: (Vec[UInt], Int) => Vec[UInt] = (value, level) =>
      NativeVecZeroConstruction.withBackend(null)(value)
    Vector(observe, install).foreach { callback =>
      val error = intercept[IllegalArgumentException] {
        TypedBalancedReductionCallbackPolicy.requireSupportedBridge(callback)
      }
      assert(error.getMessage.contains("MORPH-REDUCE-BALANCED-CALLBACK-UNSUPPORTED"), error.getMessage)
    }
  }

  test("audited recursive zero use follows helpers and excludes scalar-only initialization") {
    def nestedZero(value: BalancedBridgeZeroNested, level: Int): BalancedBridgeZeroNested =
      cloneOf(value).getZero
    val helper: (BalancedBridgeZeroNested, Int) => BalancedBridgeZeroNested =
      (value, level) => nestedZero(value, level)
    val unconstrained: (UInt, Int) => UInt =
      (value, level) => cloneOf(value).getZeroUnconstrained
    val identity: (BalancedBridgeZeroNested, Int) => BalancedBridgeZeroNested =
      (value, level) => value
    val scalarZero: (UInt, Int) => UInt = (value, level) => cloneOf(value).getZero
    assert(TypedBalancedReductionCallbackPolicy.bridgeUsesNativeVecZero(helper))
    assert(!TypedBalancedReductionCallbackPolicy.bridgeUsesNativeVecZero(unconstrained))
    assert(!TypedBalancedReductionCallbackPolicy.bridgeUsesNativeVecZero(identity))
    assert(!TypedBalancedReductionCallbackPolicy.bridgeUsesNativeVecZero(scalarZero))
    val unsupported: (Vec[UInt], Int) => Vec[UInt] = (value, level) =>
      NativeWidthProvenance.retainCloneShape(value, value).getZero
    val error = intercept[IllegalArgumentException] {
      TypedBalancedReductionCallbackPolicy.bridgeUsesNativeVecZero(unsupported)
    }
    assert(error.getMessage.contains("MORPH-REDUCE-BALANCED-CALLBACK-UNSUPPORTED"), error.getMessage)
  }

  test("registered nested Vec reduction publishes through the native zero algorithm") {
    val directory = Files.createTempDirectory("bridge-zero-nested-publication-")
    try {
      val config = SpinalConfig(targetDirectory = directory.toString, headerWithDate = false)
      config.netlistFileName = "nested.v"
      MorphVerilog(config)(new TypedBalancedReductionBridgeZeroFixture.RegisteredNested)
      val rtl = new String(Files.readAllBytes(directory.resolve("nested.v")), StandardCharsets.UTF_8)
      assert(rtl.contains("INNER") && rtl.contains("COUNT"), rtl)
      assert(rtl.contains("posedge") && rtl.contains("reset"), rtl)
    } finally {
      val stream = Files.walk(directory)
      try stream.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists(_))
      finally stream.close()
    }
  }

  test("ordinary scoped record bridges preserve native selector clone factories") {
    val directory = Files.createTempDirectory("bridge-zero-ordinary-record-publication-")
    try {
      val config = SpinalConfig(targetDirectory = directory.toString, headerWithDate = false)
      config.netlistFileName = "ordinary.v"
      MorphVerilog(config) {
        new BalancedCombinedScopedRecords(HdlInt.param("WIDTH", 5, 1, 32),
          HdlInt.param("TAG_WIDTH", 3, 1, 32), HdlInt.param("COORD_WIDTH", 7, 1, 32),
          HdlInt.param("COUNT", 1, 1, 17), HdlInt.param("MODE", 0, 0, 1), "OrdinaryRecordBridge")
      }
      val rtl = new String(Files.readAllBytes(directory.resolve("ordinary.v")), StandardCharsets.UTF_8)
      Vector("begin : g_registered_min", "begin : g_registered_max", "always @(posedge clk)",
        "delayed_key", "delayed_tag", "delayed_x", "delayed_y").foreach { expected =>
        assert(rtl.contains(expected), s"missing $expected\n$rtl")
      }
    } finally {
      val stream = Files.walk(directory)
      try stream.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists(_))
      finally stream.close()
    }
  }
}
