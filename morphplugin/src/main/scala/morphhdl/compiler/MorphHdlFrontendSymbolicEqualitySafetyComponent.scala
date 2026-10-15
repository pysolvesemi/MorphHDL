package morphhdl.compiler

import scala.tools.nsc.Global
import scala.tools.nsc.Phase
import scala.tools.nsc.plugins.PluginComponent

/**
  * Reject Scala equality whose right operand is a typed MorphHDL frontend
  * symbolic value and whose receiver is not symbolic. Such calls dispatch to
  * the receiver's ordinary Scala equality and would silently erase parameter
  * meaning.
  *
  * This check deliberately runs on typed trees. It belongs to the MorphHDL
  * plugin rather than the native SpinalHDL plugin, and it has no compile-time
  * dependency on the frontend classes whose fully-qualified names it checks.
  */
final class MorphHdlFrontendSymbolicEqualitySafetyComponent(val global: Global)
    extends PluginComponent {
  import global._

  override val phaseName: String = "morphhdl-frontend-symbolic-equality-safety"
  override val runsAfter: List[String] = List("idsl-plugin", "uncurry")
  override val runsBefore: List[String] = List("explicitouter")

  private val morphFrontendSymbolicTypes = Set(
    "morphhdl.frontend.HdlInt",
    "morphhdl.frontend.HdlBool",
    "morphhdl.frontend.GenIndex"
  )

  private val scalaEqualityMethods = Set("==", "!=", "equals", "eq", "ne")

  private def isMorphFrontendSymbolic(tree: Tree): Boolean = {
    val treeType = tree.tpe
    treeType != null && treeType != NoType && {
      treeType.dealias.widen.baseClasses.exists(symbol =>
        symbol != null && symbol != NoSymbol && morphFrontendSymbolicTypes(symbol.fullName)
      )
    }
  }

  private val nativeCarriers = Set("spinal.core.ElabInt", "spinal.core.ElabBool",
    "spinal.core.ElabScopedIndex", "spinal.core.ElabScopedCondition")
  private val numericOrBoolean = Set("scala.Byte", "scala.Short", "scala.Int", "scala.Long",
    "scala.Float", "scala.Double", "scala.Char", "scala.Boolean", "scala.math.BigInt", "scala.math.BigDecimal")
  private def hasBase(tree: Tree, names: Set[String]): Boolean =
    tree.tpe != null && tree.tpe != NoType && tree.tpe.dealias.widen.baseClasses.exists(s =>
      s != null && s != NoSymbol && names(s.fullName))

  private def rejectUnstagedNativeEquality(tree: Apply): Unit = tree.fun match {
    case Select(receiver, method) if Set("==", "!=")(method.decodedName.toString) && tree.args.size == 1 =>
      val other = tree.args.head
      if ((hasBase(receiver,nativeCarriers) && hasBase(other,nativeCarriers ++ numericOrBoolean)) ||
          (hasBase(other,nativeCarriers) && hasBase(receiver,numericOrBoolean)))
        global.globalError(tree.pos,
          "[MORPHDL-NATIVE-SYMBOLIC-EQUALITY-UNSTAGED] symbolic equality was not staged; " +
          "bind helper results to an explicit ElabInt/ElabBool or use a supported parameter predicate")
    case _ =>
  }

  private def rejectReverseMorphFrontendEquality(tree: Apply): Unit = {
    val call = tree.fun match {
      case Select(receiver, method)               => Some((receiver, method))
      case TypeApply(Select(receiver, method), _) => Some((receiver, method))
      case _                                      => None
    }
    call match {
      case Some((receiver, method))
          if scalaEqualityMethods(method.decodedName.toString) &&
            tree.args.size == 1 && !isMorphFrontendSymbolic(receiver) &&
            isMorphFrontendSymbolic(tree.args.head) =>
        global.globalError(
          tree.pos,
          "[MORPH-FRONTEND-SYMBOLIC-COMPARISON-UNSUPPORTED] " +
            "a statically typed HdlInt, HdlBool or GenIndex cannot be the right operand of Scala equality; " +
            "use a static condition or a supported parameter-aware operation"
        )
      case _ =>
    }
  }

  override def newPhase(previous: Phase): Phase = new StdPhase(previous) {
    override def apply(unit: CompilationUnit): Unit = {
      object SafetyChecker extends Traverser {
        override def traverse(tree: Tree): Unit = {
          tree match {
            // Case-class equals and other compiler-owned methods compare
            // metadata values. They are not authored hardware predicates.
            case method: DefDef if method.mods.hasFlag(Flag.SYNTHETIC) => return
            case _ =>
          }
          tree match {
            case application: Apply =>
              rejectReverseMorphFrontendEquality(application)
              rejectUnstagedNativeEquality(application)
            case _                  =>
          }
          super.traverse(tree)
        }
      }
      SafetyChecker.traverse(unit.body)
    }
  }
}
