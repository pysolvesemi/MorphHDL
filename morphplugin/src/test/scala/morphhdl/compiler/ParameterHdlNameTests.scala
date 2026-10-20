package morphhdl.compiler

import org.scalatest.funsuite.AnyFunSuite

class ParameterHdlNameTests extends AnyFunSuite {
  for((source,expected) <- Seq(
    "num_bytes" -> "NUM_BYTES", "numBytes" -> "NUM_BYTES",
    "c.num_bytes" -> "C_NUM_BYTES", "c.video.numBytes" -> "C_VIDEO_NUM_BYTES",
    "RGBWidth" -> "RGB_WIDTH", "rgb2Bytes" -> "RGB2_BYTES",
    "DATA_BITS" -> "DATA_BITS", "video2D" -> "VIDEO2_D")) {
    test(s"normalize parameter declaration path $source") {
      assert(ParameterHdlNames.inferred(source)==expected)
    }
  }
}
