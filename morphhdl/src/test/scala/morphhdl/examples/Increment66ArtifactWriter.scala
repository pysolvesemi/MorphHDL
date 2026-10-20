package morphhdl.examples

import java.nio.file.{Files,Paths}
import spinal.core._
import morphhdl._
import morphhdl.frontend.HdlInt

/** Representative artifacts from the same public fixtures used by the
  * permanent simulations and proofs. Always pass an absolute destination.
  */
object Increment66ArtifactWriter {
  def main(args:Array[String]):Unit={
    require(args.length==1)
    val root=Paths.get(args(0)).toAbsolutePath.normalize()
    Files.createDirectories(root)
    def emit(name:String,cdc:Boolean=false)(component: => Component):Unit={
      val config=SpinalConfig(targetDirectory=root.resolve(name).toString,
        oneFilePerComponent=true,headerWithDate=false,
        defaultConfigForClockDomains=ClockDomainConfig(resetKind=ASYNC,
          resetActiveLevel=if(cdc) LOW else HIGH))
      MorphVerilog(config)(component)
    }
    val loops=new NativeConditionalProcessTests
    emit("loop-constant")(new loops.ConditionalLaneLoop(false))
    emit("loop-parameterized")(new loops.ConditionalLaneLoop(true))
    emit("resize")(new ParameterExtensionsBaselineWriter.SymbolicResizeProbe(
      HdlInt.param("BUS_LOG2_BYTES",3,2,5).asElabInt.pow2*8/8))
    emit("symbolic-value")(new SymbolicValueProbe(HdlInt.param("BUS_LOG2_BYTES",3,2,5).asElabInt.pow2))
    val formals=new spinal.core.NativeExplicitFormalTests
    emit("formal-width")(new formals.Parent(0))
    emit("formal-zero")(new formals.Parent(2))
    emit("formal-multilevel")(new formals.Parent(3))
    emit("formal-hdlint-scalar")(new ParameterExtensionsBaselineWriter.ScalarTop(false))
    emit("formal-hdlint-zero")(new ParameterExtensionsBaselineWriter.ScalarTop(true))
    val cdc=new spinal.core.NativeCdcFormalTests
    emit("cdc-hierarchy",cdc=true)(new cdc.CdcParent(false))
    emit("cdc-standalone",cdc=true)(new cdc.CdcParent(true))
    val decoder=new NativeTypedLocalConstantTests
    emit("locals-fixed")(new decoder.Decoder(false))
    emit("locals-derived")(new decoder.Decoder(true))
    val factories=new NativeNamedFactoryTests
    for(lite<-Seq(false,true);symbolic<-Seq(false,true)) {
      val kind=if(lite) "axilite" else "axi4"
      emit(s"$kind-${if(symbolic) "derived" else "fixed"}")(new factories.Factory(lite,true,4,symbolic))
    }
  }
}
