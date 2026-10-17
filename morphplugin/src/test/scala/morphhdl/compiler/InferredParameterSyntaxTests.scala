package morphhdl.compiler

import org.scalatest.funsuite.AnyFunSuite

class InferredParameterSyntaxTests extends AnyFunSuite {
  import MorphHdlCompilerTestSupport._
  private val definitions = """
    package spinal.core {
      trait Param[T]
      object Param { def apply(n:Int):ElabInt=new ElabInt }
      class ElabBool { def &&(b:ElabBool):ElabBool=this }
      class ElabInt {
        def >=(n:Int):ElabBool=new ElabBool
        def <=(n:Int):ElabBool=new ElabBool
        def +(n:Int):ElabInt=this
      }
      object RtlParam {
        def inferredInt(name:String,n:Int,lo:BigInt,hi:BigInt):ElabInt=new ElabInt
        def bool(name:String,n:Boolean):ElabBool=new ElabBool
        def optional(name:String,n:Option[ElabInt],lo:BigInt,hi:BigInt,absent:Int):ElabInt=new ElabInt
      }
      object ElabControl {
        def requireCondition(p:ElabBool,file:String,line:Int):Unit=()
        def localValue[T](value:T,name:String):T=value
      }
    }
  """
  private def check(body:String)=compile(definitions+body,"InferredParameterProbe.scala")
  test("ordinary and renamed imports support declared integer domains and explicit HDL names") {
    for(imports<-Seq("import spinal.core._", "import spinal.core.{Param => P, _}")) {
      val p=if(imports.contains("=>"))"P" else "Param"
      val errors=check(s"""package probe { $imports
        class Probe { val width:$p[Int]=$p(3,"DATA_BITS")
          require(width>=1 && width<=17)
          val next=width+1
        }
      }""")
      assert(errors.isEmpty,errors.mkString("\n"))
    }
  }
  test("constructor specialization limits and named constants establish real declared bounds") {
    val errors=check("""package probe { import spinal.core._
      class Probe(val capacity:Int) {
        val lower=1
        val width:Param[Int]=Param(3)
        require(width>=lower && width<=capacity)
      }
    }""")
    assert(errors.isEmpty,errors.mkString("\n"))
  }
  test("optional typed parameter declarations lower Option actuals with inferred bounds") {
    val errors=check("""package probe { import spinal.core._
      class Probe(actual:Option[Param[Int]]) {
        val mask:Param[Int]=Param.optional(actual,0,"PIPELINE_MASK")
        require(mask>=0 && mask<=7)
      }
    }""")
    assert(errors.isEmpty,errors.mkString("\n"))
  }
  test("unbounded and empty domains cannot borrow the default as a bound") {
    for(constraint<-Seq("", "require(width>=1)", "require(width>=8 && width<=3)")) {
      val errors=check(s"""package probe { import spinal.core._
        class Probe { val width:Param[Int]=Param(3); $constraint }
      }""")
      assert(errors.exists(_.message.contains("MORPHDL-PARAM-DOMAIN-UNPROVEN")),errors.mkString("\n"))
    }
  }
  test("conditional requirements and shadowed require cannot establish a declaration domain") {
    for(constraint<-Seq("if(true) require(width>=1 && width<=8)",
        "def require(value:ElabBool):Unit=(); require(width>=1 && width<=8)")) {
      val errors=check(s"""package probe { import spinal.core._
        class Probe { val width:Param[Int]=Param(3); $constraint }
      }""")
      assert(errors.exists(_.message.contains("MORPHDL-PARAM-DOMAIN-UNPROVEN")),errors.mkString("\n"))
    }
  }
  test("foreign wildcard require does not grant domain evidence; qualified Predef remains available") {
    val base="""package probe { import spinal.core._
      object Controls { def require(value:ElabBool):Unit=() }
      import Controls._
      class Probe { val width:_root_.spinal.core.Param[_root_.scala.Int]=_root_.spinal.core.Param(3); REQUIRE(width>=1 && width<=8) }
    }"""
    val errors=check(base.replace("REQUIRE","require"))
    assert(errors.exists(_.message.contains("MORPHDL-PARAM-DOMAIN-UNPROVEN")),errors.mkString("\n"))
    val qualified=check(base.replace("REQUIRE","_root_.scala.Predef.require"))
    assert(qualified.isEmpty,qualified.mkString("\n"))
  }
  test("a foreign Param factory retains ordinary Scala behavior") {
    val errors=check("""package probe {
      import spinal.core._
      object Local {
        class Param[T](val value:T)
        object Param { def apply[T](value:T):Param[T]=new Param(value) }
        val width:Param[Int]=Param(3)
        val concrete:Int=width.value
      }
    }""")
    assert(errors.isEmpty,errors.mkString("\n"))
  }
  test("local and foreign parameter type spellings cannot borrow built-in authority") {
    for(tpe<-Seq("foreign.Int", "Int")) {
      val local=if(tpe=="Int")"class Int" else ""
      val errors=check(s"""package probe { import spinal.core._
        object foreign { class Int }
        class Probe { $local; val width:Param[$tpe]=Param(3); require(width>=1 && width<=8) }
      }""")
      assert(errors.exists(_.message.contains("MORPHDL-PARAM-TYPE-UNSUPPORTED")),errors.mkString("\n"))
    }
  }
  test("same-block foreign Param declarations override wildcard imports") {
    val errors=check("""package probe { import spinal.core._
      class Param[T](val value:T)
      object Param { def apply[T](value:T):Param[T]=new Param(value) }
      class Probe { val width:Param[Int]=Param(3); val concrete:Int=width.value }
    }""")
    assert(errors.isEmpty,errors.mkString("\n"))
  }
  test("foreign wildcard parameter factories retain their authored semantics") {
    val errors=check("""package probe { import spinal.core._
      object Foreign {
        class Param[T](val value:T)
        object Param { def apply[T](value:T):Param[T]=new Param(value) }
      }
      import Foreign._
      class Probe { val width:Param[Int]=Param(3); val value:Int=width.value }
    }""")
    // Scala may reject ambiguous imports; the parameter phase must not erase
    // that ambiguity and turn the foreign factory into a hardware declaration.
    assert(!errors.exists(_.message.contains("MORPHDL-PARAM-DOMAIN-UNPROVEN")),errors.mkString("\n"))
  }
  test("imports in sibling packages cannot authorize a foreign declaration") {
    val errors=check("""package first { import spinal.core._; class A }
      package second {
        class Param[T](val value:T)
        object Param { def apply[T](value:T):Param[T]=new Param(value) }
        class B { val width:Param[Int]=Param(3); val concrete:Int=width.value }
      }
    """)
    assert(errors.isEmpty,errors.mkString("\n"))
  }
}
