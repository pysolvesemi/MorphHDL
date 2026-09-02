package morphhdl.compiler

import org.scalatest.funsuite.AnyFunSuite

class MorphHdlFrontendSymbolicEqualitySafetyComponentTests extends AnyFunSuite {
  import MorphHdlCompilerTestSupport._

  private val symbolicDefinitions =
    """
      |package morphhdl.frontend {
      |  final class HdlBool {
      |    def select(whenTrue: HdlInt, whenFalse: HdlInt): HdlInt = whenTrue
      |  }
      |  final class HdlInt {
      |    def hdlEq(that: HdlInt): HdlBool = new HdlBool
      |    def hdlNe(that: HdlInt): HdlBool = new HdlBool
      |  }
      |  final class GenIndex
      |}
      |""".stripMargin

  test("rejects reverse equality for statically typed MorphHDL values") {
    val source = symbolicDefinitions +
      """
        |package comparison {
        |  object Rejected {
        |    type Alias = morphhdl.frontend.HdlInt
        |    val hdl = new morphhdl.frontend.HdlInt
        |    val alias: Alias = hdl
        |    val index = new morphhdl.frontend.GenIndex
        |    val bool = new morphhdl.frontend.HdlBool
        |    val a = BigInt(4) == hdl
        |    val b = BigDecimal(4) != hdl
        |    val c = "lane".equals(index)
        |    val d = new Object eq index
        |    val e = new Object ne index
        |    val f = 0 == alias
        |    val g = false == bool
        |  }
        |}
        |""".stripMargin

    val errors = compile(source, "ReverseEquality.scala")
    assert(errors.size == 7, errors.mkString("\n"))
    assert(
      errors.forall(_.message.contains("MORPH-FRONTEND-SYMBOLIC-COMPARISON-UNSUPPORTED")),
      errors.mkString("\n")
    )
    assert(errors.forall(_.source == "ReverseEquality.scala"), errors.mkString("\n"))
    assert(
      errors.exists(_.line == lineOf(source, "BigInt(4) == hdl")),
      errors.mkString("\n")
    )
  }

  test("does not intercept ordinary equality or symbolic receivers") {
    val errors = compile(
      symbolicDefinitions +
        """
          |package comparison {
          |  object Accepted {
          |    val hdl = new morphhdl.frontend.HdlInt
          |    val index = new morphhdl.frontend.GenIndex
          |    val bool = new morphhdl.frontend.HdlBool
          |    val a = BigInt(4) == BigInt(4)
          |    val b = hdl == BigInt(4)
          |    val c = index != 0
          |    val d = bool == false
          |    val e = hdl.hdlEq(hdl)
          |    val f = hdl.hdlNe(hdl)
          |  }
          |}
          |""".stripMargin
    )

    assert(errors.isEmpty, errors.mkString("\n"))
  }

  test("selection results retain equality safety") {
    val accepted = compile(
      symbolicDefinitions +
        """
          |package selection {
          |  object Accepted {
          |    val condition = new morphhdl.frontend.HdlBool
          |    val wide = new morphhdl.frontend.HdlInt
          |    val narrow = new morphhdl.frontend.HdlInt
          |    val selected = condition.select(wide, narrow)
          |    val compared = selected.hdlEq(wide)
          |  }
          |}
          |""".stripMargin
    )
    assert(accepted.isEmpty, accepted.mkString("\n"))

    val rejected = compile(
      symbolicDefinitions +
        """
          |package selection {
          |  object Rejected {
          |    val condition = new morphhdl.frontend.HdlBool
          |    val wide = new morphhdl.frontend.HdlInt
          |    val narrow = new morphhdl.frontend.HdlInt
          |    val compared = 0 == condition.select(wide, narrow)
          |  }
          |}
          |""".stripMargin
    )
    assert(rejected.size == 1, rejected.mkString("\n"))
    assert(
      rejected.head.message.contains("MORPH-FRONTEND-SYMBOLIC-COMPARISON-UNSUPPORTED")
    )
  }
}
