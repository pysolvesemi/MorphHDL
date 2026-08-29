#!/usr/bin/env python3
from pathlib import Path


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    value = path.read_text()
    count = value.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}")
    path.write_text(value.replace(old, new, 1))


fallback = Path(
    "morphhdl/src/main/scala/spinal/core/internals/"
    "ExternalParameterizedVerilogNativeFallback.scala"
)

analysis_old = '''    component.dslBody.walkDeclarations {
      case baseType: BaseType if !baseType.isSuffix => declarations += baseType
      case memory: Mem[_]                           => memories += memory
      case _                                        =>
    }
    component.dslBody.walkLeafStatements {
'''
analysis_new = '''    component.dslBody.walkDeclarations {
      case baseType: BaseType if !baseType.isSuffix => declarations += baseType
      case memory: Mem[_]                           => memories += memory
      case _                                        =>
    }
    // MultiData containers are not guaranteed to expose every flattened leaf
    // through walkDeclarations after native normalization. Include any exact
    // graph-owned BitVector carrying retained width metadata, while preserving
    // the normal declaration order for leaves already reported by SpinalHDL.
    retainedPackedLeavesOf(component).foreach { leaf =>
      if (!declarations.exists(existing => existing eq leaf)) {
        declarations += leaf
      }
    }
    component.dslBody.walkLeafStatements {
'''
replace_once(
    fallback,
    analysis_old,
    analysis_new,
    "retained packed Analysis declaration inventory",
)

value = fallback.read_text()
supports_marker = '''  def supports(
      failure: ParameterizedVerilogException,
      component: Component
  ): Boolean =
'''
if value.count(supports_marker) != 1:
    raise SystemExit(
        f"retained packed helper marker count={value.count(supports_marker)}"
    )
helper = '''  /**
    * Enumerate graph-owned native packed leaves carrying retained symbolic
    * width metadata, including leaves flattened from Vec, Bundle and other
    * MultiData containers which may no longer appear as standalone declaration
    * statements after native normalization.
    *
    * Discovery is solely by exact expression identity and component ownership.
    * Equal concrete widths, signal names, component classes and source paths are
    * never lookup keys. The traversal follows the already elaborated native AST
    * and does not infer new widths; only leaves previously proven and attached
    * by a generic provenance rule are returned.
    */
  private[internals] def retainedPackedLeavesOf(
      component: Component
  ): Vector[BitVector] = {
    if (component == null) return Vector.empty

    val visited = new IdentityHashMap[Expression, java.lang.Boolean]()
    val retained = new IdentityHashMap[BitVector, java.lang.Boolean]()
    val result = ArrayBuffer.empty[BitVector]

    def visit(expression: Expression): Unit = {
      if (expression == null ||
          visited.put(expression, java.lang.Boolean.TRUE) != null) return

      expression match {
        case value: BitVector
            if (value.component eq component) &&
              ParameterizedWidth
                .expressionOf(value)
                .exists(_.parameters.nonEmpty) &&
              retained.put(value, java.lang.Boolean.TRUE) == null =>
          result += value
        case _ =>
      }
      expression.foreachExpression(visit)
    }

    component.getOrdredNodeIo.toVector.filterNot(_.isSuffix).foreach(visit)
    component.dslBody.walkLeafStatements {
      case assignment: DataAssignmentStatement =>
        visit(assignment.target)
        visit(assignment.finalTarget)
        visit(assignment.source)
      case expression: Expression => visit(expression)
      case _                      =>
    }
    result.toVector
  }

  /** Canonical parameter inventory for the exact retained packed graph. */
  private[internals] def retainedPackedParametersOf(
      component: Component
  ): Vector[ElaborationIntegerParameter] = {
    val associated = retainedPackedLeavesOf(component).flatMap { leaf =>
      ParameterizedWidth.expressionOf(leaf).toVector.flatMap { expression =>
        expression.parameters.map(parameter => leaf -> parameter)
      }
    }
    val values = associated.map(_._2)
    values.groupBy(_.name).collectFirst {
      case (name, schemas) if schemas.distinct.size != 1 => name
    }.foreach { name =>
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-SCHEMA-CONFLICT",
        s"parameter '$name' has conflicting retained packed declarations on component '${component.definitionName}'",
        associated.find(_._2.name == name).flatMap { case (leaf, _) =>
          ParameterizedWidth.sourceLocationOf(leaf)
        }
      )
    }
    values.distinct.sortBy(_.name).toVector
  }

'''
fallback.write_text(value.replace(supports_marker, helper + supports_marker, 1))

value = fallback.read_text()
component_metadata = "ParameterizedWidth.parametersOf(component)"
component_count = value.count(component_metadata)
if component_count != 1:
    raise SystemExit(
        f"fallback retained packed component trigger count={component_count}"
    )
value = value.replace(
    component_metadata,
    "retainedPackedParametersOf(component)",
    1,
)
child_metadata = "ParameterizedWidth.parametersOf(child)"
child_count = value.count(child_metadata)
if child_count != 1:
    raise SystemExit(
        f"fallback retained packed child trigger count={child_count}"
    )
value = value.replace(
    child_metadata,
    "retainedPackedParametersOf(child)",
    1,
)
fallback.write_text(value)

publisher = Path(
    "morphhdl/src/main/scala/spinal/core/internals/"
    "MorphHdlExternalParameterizedVerilog.scala"
)
value = publisher.read_text()
component_metadata = "ParameterizedWidth.parametersOf(component)"
component_count = value.count(component_metadata)
if component_count != 3:
    raise SystemExit(
        f"publisher retained packed component inventory count={component_count}"
    )
value = value.replace(
    component_metadata,
    "ExternalParameterizedVerilogNativeFallback.retainedPackedParametersOf(component)",
)
child_metadata = "ParameterizedWidth.parametersOf(child)"
child_count = value.count(child_metadata)
if child_count != 1:
    raise SystemExit(
        f"publisher retained packed child inventory count={child_count}"
    )
value = value.replace(
    child_metadata,
    "ExternalParameterizedVerilogNativeFallback.retainedPackedParametersOf(child)",
    1,
)
publisher.write_text(value)

hierarchy = Path(
    "morphhdl/src/main/scala/spinal/core/internals/"
    "ExternalParameterizedVerilogHierarchy.scala"
)
value = hierarchy.read_text()
component_metadata = "ParameterizedWidth.parametersOf(component)"
component_count = value.count(component_metadata)
if component_count != 1:
    raise SystemExit(
        f"hierarchy retained packed component inventory count={component_count}"
    )
hierarchy.write_text(
    value.replace(
        component_metadata,
        "ExternalParameterizedVerilogNativeFallback.retainedPackedParametersOf(component)",
        1,
    )
)

test = Path(
    "morphhdl/src/test/scala/morphhdl/GenericImplicitPackedShapeTests.scala"
)
value = test.read_text()
class_marker = "class GenericImplicitPackedShapeTests extends AnyFunSuite {\n"
if value.count(class_marker) != 1:
    raise SystemExit(
        f"generic Vec witness class marker count={value.count(class_marker)}"
    )
vec_classes = '''/**
  * A second arbitrary native child whose retained leaves live inside a Vec.
  * This proves declaration publication is based on exact flattened graph
  * identity rather than direct top-level val declarations or a library class.
  */
final class GenericConcretePackedVecPipeline(width: Int) extends Component {
  setDefinitionName("GenericConcretePackedVecPipeline")

  val io = new Bundle {
    val input = in Bits (width bits)
    val output = out Bits (width bits)
  }

  val stages = Vec(Reg(Bits(width bits)) init (0), 2)
  stages(0).addTag(crossClockDomain)
  stages(0) := io.input
  stages(1) := stages(0)
  io.output := stages(1)
}

final class GenericImplicitPackedVecShapeHarness(width: HdlInt)
    extends Component {
  setDefinitionName("GenericImplicitPackedVecShapeHarness")

  val io = new Bundle {
    val input = in(morphhdl.frontend.Bits(width.bits))
    val output = out(morphhdl.frontend.Bits(width.bits))
  }

  val child = new GenericConcretePackedVecPipeline(8)
  child.setName("child")
  child.io.input := io.input
  io.output := child.io.output
}

'''
value = value.replace(class_marker, vec_classes + class_marker, 1)

test_marker = '''  private def run(directory: Path, command: Seq[String]): (Int, String) = {
'''
if value.count(test_marker) != 1:
    raise SystemExit(
        f"generic Vec witness test marker count={value.count(test_marker)}"
    )
vec_test = '''  test("arbitrary native Vec-backed packed lineage rewrites flattened leaves") {
    withTemporaryDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "generic_implicit_packed_vec_shape.v"
      val width = HdlInt.param(
        "WIDTH",
        default = BigInt(8),
        min = BigInt(4),
        max = BigInt(16)
      )

      MorphVerilog(config) {
        new GenericImplicitPackedVecShapeHarness(width)
      }

      val rtl = directory.resolve("generic_implicit_packed_vec_shape.v")
      val verilog = read(rtl)
      val lines = verilog.split("\\n", -1).toVector

      assert(verilog.contains("module GenericImplicitPackedVecShapeHarness #("))
      assert(verilog.contains("module GenericConcretePackedVecPipeline #("))
      assert(verilog.contains("parameter integer WIDTH = 8"))
      assert(verilog.contains(".WIDTH(WIDTH)"))

      val registers = lines.filter { line =>
        line.contains(" reg ") &&
        (line.contains("stages_0") || line.contains("stages_1"))
      }
      assert(
        registers.size == 2,
        s"Expected two Vec-backed registers, found ${registers.mkString(" | ")}"
      )
      assert(
        registers.forall(_.contains("[WIDTH-1:0]")),
        s"Vec-backed registers retained concrete witness widths: ${registers.mkString(" | ")}"
      )

      Vector(4, 8, 16).foreach { selectedWidth =>
        val command = Seq(
          "verilator",
          "--lint-only",
          "--language",
          "1364-2001",
          "-Wall",
          "-Wno-DECLFILENAME",
          "-Wno-UNUSED",
          "--top-module",
          "GenericImplicitPackedVecShapeHarness",
          s"-GWIDTH=$selectedWidth",
          rtl.toString
        )
        val result = run(directory, command)
        assert(
          result._1 == 0,
          s"Verilator lint failed for generic Vec WIDTH=$selectedWidth:\\n${result._2}"
        )
      }
    }
  }

'''
test.write_text(value.replace(test_marker, vec_test + test_marker, 1))
