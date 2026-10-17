package spinal.core.internals

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import morphhdl.{MorphAggregateOptions,MorphVerilog}

class StaticVecBitWriteIdentityTests extends AnyFunSuite {
  for(mode <- Seq("bit","carrier","source","width-domain")) test(s"static bit write rejects changed or invalid evidence $mode") {
    val dir=Files.createTempDirectory("static-bit-identity-")
    val options=MorphAggregateOptions(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false),
      preserveConstantVecs=true,preserveConstantLoops=true,vecLayout=MorphAggregateOptions.UnpackedArray)
    if(mode != "width-domain") options.phasesInserters += { phases =>
      phases.insert(phases.indexWhere(_.isInstanceOf[PhaseVerilog]),new PhaseMisc {
        override def impl(pc:PhaseContext):Unit = {
          val write=ParameterizedVec.vectorsOf(pc.topLevel).flatMap(ParameterizedVec.operationsOf)
            .collectFirst {case value:ParameterizedVecStaticWrite if value.target.isInstanceOf[BitAssignmentFixed] => value}.get
          val bit=write.target.asInstanceOf[BitAssignmentFixed]
          mode match {
            case "bit" => bit.bitId=2
            case "carrier" => bit.out=pc.topLevel.getAllIo.collectFirst {case value:Bits if value.isOutput => value}.get
            case "source" => write.assignment.source=new BoolLiteral(true)
          }
        }
      })
    }
    val error=intercept[Exception] {
      MorphVerilog(options)(new Component {
        setDefinitionName("InvalidBit")
        val width=if(mode=="width-domain") RtlParam.int("WIDTH",8,1,16) else ElabInt.literal(8)
        val clearBit=in Bool();val source=in Bits(width bits);val result=out Bits((width*2) bits)
        val words=Vec.fill(2)(Reg(Bits(width bits)) init(0))
        when(!clearBit) { words(0):=source }
        when(clearBit) {words(0)(3):=False}
        words(1):=words(0)
        result:=words.asBits
      })
    }
    assert(error.toString.contains("SPINAL-PARAMETERIZED-VERILOG-VEC-STATIC-WRITE-EVIDENCE-MISMATCH"),error.toString)
    assert(!Files.exists(dir.resolve("InvalidBit.v")))
  }
}
