package morphhdl

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

class LightweightPortFactoryTests extends AnyFunSuite {
  for(hardType <- Seq(false,true); syntax <- Seq("braces","short","fill"))
    test(s"typed Vec direction factories agree hardType=$hardType syntax=$syntax") {
      val setters = classOf[IODirection].getMethods.filter(m => m.getName.contains("IODirection$_setter_$Vec"))
      assert(setters.forall(m => !java.lang.reflect.Modifier.isAbstract(m.getModifiers)), setters.mkString("\n"))
      val dir=Files.createTempDirectory("lightweight-port-factory-")
      MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=dir.toString),
        preserveConstantVecs=true,preserveConstantLoops=true,
        vecLayout=MorphAggregateOptions.UnpackedArray)) {
        new Component {
          setDefinitionName("PortFactory")
          val count: ElabInt=RtlParam.int("COUNT",3,1,8)
          val width: ElabInt=RtlParam.int("WIDTH",8,1,16)
          val dataType=HardType(SInt(width bits))
          val source=if(syntax == "fill") in.Vec.fill(count)(dataType()) else if(syntax == "short") {
            if(hardType) in.Vec(dataType,count) else in.Vec(SInt(width bits),count)
          } else in(Vec(dataType,count))
          val result=if(syntax == "fill") out.Vec.fill(count)(dataType()) else if(syntax == "short") {
            if(hardType) out.Vec(dataType,count) else out.Vec(SInt(width bits),count)
          } else out(Vec(dataType,count))
          assert((source: Data).flatten.forall(_.isInput))
          assert((result: Data).flatten.forall(_.isOutput))
          for(i <- 0 until count) result(i) := source(i)
        }
      }
      val rtl=new String(Files.readAllBytes(dir.resolve("PortFactory.v")),"UTF-8")
      assert(rtl.contains("input") && rtl.contains("source") && rtl.contains("output") && rtl.contains("result"),rtl)
      val(status,log)=Increment66ToolEvidence.run(dir,Seq("iverilog","-g2001","-s","PortFactory","-o","sim","PortFactory.v"))
      assert(status==0,log)
    }
}
