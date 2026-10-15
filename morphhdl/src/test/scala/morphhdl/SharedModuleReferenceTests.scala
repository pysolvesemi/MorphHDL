package morphhdl

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

class SharedModuleReferenceTests extends AnyFunSuite {
  private def directory(prefix: String): java.nio.file.Path = sys.env.get("MORPHHDL_VEC_EVIDENCE") match {
    case Some(path) => Files.createTempDirectory(Files.createDirectories(java.nio.file.Paths.get(path)), prefix)
    case None => Files.createTempDirectory(prefix)
  }
  private def generate(mode: String, split: Boolean = false): String = {
    val dir = directory("shared-reference-" + mode)
    MorphVerilog(SpinalConfig(targetDirectory=dir.toString, oneFilePerComponent=split, headerWithDate=false)) {
      new Component {
        setDefinitionName("ReferenceTop")
        @dontName val width = morphhdl.frontend.HdlInt.param("WIDTH", 8, 1, 16).asElabInt
        val data = in Bits(width bits)
        val a = out Bits(width bits)
        val b = out Bits(width bits)
        class Child extends Component {
          setDefinitionName("SharedInvert")
          @dontName val bits = ElabFormalParameter(width, "BITS", 1, 16)
          val x = in Bits(bits bits)
          val y = out Bits(bits bits)
          y := ~x
        }
        val implementation = new Child
        implementation.x := data
        a := implementation.y
        val reference = new BlackBox {
          setBlackBoxName(if(mode=="name") "WrongName" else "SharedInvert")
          if(mode=="untyped") addGeneric("BITS", 8)
          else if(mode!="missing") addGeneric("BITS", if(mode=="domain") width+16 else width)
          val x = in Bits((if(mode=="width") width+1 else width) bits)
          val y = out Bits(width bits)
          if(mode=="implementation") setInlineVerilog("module SharedInvert(); endmodule")
        }
        if(mode!="unauthenticated") ElabModuleReference.bind(reference, implementation)
        if(mode=="width") reference.x := data.resize(width+1) else reference.x := data
        b := reference.y
      }
    }
    dir.toFile.listFiles().filter(_.getName.endsWith(".v")).map(f =>
      new String(Files.readAllBytes(f.toPath), "UTF-8")).mkString("\n")
  }
  for(split <- Seq(false,true)) test(s"explicit shared sibling validates symbolic substituted ports split=$split") {
    val rtl=generate("valid",split)
    assert("(?m)^module SharedInvert".r.findAllIn(rtl).size==1,rtl)
    assert("\\.BITS\\s*\\(WIDTH".r.findAllIn(rtl).size==2,rtl)
  }
  for(mode <- Seq("name","untyped","missing","domain","width","implementation","unauthenticated"))
  test(s"shared sibling rejects $mode") {
    val error=intercept[MorphVerilogException] { generate(mode) }
    assert(error.getMessage.contains(if(mode=="unauthenticated") "RECURSION-NONLOCAL-REFERENCE-UNSUPPORTED" else "SHARED-REFERENCE-MISMATCH"),error.getMessage)
  }
}
