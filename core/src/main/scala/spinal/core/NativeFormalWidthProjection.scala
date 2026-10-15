package spinal.core

/** Definition widths stay local to their declaring child. An explicit width
  * query on an immediate child's port observes its authenticated instance actual.
  * No elaborated bit count or same-name parameter is substitution authority.
  */
private[spinal] object NativeFormalWidthProjection {
  private object Key
  private type Bindings = Vector[(ElaborationIntegerParameter, ElaborationIntegerExpression)]

  def retain(owner: Component, formal: ElaborationIntegerParameter,
      actual: ElaborationIntegerExpression): Unit = {
    val entries = owner.userCache.get(Key).map(_.asInstanceOf[Bindings]).getOrElse(Vector.empty)
    require(!entries.exists { case (known, _) => known eq formal }, "duplicate formal width identity")
    owner.userCache(Key) = entries :+ (formal -> actual)
  }

  def inCurrentScope(port: BaseType, width: ElaborationIntegerExpression): ElaborationIntegerExpression = {
    val owner = port.component
    if (owner == null || Component.current == null || (owner.parent ne Component.current) || !port.isIo)
      return width
    val entries = owner.userCache.get(Key).map(_.asInstanceOf[Bindings]).getOrElse(Vector.empty)
    entries.collectFirst {
      case (formal, actual) if width.parameters.size == 1 &&
          (width.parameters.head eq formal) && width.generateIndex.isEmpty &&
          ElabInt.equivalentExactFunction(width, ElabInt.directParameter(formal, width.sourceLocation).expression) => actual
    }.getOrElse(width)
  }
}
