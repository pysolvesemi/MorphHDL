package spinal.core

import java.nio.file.Files
import scala.collection.JavaConverters._
import morphhdl.frontend.{FrontendException, HdlInt}
import org.scalatest.funsuite.AnyFunSuite

class TypedParameterFrontendProjectionTests extends AnyFunSuite {
  test("typed frontend import rejects an active singleton projection") {
    val directory = Files.createTempDirectory("typed-frontend-projection-")
    var rejections = 0
    try {
      morphhdl.MorphVerilog(SpinalConfig(targetDirectory = directory.toString,
        headerWithDate = false)) {
        new Component {
          val input = in(UInt(5 bits))
          val result = out(UInt(5 bits))
          val mode = HdlInt.param("MODE", 1, 1, 2).asElabInt
          ElabControl.selectSymbolic(mode.elabEq(1), "typed-frontend-projection", 1) {
            val failure = intercept[FrontendException] {
              HdlInt.fromElabIntParameter(mode)
            }
            assert(failure.code == "MORPH-FRONTEND-TYPED-PARAMETER-NOT-DIRECT")
            rejections += 1
            val selected = UInt(5 bits).setName("selected_true").dontSimplifyIt()
            selected := input + 1
            result := selected
          } {
            val failure = intercept[FrontendException] {
              HdlInt.fromElabIntParameter(mode)
            }
            assert(failure.code == "MORPH-FRONTEND-TYPED-PARAMETER-NOT-DIRECT")
            rejections += 1
            val selected = UInt(5 bits).setName("selected_false").dontSimplifyIt()
            selected := input + 2
            result := selected
          }
        }
      }
      assert(rejections == 2)
    } finally {
      val stream = Files.walk(directory)
      try stream.iterator().asScala.toVector.sortBy(_.getNameCount).reverse
        .foreach(Files.deleteIfExists(_))
      finally stream.close()
    }
  }
}
