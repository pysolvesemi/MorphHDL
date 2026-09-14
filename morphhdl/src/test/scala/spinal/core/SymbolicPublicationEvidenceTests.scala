package spinal.core

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import morphhdl.MorphVerilog
import morphhdl.frontend.HdlInt
import org.scalatest.funsuite.AnyFunSuite

class SymbolicPublicationEvidenceTests extends AnyFunSuite {
  private def p(name: String, default: Int = 2): ElabInt =
    HdlInt.param(name, default, 1, 2048).asElabInt
  private def generate(body: => Component): String = {
    val dir = Files.createTempDirectory("symbolic-publication-")
    val config = SpinalConfig(targetDirectory=dir.toString, headerWithDate=false, headerWithRepoHash=true)
    config.netlistFileName = "dut.v"
    MorphVerilog(config)(body)
    new String(Files.readAllBytes(dir.resolve("dut.v")), StandardCharsets.UTF_8)
  }
  private def legality(condition: ElabBool, message: String): Unit =
    ElabControl.requireCondition(condition, message, "SymbolicPublicationEvidenceTests.scala", 1)
  private def error(body: => Any): ParameterizedVerilogException =
    intercept[ParameterizedVerilogException](body)
  private def failsWith(text: String)(body: => Any): Unit = {
    val thrown = intercept[Exception](body)
    val messages = Iterator.iterate[Throwable](thrown)(_.getCause)
      .takeWhile(_ != null).map(t => String.valueOf(t.getMessage)).mkString("\n")
    assert(messages.contains(text), messages)
  }
  private class Reduction(width: ElabInt) extends Component {
    val din = in Bits(width bits)
    val observed = out Bool()
    observed := din.orR
  }

  test("overlapping symbolic arithmetic publishes without attainable-extrema proof") {
    val a=p("A"); val b=p("B")
    val width=(a+b)%(a+1)+1
    assert(width.bits.value == 2)
    assert(error(width.maximum).code == "SPINAL-ELAB-DOMAIN-PRODUCT-CORRELATION-UNSUPPORTED")
    val verilog=generate(new Reduction(width))
    assert(verilog.contains("(((A + B) % (A + 1)) + 1)-1:0"))
    assert(verilog.contains("parameter integer A") && verilog.contains("parameter integer B"))
    assert(!verilog.contains("SYNTHESIS")) // positivity enclosure was sufficient; no clamp needed
  }
  test("owner provenance validation does not demand an exact value image") {
    val a=p("A"); val b=p("B"); val value=(a+b)%(a+1)+1
    val owner=ElaborationProductDomain.owner(value.expression,"test",None)((_,u)=>u).get
    assert(owner.publicationRange == (BigInt(1),BigInt(2049)))
    assert(owner.equivalent(owner))
    assert(error(owner.maximum).code == "SPINAL-ELAB-DOMAIN-PRODUCT-CORRELATION-UNSUPPORTED")
  }
  test("known invalid widths and defaults fail before physical substitution") {
    error(ElabInt.literal(0).bits)
    error(ElabInt.literal(-1).bits)
    intercept[Exception](HdlInt.param("BAD",0,1,4).asElabInt)
    failsWith("SPINAL-ELAB-INT-WIDTH-DOMAIN-INVALID")(generate(new Reduction(p("A",1)-p("B",2))))
  }
  test("possibly invalid nondefault widths retain raw positivity and a genuine safe AST") {
    val verilog=generate(new Reduction(p("A",32)-p("B",2)))
    assert(verilog.contains("$signed((A - B)) > 0"))
    assert(verilog.contains("? (A - B) : 1)-1:0"))
    val start=verilog.indexOf("`ifndef SYNTHESIS")
    val end=verilog.indexOf("`endif",start)
    assert(start >= 0 && verilog.indexOf("$error",start) < end)
    assert(verilog.indexOf("$fatal",start) < end)
    assert(verilog.indexOf("input") < start)
  }
  test("mixed symbolic requires are obligations even when the default violates them") {
    for(default <- Vector(1,4)) {
      val verilog=generate(new Component {
        val a=p("A",default); val b=p("B",2)
        val din=in Bits(3 bits); val observed=out Bool(); observed:=din.orR
        legality(a>=b,"A must be >= B")
      })
      assert(verilog.contains("parameter integer A = "+default))
      assert(verilog.contains("parameter integer B = 2"))
      assert(verilog.contains("$signed(A) >= $signed(B)"))
      assert(verilog.contains("`ifndef SYNTHESIS"))
    }
  }
  test("concrete and universally false requires still reject immediately") {
    failsWith("concrete false")(generate(new Component {
      require(1>2,"concrete false")
    }))
    failsWith("SPINAL-ELAB-REQUIRE-ALWAYS-FALSE")(generate(new Component {
      legality((p("A")<0)||(p("B")<0),"always false")
    }))
  }
  test("cheap universal predicate proofs and overlapping-root cancellation are retained") {
    val a=p("A"); val b=p("B"); val c=p("C")
    assert(NativeSymbolicLegality.classification((a>0)&&(b>0)) == ElabBool.AlwaysTrue)
    assert(NativeSymbolicLegality.classification((a<0)||(b<0)) == ElabBool.AlwaysFalse)
    assert(NativeSymbolicLegality.classification(a>=b) == ElabBool.Unknown)
    assert(((a+b)-(b+c)).elabEq(a-c).isAlwaysTrue)
    assert((a-a).minimum==0 && (a-a).maximum==0)
    assert((((a+b)%(a+1)+1)>0).isAlwaysTrue)
  }
  test("copied symbolic provenance cannot authorize publication or deferred legality") {
    val a=p("A"); val b=p("B"); val width=(a+b)%(a+1)+1
    error(ElabInt.fromExpression(width.expression.copy()).bits)
    val predicate=a>=b
    val copied=ElabBool(predicate.expression.copy(),ElabBool.Unknown)
    error(NativeSymbolicLegality.classification(copied))
    failsWith("SPINAL-ELAB-BOOL-EXACT-DOMAIN-REQUIRED")(generate(new Component {legality(copied,"forged")}))
  }
  test("symbolic obligations cannot turn unsupported Scala structure into defaults") {
    failsWith("SPINAL-ELAB-DOMAIN-PRODUCT-CORRELATION-UNSUPPORTED")(generate(new Component {
      val a=p("A"); val b=p("B")
      legality(a>=b,"deferred only")
      ElabControl.selectSymbolic(((a+b)%(a+1)) > b,"test",1) {
        val wire=Bits(3 bits); wire:=0
      } {val wire=Bits(3 bits); wire:=0}
    }))
  }
  test("mixed branch-scoped obligations are explicitly rejected rather than made global") {
    val a=p("A"); val b=p("B")
    failsWith("SPINAL-ELAB-REQUIRE-STRUCTURAL-SCOPE-UNSUPPORTED")(generate(new Component {
      ElabControl.selectSymbolic(a>1,"test",1) {
        legality(a>=b,"branch-local")
        val wire=Bits(3 bits); wire:=0
      } {val wire=Bits(3 bits); wire:=0}
    }))
  }
  test("checked nondefault arithmetic overflow is not relaxed by symbolic publication") {
    val a=p("A",1); val b=p("B",1)
    assert(error((a*b)*1024).code=="SPINAL-ELAB-DOMAIN-EVIDENCE-RESULT-OUT-OF-RANGE")
  }
  test("scalar child formal receives a compound actual without a product value table") {
    val verilog=generate(new Component {
      val a=p("A"); val b=p("B")
      val width=(a+b)%(a+1)+1
      val din=in Bits(width bits); val observed=out Bool()
      val child=ElabFormalComponent.parameter(width,"WIDTH",1,4096)(w=>new Reduction(w))
      child.din:=din; observed:=child.observed
    })
    assert(verilog.contains("parameter integer WIDTH"))
    assert(verilog.contains(".WIDTH((((A + B) % (A + 1)) + 1))"))
  }
  test("separate legality obligations cannot collapse same-named declaration identities") {
    failsWith("SPINAL-ELAB-INT-INDEPENDENT-ROOTS-UNSUPPORTED") (generate(new Component {
      val first = p("A"); val second = p("A"); val b = p("B")
      val din=in Bits(3 bits); val observed=out Bool(); observed:=din.orR
      legality(first>=b,"first declaration")
      legality(second>=b,"second declaration")
    }))
  }
  test("parameter schemas conflict even when used only in separate legality obligations") {
    failsWith("SPINAL-PARAMETERIZED-VERILOG-SCHEMA-CONFLICT") (generate(new Component {
      val first = p("A"); val b = p("B")
      val second = HdlInt.param("A",1,1,4).asElabInt
      val din=in Bits(3 bits); val observed=out Bool(); observed:=din.orR
      legality(first>=b,"first schema")
      legality(second>=b,"second schema")
    }))
  }
  test("legality diagnostic strings are escaped by the native emitter") {
    val text=generate(new Component {
      val a=p("A"); val b=p("B")
      val din=in Bits(3 bits); val observed=out Bool(); observed:=din.orR
      legality(a>=b,"quoted \"A\" and slash \\ then\nnext line")
    })
    assert(text.contains("quoted \\\"A\\\" and slash \\\\ then\\nnext line"))
  }

}
