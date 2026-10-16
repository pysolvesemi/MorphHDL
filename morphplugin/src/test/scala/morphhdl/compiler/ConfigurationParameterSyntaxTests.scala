package morphhdl.compiler

import org.scalatest.funsuite.AnyFunSuite

class ConfigurationParameterSyntaxTests extends AnyFunSuite {
  import MorphHdlCompilerTestSupport._
  private val definitions="""
package spinal.core {
 class Component
 class rtlParamName(val name:String) extends scala.annotation.StaticAnnotation
 trait Param[T]
 object Param {def apply(n:Int):ElabInt=new ElabInt}
 class ElabInt {def >=(n:Int):ElabBool=new ElabBool;def <=(n:Int):ElabBool=new ElabBool;def +(n:Int):ElabInt=this}
 class ElabBool {def &&(b:ElabBool):ElabBool=this}
 object ConstructorParam {
   class Integer(val value:ElabInt)
   object Integer {implicit def literal(v:Int):Integer=new Integer(new ElabInt);implicit def symbolic(v:ElabInt):Integer=new Integer(v)}
 }
 object RtlParam {def inferredInt(name:String,n:ElabInt,lo:BigInt,hi:BigInt):ElabInt=n}
 object ElabControl {
   def requireCondition(p:ElabBool,file:String,line:Int):Unit=()
   def localValue[T](value:T,name:String):T=value
 }
}
"""
  private def check(source:String)=compile(definitions+source,"ConfigurationProbe.scala")
  test("nested immutable configuration formals and child pass-through") {
    val errors=check("""package probe {import spinal.core._
      case class Video(numBytes:Param[Int], lanes:Int)
      case class Config(video:Video)
      class Child(c:Config) extends Component {
        require(c.video.numBytes>=1 && c.video.numBytes<=8)
        val result:ElabInt=c.video.numBytes
      }
      class Parent(c:Config) extends Component {
        require(c.video.numBytes>=1 && c.video.numBytes<=8)
        val result:ElabInt=c.video.numBytes
        val child=new Child(c)
      }
      object Calls {val root=new Parent(Config(Video(4,2)))}
    }""")
    assert(errors.isEmpty,errors.mkString("\n"))
  }
  test("configuration field constraints cannot use defaults as domains") {
    val errors=check("""package probe {import spinal.core._
      case class Config(count:Param[Int])
      class Child(c:Config) extends Component {val result:ElabInt=c.count}
    }""")
    assert(errors.exists(_.message.contains("PARAM-DOMAIN-UNPROVEN")),errors.mkString("\n"))
  }
  test("mutable configuration is rejected") {
    val errors=check("""package probe {import spinal.core._
      case class Config(var count:Param[Int])
      class Child(c:Config) extends Component {require(c.count>=1 && c.count<=8)}
    }""")
    assert(errors.exists(_.message.contains("CONFIG-SHAPE-UNSUPPORTED")),errors.mkString("\n"))
  }
  test("normalized field paths cannot collide") {
    val errors=check("""package probe {import spinal.core._
      case class Config(numBytes:Param[Int], num_bytes:Param[Int])
      class Child(c:Config) extends Component {
        require(c.numBytes>=1 && c.numBytes<=8)
        require(c.num_bytes>=1 && c.num_bytes<=8)
      }
    }""")
    assert(errors.exists(_.message.contains("PARAM-NAME-COLLISION")),errors.mkString("\n"))
  }
  test("explicit constructor name collisions fail during compilation") {
    val errors=check("""package probe {import spinal.core._
      class Child(@rtlParamName("WIDTH") A:Param[Int],@rtlParamName("WIDTH") B:Param[Int]) extends Component {
        require(A>=1 && A<=8);require(B>=1 && B<=8)
      }
    }""")
    assert(errors.exists(e=>e.message.contains("PARAM-NAME-COLLISION") && e.message.contains("A") && e.message.contains("B")),errors.mkString("\n"))
  }
  test("explicit names collide with normalized configuration paths") {
    val errors=check("""package probe {import spinal.core._
      case class Config(numBytes:Param[Int],@rtlParamName("C_NUM_BYTES") other:Param[Int])
      class Child(c:Config) extends Component {
        require(c.numBytes>=1 && c.numBytes<=8);require(c.other>=1 && c.other<=8)
      }
    }""")
    assert(errors.exists(e=>e.message.contains("PARAM-NAME-COLLISION") && e.message.contains("c.numBytes") && e.message.contains("c.other")),errors.mkString("\n"))
  }
  test("cyclic configuration types diagnose without traversing indefinitely") {
    val errors=check("""package probe {import spinal.core._
      class Config(val next:Config,val count:Param[Int])
      class Child(c:Config) extends Component {require(c.count>=1 && c.count<=8)}
    }""")
    assert(errors.exists(_.message.contains("CONFIG-CYCLE")),errors.mkString("\n"))
  }
  test("effectful configuration members are not evaluated for discovery") {
    val errors=check("""package probe {import spinal.core._
      class Config(val count:Param[Int]) {def computed:Int={println("effect");4}}
      class Child(c:Config) extends Component {require(c.count>=1 && c.count<=8)}
    }""")
    assert(errors.exists(_.message.contains("CONFIG-SHAPE-UNSUPPORTED")),errors.mkString("\n"))
  }
  test("plain immutable configuration classes are supported") {
    val errors=check("""package probe {import spinal.core._
      class Config(val count:Param[Int],val ordinary:Int)
      class Child(c:Config) extends Component {require(c.count>=1 && c.count<=8);val result:ElabInt=c.count}
      object Call {val child=new Child(new Config(4,7))}
    }""")
    assert(errors.isEmpty,errors.mkString("\n"))
  }
  test("configuration discovery rejects inherited user behavior") {
    val errors=check("""package probe {import spinal.core._
      trait Hidden {var mutable:Int=1}
      class Config(val count:Param[Int]) extends Hidden
      class Child(c:Config) extends Component {require(c.count>=1 && c.count<=8)}
    }""")
    assert(errors.exists(_.message.contains("CONFIG-SHAPE-UNSUPPORTED")),errors.mkString("\n"))
  }
  test("local configuration names are owned by their declaring method") {
    val errors=check("""package probe {import spinal.core._
      object Definitions {
        def first:Unit={case class Config(count:Param[Int]);class Child(c:Config) extends Component {
          require(c.count>=1 && c.count<=8)};new Child(Config(4));()}
        def second:Unit={case class Config(width:Param[Int]);class Child(c:Config) extends Component {
          require(c.width>=1 && c.width<=8)};new Child(Config(5));()}
      }
    }""")
    assert(errors.isEmpty,errors.mkString("\n"))
  }
  for(importClause<-Seq("import models.Config","import models.{Config => Renamed}","import models._"))
    test(s"source configuration import $importClause") {
      val name=if(importClause.contains("Renamed"))"Renamed"else "Config"
      // Source wildcard providers retain the existing fail-closed shadowing
      // rule. Root-qualified native names disambiguate them explicitly.
      val component=if(importClause.endsWith("._"))"_root_.spinal.core.Component"else "Component"
      val requireName=if(importClause.endsWith("._"))"_root_.scala.Predef.require"else "require"
      val errors=check(s"""package models {import spinal.core._;case class Config(count:Param[Int])}
        package probe {import spinal.core._;$importClause
          class Child(c:$name) extends $component {$requireName(c.count>=1 && c.count<=8);val result:_root_.spinal.core.ElabInt=c.count}
          object Calls {val child=new Child($name(4))}
        }
      """)
      assert(errors.isEmpty,errors.mkString("\n"))
    }
  for(reverse<-Seq(false,true))test(s"configuration schemas across files reverse=$reverse") {
    val schema="package models {import spinal.core._;case class Config(count:Param[Int])}"
    val consumer="""package probe {import spinal.core._;import models.Config
      class Child(c:Config) extends Component {require(c.count>=1 && c.count<=8);val result:ElabInt=c.count}
      object Calls {val child=new Child(Config(4))}}
    """
    val sources=List("Definitions.scala"->definitions,"Schema.scala"->schema,"Consumer.scala"->consumer)
    val ordered=if(reverse)sources.reverse else sources
    val errors=compile(ordered.head._2,ordered.head._1,ordered.tail)
    assert(errors.isEmpty,errors.mkString("\n"))
  }
  test("configuration and component constructor defaults retain typed actuals") {
    val errors=check("""package probe {import spinal.core._
      case class Video(count:Param[Int]=4,ordinary:Int=2)
      case class Config(video:Video=Video())
      class Child(c:Config=Config()) extends Component {
        require(c.video.count>=1 && c.video.count<=8);val result:ElabInt=c.video.count
      }
      object Calls {val child=new Child()}
    }""")
    assert(errors.isEmpty,errors.mkString("\n"))
  }
  test("root-qualified configuration types ignore same-named relative schemas") {
    val errors=check("""package models {import spinal.core._;case class Config(count:Param[Int])}
      package probe {import spinal.core._
        object models {case class Config(unrelated:Int)}
        class Child(c:_root_.models.Config) extends Component {
          require(c.count>=1 && c.count<=8);val result:ElabInt=c.count
        }
        object Calls {val child=new Child(_root_.models.Config(4))}
      }
    """)
    assert(errors.isEmpty,errors.mkString("\n"))
  }

}
