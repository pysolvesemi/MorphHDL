package spinal.core.internals

import java.nio.file.Files
import morphhdl.MorphVerilog
import morphhdl.frontend.HdlInt
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib._

final case class BalancedConsumedProbeRecord(depth: ElabInt) extends Bundle {
  val flag = Bool()
  val payload = Vec(Bits(4 bits), depth)
}

class TypedBalancedReductionConsumedProbeTests extends AnyFunSuite {
  private def generate(inspect: (Component, Vec[Bits]) => Unit): Unit = {
    val directory = Files.createTempDirectory("consumed-native-probes-")
    MorphVerilog(SpinalConfig(targetDirectory = directory.toString,
      headerWithDate = false)) {
      new Component {
        val depth = HdlInt.param("INNER", 1, 1, 2).asElabInt
        val values = in(Vec(BalancedConsumedProbeRecord(depth), HdlInt.param("COUNT", 1, 1, 3)))
        val reduced = values.reduceBalancedTree((a: BalancedConsumedProbeRecord, b: BalancedConsumedProbeRecord) => {
          val result = cloneOf(a)
          result.flag := a.flag | b.flag
          result.payload := a.payload
          result
        })
        val probes = ParameterizedVec.retainedVectorsOf(this).filter(TypedBalancedReductionBackend.ownsConsumedProbe)
        assert(probes.nonEmpty, "certified native callbacks produced no consumed Vec receipt")
        val probe = probes.head.asInstanceOf[Vec[Bits]]
        assert(probe.asInstanceOf[Data].flatten.forall(_.parentScope == null), "probe leaves were not exactly consumed")
        inspect(this, probe)
        val resultFlag = out Bool()
        val resultPayload = out(Vec(Bits(4 bits), depth))
        resultFlag := reduced.flag
        resultPayload := reduced.payload
      }
    }
  }

  private def rejected(action: (Component, Vec[Bits]) => Unit): Unit = {
    val failure = intercept[Exception](generate(action))
    val messages = Iterator.iterate(failure: Throwable)(_.getCause).takeWhile(_ != null)
      .map(value => Option(value.getMessage).getOrElse("")).mkString("\n")
    assert(messages.contains("CONSUMED-PROBE-CHANGED"), messages)
  }

  test("consumed probes retain native clone metadata without copying removal authority") {
    generate { (owner, probe) =>
      val shape = ParameterizedVec.shapeOf(probe).get
      val copied = cloneOf(probe)
      assert(ParameterizedVec.shapeOf(copied).get.depth eq shape.depth)
      assert(!TypedBalancedReductionBackend.ownsConsumedProbe(copied))
      // Copying even the private receipt record cannot transfer its original
      // Vec identity. Reflection is adversarial test setup, never issuance.
      val storage = owner.userCache.values.collectFirst {
        case value: AnyRef if value.getClass.getName.endsWith("TypedBalancedReductionBackend$Storage") => value
      }.get
      val field = storage.getClass.getDeclaredFields.find(_.getName == "consumedProbeVectors").get
      field.setAccessible(true)
      val receipts = field.get(storage).asInstanceOf[java.util.IdentityHashMap[Vec[_], AnyRef]]
      receipts.put(copied, receipts.get(probe))
      try {
        val failure = intercept[IllegalArgumentException](TypedBalancedReductionBackend.ownsConsumedProbe(copied))
        assert(failure.getMessage.contains("CONSUMED-PROBE-CHANGED"), failure.getMessage)
      } finally receipts.remove(copied)
      assert(TypedBalancedReductionBackend.ownsConsumedProbe(probe))
      copied.asInstanceOf[Data].flatten.foreach(_.removeStatement())
    }
  }

  test("consumed probe receipts freeze the native clone factory identity") {
    rejected { (_, probe) =>
      probe._dataType = HardType(Bits(4 bits))
      TypedBalancedReductionBackend.ownsConsumedProbe(probe)
    }
  }

  test("consumed probe receipts reject new exact native operation evidence") {
    rejected { (_, probe) =>
      probe(0)
      TypedBalancedReductionBackend.ownsConsumedProbe(probe)
    }
  }

  test("consumed probe receipts reject resurrection of an exact removed carrier") {
    rejected { (owner, probe) =>
      owner.dslBody.append(probe.vec.head)
      TypedBalancedReductionBackend.ownsConsumedProbe(probe)
    }
  }

  test("consumed probe receipts reject a direct scalar driver without a Vec operation") {
    rejected { (_, probe) =>
      probe.vec.head := B(0, 4 bits)
      TypedBalancedReductionBackend.ownsConsumedProbe(probe)
    }
  }

  test("a direct live read cannot publish a removed native probe carrier") {
    val failure = intercept[Exception] {
      generate { (_, probe) =>
        val leaked = out(Bits(4 bits)).setName("leaked_probe")
        leaked := probe.vec.head
      }
    }
    val messages = Iterator.iterate(failure: Throwable)(_.getCause).takeWhile(_ != null)
      .map(value => Option(value.getMessage).getOrElse("")).mkString("\n")
    assert(messages.contains("OLD NETLIST RE-USED / DANGLING REFERENCE") &&
      messages.contains("leaked_probe"), messages)
  }

}
