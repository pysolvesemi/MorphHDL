package spinal.core.internals

import java.nio.file.Files
import java.nio.charset.StandardCharsets
import morphhdl.MorphVerilog
import morphhdl.frontend.HdlInt
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib._

private[internals] final class BalancedPublicationMutationFixture(mode: Int, width: HdlInt,
    count: HdlInt) extends Component {
  val words = in(Vec(UInt(width bits), count))
  val output = out(UInt(width bits))
  val result = words.reduceBalancedTree((a: UInt, b: UInt) => a + b)
  output := result
  if (mode == 1 || mode == 3) {
    var mutated = 0
    dslBody.walkStatements {
      case assignment: DataAssignmentStatement => assignment.source match {
        case operation: Operator.UInt.Add =>
          if (mode == 1) operation.right = operation.left
          else operation.left.asInstanceOf[BaseType].allowSimplifyIt()
          mutated += 1
        case _ =>
      }
      case _ =>
    }
    Predef.require(mutated > 0, "mutation control did not find a native replay operator")
  }
  if (mode == 2) {
    result.removeAssignments()
    result := words.vec.head
  }
}

private[internals] object BalancedPublicationSafetyState { var callbacks = 0 }

private[internals] final class BalancedPublicationStatefulFixture extends Component {
  val words = in(Vec(UInt(5 bits), HdlInt.param("COUNT", 1, 1, 5)))
  val output = out(UInt(5 bits))
  output := words.reduceBalancedTree((a: UInt, b: UInt) => {
    BalancedPublicationSafetyState.callbacks += 1
    a + b
  })
}

private[internals] final class BalancedPublicationSingletonFixture extends Component {
  val words = in(Vec(UInt(5 bits), HdlInt.param("COUNT", 1, 1, 1)))
  val output = out(UInt(5 bits))
  output := words.reduceBalancedTree((a: UInt, b: UInt) => {
    BalancedPublicationSafetyState.callbacks += 1
    a - b
  }, (value: UInt, _: Int) => {
    BalancedPublicationSafetyState.callbacks += 1
    RegNext(value)
  })
}

private[internals] final class BalancedPublicationCollisionFixture extends Component {
  setDefinitionName("BalancedPublicationCollision")
  val words = in(Vec(UInt(5 bits), HdlInt.param("COUNT", 1, 1, 5)))
  val collision = in(UInt(5 bits)).setName("morphhdl_balanced_1_stage_0")
  collision.dontSimplifyIt()
  val collisionOut = out(UInt(5 bits)).setName("collisionOut")
  val output = out(UInt(5 bits))
  collisionOut := collision
  output := words.reduceBalancedTree((a: UInt, b: UInt) => a + b)
}

private[internals] final class BalancedPublicationNestedOwnerFixture(count: HdlInt) extends Component {
  val words = in(Vec(UInt(5 bits), count))
  val output = out(UInt(5 bits))
  ElabControl.selectSymbolic(count.asElabInt > 2, "balanced-nested-owner", 1) {
    output := words.reduceBalancedTree((a: UInt, b: UInt) => a + b)
  } {
    output := words.vec.head
  }
}

class TypedBalancedReductionPublicationSafetyTests extends AnyFunSuite {
  private def emit(component: => Component): Unit = {
    MorphVerilog(SpinalConfig(targetDirectory = Files.createTempDirectory("balanced-safety-").toString))(component)
    ()
  }

  private def messages(error: Throwable): String = {
    val values = scala.collection.mutable.ArrayBuffer.empty[String]
    var next = error
    while (next != null) { values += Option(next.getMessage).getOrElse(""); next = next.getCause }
    values.mkString("\n")
  }

  test("mutating a native replay operator before the handoff prevents publication") {
    val error = intercept[Exception](emit(new BalancedPublicationMutationFixture(1,
      HdlInt.param("WIDTH", 5, 1, 8), HdlInt.param("COUNT", 1, 1, 5))))
    assert(messages(error).contains("GRAPH-CHANGED") ||
      messages(error).contains("GRAPH-REPLAY-STALE"), messages(error))
  }

  test("replacing the public reduction result anchor cannot silently change its meaning") {
    val error = intercept[Exception](emit(new BalancedPublicationMutationFixture(2,
      HdlInt.param("WIDTH", 5, 1, 8), HdlInt.param("COUNT", 1, 1, 5))))
    assert(messages(error).contains("ANCHOR") || messages(error).contains("GRAPH"), messages(error))
  }

  test("host-state callback rejection precedes its first native invocation") {
    BalancedPublicationSafetyState.callbacks = 0
    val error = intercept[Exception](emit(new BalancedPublicationStatefulFixture))
    assert(messages(error).contains("CALLBACK-UNSUPPORTED"), messages(error))
    assert(BalancedPublicationSafetyState.callbacks == 0)
  }

  test("singleton-only typed domains call neither callback or impose replay restrictions") {
    BalancedPublicationSafetyState.callbacks = 0
    emit(new BalancedPublicationSingletonFixture)
    assert(BalancedPublicationSafetyState.callbacks == 0)
  }

  test("generated balanced stage names cannot collide with ordinary user signals") {
    val directory = Files.createTempDirectory("balanced-name-collision-")
    MorphVerilog(SpinalConfig(targetDirectory = directory.toString))(new BalancedPublicationCollisionFixture)
    val text = new String(Files.readAllBytes(directory.resolve("BalancedPublicationCollision.v")), StandardCharsets.UTF_8)
    assert(text.contains("morphhdl_balanced_1_stage_0"), text)
    assert(text.contains("morphhdl_balanced_1_1_stage_0"), text)
    assert(text.contains("assign collisionOut = morphhdl_balanced_1_stage_0;"), text)
    assert(text.linesIterator.count(_.trim.startsWith("module BalancedPublicationCollision")) == 1, text)
  }

  test("uncertified outer generate ownership prevents module-scope tree publication") {
    val error = intercept[Exception](emit(new BalancedPublicationNestedOwnerFixture(
      HdlInt.param("COUNT", 1, 1, 5))))
    assert(messages(error).contains("OWNER"), messages(error))
  }

  test("removing a native template operand preservation policy prevents publication") {
    val error = intercept[Exception](emit(new BalancedPublicationMutationFixture(3,
      HdlInt.param("WIDTH", 5, 1, 8), HdlInt.param("COUNT", 1, 1, 5))))
    assert(messages(error).contains("ANCHOR-POLICY"), messages(error))
  }
}
