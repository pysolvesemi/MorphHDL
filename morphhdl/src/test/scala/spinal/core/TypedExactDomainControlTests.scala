package spinal.core

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.collection.JavaConverters._

import org.scalatest.funsuite.AnyFunSuite

import morphhdl.MorphVerilog
import morphhdl.frontend.HdlInt

object TypedExactDomainControlFixture {
  final class Sink extends Component {
    setDefinitionName("TypedExactDomainControlSink")

    val din = in Bits (8 bits)
    val observed = out Bool ()
    observed := din.orR
  }

  /** All three alternatives are captured from one exact DEPTH domain.  In
    * particular, a default of one must still construct both `DEPTH > 1`
    * alternatives, while defaults five and eight must still construct the
    * `DEPTH == 1` alternative.
    */
  final class Top(depth: ElabInt) extends Component {
    setDefinitionName("TypedExactDomainControlTop")

    val din = in Bits (8 bits)
    val alive = out Bool ()
    alive := din.orR

    val isOne: ElabBool = depth.elabEq(1)
    val powerOfTwo: ElabBool = depth.isPow2
    val aboveOne: ElabBool = depth > 1

    if (isOne) {
      attach(din)
    } else {
      if (powerOfTwo) attach(~din)
      else attach(din)
    }

    // The false side is deliberately absent in source and must be represented
    // by ParameterizedStructuralSynthetic.emptyBlock during typed capture.
    aboveOne.generate {
      attach(din)
    }

    private def attach(value: Bits): Unit = {
      val branchWire = Bits(8 bits)
      branchWire := value
      val sink = new Sink
      sink.din := branchWire
    }
  }
}

class TypedExactDomainControlTests extends AnyFunSuite {
  import TypedExactDomainControlFixture._

  private val Defaults = Vector(1, 5, 8)
  private val Universe = (1 to 8).map(BigInt(_)).toSet

  test("exact typed predicates retain complete equality, range, power-of-two and encoded sets") {
    Defaults.foreach { default =>
      val depth = typedDepth(default)
      val equalOne = depth.elabEq(1)
      val aboveOne = depth > 1
      val powerOfTwo = depth.isPow2
      val nested = aboveOne && !powerOfTwo
      val encoded = nested.toElabInt

      assert(rootInputs(depth) == Universe)
      assert(trueInputs(equalOne) == Set(BigInt(1)))
      assert(trueInputs(aboveOne) == Set(2, 3, 4, 5, 6, 7, 8).map(BigInt(_)))
      assert(trueInputs(powerOfTwo) == Set(1, 2, 4, 8).map(BigInt(_)))
      assert(trueInputs(nested) == Set(3, 5, 6, 7).map(BigInt(_)))
      assert(
        integerEvaluations(encoded) == Universe.toVector.sorted.map { value =>
          value -> (if (Set(3, 5, 6, 7).contains(value.toInt)) BigInt(1)
                    else BigInt(0))
        }
      )

      assert(equalOne.expression.default == (default == 1))
      assert(aboveOne.expression.default == (default > 1))
      assert(powerOfTwo.expression.default == Set(1, 2, 4, 8).contains(default))
      assert(nested.expression.default == Set(3, 5, 6, 7).contains(default))
      assert(encoded.expression.default == (if (nested.expression.default) 1 else 0))
    }
  }

  test("branch projection inverts the concrete representative without changing the global witness") {
    Defaults.foreach { default =>
      val depth = typedDepth(default)
      val equalOne = depth.elabEq(1)
      val domain = exact(equalOne.expression)
      val trueValues = domain.evaluations.collect { case (rootValue, true) =>
        rootValue
      }.toSet
      val falseValues = domain.universe -- trueValues

      ElaborationDomainContext.withAdmitted(
        domain.root,
        trueValues,
        sourceLocation = None
      ) {
        assert(depth.witness == 1)
        assert(depth.minimum == 1)
        assert(depth.maximum == 1)
        assert(equalOne.witness)
        assert(equalOne.isAlwaysTrue)
      }

      val expectedFalseRepresentative = if (default == 1) 2 else default
      ElaborationDomainContext.withAdmitted(
        domain.root,
        falseValues,
        sourceLocation = None
      ) {
        assert(depth.witness == expectedFalseRepresentative)
        assert(depth.minimum == 2)
        assert(depth.maximum == 8)
        assert(!equalOne.witness)
        assert(equalOne.isAlwaysFalse)
      }

      assert(depth.expression.default == BigInt(default))
      assert(depth.witness == default)
    }
  }

  test("nested typed control and synthetic generate close for defaults one, five and eight") {
    withTemporaryDirectory { directory =>
      Defaults.foreach { default =>
        val target = directory.resolve(s"default-$default")
        Files.createDirectories(target)
        val config = SpinalConfig(targetDirectory = target.toString)
        config.netlistFileName = s"typed_exact_domain_default_$default.v"

        var capturedTop: Top = null
        MorphVerilog(config) {
          capturedTop = new Top(typedDepth(default))
          capturedTop
        }

        assert(capturedTop ne null)
        val structuralIfs = allStructuralIfs(capturedTop)
        val syntheticGenerates =
          structuralIfs.filter(value => ParameterizedStructuralSynthetic.isSyntheticEmpty(value.whenFalse))
        assert(syntheticGenerates.size == 1)
        assert(!ParameterizedStructuralSynthetic.isSyntheticEmpty(syntheticGenerates.head.whenTrue))

        val verilog = read(target.resolve(config.netlistFileName))
        assert(verilog.contains(s"parameter integer DEPTH = $default"))
        assert(verilog.contains("DEPTH"))
        assert(verilog.contains("TypedExactDomainControlSink"))
      }
    }
  }

  test("same-named exact domains remain isolated by declaration identity") {
    val first = typedDepth(default = 5)
    val second = typedDepth(default = 5)
    val firstDomain = exact(first.expression)
    val secondDomain = exact(second.expression)

    assert(firstDomain.root ne secondDomain.root)
    assert(firstDomain.parameter == secondDomain.parameter)
    assert(firstDomain.evaluations == secondDomain.evaluations)

    ElaborationDomainContext.withAdmitted(
      firstDomain.root,
      Set(BigInt(8)),
      sourceLocation = None
    ) {
      assert(first.witness == 8)
      assert(second.witness == 5)
      assert(second.minimum == 1)
      assert(second.maximum == 8)
    }

    val error = intercept[ParameterizedVerilogException] {
      (first > 1) && (second > 1)
    }
    assert(error.code == "SPINAL-ELAB-DOMAIN-EXACT-CORRELATION-UNSUPPORTED")
  }

  private def typedDepth(default: Int): ElabInt =
    HdlInt
      .param(
        "DEPTH",
        default = BigInt(default),
        min = BigInt(1),
        max = BigInt(8)
      )
      .asElabInt

  private def exact(
      expression: ElaborationIntegerExpression
  ): ElaborationExactDomain[BigInt] =
    expression.exactDomain.getOrElse(fail("integer exact-domain evidence is missing"))

  private def exact(
      expression: ElaborationBooleanExpression
  ): ElaborationExactDomain[Boolean] =
    expression.exactDomain.getOrElse(fail("Boolean exact-domain evidence is missing"))

  private def rootInputs(value: ElabInt): Set[BigInt] =
    exact(value.expression).universe

  private def trueInputs(value: ElabBool): Set[BigInt] =
    exact(value.expression).evaluations.collect { case (rootValue, true) =>
      rootValue
    }.toSet

  private def integerEvaluations(value: ElabInt): Vector[(BigInt, BigInt)] =
    exact(value.expression).evaluations

  private def allStructuralIfs(
      component: Component
  ): Vector[ParameterizedStructure.StructuralIf] = {
    def visit(
        region: ParameterizedStructure.StructuralRegion
    ): Vector[ParameterizedStructure.StructuralIf] = {
      val current = region match {
        case value: ParameterizedStructure.StructuralIf => Vector(value)
        case _                                          => Vector.empty
      }
      current ++ region.blocks.flatMap(block => block.regions.flatMap(visit))
    }

    ParameterizedStructure.regionsOf(component).flatMap(visit)
  }

  private def read(path: Path): String =
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

  private def withTemporaryDirectory(body: Path => Unit): Unit = {
    val directory = Files.createTempDirectory("morphhdl-typed-exact-domain-")
    try body(directory)
    finally {
      val stream = Files.walk(directory)
      try {
        stream.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach { path =>
          Files.deleteIfExists(path)
        }
      } finally stream.close()
    }
  }
}
