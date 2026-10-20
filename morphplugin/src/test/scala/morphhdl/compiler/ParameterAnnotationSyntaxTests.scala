package morphhdl.compiler

import org.scalatest.funsuite.AnyFunSuite

class ParameterAnnotationSyntaxTests extends AnyFunSuite {
  import MorphHdlCompilerTestSupport._
  private val api="""
package spinal.core {
  class param extends scala.annotation.StaticAnnotation
  class localparam extends scala.annotation.StaticAnnotation
  class rtlParamName(n:String) extends scala.annotation.StaticAnnotation
  class ElabInt
  class ElabBool
  object Param {
    def declareInt(n:String,v:Int,l:Boolean,a:BigInt,b:BigInt):ElabInt=new ElabInt
    def declare(n:String,v:Int,l:Boolean):ElabInt=new ElabInt
    def declare(n:String,v:Boolean,l:Boolean):ElabBool=new ElabBool
  }
}
"""
  test("annotation imports, qualified forms and inferred factories share declaration lowering") {
    for(body<-Seq(
      "import spinal.core._; class C { @param val count=8; @localparam val enabled=true }",
      "class C { @_root_.spinal.core.param val count=8 }",
      "import spinal.core.{param=>P,localparam=>L,_}; class C { @P val count=8; @L val enabled=true }",
      "import spinal.core._; class C { val count=Param(8,local=false); val enabled=Param(true,local=true) }")) {
      val errors=compile(api+"package probe {"+body+"}","ParameterAnnotations.scala")
      assert(errors.isEmpty,errors.mkString("\n"))
    }
  }
  test("conflicting annotations, mutable declarations and runtime flags are rejected") {
    for(body<-Seq(
      "@param @localparam val count=8",
      "@param var count=8",
      "@localparam lazy val count=8",
      "val localFlag=true; val count=Param(8,local=localFlag)",
      "@param val count=Param(8,local=true)",
      "@localparam val count={ println(1); 8 }",
      "@localparam def count=8",
      "val count=Param(8,local=true,local=false)")) {
      val errors=compile(api+"package probe { import spinal.core._; class C {"+body+"} }","BadAnnotations.scala")
      assert(errors.exists(_.message.contains("MORPHDL-PARAM-DECLARATION")),errors.mkString("\n"))
    }
  }
  test("uppercase and foreign annotation spellings do not grant parameter authority") {
    val errors=compile(api+"""package probe {
      class param extends scala.annotation.StaticAnnotation
      class C { @param val COUNT:Int=8; val OTHER:Int=3 }
    }""","ForeignAnnotations.scala")
    assert(errors.isEmpty,errors.mkString("\n"))
  }
  test("source-visible pure helpers are accepted and effectful or cyclic helpers are rejected") {
    val good=compile(api+"""package probe { import spinal.core._
      class C { def constant():Int=3+5; @localparam val code=constant() }
    }""","PureConstant.scala")
    assert(good.isEmpty,good.mkString("\n"))
    for(helper<-Seq("def constant():Int={ println(1); 8 }",
        "def constant():Int=System.currentTimeMillis().toInt",
        "def other():Int=constant(); def constant():Int=other()")) {
      val errors=compile(api+"package probe { import spinal.core._; class C {"+helper+
        "; @localparam val code=constant() } }","EffectfulConstant.scala")
      assert(errors.exists(_.message.contains("MORPHDL-PARAM-INITIALIZER-EFFECT")),errors.mkString("\n"))
    }
  }

}
