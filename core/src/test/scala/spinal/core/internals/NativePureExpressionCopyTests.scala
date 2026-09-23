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

  private def withNativeEnumComparisons(
      encoding: SpinalEnumEncoding
  )(check: (
      SpinalEnum,
      SpinalEnumCraft[_],
      Operator.Enum.Equal,
      Operator.Enum.NotEqual
  ) => Unit): Unit = {
    val directory = Files.createTempDirectory("native-enum-expression-copy-")
    var enumDefinitionSeen: SpinalEnum = null
    var source: SpinalEnumCraft[_] = null
    var equal: Bool = null
    var notEqual: Bool = null
    var equalExpression: Operator.Enum.Equal = null
    var notEqualExpression: Operator.Enum.NotEqual = null
    val config = SpinalConfig(targetDirectory = directory.toString, headerWithDate = false)
    config.phasesInserters += { phases =>
      val inference = phases.indexWhere(_.isInstanceOf[PhaseInferEnumEncodings])
      require(inference >= 0)
      phases.insert(inference + 1, new PhaseMisc {
        override def impl(pc: PhaseContext): Unit = {
          check(
            enumDefinitionSeen,
            source,
            equalExpression,
            notEqualExpression
          )
        }
      })
    }
    try SpinalVerilog(config) {
      new Component {
        setDefinitionName("NativeEnumExpressionCopyFixture")
        val enumDefinition = new SpinalEnum(encoding)
        enumDefinitionSeen = enumDefinition
        val idle = enumDefinition.newElement("IDLE")
        enumDefinition.newElement("ACTIVE")
        val enumSource = in(enumDefinition())
        source = enumSource
        equal = enumSource === idle
        notEqual = enumSource =/= idle
        equal.foreachStatements {
          case assignment: DataAssignmentStatement =>
            equalExpression = assignment.source.asInstanceOf[Operator.Enum.Equal]
          case _ =>
        }
        notEqual.foreachStatements {
          case assignment: DataAssignmentStatement =>
            notEqualExpression = assignment.source.asInstanceOf[Operator.Enum.NotEqual]
          case _ =>
        }
        require(equalExpression != null && notEqualExpression != null)
        val result = out Bits(2 bits)
        result(0) := equal
        result(1) := notEqual
      }
    }
    finally {
      val paths = Files.walk(directory)
      try paths.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists(_))
      finally paths.close()
    }
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

  test("fixed-width shift copies preserve logical operator class, amount and source geometry") {
    withNativeInput { input =>
      val shift = new Operator.UInt.ShiftRightByIntFixedWidth(3)
      shift.source = input
      shift.inferredWidth = 8
      shift.widthWhenNotInferred = 8
      val copied = NativePureExpressionCopy(shift).get.asInstanceOf[Operator.UInt.ShiftRightByIntFixedWidth]
      assert(copied ne shift)
      assert(copied.source eq input)
      assert(copied.shift == 3 && copied.getWidth == 8)
      assert(NativeWidthProvenance.widthOf(copied).exists(_.default == 8))
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

  for ((label, encoding, expectedWidth) <- Vector[(String, SpinalEnumEncoding, Int)](
      ("binary", binarySequential, 1),
      ("one-hot", binaryOneHot, 2),
      ("custom nonsequential", SpinalEnumEncoding("copyCustom", index => if (index == 0) 1 else 5), 3)
    )) {
    test(s"$label enum equality and inequality copy exact native authority into fresh trees") {
      withNativeEnumComparisons(encoding) { (definition, source, equal, notEqual) =>
        val authority = NativeEnumExpressionAuthority.resolve(source).get
        assert(authority.definition eq definition)
        assert(authority.encoding eq encoding)
        assert(authority.width == expectedWidth)
        assert(NativeWidthProvenance.widthOf(source).exists(_.default == expectedWidth))

        for ((original, copied) <- Vector(
            equal -> NativePureExpressionCopy(equal).get,
            notEqual -> NativePureExpressionCopy(notEqual).get
          )) {
          val result = copied.asInstanceOf[BinaryOperator with EnumEncoded]
          assert(result ne original)
          assert(result.getClass == original.getClass)
          assert(result.getDefinition eq definition)
          assert(result.getEncoding eq encoding)
          assert(result.left eq source)
          assert(result.right ne original.right)
          val literal = result.right.asInstanceOf[EnumLiteral[_]]
          val originalLiteral = original.right.asInstanceOf[EnumLiteral[_]]
          assert(literal.senum eq originalLiteral.senum)
          assert(literal.getDefinition eq definition)
          assert(literal.getEncoding eq encoding)
          assert(literal.scalaTrace eq originalLiteral.scalaTrace)
          assert(result.scalaTrace eq original.scalaTrace)
          assert(NativeEnumExpressionAuthority.comparison(result).nonEmpty)
        }
      }
    }
  }

  test("unresolved, incompatible and case-equality enum observations fail closed") {
    withNativeEnumComparisons(binarySequential) { (definition, source, equal, _) =>
      val unresolved = new EnumLiteral(definition.elements.head)
      assert(NativeEnumExpressionAuthority.resolve(unresolved).isEmpty)
      assert(NativePureExpressionCopy(unresolved).isEmpty)

      val other = new SpinalEnum(binarySequential)
      val otherIdle = other.newElement("IDLE")
      other.newElement("ACTIVE")
      val wrongLiteral = new EnumLiteral(otherIdle).fixEncoding(binarySequential)
      val incompatible = new Operator.Enum.Equal(definition).fixEncoding(binarySequential)
      incompatible.left = source
      incompatible.right = wrongLiteral
      assert(NativeEnumExpressionAuthority.comparison(incompatible).isEmpty)
      assert(NativePureExpressionCopy(incompatible).isEmpty)

      val caseEquality = new Operator.Enum.EqualSim(definition)
      caseEquality.copyEncodingConfig(equal)
      caseEquality.left = equal.left
      caseEquality.right = equal.right
      assert(NativePureExpressionCopy(caseEquality).isEmpty)
    }
  }
}
