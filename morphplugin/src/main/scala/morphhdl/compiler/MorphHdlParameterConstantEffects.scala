package morphhdl.compiler

import scala.tools.nsc.{Global, Phase}
import scala.tools.nsc.plugins.PluginComponent

/** Check initializer effects using resolved symbols, never a callee's spelling.
  * Hardware constancy itself is subsequently proved against native identities.
  */
final class MorphHdlParameterConstantEffects(val global: Global) extends PluginComponent {
  import global._
  override val phaseName = "morphhdl-parameter-constant-effects"
  override val runsAfter = List("typer")
  override val runsBefore = List("patmat")
  override def newPhase(previous: Phase): Phase = new StdPhase(previous) {
    override def apply(unit: CompilationUnit): Unit = {
      val definitions=scala.collection.mutable.Map.empty[Symbol,DefDef]
      object Methods extends Traverser {
        override def traverse(t:Tree):Unit={
          t match {case d:DefDef if d.symbol!=NoSymbol=>definitions(d.symbol)=d;case _=>}
          super.traverse(t)
        }
      }
      currentRun.units.foreach(u=>Methods.traverse(u.body))
      val operations=Set("+","-","*","/","%","&","|","^","<<",">>",">>>",
        "<","<=",">",">=","==","!=","unary_-","unary_+","unary_!","unary_~",
        "&&","||","toInt","toLong","toBigInt")
      def trusted(symbol:Symbol):Boolean = {
        if(symbol==null || symbol==NoSymbol) return false
        val owner=symbol.owner.fullName
        val method=symbol.name.decodedName.toString
        (owner=="spinal.idslplugin.Location" && symbol.isConstructor) ||
        (Set("scala.Int","scala.Long","scala.Boolean","scala.math.BigInt")(owner) &&
          (operations(method) || Set("apply","int2bigInt","long2bigInt")(method))) ||
        (owner=="scala.math.BigInt" && method=="apply") ||
        (Set("spinal.core.UInt","spinal.core.SInt","spinal.core.Bits")(owner) && Set("+","-","&","|","^")(method)) ||
        (owner=="scala.Predef" && Set("intWrapper","longWrapper")(method)) ||
        (owner.startsWith("spinal.core.") && (
          Set("B","U","S","BitVectorLiteralFactory","BitsLiteralFactory","UIntLiteralFactory","SIntLiteralFactory","BoolFactory","BoolLiteralFactory",
            "BitCount","ElabInt","ElabBool","ElabValue","IntToWidth","IntToBuilder","package.IntBuilder","package.BigIntBuilder","IntBuilder","BigIntBuilder").exists(n=>owner=="spinal.core."+n) ||
          (owner=="spinal.core.package" && Set("IntToWidth","IntToBuilder","BigIntToBuilder","True","False")(method))))
      }
      var rejectedCall=""
      def stableAccessor(s:Symbol):Boolean = s.isAccessor && !s.isSetter &&
        (s.isStable || (s.accessed!=NoSymbol && s.accessed.isTerm && !s.accessed.asTerm.isVar))
      def pure(t:Tree, active:Set[Symbol]):Boolean=t match {
        case EmptyTree | _:Literal | _:This | _:TypeTree => true
        case New(tpt) if tpt.tpe!=null && tpt.tpe.typeSymbol.fullName=="spinal.idslplugin.Location"=>true
        case Typed(value,_)=>pure(value,active)
        case TypeApply(f,_)=>pure(f,active)
        case Ident(_) =>
          val s=t.symbol
          s!=null && s!=NoSymbol && (s.isModule || s.isPackage || s.isParameter ||
            (s.isTerm && !s.isMethod && !s.asTerm.isVar) || (s.isMethod && stableAccessor(s)))
        case Select(q,_) if t.symbol!=null && t.symbol!=NoSymbol && t.symbol.isMethod && !t.symbol.isAccessor =>
          callable(t.symbol,active) && pure(q,active)
        case Select(q,_)=>
          val s=t.symbol
          s!=null && s!=NoSymbol && !s.isSetter &&
            (!s.isAccessor || stableAccessor(s)) && (s.isMethod || !s.isTerm || !s.asTerm.isVar) && pure(q,active)
        case Apply(f,args)=>callable(f.symbol,active) && receiverPure(f,active) && args.forall(pure(_,active))
        case If(c,a,b)=>pure(c,active)&&pure(a,active)&&pure(b,active)
        case Block(stats,result)=>stats.forall {
          case v:ValDef=> !v.mods.hasFlag(Flag.MUTABLE) && pure(v.rhs,active)
          case _=>false
        } && pure(result,active)
        case _=>false
      }
      def receiverPure(t:Tree,active:Set[Symbol]):Boolean=t match {
        case Select(q,_)=>pure(q,active)
        case TypeApply(f,_)=>receiverPure(f,active)
        case _:Ident=>true
        case _=>false
      }
      def callable(s:Symbol,active:Set[Symbol]):Boolean = {
        val accepted = {
        if(s==null || s==NoSymbol || active(s)) false
        else if(s.isConstructor && s.owner.fullName!="spinal.idslplugin.Location") false
        else if(trusted(s)) true
        else if(stableAccessor(s)) true
        else definitions.get(s).exists(d=>pure(d.rhs,active+s))
        }
        if(!accepted && rejectedCall.isEmpty)rejectedCall=if(s==null || s==NoSymbol)"unresolved callee" else s.fullName
        accepted
      }
      object Check extends Traverser {
        override def traverse(t:Tree):Unit={
          t match {
            case Apply(f,args) if f.symbol!=null && f.symbol!=NoSymbol &&
              Set("spinal.core.Param.declare","spinal.core.Param.declareInt")(f.symbol.fullName) && args.size>=3 =>
              rejectedCall=""
              if(!pure(args(1),Set.empty)) reporter.error(args(1).pos,
                "MORPHDL-PARAM-INITIALIZER-EFFECT: initializer is not a proven pure constant expression; use immutable values, native literals or a pure source-visible helper" + (if(rejectedCall.isEmpty) "" else s" (unproven call: $rejectedCall)"))
            case _=>
          }
          super.traverse(t)
        }
      }
      Check.traverse(unit.body)
    }
  }
}
