package morphhdl.compiler

import org.scalatest.funsuite.AnyFunSuite

class OrdinaryLoopSyntaxTests extends AnyFunSuite {
  import MorphHdlCompilerTestSupport._
  private val definitions = """
package spinal.core {
  class rtlHardwareCallback extends scala.annotation.StaticAnnotation
  class Vec { def length:Int=4; def apply(i:ElabScopedIndex):probe.Wire=new probe.Wire }
  class ElabInt
  class ElabScopedIndex
  class ElabFiniteIndex extends ElabScopedIndex
  object ElabControl { def localValue[T](v:T,name:String):T=v }
  object ElabFiniteRange {
    def foreachGuardedVecConstant(end:Int,inclusive:Boolean)(@rtlHardwareCallback body:ElabFiniteIndex=>Unit):Unit=()
    def foreachGuardedVecConstantFrom(start:Int,end:Int,inclusive:Boolean)(@rtlHardwareCallback body:ElabScopedIndex=>Unit):Unit=()
    def foreachVecLength(vector:Vec,start:Int,inclusive:Boolean)(@rtlHardwareCallback body:ElabScopedIndex=>Unit):Unit=()
    def foreachConstantFrom(start:Int,end:Int,inclusive:Boolean)(@rtlHardwareCallback body:ElabScopedIndex=>Unit):Unit=()
    def foreachConstant(end:Int,inclusive:Boolean)(@rtlHardwareCallback body:ElabFiniteIndex=>Unit):Unit=()
  }
}
package probe {
  import spinal.core._
  class Wire {def :=(v:Boolean):Unit=()}
  class Wires {def apply(i:ElabScopedIndex):Wire=new Wire}
"""
  private def check(body:String)=compile(definitions+body+"}", "OrdinaryLoopProbe.scala")
  for(start<-Seq(0,1)) test(s"typed native Vec callback uses ordinary scope bridge start=$start") {
    val errors=check(s"class C {val out=new Vec;for(i<-$start until 4)out(i):=true}")
    assert(errors.isEmpty, errors.mkString("\n"))
  }
  for(bound<-Seq("4","p.bytes","p.bytes + 1","0","-1")) test(s"immutable ordinary hardware bound $bound") {
    val errors=check(s"""case class Config(bytes:Int)
      class C(p:Config) {val out=new Wires;for(i<-0 until $bound) out(i):=true}
    """)
    assert(errors.isEmpty, errors.mkString("\n"))
  }
  test("inclusive ordinary hardware range") {
    assert(check("class C {val out=new Wires;for(i<-0 to 3) out(i):=true}").isEmpty)
  }
  for(member<-Seq("var bytes:Int=4","def bytes:Int=4")) test(s"reject unsupported bound $member") {
    val errors=check(s"class Config {$member};class C(p:Config) {val out=new Wires;for(i<-0 until p.bytes) out(i):=true}")
    assert(errors.exists(_.message.contains("CONSTANT-LOOP-BOUND-UNSUPPORTED")), errors.mkString("\n"))
  }
  test("reject host effect in retained statement loop") {
    val errors=check("class C {val out=new Wires;for(i<-0 until 4) {println(123);out(i):=true}}")
    assert(errors.exists(_.message.contains("EFFECT")), errors.mkString("\n"))
  }
  test("ordinary host loops keep Scala index semantics") {
    assert(check("object C {val values=scala.collection.mutable.ArrayBuffer[Int]();for(i<-0 until 4)values+=i}").isEmpty)
  }
  test("host collections retain Int indices without disabling independent supported loops") {
    val errors=check("""class C {
      val values=Array.fill(4)(new Wire)
      val outputs=new Wires
      for(i<-0 until 4) values(i):=true
      for(i<-0 until 4) outputs(i):=true
    }""")
    assert(errors.isEmpty,errors.mkString("\n"))
  }
  test("Seq transformations retain host collection indexing") {
    val errors=check("""class C {
      val values=Seq(1,2,3).zipWithIndex.map { case (n,i)=>new Wire }
      for(i<-0 until 3) values(i):=true
    }""")
    assert(errors.isEmpty,errors.mkString("\n"))
  }
  test("Vec views of existing scalar ports retain host ownership") {
    val errors=check("""object Vec {def apply(values:Wire*):Seq[Wire]=values}
      class C {val a=new Wire;val b=new Wire;val values=Vec(a,b)
        for(i<-0 until 2) values(i):=true
      }""")
    assert(errors.isEmpty,errors.mkString("\n"))
  }
  test("nested filtered inclusive topology remains host elaboration") {
    val errors=check("""class C {
      val values=Array.fill(4)(new Wire)
      for(j<-0 until 4) {
        for(n<-0 to 3 if j+n<4) values(j+n):=true
      }
    }""")
    assert(errors.isEmpty,errors.mkString("\n"))
  }

  test("explicitly retained bound rejects host collection topology with a specific diagnostic") {
    val errors=check("""class C {val count:ElabInt=new ElabInt;val values=Array.fill(4)(new Wire)
      for(i<-0 until count) values(i):=true
    }""")
    assert(errors.exists(_.message.contains("TYPED-RANGE-HOST-TOPOLOGY")),errors.mkString("\n"))
  }


  for(start<-Seq("1","p.bytes")) test(s"immutable nonzero start $start") {
    assert(check(s"case class Config(bytes:Int);class C(p:Config){val out=new Wires;for(i<-$start until 5)out(i):=true}").isEmpty)
  }
  for(member<-Seq("var bytes:Int=1","def bytes:Int=1")) test(s"reject effectful start $member") {
    val errors=check(s"class Config {$member};class C(p:Config){val out=new Wires;for(i<-p.bytes until 5)out(i):=true}")
    assert(errors.exists(_.message.contains("CONSTANT-LOOP-BOUND-UNSUPPORTED")),errors.mkString("\n"))
  }

  test("immutable Vec length owner") {
    assert(check("class C {val vec=new Vec;val out=new Wires;for(i<-0 until vec.length)out(i):=true}").isEmpty)
  }
  for(member<-Seq("var vec=new Vec","def vec=new Vec")) test(s"reject unstable Vec length owner $member") {
    val errors=check(s"class C {$member;val out=new Wires;for(i<-0 until vec.length)out(i):=true}")
    assert(errors.exists(_.message.contains("CONSTANT-LOOP-BOUND-UNSUPPORTED")),errors.mkString("\n"))
  }

}
