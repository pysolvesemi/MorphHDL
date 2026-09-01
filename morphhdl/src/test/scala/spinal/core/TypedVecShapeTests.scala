package spinal.core

import java.nio.file.{Files, Path}

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer

import org.scalatest.funsuite.AnyFunSuite

import morphhdl.frontend.HdlInt
import spinal.lib.{Flow, Stream}

private object TypedVecShapeTestFixture {
  final case class Pixel() extends Bundle {
    val r = UInt(8 bits)
    val g = UInt(8 bits)
    val b = UInt(8 bits)
  }

  final case class Envelope(width: ElabInt, depth: ElabInt) extends Bundle {
    val tag = Bits(3 bits)
    val samples = Vec(UInt(width bits), depth)
  }
}

/** Native metadata contracts for a Vec whose logical depth is an ElabInt.
  *
  * These tests intentionally inspect only the generic typed carrier retained
  * beside the authoritative SpinalHDL Vec. Verilog publication is covered by
  * [[morphhdl.TypedParameterizedVecTests]].
  */
class TypedVecShapeTests extends AnyFunSuite {
  import TypedVecShapeTestFixture._

  test("symbolic width and depth remain factorized on an ordinary native Vec") {
    withSpinalElaboration { () =>
      val width = parameter("WIDTH", default = 7, minimum = 1, maximum = 16)
      val depth = parameter("DEPTH", default = 5, minimum = 1, maximum = 8)
      val vector = Vec(UInt(width bits), depth)
      val shape = requiredShape(vector)

      assert(vector.isInstanceOf[Vec[_]])
      assert(vector(0).isInstanceOf[UInt])
      assert(shape.depth.verilog == "DEPTH")
      assert(shape.depth.default == 5)
      assert(shape.depth.minimum == 1)
      assert(shape.depth.maximum == 8)
      assert(shape.witnessDepth == 5)
      assert(shape.carrierCapacity == 8)
      assert(vector.carrierLength == 8)
      assert(shape.elementLeaves.size == 1)
      assert(shape.elementLeaves.head.typeObject eq spinal.core.internals.TypeUInt)
      assert(shape.elementLeaves.head.width.verilog == "WIDTH")
      assert(shape.elementLeaves.head.width.default == 7)
      assert(shape.elementLeaves.head.width.minimum == 1)
      assert(shape.elementLeaves.head.width.maximum == 16)
      assert(shape.elementWidthDefault == 7)
      assert(shape.elementWidthMinimum == 1)
      assert(shape.elementWidthMaximum == 16)
      assert(shape.parameters.map(_.name) == Vector("DEPTH", "WIDTH"))

      val depthRoot = shape.depth.parameterRoots.head
      val widthRoot = shape.elementLeaves.head.width.parameterRoots.head
      assert(!(depthRoot eq widthRoot))
      assert(depthRoot.name == "DEPTH")
      assert(widthRoot.name == "WIDTH")
    }
  }

  test("symbolic width with concrete depth retains a typed Vec shape") {
    withSpinalElaboration { () =>
      val width = parameter("WIDTH", default = 6, minimum = 1, maximum = 12)
      val vector = Vec(UInt(width bits), 4)
      val shape = requiredShape(vector)

      assert(vector.length == 4)
      assert(shape.depth.parameters.isEmpty)
      assert(shape.depth.verilog == "4")
      assert(shape.depth.default == 4)
      assert(shape.witnessDepth == 4)
      assert(shape.carrierCapacity == 4)
      assert(shape.elementLeaves.map(_.width.verilog) == Vector("WIDTH"))
      assert(shape.parameters.map(_.name) == Vector("WIDTH"))
    }
  }

  test("concrete width with symbolic depth retains depth independently") {
    withSpinalElaboration { () =>
      val depth = parameter("DEPTH", default = 3, minimum = 1, maximum = 8)
      val vector = Vec(UInt(8 bits), depth)
      val shape = requiredShape(vector)

      assert(shape.depth.verilog == "DEPTH")
      assert(shape.elementLeaves.map(_.width.verilog) == Vector("8"))
      assert(shape.elementWidthDefault == 8)
      assert(shape.parameters.map(_.name) == Vector("DEPTH"))
    }
  }

  test("public typed Vec construction rejects bounded symbolic depth without exact evidence") {
    var failure: ParameterizedVerilogException = null
    withSpinalElaboration { () =>
      val parameter = ElaborationIntegerParameter(
        "DEPTH",
        default = 3,
        minimum = 1,
        maximum = 8
      )
      val root = ElaborationIntegerParameterRoot.fresh("DEPTH")
      val inexact = ElabInt.fromExpression(
        ElaborationIntegerExpression(
          verilog = "DEPTH",
          default = 3,
          minimum = 1,
          maximum = 8,
          parameters = Vector(parameter),
          parameterRoots = Vector(root)
        )
      )

      failure = intercept[ParameterizedVerilogException] {
        Vec(UInt(8 bits), inexact)
      }
    }

    assert(failure != null)
    assert(failure.code == "SPINAL-ELAB-INT-VEC-DEPTH-DOMAIN-INVALID")
  }

  test("public typed Vec rejects an equal-but-foreign raw exact schema") {
    var failure: ParameterizedVerilogException = null
    withSpinalElaboration { () =>
      val originalSchema = ElaborationIntegerParameter(
        "FOREIGN_RAW_VEC_DEPTH",
        default = 2,
        minimum = 1,
        maximum = 3
      )
      val root = originalSchema.declarationRoot
      val copiedSchema = originalSchema.copy()
      val evaluations = Vector(
        BigInt(1) -> BigInt(1),
        BigInt(2) -> BigInt(2),
        BigInt(3) -> BigInt(3)
      )
      ElaborationExactDomain.checked[BigInt](
        root,
        originalSchema,
        evaluations,
        sourceLocation = None,
        role = "authoritative typed Vec depth schema"
      )
      val forged = ElabInt.fromTrustedExactExpressionForTest(
        ElaborationIntegerExpression(
          verilog = "FOREIGN_RAW_VEC_DEPTH",
          default = 2,
          minimum = 1,
          maximum = 3,
          parameters = Vector(copiedSchema),
          parameterRoots = Vector(root),
          exactDomain = Some(
            ElaborationExactDomain[BigInt](
              root,
              copiedSchema,
              evaluations
            )
          )
        )
      )

      failure = intercept[ParameterizedVerilogException] {
        Vec(UInt(8 bits), forged)
      }
    }

    assert(failure != null)
    assert(failure.code == "SPINAL-ELAB-INT-VEC-DEPTH-DOMAIN-INVALID")
  }

  test("public typed Vec rejects a forged symbolic leaf width before RTL publication") {
    val directory = Files.createTempDirectory("morphhdl-forged-vec-leaf-width-")
    try {
      val rtl = directory.resolve("forged_vec_leaf_width.v")
      val config = SpinalConfig(
        targetDirectory = directory.toString,
        headerWithRepoHash = false,
        withTimescale = false,
        printFilelist = false
      )
      config.netlistFileName = rtl.getFileName.toString

      val failure = intercept[ParameterizedVerilogException] {
        SpinalVerilog(config) {
          new Component {
            val keep = out(Bool())
            keep := False

            val schema = ElaborationIntegerParameter(
              "FORGED_VEC_LEAF_WIDTH",
              default = 8,
              minimum = 1,
              maximum = 16
            )
            val root = ElaborationIntegerParameterRoot.fresh(
              "FORGED_VEC_LEAF_WIDTH"
            )
            val forged = ParameterizedBitCount(
              value = 8,
              parameter = None,
              expression = Some(
                ElaborationIntegerExpression(
                  verilog = "FORGED_VEC_LEAF_WIDTH",
                  default = 8,
                  minimum = 1,
                  maximum = 16,
                  parameters = Vector(schema),
                  parameterRoots = Vector(root)
                )
              )
            )
            val vector = Vec(
              UInt(forged),
              parameter(
                "FORGED_VEC_DEPTH",
                default = 3,
                minimum = 1,
                maximum = 4
              )
            )
            vector.vec.foreach(_ := 0)
          }
        }
      }

      assert(
        failure.code ==
          "SPINAL-PARAMETERIZED-VERILOG-WIDTH-EXACT-DOMAIN-REQUIRED"
      )
      assert(!Files.exists(rtl), "forged Vec leaf width published partial RTL")
    } finally deleteRecursively(directory)
  }

  test("varying typed Vec geometry never exposes finite carrier capacity") {
    withSpinalElaboration { () =>
      val depth = parameter("DEPTH", default = 5, minimum = 1, maximum = 8)
      val vector = Vec(UInt(8 bits), depth)

      expectUnsupported(vector.length)
      expectUnsupported(vector.size)
      expectUnsupported(vector.range)
      expectUnsupported(vector.indices)
      expectUnsupported(vector.getBitsWidth)
      expectUnsupported(widthOf(vector))
      assert(vector.toString.contains("DEPTH elements"))
      assert(!vector.toString.contains("8 elements"))

      val logicalWidth = widthOfExpr(vector)
      assert(logicalWidth.witness == 40)
      assert(logicalWidth.minimum == 8)
      assert(logicalWidth.maximum == 64)
      assert(compact(logicalWidth.expression.verilog).contains("DEPTH"))
      assert(vector.carrierLength == 8)
      assert(vector.carrierBitsWidth == 64)
    }
  }

  test("singleton projection exposes logical rather than carrier geometry") {
    withSpinalElaboration { () =>
      val depth = parameter("DEPTH", default = 5, minimum = 1, maximum = 8)
      val vector = Vec(UInt(8 bits), depth)
      val domain = requiredShape(vector).depth.exactDomain.getOrElse(
        fail("typed Vec depth lost exact-domain evidence")
      )

      ElaborationDomainContext.withAdmitted(
        domain.root,
        Set(BigInt(3)),
        sourceLocation = None
      ) {
        assert(vector.length == 3)
        assert(vector.size == 3)
        assert(vector.range == (0 until 3))
        assert(vector.indices == (0 until 3))
        assert(vector.getBitsWidth == 24)
        assert(widthOf(vector) == 24)
      }

      expectUnsupported(vector.length)
      expectUnsupported(vector.getBitsWidth)
      assert(vector.carrierLength == 8)
      assert(vector.carrierBitsWidth == 64)
    }
  }

  test("fixed count and width preserve ordinary public Vec geometry") {
    withSpinalElaboration { () =>
      val width = parameter("WIDTH", default = 8, minimum = 8, maximum = 8)
      val depth = parameter("DEPTH", default = 3, minimum = 3, maximum = 3)
      val vector = Vec(UInt(width bits), depth)

      assert(vector.length == 3)
      assert(vector.range == (0 until 3))
      assert(vector.getBitsWidth == 24)
      assert(widthOf(vector) == 24)
    }
  }

  test("fixed depth does not authorize a varying element width as Int geometry") {
    withSpinalElaboration { () =>
      val width = parameter("WIDTH", default = 6, minimum = 1, maximum = 12)
      val vector = Vec(UInt(width bits), 4)

      assert(vector.length == 4)
      assert(vector.range == (0 until 4))
      expectUnsupported(vector.getBitsWidth)
      expectUnsupported(widthOf(vector))

      val logicalWidth = widthOfExpr(vector)
      assert(logicalWidth.witness == 24)
      assert(logicalWidth.minimum == 4)
      assert(logicalWidth.maximum == 48)
      assert(compact(logicalWidth.expression.verilog).contains("WIDTH"))
    }
  }

  test("widthOfExpr rejects independently rooted Vec dimensions") {
    withSpinalElaboration { () =>
      val width = parameter("WIDTH", default = 7, minimum = 1, maximum = 16)
      val depth = parameter("DEPTH", default = 5, minimum = 1, maximum = 8)
      val vector = Vec(UInt(width bits), depth)

      val failure = intercept[ParameterizedVerilogException] {
        widthOfExpr(vector)
      }
      assert(failure.code == "SPINAL-ELAB-INT-INDEPENDENT-ROOTS-UNSUPPORTED")
    }
  }

  test("compound element width and depth expressions are retained exactly") {
    withSpinalElaboration { () =>
      val width = parameter("WIDTH", default = 5, minimum = 1, maximum = 12)
      val depth = parameter("DEPTH", default = 4, minimum = 1, maximum = 7)
      val vector = Vec(Bits((width + 1) bits), depth + 1)
      val shape = requiredShape(vector)

      assert(compact(shape.depth.verilog) == "(DEPTH+1)")
      assert(shape.depth.default == 5)
      assert(shape.depth.minimum == 2)
      assert(shape.depth.maximum == 8)
      assert(compact(shape.elementLeaves.head.width.verilog) == "(WIDTH+1)")
      assert(shape.elementLeaves.head.width.default == 6)
      assert(shape.elementLeaves.head.width.minimum == 2)
      assert(shape.elementLeaves.head.width.maximum == 13)
      assert(shape.parameters.map(_.name) == Vector("DEPTH", "WIDTH"))
    }
  }

  test("clone HardType Reg Stream Flow and enclosing Bundle retain Vec shape roots") {
    withSpinalElaboration { () =>
      val width = parameter("WIDTH", default = 5, minimum = 1, maximum = 9)
      val depth = parameter("DEPTH", default = 3, minimum = 1, maximum = 5)
      val original = Vec(UInt(width bits), depth)
      val cloned = cloneOf(original)
      val hardClone = HardType(original)()
      val registered = Reg(HardType(original))
      val streamPayload = Stream(HardType(original)).payload
      val flowPayload = Flow(HardType(original)).payload
      val envelope = Envelope(width, depth)
      val envelopeClone = cloneOf(envelope)

      val originalShape = requiredShape(original)
      val copies = Vector(
        cloned,
        hardClone,
        registered,
        streamPayload,
        flowPayload,
        envelope.samples,
        envelopeClone.samples
      )
      copies.foreach { copy =>
        val copiedShape = requiredShape(copy)
        assert(copiedShape.depth.verilog == originalShape.depth.verilog)
        assert(copiedShape.witnessDepth == originalShape.witnessDepth)
        assert(copiedShape.carrierCapacity == originalShape.carrierCapacity)
        assert(copiedShape.elementLeaves.map(_.path) == originalShape.elementLeaves.map(_.path))
        assert(
          copiedShape.depth.parameterRoots
            .zip(originalShape.depth.parameterRoots)
            .forall { case (left, right) => left eq right }
        )
        assert(
          copiedShape.elementLeaves
            .zip(originalShape.elementLeaves)
            .forall { case (left, right) =>
              left.width.parameterRoots
                .zip(right.width.parameterRoots)
                .forall { case (leftRoot, rightRoot) => leftRoot eq rightRoot }
            }
        )
      }
    }
  }

  test("Vec of Bundle retains logical fields and twenty-four-bit element shape") {
    withSpinalElaboration { () =>
      val depth = parameter("DEPTH", default = 5, minimum = 1, maximum = 8)
      val pixels = Vec(Pixel(), depth)
      val shape = requiredShape(pixels)

      assert(pixels(0).isInstanceOf[Pixel])
      assert(pixels(0).r.isInstanceOf[UInt])
      assert(shape.elementLeaves.size == 3)
      assert(shape.elementLeaves.map(_.width.verilog) == Vector("8", "8", "8"))
      assert(shape.elementLeaves.forall(_.typeObject eq spinal.core.internals.TypeUInt))
      assert(shape.elementWidthDefault == 24)
      assert(shape.elementWidthMinimum == 24)
      assert(shape.elementWidthMaximum == 24)
    }
  }

  test("constant and dynamic indexing record native Vec operations without erasing shape") {
    withSpinalElaboration { () =>
      val depth = parameter("DEPTH", default = 5, minimum = 3, maximum = 8)
      val vector = Vec(UInt(8 bits), depth)
      val address = UInt(3 bits)
      val first = vector(0)
      val third = vector(2)
      val selected = vector(address)
      val operations = ParameterizedVec.operationsOf(vector)

      assert(first.isInstanceOf[UInt])
      assert(third.isInstanceOf[UInt])
      assert(selected.isInstanceOf[UInt])
      assert(operations.collect { case value: ParameterizedVecStaticIndex => value.index } == Vector(0, 2))
      assert(operations.count(_.isInstanceOf[ParameterizedVecDynamicAccess]) == 1)
      assert(requiredShape(vector).depth.verilog == "DEPTH")
    }
  }

  test("compatible whole-Vec assignment remains one native Vec operation") {
    withSpinalElaboration { () =>
      val width = parameter("WIDTH", default = 5, minimum = 1, maximum = 9)
      val depth = parameter("DEPTH", default = 3, minimum = 1, maximum = 8)
      val source = Vec(UInt(width bits), depth)
      val target = Vec(UInt(width bits), depth)

      target := source

      val assignments = ParameterizedVec.operationsOf(target).collect { case value: ParameterizedVecWholeAssignment =>
        value
      }
      assert(assignments.size == 1)
      assert(assignments.head.source eq source)
      assert(assignments.head.assignments.size == requiredShape(target).carrierCapacity)
      assert(requiredShape(target).depth.verilog == "DEPTH")
    }
  }

  test("same-root exhaustive Vec dimensions accept equivalent authored algebra") {
    withSpinalElaboration { () =>
      val width = parameter("WIDTH", default = 5, minimum = 1, maximum = 9)
      val depth = parameter("DEPTH", default = 3, minimum = 1, maximum = 8)
      val source = Vec(UInt(width bits), depth)
      val wholeTarget = Vec(UInt((width + 0) bits), depth * 1)
      val packedTarget = Vec(UInt((width + 0) bits), depth * 1)

      source.vec.foreach(_ := 0)
      wholeTarget := source
      packedTarget.assignFromBits(source.asBits)

      assert(
        ParameterizedVec
          .operationsOf(wholeTarget)
          .count(_.isInstanceOf[ParameterizedVecWholeAssignment]) == 1
      )
      assert(
        ParameterizedVec
          .operationsOf(packedTarget)
          .count(_.isInstanceOf[ParameterizedVecPackedAssignment]) == 1
      )
      assert(
        ElabInt.equivalentExactFunction(
          depth.projectedExpression("full-domain Vec equivalence"),
          (depth * 1).expression
        )
      )
    }
  }

  test("same witnesses and summaries do not replace exhaustive Vec evidence") {
    val base = parameter("DEPTH", default = 2, minimum = 1, maximum = 3)
    val increasing = (base + 1).expression
    val decreasing = ((base * -1) + 5).expression

    assert(increasing.default == decreasing.default)
    assert(increasing.minimum == decreasing.minimum)
    assert(increasing.maximum == decreasing.maximum)
    assert(!ElabInt.equivalentExactFunction(increasing, decreasing))

    val first = parameter("SAME_DEPTH", default = 2, minimum = 1, maximum = 3)
    val second = parameter("SAME_DEPTH", default = 2, minimum = 1, maximum = 3)
    assert(first.expression.parameters == second.expression.parameters)
    assert(!ElabInt.equivalentExactFunction(first.expression, second.expression))
  }

  test("equal Vec schemas on distinct roots cannot authorize direct aggregate hierarchy") {
    withSpinalElaboration { () =>
      final class Holder(depth: ElabInt) extends Component {
        val vector = Vec(Bool(), depth)
        vector.vec.foreach(_ := False)
      }

      val canonicalDepth =
        parameter("DEPTH", default = 3, minimum = 1, maximum = 8)
      val actualDepth =
        parameter("DEPTH", default = 3, minimum = 1, maximum = 8)
      val canonical = new Holder(canonicalDepth)
      val actual = new Holder(actualDepth)

      assert(
        ParameterizedVec
          .exactDirectAggregateHierarchyBinding(
            canonical.vector,
            actual.vector,
            canonicalDepth.parameters.head
          )
          .isEmpty
      )
    }
  }

  test("partial Vec dimension evidence cannot lose projection provenance") {
    val depth = parameter("DEPTH", default = 3, minimum = 1, maximum = 4)
    val domain = depth.expression.exactDomain.getOrElse(
      fail("typed Vec equivalence input lost exact-domain evidence")
    )

    ElaborationDomainContext.withAdmitted(
      domain.root,
      Set(BigInt(2), BigInt(3)),
      sourceLocation = None
    ) {
      val projected = depth.projectedExpression("partial Vec dimension")
      val derivedWithProjection = (depth * 1).expression
      assert(projected.exactDomain.exists(_.evidenceValues == Set(BigInt(2), BigInt(3))))
      assert(derivedWithProjection.projectionProvenance.nonEmpty)
      assert(ElabInt.equivalentExactFunction(projected, derivedWithProjection))

      val copiedWithoutProjection = derivedWithProjection.copy()
      assert(copiedWithoutProjection.projectionProvenance.isEmpty)
      val failure = intercept[ParameterizedVerilogException] {
        ElabInt.fromExpression(copiedWithoutProjection).bits
      }
      assert(
        failure.code == "SPINAL-ELAB-DOMAIN-EXACT-AUTHORITY-MISSING"
      )
    }
  }

  test("carrier capacity is independent of the parameter default witness") {
    def observe(default: Int): (Int, Int, BigInt, BigInt) = {
      var result: (Int, Int, BigInt, BigInt) = null
      withSpinalElaboration { () =>
        val depth = parameter("DEPTH", default, minimum = 1, maximum = 8)
        val vector = Vec(UInt(8 bits), depth)
        val shape = requiredShape(vector)
        result = (
          vector.carrierLength,
          shape.carrierCapacity,
          shape.depth.minimum,
          shape.depth.maximum
        )
      }
      result
    }

    assert(observe(default = 1) == (8, 8, BigInt(1), BigInt(8)))
    assert(observe(default = 5) == (8, 8, BigInt(1), BigInt(8)))
  }

  test("constant index must exist for every legal symbolic depth") {
    var failure: ParameterizedVerilogException = null
    withSpinalElaboration { () =>
      val depth = parameter("DEPTH", default = 5, minimum = 1, maximum = 8)
      val vector = Vec(UInt(8 bits), depth)
      failure = intercept[ParameterizedVerilogException] {
        vector(2)
      }
    }
    assert(failure.code == "SPINAL-ELAB-VEC-STATIC-INDEX-DOMAIN-INVALID")
  }

  test("dynamic address must cover the complete symbolic depth domain") {
    var failure: ParameterizedVerilogException = null
    withSpinalElaboration { () =>
      val depth = parameter("DEPTH", default = 5, minimum = 1, maximum = 8)
      val vector = Vec(UInt(8 bits), depth)
      failure = intercept[ParameterizedVerilogException] {
        vector(UInt(2 bits))
      }
    }
    assert(failure.code == "SPINAL-ELAB-VEC-ADDRESS-CAPACITY-INVALID")
  }

  test("depth-correlated addressWidth is accepted pointwise across the domain") {
    withSpinalElaboration { () =>
      val depth = parameter("DEPTH", default = 5, minimum = 1, maximum = 8)
      val vector = Vec(UInt(8 bits), depth)
      val address = UInt(depth.addressWidth bits)
      val selected = vector(address)
      val addressExpression = ParameterizedWidth
        .expressionOf(address)
        .getOrElse(fail("typed Vec address width metadata is missing"))

      assert(selected.isInstanceOf[UInt])
      assert(compact(addressExpression.verilog) == "morphhdl_address_width(DEPTH)")
      assert(addressExpression.default == 3)
      assert(addressExpression.minimum == 1)
      assert(addressExpression.maximum == 3)
      assert(
        addressExpression.parameterRoots
          .zip(requiredShape(vector).depth.parameterRoots)
          .forall { case (addressRoot, depthRoot) => addressRoot eq depthRoot }
      )
      assert(
        ParameterizedVec
          .operationsOf(vector)
          .count(_.isInstanceOf[ParameterizedVecDynamicAccess]) == 1
      )
    }
  }

  test("incompatible symbolic Vec assignments fail even when witnesses match") {
    var failure: ParameterizedVerilogException = null
    withSpinalElaboration { () =>
      val leftDepth = parameter("LEFT_DEPTH", default = 5, minimum = 1, maximum = 8)
      val rightBase = parameter("RIGHT_BASE", default = 4, minimum = 0, maximum = 7)
      val left = Vec(UInt(8 bits), leftDepth)
      val right = Vec(UInt(8 bits), rightBase + 1)
      assert(requiredShape(left).witnessDepth == requiredShape(right).witnessDepth)
      assert(requiredShape(left).carrierCapacity == requiredShape(right).carrierCapacity)
      failure = intercept[ParameterizedVerilogException] {
        left := right
      }
    }
    assert(failure.code == "SPINAL-ELAB-VEC-ASSIGNMENT-SHAPE-MISMATCH")
  }

  test("deferred symbolic-depth Vec APIs fail closed before native carrier use") {
    expectSymbolicOperationUnsupported("fixed range selection") { (vector, _) =>
      vector(0 until 1)
    }
    expectSymbolicOperationUnsupported("one-hot access") { (vector, _) =>
      vector.oneHotAccess(B(1, 8 bits))
    }
    expectSymbolicOperationUnsupported("equality") { (vector, depth) =>
      vector === Vec(Bits(8 bits), depth)
    }
    expectSymbolicOperationUnsupported("inequality") { (vector, depth) =>
      vector =/= Vec(Bits(8 bits), depth)
    }
    expectSymbolicOperationUnsupported("four-state equality") { (vector, depth) =>
      vector =::= Vec(Bits(8 bits), depth)
    }
    expectSymbolicOperationUnsupported("zero construction") { (vector, _) =>
      vector.getZero
    }
    expectSymbolicOperationUnsupported("bitwise or") { (vector, depth) =>
      vector | Vec(Bits(8 bits), depth)
    }
    expectSymbolicOperationUnsupported("bitwise and") { (vector, depth) =>
      vector & Vec(Bits(8 bits), depth)
    }
    expectSymbolicOperationUnsupported("bitwise xor") { (vector, depth) =>
      vector ^ Vec(Bits(8 bits), depth)
    }
    expectSymbolicOperationUnsupported("bitwise inversion") { (vector, _) =>
      ~vector
    }
    expectSymbolicOperationUnsupported("ranged packed assignment") { (vector, _) =>
      vector.assignFromBits(B(0, 8 bits), hi = 7, lo = 0)
    }
  }

  test("ordinary concrete Vec APIs remain on their native surface") {
    exerciseConcreteOperation(vector => vector(0 until 1))
    exerciseConcreteOperation(vector => vector.oneHotAccess(B(1, 3 bits)))
    exerciseConcreteOperation(vector => vector === Vec(Bits(8 bits), 3))
    exerciseConcreteOperation(vector => vector =/= Vec(Bits(8 bits), 3))
    exerciseConcreteOperation(vector => vector =::= Vec(Bits(8 bits), 3))
    exerciseConcreteOperation(_.getZero)
    exerciseConcreteOperation(vector => vector | Vec(Bits(8 bits), 3))
    exerciseConcreteOperation(vector => vector & Vec(Bits(8 bits), 3))
    exerciseConcreteOperation(vector => vector ^ Vec(Bits(8 bits), 3))
    exerciseConcreteOperation(vector => ~vector)
    exerciseConcreteOperation(
      _.assignFromBits(B(0, 8 bits), hi = 7, lo = 0)
    )
  }

  test("symbolic Vec depths that reach zero or negative values fail closed") {
    Vector(
      ("ZERO_DEPTH", 1, 0, 8),
      ("NEGATIVE_DEPTH", 1, -2, 8)
    ).foreach { case (name, default, minimum, maximum) =>
      var failure: ParameterizedVerilogException = null
      withSpinalElaboration { () =>
        val depth = parameter(name, default, minimum, maximum)
        failure = intercept[ParameterizedVerilogException] {
          Vec(UInt(8 bits), depth)
        }
      }
      assert(failure.code == "SPINAL-ELAB-INT-VEC-DEPTH-DOMAIN-INVALID")
    }

    var expressionFailure: ParameterizedVerilogException = null
    withSpinalElaboration { () =>
      val base = parameter("BASE_DEPTH", default = 4, minimum = 1, maximum = 8)
      expressionFailure = intercept[ParameterizedVerilogException] {
        Vec(UInt(8 bits), base - 1)
      }
    }
    assert(expressionFailure.code == "SPINAL-ELAB-INT-VEC-DEPTH-DOMAIN-INVALID")
  }

  test("ElabInt literals delegate to the ordinary concrete Vec path") {
    withSpinalElaboration { () =>
      val ordinary = Vec(UInt(8 bits), 4)
      val typedLiteral = Vec(UInt(8 bits), ElabInt.literal(4))

      assert(ordinary.length == 4)
      assert(typedLiteral.length == 4)
      assert(ordinary.range == (0 until 4))
      assert(typedLiteral.range == ordinary.range)
      assert(ordinary.getBitsWidth == 32)
      assert(typedLiteral.getBitsWidth == ordinary.getBitsWidth)
      assert(widthOf(ordinary) == 32)
      assert(widthOf(typedLiteral) == widthOf(ordinary))
      assert(widthOfExpr(ordinary).witness == 32)
      assert(widthOfExpr(typedLiteral).witness == 32)
      assert(ParameterizedVec.shapeOf(ordinary).isEmpty)
      assert(ParameterizedVec.shapeOf(typedLiteral).isEmpty)
      assert(ordinary.getClass == typedLiteral.getClass)
    }
  }

  test("ParameterizedWidth typed Vec keeps one stable authored HardType") {
    var ordinaryCalls = 0
    withSpinalElaboration { () =>
      val vector = ParameterizedWidth.Vec(
        {
          ordinaryCalls += 1
          UInt(8 bits)
        },
        4
      )
      assert(vector.length == 4)
    }

    var literalCalls = 0
    withSpinalElaboration { () =>
      val vector = ParameterizedWidth.Vec(
        {
          literalCalls += 1
          UInt(8 bits)
        },
        ElabInt.literal(4)
      )
      assert(vector.length == 4)
      assert(ParameterizedVec.shapeOf(vector).isEmpty)
    }

    var symbolicCalls = 0
    withSpinalElaboration { () =>
      val depth = parameter("DEPTH", default = 4, minimum = 1, maximum = 7)
      val vector = ParameterizedWidth.Vec(
        {
          symbolicCalls += 1
          UInt(8 bits)
        },
        depth
      )
      assert(vector.carrierLength == 7)
      assert(requiredShape(vector).depth.verilog == "DEPTH")
    }

    assert(ordinaryCalls == 1)
    assert(literalCalls == ordinaryCalls)
    assert(symbolicCalls == ordinaryCalls)
  }

  test("Int symbolic and literal Vec factories share generator order and carrier graph") {
    val capacity = 4
    val ordinaryCalls = ArrayBuffer.empty[Int]
    val symbolicCalls = ArrayBuffer.empty[Int]
    val literalCalls = ArrayBuffer.empty[Int]
    var ordinaryGraph: Vector[(String, Vector[(String, Int)], Boolean, Boolean)] = null
    var symbolicGraph: Vector[(String, Vector[(String, Int)], Boolean, Boolean)] = null
    var literalGraph: Vector[(String, Vector[(String, Int)], Boolean, Boolean)] = null

    withSpinalElaboration { () =>
      def generated(prefix: String, calls: ArrayBuffer[Int]): UInt = {
        val ordinal = calls.size
        calls += ordinal
        UInt(8 bits).setName(s"${prefix}_$ordinal")
      }
      def graph(
          vector: Vec[UInt]
      ): Vector[(String, Vector[(String, Int)], Boolean, Boolean)] =
        vector.vec.map { element =>
          (
            element.getClass.getName,
            element.flatten.toVector.map(leaf => leaf.getClass.getName -> leaf.getBitsWidth),
            element.parent eq vector,
            element.getRootParent eq vector
          )
        }
      def assertElementOrder(vector: Vec[UInt], prefix: String): Unit =
        assert(
          vector.vec.map(_.getName()) ==
            (0 until capacity).map(index => s"${prefix}_$index").toVector
        )

      val ordinary = Vec(generated("ordinary", ordinaryCalls), capacity)
      val depth = parameter("DEPTH", default = 3, minimum = 1, maximum = capacity)
      val symbolic = Vec(generated("symbolic", symbolicCalls), depth)
      val literal = Vec(
        generated("literal", literalCalls),
        ElabInt.literal(capacity)
      )

      assertElementOrder(ordinary, "ordinary")
      assertElementOrder(symbolic, "symbolic")
      assertElementOrder(literal, "literal")
      ordinaryGraph = graph(ordinary)
      symbolicGraph = graph(symbolic)
      literalGraph = graph(literal)
      assert(ParameterizedVec.shapeOf(ordinary).isEmpty)
      assert(ParameterizedVec.shapeOf(literal).isEmpty)
      val symbolicShape = requiredShape(symbolic)
      assert(symbolicShape.carrierCapacity == capacity)
      assert(symbolicShape.depth.verilog == "DEPTH")
    }

    assert(ordinaryCalls.take(capacity).toVector == (0 until capacity).toVector)
    assert(symbolicCalls.toVector == ordinaryCalls.toVector)
    assert(literalCalls.toVector == ordinaryCalls.toVector)
    assert(symbolicGraph == ordinaryGraph)
    assert(literalGraph == ordinaryGraph)
    assert(ordinaryGraph.forall { case (_, _, directParent, rootParent) =>
      directParent && rootParent
    })
  }

  private def parameter(
      name: String,
      default: Int,
      minimum: Int,
      maximum: Int
  ): ElabInt =
    HdlInt
      .param(
        name,
        default = BigInt(default),
        min = BigInt(minimum),
        max = BigInt(maximum)
      )
      .asElabInt

  private def requiredShape(vector: Vec[_]): ParameterizedVecShape =
    ParameterizedVec
      .shapeOf(vector)
      .getOrElse(fail("typed Vec shape metadata is missing"))

  private def compact(value: String): String = value.replaceAll("\\s+", "")

  private def expectUnsupported(body: => Any): Unit = {
    val failure = intercept[ParameterizedVerilogException](body)
    assert(failure.code == "SPINAL-ELAB-VEC-OPERATION-UNSUPPORTED")
  }

  private def expectSymbolicOperationUnsupported(
      operation: String
  )(body: (Vec[Bits], ElabInt) => Any): Unit = {
    var failure: ParameterizedVerilogException = null
    withSpinalElaboration { () =>
      val depth = parameter("DEPTH", default = 3, minimum = 1, maximum = 8)
      val vector = Vec(Bits(8 bits), depth)
      failure = intercept[ParameterizedVerilogException] {
        body(vector, depth)
      }
    }
    assert(failure != null, s"$operation did not fail closed")
    assert(
      failure.code == "SPINAL-ELAB-VEC-OPERATION-UNSUPPORTED",
      s"$operation failed with ${failure.code}"
    )
  }

  private def exerciseConcreteOperation(body: Vec[Bits] => Any): Unit =
    withSpinalElaboration { () =>
      val vector = Vec(Bits(8 bits), 3)
      body(vector)
    }

  private def withSpinalElaboration(body: () => Unit): Unit = {
    val directory = Files.createTempDirectory("morphhdl-typed-vec-shape-")
    try {
      SpinalVerilog(
        SpinalConfig(
          targetDirectory = directory.toString,
          headerWithRepoHash = false,
          withTimescale = false,
          printFilelist = false
        )
      ) {
        new Component {
          val keep = out(Bool())
          keep := False
          body()
        }
      }
    } finally deleteRecursively(directory)
  }

  private def deleteRecursively(directory: Path): Unit = {
    val stream = Files.walk(directory)
    try {
      stream.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach { path =>
        Files.deleteIfExists(path)
      }
    } finally stream.close()
  }
}
