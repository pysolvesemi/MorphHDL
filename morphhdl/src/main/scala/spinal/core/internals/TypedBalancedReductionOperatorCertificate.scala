package spinal.core.internals

import spinal.core._

/** Native callback-body evidence. The operation key describes the ordered
  * graph, not an algebraic licence to reassociate the native reduction tree.
  */
private[spinal] trait TypedBalancedReductionOperatorCertificate {
  def nativeResult: BaseType
  def resultWidth: ElaborationIntegerExpression
  def operatorClass: Class[_]
  def operationKey: Any
  def validateFreshness(): Unit
  def replay(left: BaseType, right: BaseType): BaseType
}
