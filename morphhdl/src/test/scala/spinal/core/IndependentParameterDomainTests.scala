package spinal.core

import morphhdl.frontend.HdlInt
import org.scalatest.funsuite.AnyFunSuite

/** Public typed declarations are the only source of product-domain authority. */
class IndependentParameterDomainTests extends AnyFunSuite {
  private def param(name: String, min: Int = 1, max: Int = 4, default: Int = 2): ElabInt =
    HdlInt.param(name, BigInt(default), BigInt(min), BigInt(max)).asElabInt
  private def root(value: ElabInt): ElaborationIntegerParameterRoot = value.expression.exactDomain.get.root
  private def rejects(body: => Any): ParameterizedVerilogException = intercept[ParameterizedVerilogException](body)
  private def exact(value: ElabInt, expected: BigInt): Unit = {
    assert(value.minimum == expected)
    assert(value.maximum == expected)
  }
  private def widthAuthority(value: ElaborationIntegerExpression): Unit =
    ElaborationWidthAuthority.requireAuthoritative(value, "product regression", "TEST-WIDTH-AUTHORITY")

  test("exact reproducer preserves independent equal-default roots beyond the old product cap") {
    val a = param("DATA_BITS", 1, 2048, 32)
    val b = param("GENERATION_BITS", 2, 64, 32)
    val width = a + b
    assert(root(a) ne root(b))
    assert(width.minimum == 3 && width.maximum == 2112 && width.witness == 64)
    assert(width.parameters.size == 2 && width.bits.value == 64)
    assert(width.expression.exactDomain.isEmpty) // no fabricated single-root table
    assert(ElaborationProductDomain.isRetained(width.expression))
    for ((x, y) <- Vector((32,32), (1,2), (1,64), (2048,2), (2048,64), (120,8), (17,32))) {
      assert(ElaborationWidthAuthority.evaluate(width.expression,
        Vector(root(a) -> BigInt(x), root(b) -> BigInt(y))).contains(BigInt(x + y)))
    }
    assert(ElaborationWidthAuthority.evaluate(width.expression, Vector(root(a) -> BigInt(32))).isEmpty)
    assert(ElaborationWidthAuthority.evaluate(width.expression,
      Vector(root(a) -> BigInt(32), root(a) -> BigInt(32), root(b) -> BigInt(32))).isEmpty)
    assert(ElaborationWidthAuthority.evaluate(width.expression,
      Vector(root(a) -> BigInt(0), root(b) -> BigInt(2))).isEmpty)
  }

  test("large separable products and sums use attainable compositional extrema") {
    val values = Vector.tabulate(4)(i => param("AXIS_" + i, 1, 4096, 2))
    val sum = values.reduce(_ + _)
    assert(sum.minimum == 4 && sum.maximum == 16384)
    // 4096^4 tuples: constructing this certificate must not enumerate them.
    assert(BigInt(4096).pow(4) > ElabInt.MaximumExactDomainSize)
    val product = (values(0) + values(1)) * (values(2) + values(3))
    assert(product.minimum == 4 && product.maximum == BigInt(8192).pow(2))
    val record = values(0) + 7 + values(1) + values(2) + 16 + 1
    assert(record.minimum == 27 && record.maximum == 12312)
    assert(((values(0) > 0) && (values(1) > 0)).isAlwaysTrue)
  }

  test("same-root and partially overlapping dependencies are never independent copies") {
    val a = param("A"); val b = param("B"); val c = param("C")
    exact(a - a, 0)
    exact((a + b) - (b + a), 0)
    assert(((a + b) - (a + c)).elabEq(b - c).isAlwaysTrue)
    assert((a * 2).elabEq(a + a).isAlwaysTrue)
    assert(((a + b) * 2).elabEq((a + b) + (a + b)).isAlwaysTrue)
    val overlap = (a * b) - (b * c)
    assert(overlap.minimum == -12 && overlap.maximum == 12)
    assert((a.elabEq(b)).isSymbolic) // equal defaults do not imply equal roots
  }

  test("composed value functions agree with an independent exhaustive three-root oracle") {
    val a = param("A", 1, 6); val b = param("B", 1, 6); val c = param("C", 1, 6)
    val expressions = Vector[(ElabInt, (Int, Int, Int) => BigInt)](
      ((a + b) - (a + c), (x,y,z) => BigInt(y-z)),
      ((a * b) - (b * c), (x,y,z) => BigInt(x*y-y*z)),
      (((a + b) * c) / (b + 1), (x,y,z) => BigInt((x+y)*z/(y+1))),
      ((a + b) % (b + c), (x,y,z) => BigInt((x+y)%(y+z))),
      (ElaborationWidthAuthority.maximum(a+b,b+c), (x,y,z) => BigInt(math.max(x+y,y+z))),
      (ElaborationWidthAuthority.minimum(a+b,b+c), (x,y,z) => BigInt(math.min(x+y,y+z))),
      (((a < b) && (b < c)).toElabInt, (x,y,z) => if(x<y && y<z) BigInt(1) else BigInt(0)),
      (((a < b) || (b < c)).toElabInt, (x,y,z) => if(x<y || y<z) BigInt(1) else BigInt(0))
    )
    expressions.foreach { case (value, expected) =>
      val results = for (x <- 1 to 6; y <- 1 to 6; z <- 1 to 6) yield {
        val wanted = expected(x,y,z)
        assert(ElaborationWidthAuthority.evaluate(value.expression,
          Vector(root(a)->BigInt(x),root(b)->BigInt(y),root(c)->BigInt(z))).contains(wanted))
        wanted
      }
      assert(value.minimum == results.min && value.maximum == results.max)
    }
  }

  test("independent predicates classify all true all false and mixed tuples") {
    val a = param("A"); val b = param("B")
    assert(((a > 0) && (b > 0)).isAlwaysTrue)
    assert(((a < 1) || (b < 1)).isAlwaysFalse)
    val condition = (a <= 2) && (b <= 2)
    assert(condition.witness && condition.isSymbolic)
    assert(!condition.isAlwaysTrue && !condition.isAlwaysFalse)
    assert((condition || !condition).isAlwaysTrue)
    assert((condition && !condition).isAlwaysFalse)
    val legal = !(b > 1) || (a >= b)
    assert(legal.witness && legal.isSymbolic)
    val encoded = legal.toElabInt
    assert(ElaborationWidthAuthority.evaluate(encoded.expression,
      Vector(root(a)->BigInt(1),root(b)->BigInt(2))).contains(BigInt(0)))
    assert(ElaborationWidthAuthority.evaluate(encoded.expression,
      Vector(root(a)->BigInt(4),root(b)->BigInt(2))).contains(BigInt(1)))
  }

  test("equal names or value-equal schema copies cannot replace declaration identity") {
    val a = param("SAME"); val b = param("SAME")
    assert(root(a) ne root(b))
    assert(rejects(a+b).code == "SPINAL-ELAB-INT-INDEPENDENT-ROOTS-UNSUPPORTED")
    assert(rejects((a>0) && (b>0)).code == "SPINAL-ELAB-INT-INDEPENDENT-ROOTS-UNSUPPORTED")
    val conflict = param("SAME", 1, 5)
    assert(rejects(a+conflict).code == "SPINAL-ELAB-INT-PARAMETER-SCHEMA-CONFLICT")
    val foreign = param("FOREIGN")
    val sum = a + foreign
    val equalSchema = sum.expression.parameters.map(_.copy())
    assert(equalSchema == sum.expression.parameters)
    rejects(widthAuthority(sum.expression.copy(parameters = equalSchema)))
  }

  test("copying or forging public product summaries never transfers proof authority") {
    val a = param("A"); val b = param("B")
    val width = (a+b).expression
    val mutations = Vector(
      width.copy(), width.copy(default = 1), width.copy(minimum = 4),
      width.copy(maximum = 9), width.copy(verilog = "A * B"),
      width.copy(parameters = Vector(width.parameters.head)),
      width.copy(parameterRoots = Vector(width.parameterRoots.head)),
      width.copy(parameters = Vector.empty, parameterRoots = Vector.empty))
    mutations.foreach { changed =>
      assert(!ElaborationProductDomain.isRetained(changed))
      rejects(widthAuthority(changed))
      rejects(ElaborationWidthAuthority.add(changed, ElabInt.literal(1).expression))
    }
    val predicate = (a>1) && (b>1)
    val copied = new ElabBool(predicate.expression.copy(), ElabBool.AlwaysTrue)
    rejects(copied.isAlwaysTrue)
    rejects(copied.toElabInt)
    rejects(copied && predicate)
  }

  test("zero negative and overflowing widths are rejected beyond the default tuple") {
    val a = param("A"); val b = param("B")
    rejects((a-b).bits)
    rejects(((a+b)-(a+b)).bits)
    rejects((a-b-10).bits)
    val large = param("LARGE", 1, 4096, 1)
    val factor = param("FACTOR", 1, 4096, 1)
    assert(rejects((large*factor)*256).code == "SPINAL-ELAB-DOMAIN-EVIDENCE-RESULT-OUT-OF-RANGE")
    rejects((a+b)/(a-b))
    rejects((a+b)%(a-b))
  }

  test("unsupported overlapping correlations fail explicitly instead of sampling") {
    val a = param("A", 1, 512); val b = param("B", 1, 512)
    assert((a+b).maximum == 1024)
    val symbolic = (a+b)%(a+1) + 1
    assert(symbolic.bits.value == 2) // publication authenticates the AST without joint evaluation
    assert(rejects(symbolic.maximum).code == "SPINAL-ELAB-DOMAIN-PRODUCT-CORRELATION-UNSUPPORTED")
    // An arbitrary host callback is not inferred to be monotone from a sample.
    assert(rejects(ElaborationWidthAuthority.provesRelation((a+b).expression, a.expression)(_ >= _)).code ==
      "SPINAL-ELAB-DOMAIN-PRODUCT-CORRELATION-UNSUPPORTED")
  }

  test("nested single-root projection preserves all axes and deterministic representatives") {
    val a = param("A", 1, 8, 1); val b = param("B", 1, 4, 2)
    val global = a + b
    var partial: ElabInt = null
    var cancelled: ElabInt = null
    var dominated: ElabInt = null
    var predicate: ElabBool = null
    ElaborationDomainContext.withAdmitted(root(a), Set(BigInt(4),BigInt(5)), None) {
      partial = global + 1
      assert(partial.minimum == 6 && partial.maximum == 10 && partial.witness == 7)
      cancelled = partial - partial + 1
      exact(cancelled,1)
      dominated = ElaborationWidthAuthority.maximum(partial, ElabInt.literal(100))
      exact(dominated,100)
      predicate = (partial > 0) && (b > 0)
      assert(predicate.isAlwaysTrue)
      ElaborationDomainContext.withAdmitted(root(b), Set(BigInt(3)), None) {
        assert(partial.minimum == 8 && partial.maximum == 9 && partial.witness == 8)
      }
      assert(partial.minimum == 6 && partial.maximum == 10)
    }
    assert(global.minimum == 2 && global.maximum == 12)
    Vector(partial,cancelled,dominated).foreach(value => {
      assert(rejects(value.minimum).code == "SPINAL-ELAB-DOMAIN-EVIDENCE-SCOPE-MISMATCH")
      rejects(value + 1)
    })
    rejects(predicate.isAlwaysTrue)
    rejects(predicate.toElabInt)
  }

  test("logarithms and conditional extrema retain proof semantics including log2Up zero") {
    val a = param("A", 0, 8, 0); val b = param("B", 0, 8, 0)
    val sum = a+b
    assert(sum.log2Up.minimum == 0 && sum.log2Up.maximum == 4)
    assert(sum.log2Up.expression.verilog.contains("morphhdl_ceil_log2"))
    rejects(sum.addressWidth)
    assert((sum+1).addressWidth.minimum == 1 && (sum+1).addressWidth.maximum == 5)
    assert(ElaborationWidthAuthority.minimumWhen((sum+1).expression, (a>4).expression).contains(BigInt(6)))
    assert(ElaborationWidthAuthority.maximumWhen((sum+1).expression, (a>4).expression).contains(BigInt(17)))
    val zero = (a+b)-(a+b)
    exact(zero.pow2,1)
  }
}
