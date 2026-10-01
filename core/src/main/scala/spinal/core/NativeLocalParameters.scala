package spinal.core

import java.util.IdentityHashMap
import scala.collection.mutable.ArrayBuffer

/** Naming is presentation metadata on exact typed calculations. It grants no
  * domain authority and is never reconstructed from witnesses or Verilog text.
  * Storage belongs to the elaborated component, so another generation cannot
  * reuse a declaration or an allocated name.
  */
object NativeLocalParameters {
  private[core] final case class Calculation(operator: String,
      operands: Vector[ElaborationIntegerExpression])
  private final case class Binding(expression: ElaborationIntegerExpression, hint: String, packedWidth: Option[Int] = None)
  private class State {
    val bindings = ArrayBuffer.empty[Binding]
    val names = new IdentityHashMap[ElaborationIntegerExpression, String]()
    val ordered = ArrayBuffer.empty[ElaborationIntegerExpression]
    var prepared = false
  }
  private object Key
  private def origin(value: ElaborationIntegerExpression): ElaborationIntegerExpression =
    value.localCalculationOrigin.getOrElse(value)

  /** Only the trusted projection path calls this after checking the domain.
    * A restricted projection and a public case-class copy inherit no names.
    */
  private[core] def projected(source: ElaborationIntegerExpression,
      target: ElaborationIntegerExpression): ElaborationIntegerExpression = {
    if (!ElaborationDomainContext.hasActiveRestrictions && (source ne target))
      target.localCalculationOrigin = Some(origin(source))
    target
  }
  private def state(owner: Component): State =
    owner.userCache.getOrElseUpdate(Key, new State).asInstanceOf[State]
  private def existing(owner: Component): Option[State] =
    Option(owner).flatMap(_.userCache.get(Key)).map(_.asInstanceOf[State])

  private[core] def calculated(result: ElabInt, operator: String, operands: ElabInt*): ElabInt = {
    if (!ElaborationDomainContext.hasActiveRestrictions)
      result.expression.localCalculation = Some(Calculation(operator, operands.map(_.expression).toVector))
    result
  }

  /** The existing compiler member callback supplies the hint, not identity.
    * Restricted branch expressions retain their original direct rendering.
    */
  private[core] def bind(owner: Component, value: ElabInt, hint: String): Unit = {
    if (value == null || value.isConcrete || value.expression.localCalculation.isEmpty ||
        ElaborationDomainContext.hasActiveRestrictions || (Component.current ne owner) ||
        (DslScopeStack.get ne owner.dslBody)) return
    val retained = state(owner)
    if (!retained.bindings.exists(_.expression eq value.expression))
      retained.bindings += Binding(value.expression, hint)
  }

  private[core] def bindTyped(owner: Component, value: ElabInt, hint: String, width: Int): Unit = {
    val retained = state(owner)
    require(!retained.prepared, "typed locals must be declared before publication")
    if (retained.bindings.exists(binding => binding.hint == hint && binding.packedWidth.nonEmpty))
      ParameterizedVerilogException.fail("SPINAL-LOCALPARAM-NAME-DUPLICATE", s"local '$hint' is already declared", None)
    if (retained.bindings.exists(binding => (binding.expression eq value.expression) && binding.packedWidth.nonEmpty))
      ParameterizedVerilogException.fail("SPINAL-LOCALPARAM-IDENTITY-DUPLICATE", "one exact value already owns a local declaration; reuse its handle", None)
    val visited = new IdentityHashMap[ElaborationIntegerExpression, java.lang.Boolean]()
    def checkDependency(raw: ElaborationIntegerExpression): Unit = {
      val expression = origin(raw)
      if (visited.containsKey(expression)) return
      visited.put(expression, true)
      if (expression.typedLocalOwner != null && (expression.typedLocalOwner ne owner))
        ParameterizedVerilogException.fail("SPINAL-LOCALPARAM-OWNER-MISMATCH", "typed local dependency belongs to another Component", None)
      expression.localCalculation.foreach(_.operands.foreach(checkDependency))
    }
    checkDependency(value.expression)
    value.expression.typedLocalOwner = owner
    val explicit = Binding(value.expression, hint, Some(width))
    val automatic = retained.bindings.indexWhere(_.expression eq value.expression)
    if (automatic < 0) retained.bindings += explicit else retained.bindings.update(automatic, explicit)
  }

  private[spinal] def typedExpressions(owner: Component): Vector[ElaborationIntegerExpression] =
    existing(owner).toVector.flatMap(_.bindings.filter(_.packedWidth.nonEmpty).map(_.expression))

  private[spinal] def hasTyped(owner: Component): Boolean = typedExpressions(owner).nonEmpty

  private def name(hint: String): String = {
    val words = hint.replaceAll("([a-z0-9])([A-Z])", "$1_$2")
      .replaceAll("([A-Z])([A-Z][a-z])", "$1_$2")
      .replaceAll("[^A-Za-z0-9_]", "_").toUpperCase(java.util.Locale.ROOT)
    if (words.isEmpty) "LOCAL_VALUE"
    else if (words.head.isDigit) "LOCAL_" + words else words
  }

  /** Called only by the parameterized backend, after user/formal names are
    * reserved and before native emission. Only live typed uses select locals.
    */
  private[spinal] def prepare(owner: Component,
      uses: Vector[ElaborationIntegerExpression]): Unit = existing(owner).foreach { retained =>
    require(!retained.prepared, "local parameters must be prepared once per component")
    val visiting = new IdentityHashMap[ElaborationIntegerExpression, java.lang.Boolean]()
    val visited = new IdentityHashMap[ElaborationIntegerExpression, java.lang.Boolean]()
    def visit(value: ElaborationIntegerExpression): Unit = {
      val expression = origin(value)
      if (visited.containsKey(expression)) return
      require(!visiting.containsKey(expression), "cycle in typed local parameter calculations")
      visiting.put(expression, true)
      expression.localCalculation.foreach(_.operands.foreach(visit))
      retained.bindings.find(_.expression eq expression).foreach { binding =>
        ElaborationWidthAuthority.requireAuthoritative(expression, "native local parameter",
          "SPINAL-LOCALPARAM-AUTHORITY-MISSING")
        val requested = if (binding.packedWidth.nonEmpty) binding.hint else name(binding.hint)
        val allocated = owner.localNamingScope.allocateName(requested)
        if (binding.packedWidth.nonEmpty && allocated != requested)
          ParameterizedVerilogException.fail("SPINAL-LOCALPARAM-NAME-COLLISION", s"local '$requested' collides with a native declaration", None)
        retained.names.put(expression, allocated)
        retained.ordered += expression
      }
      visiting.remove(expression)
      visited.put(expression, true)
    }
    (uses ++ typedExpressions(owner)).foreach(visit)
    retained.prepared = true
  }

  private[spinal] def reference(owner: Component,
      expression: ElaborationIntegerExpression): Option[String] =
    existing(owner).flatMap(value => Option(value.names.get(origin(expression))))

  private def render(owner: Component, expression: ElaborationIntegerExpression,
      definition: Boolean): String = {
    if (!definition) reference(owner, expression).foreach(value => return value)
    expression.localCalculation match {
      case Some(Calculation(operator, Vector(left, right)))
          if Set("+", "-", "*", "/", "%").contains(operator) =>
        s"(${render(owner, left, false)} $operator ${render(owner, right, false)})"
      case Some(Calculation("log2Up", Vector(value))) =>
        s"morphhdl_ceil_log2(${render(owner, value, false)})"
      case Some(Calculation("addressWidth", Vector(value))) =>
        s"morphhdl_address_width(${render(owner, value, false)})"
      case Some(Calculation("pow2", Vector(value))) =>
        s"(1 << (${render(owner, value, false)}))"
      case Some(_) => ParameterizedVerilogException.fail("SPINAL-LOCALPARAM-CALCULATION-INVALID",
        "native local parameter retains an unsupported typed calculation", expression.sourceLocation)
      case None => expression.verilog
    }
  }

  private[core] def declarations(owner: Component): String = existing(owner).map { retained =>
    retained.ordered.map { expression =>
      val packed = retained.bindings.find(_.expression eq expression).flatMap(_.packedWidth)
      val kind = packed.map(width => s"[${width - 1}:0]").getOrElse("integer")
      s"  localparam $kind ${retained.names.get(expression)} = ${render(owner, expression, true)};\n"
    }.mkString
  }.getOrElse("")
}
