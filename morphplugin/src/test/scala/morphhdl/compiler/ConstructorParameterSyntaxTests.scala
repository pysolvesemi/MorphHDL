package morphhdl.compiler

import org.scalatest.funsuite.AnyFunSuite

class ConstructorParameterSyntaxTests extends AnyFunSuite {
  import MorphHdlCompilerTestSupport._
  private val definitions="""
    package spinal.core {
      class rtlParamName(val name:String) extends scala.annotation.StaticAnnotation
      class Component
      trait Param[T]
      object Param {def apply(n:Int):ElabInt=new ElabInt}
      class ElabInt {def >=(n:Int):ElabBool=new ElabBool;def <=(n:Int):ElabBool=new ElabBool;def +(n:Int):ElabInt=this}
      class ElabBool {def &&(that:ElabBool):ElabBool=this}
      object ConstructorParam {
        class Integer(val value:ElabInt)
        object Integer {implicit def literal(v:Int):Integer=new Integer(new ElabInt);implicit def symbolic(v:ElabInt):Integer=new Integer(v)}
      }
      object RtlParam {def int(name:String,n:ElabInt,lo:Int,hi:Int):ElabInt=n;def inferredInt(name:String,n:ElabInt,lo:BigInt,hi:BigInt):ElabInt=n}
      object ElabControl {
        def requireCondition(p:ElabBool,file:String,line:Int):Unit=()
        def localValue[T](value:T,name:String):T=value
      }
    }
  """
  private def check(body:String)=compile(definitions+body,"ConstructorParameterProbe.scala")
  test("constructor-only formals accept renamed imports named defaults and Int actuals") {
    val errors=check("""package probe { import spinal.core.{Component=>C,Param=>P,_}
      class Probe(WIDTH:P[Int]=8) extends C {
        require(WIDTH>=1 && WIDTH<=64)
        val result:ElabInt=WIDTH
        def local(WIDTH:Int):Int=WIDTH+1
      }
      object Calls {val a=new Probe();val b=new Probe(WIDTH=3);val n=4;val c=new Probe(n+1);val d=new Probe(P(7))}
    }""")
    assert(errors.isEmpty,errors.mkString("\n"))
  }
  test("constructor formals need real finite bounds") {
    val errors=check("""package probe {import spinal.core._
      class Probe(WIDTH:Param[Int]=8) extends Component { require(WIDTH>=1) }
    }""")
    assert(errors.exists(_.message.contains("PARAM-DOMAIN-UNPROVEN")),errors.mkString("\n"))
  }
  test("ordinary Scala constructors and foreign Component types retain actual-only annotations") {
    val errors=check("""package foreign {class Component}
      package probe {import spinal.core._
      class Plain(WIDTH:Param[Int]) {val actual:ElabInt=WIDTH}
      class Foreign(WIDTH:Param[Int]) extends foreign.Component {val actual:ElabInt=WIDTH}
      object Calls {val a=new Plain(Param(1));val b=new Foreign(Param(1))}
    }""")
    assert(errors.isEmpty,errors.mkString("\n"))
  }
  test("constructor argument accessors and multiple parameter lists reject explicitly") {
    for(signature<-Seq("val WIDTH:Param[Int]", "var WIDTH:Param[Int]")) {
      val errors=check(s"""package probe {import spinal.core._
        class Probe($signature) extends Component { require(WIDTH>=1 && WIDTH<=8) }
      }""")
      assert(errors.exists(_.message.contains("CONSTRUCTOR-PARAM-UNSUPPORTED")),errors.mkString("\n"))
    }
    val errors=check("""package probe {import spinal.core._
      class Probe(WIDTH:Param[Int])(n:Int) extends Component {require(WIDTH>=1 && WIDTH<=8)}
    }""")
    assert(errors.exists(_.message.contains("CONSTRUCTOR-PARAM-UNSUPPORTED")),errors.mkString("\n"))
  }
  test("hardware-like and untyped foreign actuals cannot use constructor integer conversion") {
    val errors=check("""package probe {import spinal.core._
      class Probe(WIDTH:Param[Int]) extends Component {require(WIDTH>=1 && WIDTH<=8)}
      class Hardware
      object Calls {val bad=new Probe(new Hardware)}
    }""")
    assert(errors.exists(_.message.contains("type mismatch")),errors.mkString("\n"))
  }
  test("legacy explicit body declarations keep their actual-only constructor binding") {
    val errors=check("""package probe {import spinal.core._
      class Probe(actual:Param[Int]) extends Component {
        val WIDTH:Param[Int]=Param(actual);require(WIDTH>=1 && WIDTH<=8)
      }
      object Calls {val a=new Probe(Param(3))}
    }""")
    assert(errors.isEmpty,errors.mkString("\n"))
  }
  test("constructor HDL name overrides require one portable literal") {
    for(annotation<-Seq("@rtlParamName(\"bad-name\")", "@rtlParamName(\"A\") @rtlParamName(\"B\")")) {
      val errors=check(s"""package probe {import spinal.core._
        class Probe($annotation WIDTH:Param[Int]) extends Component {require(WIDTH>=1 && WIDTH<=8)}
      }""")
      assert(errors.exists(_.message.contains("CONSTRUCTOR-PARAM-NAME")),errors.mkString("\n"))
    }
  }
  test("legacy explicit RtlParam body factories retain constructor actual-only compatibility") {
    val errors=check("""package probe {import spinal.core.{RtlParam=>R,_}
      class Probe(actual:Param[Int]) extends Component {
        val WIDTH:ElabInt=R.int("WIDTH",actual,1,8)
      }
      object Calls {val a=new Probe(Param(3))}
    }""")
    assert(errors.isEmpty,errors.mkString("\n"))
  }

  test("constructor rewriting respects nested this owners and shadowed method and pattern names") {
    val errors=check("""package probe {import spinal.core._
      class Probe(width:Param[Int]) extends Component {
        require(width>=1 && width<=8)
        def shadow(width:Int):ElabInt=this.width
        def pattern(n:Int):Int=n match {case width=>width+1}
        class Inner {val width=3;def own:Int=this.width;def outer:ElabInt=Probe.this.width}
      }
      object Calls {val a=new Probe(3)}
    }""")
    assert(errors.isEmpty,errors.mkString("\n"))
  }

}
