package spinal.core

import java.nio.file.Files

import scala.collection.JavaConverters._

import org.scalatest.funsuite.AnyFunSuite

import morphhdl.MorphVerilog
import spinal.lib.{Counter, ExternalParameterizedCounterRegistry}

class CounterRegistryAtomicityTests extends AnyFunSuite {
  test("rejected Counter width cannot leave partial boundary authority") {
    withTemporaryDirectory { directory =>
      val rtl = directory.resolve("rejected_counter_width.v")
      var counter: Counter = null
      val failure = MorphVerilog.tryGenerate(
        SpinalConfig(
          targetDirectory = directory.toString,
          netlistFileName = rtl.getFileName.toString
        )
      ) {
        new Component {
          counter = Counter(4 bits)
          val schema = ElaborationIntegerParameter(
            "COUNTER_WIDTH",
            default = 4,
            minimum = 1,
            maximum = 8
          )
          val forged = ElaborationIntegerExpression(
            verilog = "(COUNTER_WIDTH + 1000)",
            default = 4,
            minimum = 4,
            maximum = 1004,
            parameters = Vector(schema),
            sourceLocation = Some("counter-registry-atomicity"),
            parameterRoots = Vector(schema.declarationRoot)
          )
          ExternalParameterizedCounterRegistry.attach(
            counter,
            ParameterizedBitCount(
              value = 4,
              parameter = None,
              sourceLocation = Some("counter-registry-atomicity"),
              expression = Some(forged)
            )
          )
        }
      } match {
        case Left(value)  => value
        case Right(value) => fail(s"expected forged Counter width rejection, received $value")
      }

      assert(
        failure.detail.contains(
          "SPINAL-PARAMETERIZED-VERILOG-WIDTH-EXACT-DOMAIN-REQUIRED"
        ),
        failure.detail
      )
      assert(counter != null)
      assert(ExternalParameterizedCounterRegistry.metadataOf(counter).isEmpty)
      assert(ParameterizedWidth.expressionOf(counter.valueNext).isEmpty)
      assert(ParameterizedWidth.expressionOf(counter.value).isEmpty)
      assert(!Files.exists(rtl), "rejected Counter width published partial RTL")
    }
  }

  private def withTemporaryDirectory[T](body: java.nio.file.Path => T): T = {
    val directory = Files.createTempDirectory("morphhdl-counter-registry-atomicity-")
    try body(directory)
    finally {
      val stream = Files.walk(directory)
      try {
        stream.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach { path =>
          Files.deleteIfExists(path)
        }
      } finally stream.close()
    }
  }
}
