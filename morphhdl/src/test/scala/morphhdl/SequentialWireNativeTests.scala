package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.collection.JavaConverters._
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

class SequentialWireNativeTests extends AnyFunSuite {
  private object UnknownIntent extends SpinalTag
  private def emit(disabled: Boolean = false)(factory: => Component): String = {
    val dir = Files.createTempDirectory("sequential-native-")
    try {
      val config = SpinalConfig(targetDirectory = dir.toString, headerWithDate = false,
        oneFilePerComponent = false)
      config.netlistFileName = "dut.v"
      MorphVerilog(if (disabled) MorphWireAssignmentPasses(config, enabled = false) else config)(factory)
      new String(Files.readAllBytes(dir.resolve("dut.v")), StandardCharsets.UTF_8)
    } finally {
      val paths = Files.walk(dir)
      try paths.iterator.asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists(_))
      finally paths.close()
    }
  }
  private def feedback: Component = new Component {
    val enable, priority = in Bool()
    val countOut = out UInt(13 bits)
    val flagOut = out Bool()
    val count = Reg(UInt(13 bits)) init(3)
    val flag = Reg(Bool()) init(False)
    @dontName val predicate = count === 3
    flag := predicate
    when(predicate) { count := 0 } elsewhen(!enable) { count := 9 } otherwise { count := count + 1 }
    when(priority) { count := 7 }
    countOut := count
    flagOut := flag
  }
  test("shared predicate over register pre-edge value inlines into condition and register RHS") {
    val v = emit()(feedback)
    assert(!v.split("\n").exists(line => line.trim.startsWith("assign when_")), v)
    assert("if\\(\\(count == 13'h0*3\\)\\)".r.findFirstIn(v).nonEmpty, v)
    assert("flag <= \\(count == 13'h0*3\\);".r.findFirstIn(v).nonEmpty, v)
    assert("count <= 13'h0*7;".r.findFirstIn(v).nonEmpty, v)
    assert(emit(disabled = true)(feedback).contains("assign when_"))
  }
  private final class Protected(protection: Int, aliasCase: Boolean) extends Component {
    val enable = in Bool()
    val source = in UInt(18 bits)
    val result = out UInt(13 bits)
    val state = Reg(UInt(13 bits)) init(0)
    @dontName val predicate = !enable
    @dontName val alias = UInt(18 bits)
    alias := source
    @dontName val protectedNode: BaseType = if (aliasCase) alias else predicate
    protection match {
      case 0 => protectedNode.addAttribute("keep")
      case 1 => protectedNode.dontSimplifyIt()
      case 2 => protectedNode.setAsVital()
      case 3 => protectedNode.freeze()
      case 4 => protectedNode.addTag(UnknownIntent)
      case 5 => protectedNode.setName(if (aliasCase) "_zz_user_signal" else "when_user_l999")
      case 6 => protectedNode.addTag(spinal.core.sim.SimPublic)
    }
    when(predicate) { state := alias.resized }
    result := state
  }
  for (aliasCase <- Vector(false, true);
       (label, protection) <- Vector("keep", "dontSimplify", "vital", "frozen", "unknown tag", "explicit lookalike", "debug").zipWithIndex) {
    test(s"$label ${if (aliasCase) "direct alias" else "condition"} remains a named identity for register consumers") {
      var dut: Protected = null
      val v = emit() { dut = new Protected(protection, aliasCase); dut }
      val name = dut.protectedNode.getName()
      assert(name.nonEmpty)
      assert(v.contains(s"assign $name = "), v)
      if (!aliasCase) assert(v.contains(s"if($name)"), v)
    }
  }
  test("named source and arithmetic slice bases survive while direct register slices simplify") {
    val v = emit() { new Component {
      val enable = in Bool()
      val first, second = in UInt(18 bits)
      val result, arithmeticOut = out UInt(13 bits)
      val namedSource = first + second
      val total = Reg(UInt(13 bits)) init(0)
      val arithmetic = Reg(UInt(13 bits)) init(0)
      when(enable) {
        total := namedSource.resized
        arithmetic := (first + second).resize(13)
      }
      result := total
      arithmeticOut := arithmetic
    }}
    assert(v.contains("assign namedSource = (first + second);"), v)
    assert(v.contains("total <= namedSource[12:0];"), v)
    assert(v.contains("assign _zz_"), v)
    assert(!v.contains("(first + second)["), v)
  }
  test("unsupported switch consumers retain the condition helper rather than being partially rewritten") {
    var predicate: Bool = null
    val v = emit() { new Component {
      val a = in Bool()
      val select = in UInt(2 bits)
      val result = out UInt(8 bits)
      val state = Reg(UInt(8 bits)) init(0)
      @dontName val testCondition = !a
      predicate = testCondition
      when(testCondition) { state := 1 }
      switch(select) {
        is(0) { when(testCondition) { state := 2 } }
        default { state := 3 }
      }
      result := state
    }}
    assert(v.contains(s"assign ${predicate.getName()} = "), v)
  }
}
