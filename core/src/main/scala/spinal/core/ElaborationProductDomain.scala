package spinal.core

import java.lang.ref.{ReferenceQueue, WeakReference}
import scala.collection.mutable

/** Checked composition of typed expressions over independent declaration roots.
  *
  * This is deliberately not public expression metadata. Only authenticated
  * single-root leaves and already certified operations can enter this algebra;
  * the certificate belongs to the exact expression object, not its text or its
  * copyable case-class fields. Every source axis, including algebraically
  * cancelled dependencies, retains its schema identity and authorized scope.
  *
  * Sums are normalized as linear combinations of exact value functions. Terms
  * with overlapping roots are proved together; disjoint groups compose their
  * attainable extrema. Thus a large independent sum never constructs a product
  * table. Non-separable correlations use a bounded joint evaluator, or fail
  * explicitly. Bounds returned here are proved attainable extrema, not interval
  * guesses and never evaluations of only the default tuple.
  */
private[spinal] object ElaborationProductDomain {
  private val Role = "typed product-domain expression"
  private val Missing = "SPINAL-ELAB-DOMAIN-PRODUCT-AUTHORITY-MISSING"
  private val Unsupported = "SPINAL-ELAB-DOMAIN-PRODUCT-CORRELATION-UNSUPPORTED"
  private val MaximumJointValues = ElaborationExactDomain.MaximumDomainSize
  private val MaximumTerms = 256
  private val MaximumAxes = 32

  private type Root = ElaborationIntegerParameterRoot
  private type Environment = Map[Root, BigInt]

  private final case class Axis(domain: ElaborationExactDomain[_], values: Vector[BigInt]) {
    def root: Root = domain.root
    def schema: ElaborationIntegerParameter = domain.parameter
  }
  private final case class Extrema(minimum: BigInt, maximum: BigInt) {
    def constant: Boolean = minimum == maximum
    def scale(coefficient: BigInt): Extrema =
      if (coefficient >= 0) Extrema(minimum * coefficient, maximum * coefficient)
      else Extrema(maximum * coefficient, minimum * coefficient)
  }

  private sealed trait Atom {
    def roots: Set[Root]
    def evaluate(values: Environment): BigInt
  }
  private final case class Leaf(root: Root, values: Map[BigInt, BigInt]) extends Atom {
    val roots: Set[Root] = Set(root)
    def evaluate(environment: Environment): BigInt = values(environment(root))
  }
  private final case class Operation(operator: String, operands: Vector[Form]) extends Atom {
    lazy val roots: Set[Root] = operands.flatMap(_.roots).toSet
    def evaluate(environment: Environment): BigInt = {
      def at(index: Int): BigInt = operands(index).evaluate(environment)
      operator match {
        case "*" => at(0) * at(1)
        case "/" => at(0) / at(1)
        case "%" => at(0) % at(1)
        case "min" => at(0).min(at(1))
        case "max" => at(0).max(at(1))
        case "<" => boolean(at(0) < at(1))
        case "==" => boolean(at(0) == at(1))
        case "&&" => boolean(at(0) != 0 && at(1) != 0)
        case "||" => boolean(at(0) != 0 || at(1) != 0)
        case "choose" => if (at(0) != 0) at(1) else at(2)
        case "log2Up" => if (at(0) == 0) BigInt(0) else BigInt((at(0) - 1).bitLength)
        case "addressWidth" => BigInt(math.max(1, (at(0) - 1).bitLength))
        case "pow2" => BigInt(1) << at(0).toInt
        case "isPow2" => boolean(at(0) >= 0 && at(0).bitCount == 1)
        case other => throw new IllegalArgumentException(s"unknown certified operator '$other'")
      }
    }
  }
  private final case class Form(constant: BigInt, terms: Map[Atom, BigInt]) {
    lazy val roots: Set[Root] = terms.keysIterator.flatMap(_.roots).toSet
    def evaluate(environment: Environment): BigInt =
      terms.foldLeft(constant) { case (value, (atom, coefficient)) =>
        value + coefficient * atom.evaluate(environment)
      }
    def scale(coefficient: BigInt): Form =
      if (coefficient == 0) literal(0)
      else Form(constant * coefficient, terms.map { case (atom, value) => atom -> (value * coefficient) })
    def +(that: Form): Form = {
      val merged = that.terms.foldLeft(terms) { case (known, (atom, coefficient)) =>
        val sum = known.getOrElse(atom, BigInt(0)) + coefficient
        if (sum == 0) known - atom else known.updated(atom, sum)
      }
      if (merged.size > MaximumTerms)
        fail(Unsupported, s"normalized expression exceeds $MaximumTerms terms", None)
      Form(constant + that.constant, merged)
    }
    def -(that: Form): Form = this + that.scale(-1)
  }
  private def literal(value: BigInt): Form = Form(value, Map.empty)
  private def atom(value: Atom): Form = Form(0, Map(value -> BigInt(1)))
  private def boolean(value: Boolean): BigInt = if (value) BigInt(1) else BigInt(0)
  private def operation(operator: String, left: Form, right: Form): Form = operator match {
    case "+" => left + right
    case "-" => left - right
    case "*" if left.terms.isEmpty => right.scale(left.constant)
    case "*" if right.terms.isEmpty => left.scale(right.constant)
    case ">" => atom(Operation("<", Vector(right, left)))
    case "<=" => literal(1) - atom(Operation("<", Vector(right, left)))
    case ">=" => literal(1) - atom(Operation("<", Vector(left, right)))
    case _ => atom(Operation(operator, Vector(left, right)))
  }

  private final case class Certificate(form: Form, axes: Vector[Axis])
  private final class Identity(value: AnyRef, queue: ReferenceQueue[AnyRef])
      extends WeakReference[AnyRef](value, queue) {
    private val hash = System.identityHashCode(value)
    override def hashCode(): Int = hash
    override def equals(other: Any): Boolean = other match {
      case that: Identity => (this eq that) || ((get ne null) && (get eq that.get))
      case _ => false
    }
  }
  private val queue = new ReferenceQueue[AnyRef]()
  private val retained = mutable.HashMap.empty[Identity, Certificate]
  private def reap(): Unit = {
    var reference = queue.poll()
    while (reference != null) {
      retained.remove(reference.asInstanceOf[Identity])
      reference = queue.poll()
    }
  }
  private def certificate(value: AnyRef): Option[Certificate] = synchronized {
    reap()
    if (value == null) None else retained.get(new Identity(value, null))
  }
  private def retain(value: AnyRef, proof: Certificate): Unit = synchronized {
    reap()
    retained.put(new Identity(value, queue), proof)
    ()
  }
  private[core] def isRetained(value: ElaborationIntegerExpression): Boolean = certificate(value).nonEmpty
  private[core] def isRetained(value: ElaborationBooleanExpression): Boolean = certificate(value).nonEmpty
  private[core] def needs(left: ElaborationIntegerExpression, right: ElaborationIntegerExpression): Boolean =
    isRetained(left) || isRetained(right) ||
      (left.completedParameterRoots ++ right.completedParameterRoots).distinct.size > 1
  private[core] def needs(left: ElaborationBooleanExpression, right: ElaborationBooleanExpression): Boolean =
    isRetained(left) || isRetained(right) ||
      (left.completedParameterRoots ++ right.completedParameterRoots).distinct.size > 1

  private def fail(code: String, detail: String, location: Option[String]): Nothing =
    ParameterizedVerilogException.fail(code, s"$Role $detail", location)

  private def merge(sources: Vector[Certificate], location: Option[String]): Vector[Axis] = {
    val axes = sources.flatMap(_.axes).foldLeft(Vector.empty[Axis]) { (known, axis) =>
      known.find(_.root.name == axis.root.name) match {
        case Some(previous) if previous.schema != axis.schema =>
          fail("SPINAL-ELAB-INT-PARAMETER-SCHEMA-CONFLICT",
            s"has conflicting schemas for '${axis.root.name}'", location)
        case Some(previous) if (previous.root ne axis.root) || (previous.schema ne axis.schema) =>
          fail("SPINAL-ELAB-INT-INDEPENDENT-ROOTS-UNSUPPORTED",
            s"has distinct declarations for the same emitted name '${axis.root.name}'", location)
        case Some(previous) if previous.values != axis.values =>
          fail("SPINAL-ELAB-DOMAIN-EVIDENCE-SCOPE-MISMATCH",
            s"has incompatible authorized scopes for '${axis.root.name}'", location)
        case Some(_) => known
        case None => known :+ axis
      }
    }.sortBy(_.root.name)
    if (axes.size > MaximumAxes)
      fail(Unsupported, s"exceeds $MaximumAxes independent axes", location)
    axes
  }

  private def active(proof: Certificate, role: String, location: Option[String]): Certificate = {
    val axes = proof.axes.map { axis =>
      val requested = ElaborationDomainContext.admitted(axis.domain)
      if (requested.isEmpty || !requested.subsetOf(axis.values.toSet))
        fail("SPINAL-ELAB-DOMAIN-EVIDENCE-SCOPE-MISMATCH",
          s"$role cannot use '${axis.root.name}' outside its authorized branch", location)
      axis.copy(values = requested.toVector.sorted)
    }
    proof.copy(axes = axes)
  }
  private def validateInventory(parameters: Vector[ElaborationIntegerParameter], roots: Vector[Root],
                                proof: Certificate, role: String, location: Option[String]): Unit = {
    if (roots.size != proof.axes.size || parameters.size != proof.axes.size || proof.axes.exists { axis =>
        !roots.exists(_ eq axis.root) || !parameters.exists(_ eq axis.schema) ||
          !axis.root.isAuthoritativeSchema(axis.schema)
      }) fail(Missing, s"$role lost its declaration/schema identities", location)
  }
  private[core] def requireInteger(value: ElaborationIntegerExpression, role: String): Unit = {
    ElabInt.validateExpression(value, role)
    val proof = certificate(value).getOrElse(fail(Missing, s"$role lacks its private certificate", value.sourceLocation))
    if (value.generateIndex.nonEmpty) fail(Missing, s"$role includes a generate index", value.sourceLocation)
    validateInventory(value.parameters, value.completedParameterRoots, proof, role, value.sourceLocation)
    active(proof, role, value.sourceLocation)
    ()
  }
  private[core] def requireBoolean(value: ElaborationBooleanExpression, role: String): Unit = {
    ElabInt.validateExpression(value, role)
    val proof = certificate(value).getOrElse(fail(Missing, s"$role lacks its private certificate", value.sourceLocation))
    validateInventory(value.parameters, value.completedParameterRoots, proof, role, value.sourceLocation)
    active(proof, role, value.sourceLocation)
    ()
  }

  private def source(value: ElaborationIntegerExpression): Certificate = certificate(value) match {
    case Some(proof) =>
      requireInteger(value, Role)
      active(proof, Role, value.sourceLocation)
    case None =>
      val exact = ElabInt.requireAuthoritativeIntegerDomain(value, Role, Missing, requireExactExtrema = false)
      exact match {
        case None => Certificate(literal(value.default), Vector.empty)
        case Some(domain) =>
          val values = ElaborationDomainContext.requireEvidence(domain, Role, value.sourceLocation).toVector.sorted
          Certificate(atom(Leaf(domain.root, domain.evaluations.toMap)), Vector(Axis(domain, values)))
      }
  }
  private def source(value: ElaborationBooleanExpression): Certificate = certificate(value) match {
    case Some(proof) =>
      requireBoolean(value, Role)
      active(proof, Role, value.sourceLocation)
    case None =>
      val exact = ElabInt.requireAuthoritativeBooleanDomain(value, Role, Missing)
      exact match {
        case None => Certificate(literal(boolean(value.default)), Vector.empty)
        case Some(domain) =>
          val values = ElaborationDomainContext.requireEvidence(domain, Role, value.sourceLocation).toVector.sorted
          Certificate(atom(Leaf(domain.root, domain.evaluations.map { case (key, result) => key -> boolean(result) }.toMap)),
            Vector(Axis(domain, values)))
      }
  }
  private def representative(axes: Vector[Axis]): Environment = axes.map { axis =>
    axis.root -> (if (axis.values.contains(axis.schema.default)) axis.schema.default else axis.values.min)
  }.toMap
  private def relevant(roots: Set[Root], axes: Vector[Axis]): Vector[Axis] = axes.filter(axis => roots(axis.root))
  private def domainSize(axes: Vector[Axis]): BigInt = axes.foldLeft(BigInt(1))((n, axis) => n * axis.values.size)

  /** Streaming fallback: no product vector is built, and only overlapping
    * dependencies enter this evaluator. The cap is a rejection, not a sample.
    */
  private def foreachTuple(axes: Vector[Axis], location: Option[String])(body: Environment => Unit): Unit = {
    val size = domainSize(axes)
    if (size > MaximumJointValues)
      fail(Unsupported, s"non-separable correlation requires $size tuples, above the exact fallback limit $MaximumJointValues",
        location)
    def visit(index: Int, values: Environment): Unit = {
      if (index == axes.size) body(values)
      else axes(index).values.foreach(value => visit(index + 1, values.updated(axes(index).root, value)))
    }
    visit(0, Map.empty)
  }
  private def enumerate(form: Form, axes: Vector[Axis], location: Option[String]): Extrema = {
    var low: Option[BigInt] = None
    var high: Option[BigInt] = None
    foreachTuple(relevant(form.roots, axes), location) { values =>
      val result = form.evaluate(values)
      low = Some(low.fold(result)(_.min(result)))
      high = Some(high.fold(result)(_.max(result)))
    }
    Extrema(low.get, high.get)
  }
  private def image(form: Form, axes: Vector[Axis], location: Option[String]): Set[BigInt] = {
    val results = mutable.HashSet.empty[BigInt]
    foreachTuple(relevant(form.roots, axes), location)(values => results += form.evaluate(values))
    results.toSet
  }

  private def bounds(form: Form, axes: Vector[Axis], location: Option[String]): Extrema = {
    // Connected components, not a binary 'different roots' heuristic: a term
    // depending on {a,b} and one depending on {b,c} belong to the same proof.
    var groups = Vector.empty[Map[Atom, BigInt]]
    form.terms.foreach { case term @ (current, _) =>
      val (overlap, disjoint) = groups.partition(group =>
        group.keysIterator.exists(other => (current.roots intersect other.roots).nonEmpty))
      groups = disjoint :+ (overlap.foldLeft(Map(term))(_ ++ _))
    }
    // A new component may connect earlier components transitively; the fold
    // above merges all of them at once, preserving that connected closure.
    val limits = groups.map { group =>
      if (group.size == 1) {
        val (value, coefficient) = group.head
        atomBounds(value, axes, location).scale(coefficient)
      } else enumerate(Form(0, group), axes, location)
    }
    Extrema(form.constant + limits.map(_.minimum).sum, form.constant + limits.map(_.maximum).sum)
  }
  private def atomBounds(value: Atom, axes: Vector[Axis], location: Option[String]): Extrema = value match {
    case leaf: Leaf =>
      val values = axes.find(_.root eq leaf.root).get.values.map(leaf.values)
      Extrema(values.min, values.max)
    case current @ Operation(operator, operands) =>
      def b(index: Int): Extrema = bounds(operands(index), axes, location)
      def fallback: Extrema = enumerate(atom(current), axes, location)
      def independent: Boolean = (operands(0).roots intersect operands(1).roots).isEmpty
      operator match {
        case "*" =>
          val l = b(0); val r = b(1)
          if (independent || l.constant || r.constant) {
            val corners = Vector(l.minimum * r.minimum, l.minimum * r.maximum,
              l.maximum * r.minimum, l.maximum * r.maximum)
            Extrema(corners.min, corners.max)
          } else fallback
        case "/" =>
          val l = b(0); val r = b(1)
          if (l.minimum < 0 || r.minimum <= 0)
            fail("SPINAL-ELAB-INT-DIVISION-DOMAIN-UNSUPPORTED", "division needs a non-negative dividend and positive divisor", location)
          if (independent || r.constant) Extrema(l.minimum / r.maximum, l.maximum / r.minimum)
          else fallback
        case "%" =>
          val l = b(0); val r = b(1)
          if (l.minimum < 0 || r.minimum <= 0)
            fail("SPINAL-ELAB-INT-MODULO-DOMAIN-UNSUPPORTED", "remainder needs a non-negative dividend and positive divisor", location)
          if (operands(0) == operands(1) || (r.constant && r.minimum == 1)) Extrema(0, 0)
          else if (l.maximum < r.minimum) l
          else fallback
        case "<" =>
          val difference = bounds(operands(0) - operands(1), axes, location)
          if (difference.maximum < 0) Extrema(1, 1)
          else if (difference.minimum >= 0) Extrema(0, 0)
          else Extrema(0, 1) // both attained extrema give opposite truth values
        case "==" =>
          val difference = bounds(operands(0) - operands(1), axes, location)
          if (difference.minimum == 0 && difference.maximum == 0) Extrema(1, 1)
          else if (difference.minimum > 0 || difference.maximum < 0) Extrema(0, 0)
          else if (difference.minimum == 0 || difference.maximum == 0) Extrema(0, 1)
          else if (independent) {
            val l = image(operands(0), axes, location)
            val r = image(operands(1), axes, location)
            if ((l intersect r).isEmpty) Extrema(0, 0)
            else if (l.size == 1 && r.size == 1) Extrema(1, 1)
            else Extrema(0, 1)
          } else fallback
        case "&&" | "||" =>
          val l = b(0); val r = b(1)
          if (operator == "&&" && (l.maximum == 0 || r.maximum == 0)) Extrema(0, 0)
          else if (operator == "||" && (l.minimum == 1 || r.minimum == 1)) Extrema(1, 1)
          else if (l.constant) r
          else if (r.constant || operands(0) == operands(1)) l
          else if (operands(0) + operands(1) == literal(1))
            if (operator == "&&") Extrema(0, 0) else Extrema(1, 1)
          else if (independent) Extrema(0, 1)
          else fallback
        case "min" | "max" =>
          val difference = bounds(operands(0) - operands(1), axes, location)
          if (difference.minimum >= 0) b(if (operator == "max") 0 else 1)
          else if (difference.maximum <= 0) b(if (operator == "max") 1 else 0)
          else if (independent) {
            val l = b(0); val r = b(1)
            if (operator == "max") Extrema(l.minimum.max(r.minimum), l.maximum.max(r.maximum))
            else Extrema(l.minimum.min(r.minimum), l.maximum.min(r.maximum))
          } else fallback
        case "choose" =>
          val guard = b(0)
          if (guard.constant) b(if (guard.minimum != 0) 1 else 2)
          else if (operands(1) == operands(2)) b(1)
          else if ((operands(0).roots intersect (operands(1).roots ++ operands(2).roots)).isEmpty) {
            val yes = b(1); val no = b(2)
            Extrema(yes.minimum.min(no.minimum), yes.maximum.max(no.maximum))
          } else if (operands(0).roots.size == 1) {
            val root = operands(0).roots.head
            val axis = axes.find(_.root eq root).get
            val (yesValues, noValues) = axis.values.partition(value => operands(0).evaluate(Map(root -> value)) != 0)
            val choices = Vector(yesValues -> operands(1), noValues -> operands(2)).collect {
              case (values, arm) if values.nonEmpty =>
                bounds(arm, axes.map(a => if (a.root eq root) a.copy(values = values) else a), location)
            }
            Extrema(choices.map(_.minimum).min, choices.map(_.maximum).max)
          } else fallback
        case "log2Up" | "addressWidth" =>
          val input = b(0)
          if (operator == "log2Up" && input.minimum < 0)
            fail("SPINAL-ELAB-INT-LOG2-DOMAIN-NEGATIVE", "log2Up input must stay non-negative", location)
          if (operator == "addressWidth" && input.minimum < 1)
            fail("SPINAL-ELAB-INT-ADDRESS-WIDTH-DOMAIN-NONPOSITIVE", "addressWidth input must stay positive", location)
          def evaluate(value: BigInt): BigInt =
            if (operator == "addressWidth") BigInt(math.max(1, (value - 1).bitLength))
            else if (value == 0) BigInt(0) else BigInt((value - 1).bitLength)
          Extrema(evaluate(input.minimum), evaluate(input.maximum))
        case "pow2" =>
          val input = b(0)
          if (input.minimum < 0 || input.maximum > 30)
            fail("SPINAL-ELAB-INT-POW2-DOMAIN-INVALID", "power-of-two exponent must be in 0..30", location)
          Extrema(BigInt(1) << input.minimum.toInt, BigInt(1) << input.maximum.toInt)
        case "isPow2" => fallback
        case other => throw new IllegalArgumentException(s"unknown certified operator '$other'")
      }
  }

  private def checkedExtrema(proof: Certificate, location: Option[String]): Extrema = {
    val limits = bounds(proof.form, proof.axes, location)
    if (!limits.minimum.isValidInt || !limits.maximum.isValidInt)
      fail("SPINAL-ELAB-DOMAIN-EVIDENCE-RESULT-OUT-OF-RANGE",
        s"reaches [${limits.minimum}, ${limits.maximum}] outside the Scala/Verilog Int domain", location)
    limits
  }
  private def publishInteger(verilog: String, proof: Certificate,
                             location: Option[String]): ElaborationIntegerExpression = {
    val limits = checkedExtrema(proof, location)
    val default = proof.form.evaluate(representative(proof.axes))
    if (proof.axes.isEmpty)
      return ElaborationIntegerExpression(default.toString, default, default, default, Vector.empty, sourceLocation = location)
    val result = ElaborationIntegerExpression(verilog, default, limits.minimum, limits.maximum,
      proof.axes.map(_.schema), sourceLocation = location, parameterRoots = proof.axes.map(_.root))
    // Retain the complete dependency/scope inventory even for an exactly
    // constant function. A cancelled branch-local root must not escape.
    retain(result, if (limits.constant) proof.copy(form = literal(limits.minimum)) else proof)
    result
  }
  private def publishBoolean(verilog: String, proof: Certificate, location: Option[String]): ElabBool = {
    val limits = bounds(proof.form, proof.axes, location)
    if (limits.minimum < 0 || limits.maximum > 1)
      fail(Missing, "Boolean derivation is not exactly 0/1", location)
    val result = ElaborationBooleanExpression(verilog, proof.form.evaluate(representative(proof.axes)) != 0,
      proof.axes.map(_.schema), sourceLocation = location, parameterRoots = proof.axes.map(_.root))
    retain(result, if (limits.constant) proof.copy(form = literal(limits.minimum)) else proof)
    ElabBool(result, truth(limits))
  }
  private def truth(limits: Extrema): ElabBool.Truth =
    if (limits.minimum == 1) ElabBool.AlwaysTrue
    else if (limits.maximum == 0) ElabBool.AlwaysFalse
    else ElabBool.Unknown

  private[core] def integer(operator: String, left: ElaborationIntegerExpression,
                            right: ElaborationIntegerExpression): ElaborationIntegerExpression = {
    val l = source(left); val r = source(right)
    val location = left.sourceLocation.orElse(right.sourceLocation)
    publishInteger(s"(${left.verilog} $operator ${right.verilog})",
      Certificate(operation(operator, l.form, r.form), merge(Vector(l, r), location)), location)
  }
  private[core] def compare(operator: String, left: ElaborationIntegerExpression,
                            right: ElaborationIntegerExpression): ElabBool = {
    val l = source(left); val r = source(right)
    val location = left.sourceLocation.orElse(right.sourceLocation)
    publishBoolean(s"((${left.verilog}) $operator (${right.verilog}))",
      Certificate(operation(operator, l.form, r.form), merge(Vector(l, r), location)), location)
  }
  private[core] def logical(operator: String, left: ElaborationBooleanExpression,
                            right: ElaborationBooleanExpression): ElabBool = {
    val l = source(left); val r = source(right)
    val location = left.sourceLocation.orElse(right.sourceLocation)
    publishBoolean(s"((${left.verilog}) $operator (${right.verilog}))",
      Certificate(operation(operator, l.form, r.form), merge(Vector(l, r), location)), location)
  }
  private[core] def not(value: ElaborationBooleanExpression): ElabBool = {
    val proof = source(value)
    publishBoolean(s"!(${value.verilog})", proof.copy(form = literal(1) - proof.form), value.sourceLocation)
  }
  private[core] def toInteger(value: ElaborationBooleanExpression): ElaborationIntegerExpression =
    publishInteger(s"((${value.verilog}) ? 1 : 0)", source(value), value.sourceLocation)
  private[core] def unary(operator: String, value: ElaborationIntegerExpression): ElaborationIntegerExpression = {
    val proof = source(value)
    val verilog = operator match {
      case "log2Up" => s"morphhdl_ceil_log2(${value.verilog})"
      case "addressWidth" => s"morphhdl_address_width(${value.verilog})"
      case "pow2" => s"(1 << (${value.verilog}))"
    }
    publishInteger(verilog, proof.copy(form = atom(Operation(operator, Vector(proof.form)))), value.sourceLocation)
  }
  private[core] def isPow2(value: ElaborationIntegerExpression): ElabBool = {
    val proof = source(value)
    publishBoolean(s"((${value.verilog} > 0) && ((${value.verilog} & (${value.verilog} - 1)) == 0))",
      proof.copy(form = atom(Operation("isPow2", Vector(proof.form)))), value.sourceLocation)
  }
  private[core] def extremum(operator: String, left: ElaborationIntegerExpression,
                             right: ElaborationIntegerExpression): ElaborationIntegerExpression = {
    val l = source(left); val r = source(right)
    val location = left.sourceLocation.orElse(right.sourceLocation)
    val axes = merge(Vector(l, r), location)
    // Even a dominated arm may carry a branch restriction needed by the
    // dominance proof. Keep all original axes and the authored selection.
    val comparison = if (operator == "max") ">" else "<"
    publishInteger(s"((${left.verilog} $comparison ${right.verilog}) ? ${left.verilog} : ${right.verilog})",
      Certificate(operation(operator, l.form, r.form), axes), location)
  }
  private[core] def choose(condition: ElaborationBooleanExpression, yes: ElaborationIntegerExpression,
                           no: ElaborationIntegerExpression): ElaborationIntegerExpression = {
    val c = source(condition); val y = source(yes); val n = source(no)
    val location = condition.sourceLocation.orElse(yes.sourceLocation).orElse(no.sourceLocation)
    publishInteger(s"(${condition.verilog} ? ${yes.verilog} : ${no.verilog})",
      Certificate(atom(Operation("choose", Vector(c.form, y.form, n.form))), merge(Vector(c, y, n), location)), location)
  }

  private[core] def projectedTruth(value: ElaborationBooleanExpression): ElabBool.Truth = {
    val proof = source(value)
    truth(bounds(proof.form, proof.axes, value.sourceLocation))
  }
  private[core] def project(value: ElaborationIntegerExpression, role: String): ElaborationIntegerExpression =
    certificate(value) match {
      case None => ElabInt.projectExpression(value, role)
      case Some(original) =>
        val current = source(value)
        if (current.axes == original.axes) value else publishInteger(value.verilog, current, value.sourceLocation)
    }
  private[core] def project(value: ElaborationBooleanExpression, role: String): ElaborationBooleanExpression =
    certificate(value) match {
      case None => ElabBool.projectExpression(value, role)
      case Some(original) =>
        val current = source(value)
        if (current.axes == original.axes) value else publishBoolean(value.verilog, current, value.sourceLocation).expression
    }
  private[core] def hasCompleteDomain(value: ElaborationIntegerExpression): Boolean =
    certificate(value).exists(_.axes.forall(axis => axis.values.toSet == axis.domain.universe))
  private[core] def equivalent(left: ElaborationIntegerExpression, right: ElaborationIntegerExpression): Boolean = {
    val l = source(left); val r = source(right)
    if (!sameAxes(l.axes, r.axes)) false
    else {
      val limits = bounds(l.form - r.form, l.axes, left.sourceLocation.orElse(right.sourceLocation))
      limits.minimum == 0 && limits.maximum == 0
    }
  }
  /** Fixed typed relations can use compositional proofs; an arbitrary Scala
    * callback must instead be evaluated exhaustively within the fallback cap.
    */
  private[core] def provesPositiveComparison(left: ElaborationIntegerExpression,
      right: ElaborationIntegerExpression, operator: String): Boolean = {
    require(Set("==", "<", "<=", ">", ">=")(operator), "unsupported typed comparison")
    val l = source(left); val r = source(right)
    val location = left.sourceLocation.orElse(right.sourceLocation)
    val axes = merge(Vector(l, r), location)
    bounds(l.form, axes, location).minimum > 0 &&
      bounds(r.form, axes, location).minimum > 0 &&
      bounds(operation(operator, l.form, r.form), axes, location).minimum == 1
  }
  private[core] def provesRelation(left: ElaborationIntegerExpression,
      right: ElaborationIntegerExpression)(relation: (BigInt, BigInt) => Boolean): Boolean = {
    val l = source(left); val r = source(right)
    val location = left.sourceLocation.orElse(right.sourceLocation)
    val axes = merge(Vector(l, r), location)
    var result = true
    foreachTuple(relevant(l.form.roots ++ r.form.roots, axes), location) { values =>
      if (!relation(l.form.evaluate(values), r.form.evaluate(values))) result = false
    }
    result
  }
  private[core] def conditionedExtrema(value: ElaborationIntegerExpression,
      condition: ElaborationBooleanExpression): Option[(BigInt, BigInt)] = {
    val v = source(value); val c = source(condition)
    val location = value.sourceLocation.orElse(condition.sourceLocation)
    val axes = merge(Vector(v, c), location)
    val guard = bounds(c.form, axes, location)
    def pair(limits: Extrema): Option[(BigInt, BigInt)] = Some(limits.minimum -> limits.maximum)
    if (guard.maximum == 0) None
    else if (guard.minimum == 1 || (c.form.roots intersect v.form.roots).isEmpty)
      pair(bounds(v.form, axes, location))
    else if (c.form.roots.size == 1) {
      val root = c.form.roots.head
      val selected = axes.map(axis => if (axis.root ne root) axis else
        axis.copy(values = axis.values.filter(n => c.form.evaluate(Map(root -> n)) != 0)))
      pair(bounds(v.form, selected, location))
    } else {
      var low: Option[BigInt] = None
      var high: Option[BigInt] = None
      foreachTuple(relevant(v.form.roots ++ c.form.roots, axes), location) { values =>
        if (c.form.evaluate(values) != 0) {
          val result = v.form.evaluate(values)
          low = Some(low.fold(result)(_.min(result)))
          high = Some(high.fold(result)(_.max(result)))
        }
      }
      low.map(_ -> high.get)
    }
  }

  private def sameAxes(left: Vector[Axis], right: Vector[Axis]): Boolean =
    left.size == right.size && left.forall(l => right.exists(r =>
      (l.root eq r.root) && (l.schema eq r.schema) && l.values == r.values))
  private[core] def evaluate(value: ElaborationIntegerExpression, bindings: Vector[(Root, BigInt)]): Option[BigInt] = {
    val proof = source(value)
    evaluate(proof, bindings)
  }
  private def evaluate(proof: Certificate, bindings: Vector[(Root, BigInt)]): Option[BigInt] = {
    if (bindings.map(_._1).distinct.size != bindings.size) return None
    if (proof.axes.exists(axis => !bindings.find(_._1 eq axis.root).exists(entry => axis.values.contains(entry._2)))) None
    else Some(proof.form.evaluate(bindings.toMap))
  }

  /** A post-capture proof bound to the exact native owner. No construction
    * branch is reopened and no default tuple is substituted for that owner.
    */
  private[core] final class OwnerProof private[ElaborationProductDomain] (private val proof: Certificate,
                                                                         val sourceLocation: Option[String]) {
    private val limits = checkedExtrema(proof, sourceLocation)
    val roots: Vector[Root] = proof.axes.map(_.root)
    val schemas: Vector[ElaborationIntegerParameter] = proof.axes.map(_.schema)
    val rootValues: Vector[Vector[BigInt]] = proof.axes.map(_.values)
    val minimum: BigInt = limits.minimum
    val maximum: BigInt = limits.maximum
    val default: BigInt = proof.form.evaluate(representative(proof.axes))
    def evaluate(bindings: Vector[(Root, BigInt)]): Option[BigInt] = ElaborationProductDomain.evaluate(proof, bindings)
    def sameDomain(that: OwnerProof): Boolean = sameAxes(proof.axes, that.proof.axes)
    def equivalent(that: OwnerProof): Boolean = sameDomain(that) && {
      val difference = bounds(proof.form - that.proof.form, proof.axes, sourceLocation)
      difference.minimum == 0 && difference.maximum == 0
    }
    def nonNegativeDifference(that: OwnerProof): Boolean = {
      val axes = merge(Vector(proof, that.proof), sourceLocation)
      bounds(proof.form - that.proof.form, axes, sourceLocation).minimum >= 0
    }
    def combine(operator: String, that: OwnerProof): OwnerProof = {
      val axes = merge(Vector(proof, that.proof), sourceLocation.orElse(that.sourceLocation))
      new OwnerProof(Certificate(operation(operator, proof.form, that.proof.form), axes), sourceLocation)
    }
  }
  private[core] def literalOwner(value: BigInt): OwnerProof =
    new OwnerProof(Certificate(literal(value), Vector.empty), None)

  private[core] def owner(value: ElaborationIntegerExpression, role: String, location: Option[String])(
      ownerValues: (Root, Set[BigInt]) => Set[BigInt]): Option[OwnerProof] = {
    val retainedProof = certificate(value)
    if (retainedProof.isEmpty) {
      // Authenticate raw single-root metadata through the very same carrier
      // validator as construction, but against the captured native owner.
      val exact = ElabInt.authoritativeIntegerOwnerDomain(value, role, Missing)(ownerValues)
      return Some(exact match {
        case None => literalOwner(value.default)
        case Some((domain, admitted)) =>
          new OwnerProof(Certificate(atom(Leaf(domain.root, domain.evaluations.toMap)),
            Vector(Axis(domain, admitted.toVector.sorted))), location)
      })
    }
    retainedProof.map { original =>
    ElabInt.validateExpression(value, role)
    validateInventory(value.parameters, value.completedParameterRoots, original, role, location)
    val axes = original.axes.map { axis =>
      val requested = ownerValues(axis.root, axis.domain.universe)
      if (requested.isEmpty || !requested.subsetOf(axis.values.toSet))
        fail("SPINAL-ELAB-DOMAIN-PROJECTION-OWNER-SCOPE-MISMATCH",
          s"$role exceeds its authorized owner scope for '${axis.root.name}'", location)
      axis.copy(values = requested.toVector.sorted)
    }
    val owned = new OwnerProof(original.copy(axes = axes), location)
    if (owned.default != value.default)
      fail("SPINAL-ELAB-DOMAIN-PROJECTION-OWNER-REPRESENTATIVE-MISMATCH",
        s"$role witness ${value.default} does not match owner representative ${owned.default}", location)
    if (owned.minimum < value.minimum || owned.maximum > value.maximum)
      fail("SPINAL-ELAB-DOMAIN-PROJECTION-BOUNDS-MISMATCH", s"$role escapes its retained extrema", location)
    owned
  }
  }
}
