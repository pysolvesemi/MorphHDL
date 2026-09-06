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

private[internals] final class BalancedPublicationNestedLabelCollisionFixture(width: HdlInt,
    inner: HdlInt, one: HdlInt, count: HdlInt) extends Component {
  setDefinitionName("BalancedPublicationNestedLabelCollision")
  val words = in(Vec(BalancedCompositeCountedRecord(width, width, width, width, inner, one, one),
    count)).setName("words")
  val result = out(BalancedCompositeCountedRecord(width, width, width, width, inner, one, one)).setName("result")
  // Leaf six is samples(1).unsigned, which is absent when INNER is one.
  val present = in(Bool()).setName("morphhdl_balanced_1_result_leaf_6_present")
  val absent = in(Bool()).setName("morphhdl_balanced_1_result_leaf_6_absent")
  val published = in(Bool()).setName("morphhdl_balanced_1_result_leaf_6_published")
  val presentOut = out(Bool()).setName("presentOut")
  val absentOut = out(Bool()).setName("absentOut")
  val publishedOut = out(Bool()).setName("publishedOut")
  presentOut := present
  absentOut := absent
  publishedOut := published
  result := words.reduceBalancedTree((a: BalancedCompositeCountedRecord, b: BalancedCompositeCountedRecord) =>
    Mux(a.key <= b.key, a, b))
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
    assert(messages(error).contains("GRAPH-CHANGED"), messages(error))
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

  test("nested result generate labels cannot collide with live user signal names") {
    val directory = Files.createTempDirectory("balanced-nested-label-collision-")
    MorphVerilog(SpinalConfig(targetDirectory = directory.toString))(new BalancedPublicationNestedLabelCollisionFixture(
      HdlInt.literal(5), HdlInt.param("INNER", 1, 1, 2), HdlInt.literal(1), HdlInt.param("COUNT", 1, 1, 2)))
    val text = new String(Files.readAllBytes(directory.resolve("BalancedPublicationNestedLabelCollision.v")), StandardCharsets.UTF_8)
    val base = "morphhdl_balanced_1_result_leaf_6"
    val collisions = Vector("present", "absent", "published").map(suffix => base + "_" + suffix)
    val labels = """\bbegin\s*:\s*([A-Za-z_][A-Za-z0-9_$]*)""".r.findAllMatchIn(text).map(_.group(1)).toVector
    for (suffix <- Vector("present", "absent", "published")) {
      assert(text.contains(s"assign ${suffix}Out = ${base}_$suffix;"), text)
    }
    assert(labels.toSet.intersect(collisions.toSet).isEmpty, text)
    // The two conditional branches must exist, and their allocated names must differ.
    for (suffix <- Vector("present", "absent")) {
      val allocated = labels.filter(_.contains(s"_result_leaf_6_$suffix"))
      assert(allocated.nonEmpty, text)
      assert(allocated.forall(_ != s"${base}_$suffix"), text)
    }
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
