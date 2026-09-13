package spinal.core.internals

import java.nio.file.Files
import scala.collection.JavaConverters._
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

class NativePureExpressionCopyTests extends AnyFunSuite {
  private def withNativeInput(check: UInt => Unit): Unit = {
    val directory = Files.createTempDirectory("native-pure-expression-copy-")
    var input: UInt = null
    val config = SpinalConfig(targetDirectory = directory.toString, headerWithDate = false)
    config.phasesInserters += { phases =>
      val emission = phases.indexWhere(_.isInstanceOf[PhaseVerilog])
      require(emission >= 0)
      phases.insert(emission, new PhaseMisc {
        override def impl(pc: PhaseContext): Unit = check(input)
      })
    }
    try SpinalVerilog(config) {
      new Component {
        setDefinitionName("NativePureExpressionCopyFixture")
        input = in UInt(8 bits)
        val result = out UInt(8 bits)
        result := input
      }
    }
    finally {
      val paths = Files.walk(directory)
      try paths.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists(_))
      finally paths.close()
    }
  }

  private def subtract(input: Expression with WidthProvider): Operator.UInt.Sub = {
    val value = new Operator.UInt.Sub
    value.left = input
    value.right = UIntLiteral(1, 8)
    value.inferredWidth = 8
    value.widthWhenNotInferred = 8
    value
  }

  test("shared operator edges clone independently while signal identity and widths survive") {
    withNativeInput { input =>
      val shared = subtract(input)
      val source = new Operator.UInt.Add
      source.left = shared
      source.right = shared
      source.inferredWidth = 8
      source.widthWhenNotInferred = 8
      val copied = NativePureExpressionCopy(source).get.asInstanceOf[Operator.UInt.Add]
      assert(copied ne source)
      assert(copied.left ne copied.right)
      for (child <- Vector(copied.left, copied.right)) {
        val sub = child.asInstanceOf[Operator.UInt.Sub]
        assert(sub ne shared)
        assert(sub.left eq input)
        assert(sub.right ne shared.right)
        assert(sub.inferredWidth == 8 && sub.widthWhenNotInferred == 8)
        assert(sub.scalaTrace eq shared.scalaTrace)
      }
      val second = NativePureExpressionCopy(source).get.asInstanceOf[Operator.UInt.Add]
      assert(second.left ne copied.left)
    }
  }

  test("metadata, unrepresented subclasses, expression cycles and expansion fail closed") {
    withNativeInput { input =>
      object Retain extends SpinalTag
      val tagged = new Operator.UInt.Smaller
      tagged.left = input
      tagged.right = UIntLiteral(1, 8)
      tagged.addTag(Retain)
      assert(NativePureExpressionCopy(tagged).isEmpty)
      val subclass = new Operator.UInt.Add {}
      subclass.left = input
      subclass.right = input
      assert(NativePureExpressionCopy(subclass).isEmpty)
      val cycle = subtract(input)
      cycle.left = cycle
      assert(NativePureExpressionCopy(cycle).isEmpty)
      var deep: Expression with WidthProvider = input
      for (_ <- 0 until 130) deep = subtract(deep)
      assert(NativePureExpressionCopy(deep).isEmpty)
    }
  }
}
