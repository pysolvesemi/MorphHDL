package spinal.core.internals

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib._
import morphhdl.frontend.HdlInt

class TypedBalancedReductionFreshnessTests extends AnyFunSuite {
  test("shared prerequisite checks expire on normal and exceptional traversal exits") {
    val identity = new Object
    var checks = 0
    def check(): Unit = TypedBalancedReductionFreshness.once(identity) { checks += 1 }
    TypedBalancedReductionFreshness.read { check(); check() }
    assert(checks == 1)
    check()
    assert(checks == 2)
    intercept[IllegalArgumentException] {
      TypedBalancedReductionFreshness.read { check(); throw new IllegalArgumentException("end") }
    }
    check()
    assert(checks == 4)
    var failedChecks = 0
    TypedBalancedReductionFreshness.read {
      for (_ <- 0 until 2) intercept[IllegalArgumentException] {
        TypedBalancedReductionFreshness.once(newFailureIdentity) {
          failedChecks += 1
          throw new IllegalArgumentException("failed validation")
        }
      }
    }
    assert(failedChecks == 2)
  }
  private val newFailureIdentity = new Object

  test("validated operator proof rejects a subsequently appended driver and recovers after removal") {
    val width = HdlInt.param("WIDTH", 5, 1, 8)
    val count = HdlInt.param("COUNT", 3, 1, 3)
    SpinalConfig(targetDirectory = Files.createTempDirectory("freshness-drivers-").toString,
      headerWithDate = false, headerWithRepoHash = false).generateVerilog(new Component {
      val words = Vec(UInt(width bits), count)
      words.vec.foreach(_ := 0)
      val native: ElabBalancedReduction.Native[UInt] = (values, operation, bridge) =>
        new TraversableOnceAnyPimped[UInt](values).reduceBalancedTree(operation, bridge)
      val captured = TypedBalancedReductionCapture(words, (a: UInt, b: UInt) => a + b,
        (value: UInt, _: Int) => value, native)
      val callback = captured.rows.head.operator.get
      val proof = TypedBalancedReductionOperatorReplay.certify(callback)
      proof.validateFreshness()
      val result = callback.result.asInstanceOf[UInt]
      result := U(0, 5 bits)
      val added = result.dlcLast.asInstanceOf[DataAssignmentStatement]
      for (_ <- 0 until 2) {
        val error = intercept[IllegalArgumentException](proof.validateFreshness())
        assert(error.getMessage.contains("REPLAY-STALE-GRAPH"), error.getMessage)
      }
      added.removeStatement()
      proof.validateFreshness()
      // Rebuilding inventories must also observe a driver's target changing
      // without inserting or deleting its statement from the owner scope.
      val original = callback.assignments.head
      val target = original.target
      original.target = words.vec(2)
      val error = intercept[IllegalArgumentException](proof.validateFreshness())
      assert(error.getMessage.contains("REPLAY-STALE-GRAPH"), error.getMessage)
      original.target = target
      proof.validateFreshness()
    })
  }
}
