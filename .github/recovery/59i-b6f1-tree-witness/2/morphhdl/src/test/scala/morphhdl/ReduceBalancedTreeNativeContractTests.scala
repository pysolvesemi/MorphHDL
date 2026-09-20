package morphhdl

import org.scalatest.funsuite.AnyFunSuite
import spinal.lib.TraversableOnceAnyPimped
import scala.collection.mutable.ArrayBuffer

/** The actual native helper is the subject, not a newly authored tree. */
class ReduceBalancedTreeNativeContractTests extends AnyFunSuite {
  private case class Trace(leaves: Vector[Int], depth: Int, bridges: Vector[Vector[Int]])

  test("empty native collections fail rather than inventing an identity") {
    intercept[AssertionError] {
      new TraversableOnceAnyPimped[Int](Vector.empty).reduceBalancedTree(_ + _)
    }
  }

  test("a singleton is returned by identity without invoking either callback") {
    val element = new Object
    val result = new TraversableOnceAnyPimped[Object](Vector(element)).reduceBalancedTree(
      (_, _) => fail("singleton invoked operator"),
      (_, _) => fail("singleton invoked level bridge")
    )
    assert(result eq element)
  }

  test("two elements invoke one operator and bridge at level zero") {
    val events = ArrayBuffer.empty[String]
    val result = new TraversableOnceAnyPimped[Int](Vector(2, 3)).reduceBalancedTree(
      (a, b) => { events += "op"; a + b },
      (value, level) => { events += s"bridge:$level"; value }
    )
    assert(result == 5)
    assert(events.toVector == Vector("op", "bridge:0"))
  }

  test("odd tails are bridged but never padded or passed through the operator") {
    val levels = ArrayBuffer.empty[Int]
    val result = new TraversableOnceAnyPimped[String](Vector("a", "b", "c", "d", "e"))
      .reduceBalancedTree(
        (a, b) => s"($a+$b)",
        (value, level) => { levels += level; s"$value@$level" }
      )
    assert(result == "(((a+b)@0+(c+d)@0)@1+e@0@1)@2")
    assert(levels.toVector == Vector(0, 0, 0, 1, 1, 2))
  }

  test("ordered coverage N-minus-one operators and logarithmic depth hold across boundaries") {
    for (n <- 1 to 257) {
      var calls = 0
      val nodes = Vector.tabulate(n)(i => Trace(Vector(i), 0, Vector(Vector.empty)))
      val result = new TraversableOnceAnyPimped[Trace](nodes).reduceBalancedTree(
        (a, b) => {
          calls += 1
          Trace(a.leaves ++ b.leaves, 1 + math.max(a.depth, b.depth), a.bridges ++ b.bridges)
        },
        (value, level) => value.copy(bridges = value.bridges.map(_ :+ level))
      )
      val depth = BigInt(n - 1).bitLength
      assert(result.leaves == (0 until n).toVector)
      assert(calls == n - 1)
      assert(result.depth == depth)
      assert(result.bridges.forall(_ == (0 until depth).toVector))
    }
  }

  test("the concrete generic callback is not restricted to hardware data") {
    val result = new TraversableOnceAnyPimped[Set[Int]](
      Vector(Set(1, 2), Set(2, 3), Set(4), Set(5, 6), Set(7))
    ).reduceBalancedTree(_ union _)
    assert(result == (1 to 7).toSet)
  }

  test("existing concrete non-associative callbacks remain available unchanged") {
    val result = new TraversableOnceAnyPimped[Int](Vector(1, 2, 3, 4, 5))
      .reduceBalancedTree((a, b) => a - b, (value, level) => value + 10 * (level + 1))
    assert(result == 15)
  }

  test("native callback invocation order is deterministic") {
    def execute(): Vector[String] = {
      val events = ArrayBuffer.empty[String]
      new TraversableOnceAnyPimped[Int]((1 to 17).toVector).reduceBalancedTree(
        (a, b) => { events += s"op:$a:$b"; a + b },
        (value, level) => { events += s"bridge:$level:$value"; value }
      )
      events.toVector
    }
    assert(execute() == execute())
  }
}
