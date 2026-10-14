package nativeapplication

import spinal.core._

/** Adjacent-pair modular unsigned sum with independent word/count formals.
  * The interface and storage use the exact DATA_WIDTH formal throughout.
  */
class NativeWidthRecursiveTree(countActual: ElabInt, widthActual: ElabInt,
    maskActual: Option[ElabInt], difference: Boolean = false) extends Component {
  setDefinitionName(if(difference) "WidthDifferenceTree" else "WidthSumTree")
  @dontName val count=ElabFormalParameter(countActual,"COUNT",1,16)
  @dontName val width=ElabFormalParameter(widthActual,"DATA_WIDTH",1,64)
  @dontName val initial=ElabFormalParameter(countActual,"INITIAL_COUNT",1,16)
  @dontName val mask=maskActual.map(ElabFormalParameter(_,"PIPELINE_MASK",0,15))
  ElabControl.requireCondition(initial >= count,"INITIAL_COUNT must cover COUNT","WidthTree",1)
  class Ports(values: Vector[ElabInt]) extends Bundle {
    val clk,reset,enable=in Bool()
    val terms=in Bits((values(0)*values(1)) bits)
    val result=out UInt(values(1) bits)
  }
  @dontName val recursive=ElabRecursiveComponent(Vector(count,width,initial)++mask.toVector)(new Ports(_))
  val io=recursive.ports
  val logic=new ClockingArea(ClockDomain(io.clk,io.reset,clockEnable=io.enable,
      config=ClockDomainConfig(resetKind=ASYNC,resetActiveLevel=LOW))) {
    val lanes=Vec(UInt(width bits),count)
    lanes.assignFromBits(io.terms)
    ElabControl.selectSymbolic(count <= 1,"WidthTree",2) {
      io.result := lanes(0)
    } {
      @dontName val nextCount=(count+1)/2
      val combined=Vec(UInt(width bits),nextCount)
      ElabFiniteRange.foreach(nextCount,"combine") { i =>
        i.whenAffineLessThan(2,1,count,"pair") {
          val left=i.affine(lanes,2,0); val right=i.affine(lanes,2,1)
          i(combined) := (if(difference) left-right else left+right)
        } { i(combined) := i.affine(lanes,2,0) }
      }
      val nextData=if(mask.nonEmpty) {
        val values=Vec(UInt(width bits),nextCount)
        @dontName val registered=(mask.get*count.log2Up.pow2/initial.log2Up.pow2)%2
        ElabFiniteRange.foreach(nextCount,"pipeline") { i =>
          ElabControl.selectSymbolic(registered.elabEq(1),"WidthTree",3) {
            i(values) := (RegNext(i(combined)) init(0))
          } { i(values) := i(combined) }
        }
        values
      } else combined
      val child=recursive(count -> nextCount)
      child.ports.clk := io.clk
      child.ports.reset := io.reset
      child.ports.enable := io.enable
      child.ports.terms := nextData.asBits
      io.result := child.ports.result
    }
  }
}
