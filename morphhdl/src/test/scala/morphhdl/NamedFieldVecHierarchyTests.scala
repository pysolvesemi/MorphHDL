package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

private object NamedFieldVecHierarchyFixture {
  import NamedFieldVecFixture._

  final class DirectRegisters(width: ElabInt, blueWidth: ElabInt, count: ElabInt) extends Component {
    setDefinitionName("NamedFieldVecDirectRegisters")
    val clk = in(Bool())
    val enable = in(Bool())
    val pixels = in(Vec(Pixel(width, blueWidth), count))
    val result = out(Vec(Pixel(width, blueWidth), count))
    val child = new Child(width, blueWidth, count)
    child.pixels := pixels
    val area = new ClockingArea(ClockDomain(clock = clk)) {
      val registers = Reg(Vec(Pixel(width, blueWidth), count)).setName("registers").dontSimplifyIt()
      when(enable) { registers := child.result }
    }
    result := area.registers
  }
}

class NamedFieldVecHierarchyTests extends AnyFunSuite {
  import NamedFieldVecFixture._

  private def generate(named: Boolean): String = {
    val directory = Files.createTempDirectory("named-field-child-registers-")
    val base = config(directory, "DirectRegisters.v")
    val selected = if (named) MorphNamedFieldVectors.enable(base) else base
    MorphVerilog(MorphSignedCasts.enable(selected)) {
      new NamedFieldVecHierarchyFixture.DirectRegisters(
        parameter("WIDTH", 3, 4), parameter("BLUE_WIDTH", 2, 4), parameter("COUNT", 1, 3))
    }
    new String(Files.readAllBytes(directory.resolve("DirectRegisters.v")), StandardCharsets.UTF_8)
  }

  Vector(true, false).foreach { named =>
    test(s"direct child Vec output preserves enabled register update in ${if (named) "named" else "legacy"} layout") {
      val text = generate(named)
      val port = if (named) "result_red" else "result"
      val target = if (named) "registers_red" else "registers"
      val connection = ("\\." + port + "\\s*\\(\\s*([A-Za-z_][A-Za-z0-9_$]*)\\s*\\)").r
        .findFirstMatchIn(text).map(_.group(1)).getOrElse(fail(text))
      assert(connection != target, "the child output must not directly drive the clocked destination")
      assert(("(?m)^\\s*wire\\s+\\[[^\\]]+\\]\\s+" + connection + "\\s*;").r.findFirstIn(text).nonEmpty, text)
      val process = "(?s)always\\s*@\\(posedge clk\\)(.*?)endmodule".r
        .findFirstMatchIn(text).map(_.group(1)).getOrElse(fail(text))
      assert(process.contains("if(enable)"), process)
      assert(("(?s)[^;]*\\b" + target + "\\b[^;]*<=\\s*[^;]*\\b" + connection + "\\b[^;]*;").r
        .findFirstIn(process).nonEmpty, process)
    }
  }
}
