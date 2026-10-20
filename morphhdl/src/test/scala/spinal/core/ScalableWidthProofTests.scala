package spinal.core

import morphhdl.frontend.HdlInt
import org.scalatest.funsuite.AnyFunSuite

class ScalableWidthProofTests extends AnyFunSuite {
  private def param(name: String = "TIMEOUT") = HdlInt.param(name, 1023, 32, 16777216).asElabInt
  private def proves(a:ElaborationIntegerExpression,b:ElaborationIntegerExpression,op:String="==") =
    ElaborationWidthAuthority.provesPositiveComparison(a,b,op)
  test("logarithmic width identity and dominated min/max are proved without enumerating timeout tuples") {
    val n=param(); val w=(n+1).log2Up.expression
    val one=ElabInt.literal(1).expression; val huge=ElabInt.literal(64).expression
    val selected=ElaborationWidthAuthority.minimum(ElaborationWidthAuthority.maximum(w,one),huge)
    assert(proves(w,w)); assert(proves(selected,w)); assert(proves(w,selected))
    assert(proves(w,huge,"<="))
    assert(n.expression.minimum==32 && n.expression.maximum==16777216)
  }
  test("default-equal logarithms and unrelated roots do not authorize a width match") {
    val n=param(); val w=(n+1).log2Up.expression; val other=n.log2Up.expression
    assert(w.default==other.default)
    assert(!proves(w,other)); assert(!proves(w,ElabInt.literal(11).expression))
    assert(!proves(w,(param("OTHER")+1).log2Up.expression))
    val growing=ElaborationWidthAuthority.maximum(w,ElabInt.literal(11).expression)
    assert(!proves(growing,w))
  }
  test("logarithmic boundary values retain their complete parameter geometry") {
    val n=param(); val width=(n+1).log2Up.expression
    val root=n.expression.completedParameterRoots.head
    val points=(Seq(32,16777216) ++ (5 to 24).flatMap(k=>Seq((1<<k)-1,1<<k,(1<<k)+1))).filter(x=>x>=32 && x<=16777216).distinct
    for(value<-points) assert(ElaborationProductDomain.evaluate(width,Vector(root->BigInt(value))).contains(BigInt(BigInt(value).bitLength)))
  }
  test("copied certificates and nonpositive widths retain rejection") {
    val w=(param()+1).log2Up.expression
    intercept[ParameterizedVerilogException]{proves(w.copy(),w)}
    assert(!proves(ElabInt.literal(0).expression,ElabInt.literal(0).expression))
    val a=param();val b=param()
    intercept[ParameterizedVerilogException]{proves((a+1).log2Up.expression,(b+1).log2Up.expression)}
  }
}
