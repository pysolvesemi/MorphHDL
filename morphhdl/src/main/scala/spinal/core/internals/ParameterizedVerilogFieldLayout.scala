package spinal.core.internals

import scala.collection.mutable

import spinal.core._

/** Wiring geometry for the field-preserving Vec publication profile.
  *
  * A field vector visits axes outermost first, with the last axis varying
  * fastest and coordinate zero occupying the least significant scalar slice.
  * Explicit Vec.asBits/assignFromBits retain the native recursive element-major
  * order; [[Layout.packedBridge]] implements that permutation using wiring.
  * Field paths, axes and packing order come from exact retained native shape
  * metadata. Emitted names are only allocated after that geometry is known.
  */
private[internals] object ParameterizedVerilogFieldLayout {
  private val PortableIdentifier = "[A-Za-z_][A-Za-z0-9_$]*".r

  final case class Field(
      retained: ParameterizedVecFieldShape,
      name: String,
      scalarWidth: String,
      dimensions: Vector[String],
      width: String,
      range: String
  ) {
    def path: Vector[String] = retained.path
    def typeObject: AnyRef = retained.typeObject
    def leafIndices: Vector[Int] = retained.carrierLeafIndices
  }

  final class Layout private[ParameterizedVerilogFieldLayout] (
      val shape: ParameterizedVecShape,
      val baseName: String,
      val fields: Vector[Field],
      val elementWidth: String,
      val totalWidth: String,
      renderExpr: ElaborationIntegerExpression => String,
      private val packing: Map[Vector[String], Packing]
  ) {
    val range: String = s"[($totalWidth)-1:0]"

    /** Same-shape internal aggregate only. This is deliberately field-major;
      * explicit bit packing must use packedBridge instead.
      */
    val aggregate: String =
      if (fields.size == 1) fields.head.name
      else fields.reverse.map(_.name).mkString("{", ", ", "}")

    private val byLeaf: Map[Int, (Field, Vector[Int])] = fields.flatMap { field =>
      field.leafIndices.zipWithIndex.map { case (leafIndex, ordinal) =>
        var remainder = ordinal
        val coordinates = field.retained.dimensions.reverse.map { axis =>
          val value = remainder % axis.carrierCapacity
          remainder /= axis.carrierCapacity
          value
        }.reverse
        if (remainder != 0) fail(
          "FIELD-CARRIER-ORDINAL-INVALID",
          s"field '${field.path.mkString(".")}' leaf ordinal $ordinal exceeds its retained dimensions",
          shape.sourceLocation
        )
        leafIndex -> (field -> coordinates)
      }
    }.toMap

    def fieldForLeaf(leafIndex: Int): Field = exactLeaf(leafIndex)._1

    /** Coordinates within the element; the outer Vec axis is supplied by the
      * operation. Decoding uses audited native capacity, never symbolic depth.
      */
    def leafCoordinates(leafIndex: Int): Vector[Int] = exactLeaf(leafIndex)._2

    private def exactLeaf(leafIndex: Int): (Field, Vector[Int]) =
      byLeaf.getOrElse(leafIndex, fail(
        "FIELD-LEAF-MISSING",
        s"Vec '$baseName' has no retained field for native leaf $leafIndex",
        shape.sourceLocation
      ))

    def slice(field: Field, indices: Vector[String]): String = {
      s"${field.name}[(${fieldOffset(field, indices)}) +: ${field.scalarWidth}]"
    }

    def fieldOffset(field: Field, indices: Vector[String]): String = {
      if (!fields.exists(_ eq field) || indices.size != field.dimensions.size)
        fail(
          "FIELD-SLICE-GEOMETRY-MISMATCH",
          s"Vec '$baseName' field '${field.path.mkString(".")}' needs ${field.dimensions.size} exact coordinates",
          shape.sourceLocation
        )
      val ordinal = indices.zipWithIndex.foldLeft("0") { case (previous, (index, axis)) =>
        sum(Vector(product(Vector(previous, field.dimensions(axis))), index))
      }
      product(Vector(ordinal, field.scalarWidth))
    }

    def sliceBlock(field: Field, indices: Vector[String], width: String): String = {
      s"${field.name}[(${fieldOffset(field, indices)}) +: $width]"
    }

    def constantSlice(elementIndex: Int, leafIndex: Int): String = {
      if (elementIndex < 0 || elementIndex >= shape.carrierCapacity)
        fail(
          "FIELD-OUTER-INDEX-INVALID",
          s"Vec '$baseName' element $elementIndex is outside its retained carrier capacity ${shape.carrierCapacity}",
          shape.sourceLocation
        )
      val (field, coordinates) = exactLeaf(leafIndex)
      slice(field, elementIndex.toString +: coordinates.map(_.toString))
    }

    def dynamicSlice(address: String, leafIndex: Int, clampRead: Boolean): String = {
      val (field, coordinates) = exactLeaf(leafIndex)
      slice(field, selectedAddress(address, clampRead) +: coordinates.map(_.toString))
    }

    /** Exact recursive element-major slice, also usable by the legacy packed
      * profile when an element contains independently parameterized Vec axes.
      */
    def packedSlice(packedName: String, field: Field, indices: Vector[String]): String = {
      requireIdentifier(packedName, "packed slice", shape.sourceLocation)
      val offset = packedOffset(field, indices)
      s"$packedName[($offset) +: ${field.scalarWidth}]"
    }

    def packingOffset(leafIndex: Int, outerExpr: String): String = {
      val (field, coordinates) = exactLeaf(leafIndex)
      packedOffset(field, outerExpr +: coordinates.map(_.toString))
    }

    def packedOffset(field: Field, indices: Vector[String]): String = {
      if (!fields.exists(_ eq field) || indices.size != field.dimensions.size)
        fail("FIELD-PACKED-SLICE-GEOMETRY-MISMATCH",
          s"Vec '$baseName' field '${field.path.mkString(".")}' has an invalid packed coordinate list", shape.sourceLocation)
      val retainedPacking = packing(field.path)
      sum(Vector(product(Vector(indices.head, elementWidth)), retainedPacking.constant) ++
        retainedPacking.strides.zip(indices.tail).map { case (stride, index) => product(Vector(index, stride)) })
    }

    def packedConstantSlice(packedName: String, elementIndex: Int, leafIndex: Int): String = {
      if (elementIndex < 0 || elementIndex >= shape.carrierCapacity)
        fail("FIELD-OUTER-INDEX-INVALID",
          s"Vec '$baseName' element $elementIndex is outside its retained carrier capacity ${shape.carrierCapacity}", shape.sourceLocation)
      val (field, coordinates) = exactLeaf(leafIndex)
      packedSlice(packedName, field, elementIndex.toString +: coordinates.map(_.toString))
    }

    def packedDynamicSlice(packedName: String, address: String, leafIndex: Int, clampRead: Boolean): String = {
      val (field, coordinates) = exactLeaf(leafIndex)
      packedSlice(packedName, field, selectedAddress(address, clampRead) +: coordinates.map(_.toString))
    }

    private def selectedAddress(address: String, clampRead: Boolean): String = {
      if (clampRead) {
        val depth = normalized(shape.depth, renderExpr)
        s"(($address) < ($depth) ? ($address) : (($depth) - 1))"
      } else address
    }

    /** Complete namespace introduced by packedBridge, including generate
      * scopes. Reserving only the prefix misses legal user identifiers that
      * happen to equal one generated loop index or label.
      */
    def packingIdentifiers(labelPrefix: String): Set[String] = {
      requireIdentifier(labelPrefix, "packed bridge label", shape.sourceLocation)
      fields.zipWithIndex.flatMap { case (field, fieldIndex) =>
        field.dimensions.indices.flatMap(axis => Vector(
          s"${labelPrefix}_f${fieldIndex}_i$axis",
          s"${labelPrefix}_f${fieldIndex}_d$axis"
        ))
      }.toSet
    }

    def allocatePackingLabel(preferred: String, isOccupied: String => Boolean): String = {
      var label = preferred
      var ordinal = 0
      while (isOccupied(label) || packingIdentifiers(label).exists(isOccupied)) {
        ordinal += 1
        label = s"${preferred}_$ordinal"
      }
      label
    }

    /** One module-scope generate block per field. Assignments are scalar
      * indexed part-selects, so independent dimensions stay symbolic and no
      * parameter override adds numbered declarations or ports.
      *
      * `toPacked` true implements Vec.asBits. False drives the field wires
      * from a packed source for assignFromBits. Procedural consumers can use
      * a separately named wire Layout and assign its aggregate in the original
      * statement position, preserving conditions and priority.
      */
    def packedBridge(
        packedName: String,
        toPacked: Boolean,
        labelPrefix: String
    ): Vector[String] = packedBridge(packedName, toPacked, labelPrefix, (field, indices) => slice(field, indices))

    /** Projection-aware form: packing remains local to this exact logical
      * shape while the field-side slices can refer to its owning root Vec.
      */
    def packedBridge(
        packedName: String,
        toPacked: Boolean,
        labelPrefix: String,
        selectField: (Field, Vector[String]) => String
    ): Vector[String] = {
      requireIdentifier(packedName, "packed bridge", shape.sourceLocation)
      requireIdentifier(labelPrefix, "packed bridge label", shape.sourceLocation)
      val out = Vector.newBuilder[String]
      fields.zipWithIndex.foreach { case (field, fieldIndex) =>
        val indices = field.dimensions.indices.map(axis => s"${labelPrefix}_f${fieldIndex}_i$axis").toVector
        indices.foreach(index => out += s"  genvar $index;")
      }
      out += "  generate"
      fields.zipWithIndex.foreach { case (field, fieldIndex) =>
        val indices = field.dimensions.indices.map(axis => s"${labelPrefix}_f${fieldIndex}_i$axis").toVector
        indices.zip(field.dimensions).zipWithIndex.foreach { case ((index, depth), axis) =>
          val indent = "    " + ("  " * axis)
          out += s"${indent}for ($index = 0; $index < ($depth); $index = $index + 1) begin : ${labelPrefix}_f${fieldIndex}_d$axis"
        }
        val packedSelection = packedSlice(packedName, field, indices)
        val fieldSlice = selectField(field, indices)
        val (target, source) = if (toPacked) (packedSelection, fieldSlice) else (fieldSlice, packedSelection)
        out += s"${"    " + ("  " * indices.size)}assign $target = $source;"
        indices.indices.reverse.foreach(axis => out += s"${"    " + ("  " * axis)}end")
      }
      out += "  endgenerate"
      out.result()
    }
  }

  private final case class Packing(constant: String, strides: Vector[String])

  final case class Bridge(layout: Layout, labelPrefix: String)

  /** Allocate a complete generated field/loop family against one stable
    * namespace. A collision on any suffix moves the whole private bridge to
    * the next deterministic base, without changing public field names.
    */
  def allocateBridge(
      shape: ParameterizedVecShape,
      preferred: String,
      renderExpr: ElaborationIntegerExpression => String,
      isOccupied: String => Boolean
  ): Bridge = {
    var base = preferred
    var ordinal = 0
    var layout = fromShape(shape, base, renderExpr)
    def collides: Boolean = isOccupied(base) || isOccupied(base + "_layout") ||
      layout.fields.exists(field => isOccupied(field.name)) ||
      layout.packingIdentifiers(base + "_layout").exists(isOccupied)
    while (collides) {
      ordinal += 1
      base = s"${preferred}_$ordinal"
      layout = fromShape(shape, base, renderExpr)
    }
    Bridge(layout, base + "_layout")
  }

  def build(
      vector: Vec[_],
      shape: ParameterizedVecShape,
      baseName: String,
      renderExpr: ElaborationIntegerExpression => String,
      occupiedNames: Set[String] = Set.empty[String]
  ): Layout = {
    if (vector == null)
      throw new IllegalArgumentException("field publication requires an exact Vec")
    fromShape(shape, baseName, renderExpr, occupiedNames)
  }

  /** Pure retained-metadata geometry. Publication enters through build with
    * its identity-owned Vec; this form also supports independent layout tests.
    */
  def fromShape(
      shape: ParameterizedVecShape,
      baseName: String,
      renderExpr: ElaborationIntegerExpression => String,
      occupiedNames: Set[String] = Set.empty[String]
  ): Layout = {
    if (shape == null || renderExpr == null)
      throw new IllegalArgumentException("field layout requires a retained shape and expression renderer")
    requireIdentifier(baseName, "Vec field base", shape.sourceLocation)
    if (shape.elementFields.isEmpty || shape.elementFields.map(_.path).distinct.size != shape.elementFields.size)
      fail("FIELD-PATH-AMBIGUOUS", s"Vec '$baseName' has an empty or repeated retained scalar field path", shape.sourceLocation)
    val allIndices = shape.elementFields.flatMap(_.carrierLeafIndices)
    if (allIndices.sorted != shape.elementLeaves.indices.toVector)
      fail("FIELD-CARRIER-COVERAGE-MISMATCH", s"Vec '$baseName' recursive fields do not cover its exact native scalar leaves once", shape.sourceLocation)
    shape.elementFields.foreach { field =>
      field.carrierLeafIndices.foreach { ordinal =>
        val leaf = shape.elementLeaves(ordinal)
        if ((leaf.typeObject ne field.typeObject) || !ElabInt.equivalentExpression(leaf.width, field.width))
          fail("FIELD-CARRIER-LEAF-MISMATCH",
            s"Vec '$baseName' field '${field.path.mkString(".")}' changes native scalar leaf $ordinal type or width", shape.sourceLocation)
      }
    }

    val preferred = shape.elementFields.map(field =>
      if (field.path.isEmpty) baseName else baseName + "_" + field.path.map(readableSegment).mkString("_")
    )
    val repeats = preferred.groupBy(identity).collect { case (name, values) if values.size > 1 => name }.toSet
    val used = mutable.HashSet.empty[String] ++ occupiedNames
    val names = shape.elementFields.zip(preferred).map { case (field, natural) =>
      val name = if (repeats(natural) || occupiedNames(natural)) {
        natural + "__p" + encodedPath(field.path)
      } else natural
      // A fully encoded path gives an injective suffix. If a user declaration
      // deliberately occupies it, fail closed rather than varying an ABI with
      // transient declaration discovery order.
      if (used(name) || (name != natural && preferred.contains(name)))
        fail("FIELD-NAME-COLLISION", s"Vec '$baseName' field '${field.path.mkString(".")}' cannot allocate unambiguous identifier '$name'", shape.sourceLocation)
      requireIdentifier(name, "Vec scalar field", shape.sourceLocation)
      used += name
      name
    }

    val fields = shape.elementFields.zip(names).map { case (field, name) =>
      if (field.dimensions.exists(_.carrierCapacity < 1) ||
          field.dimensions.map(axis => BigInt(axis.carrierCapacity)).product != BigInt(field.carrierLeafIndices.size))
        fail("FIELD-CARRIER-DIMENSIONS-MISMATCH", s"Vec '$baseName' field '${field.path.mkString(".")}' has inconsistent native dimensions", shape.sourceLocation)
      val scalarWidth = normalized(field.width, renderExpr)
      val dimensions = normalized(shape.depth, renderExpr) +: field.dimensions.map(axis => normalized(axis.depth, renderExpr))
      val width = product(scalarWidth +: dimensions)
      Field(field, name, scalarWidth, dimensions, width, s"[($width)-1:0]")
    }

    def logicalWidth(node: ParameterizedVecLayoutNode): String = node match {
      case ParameterizedVecLayoutScalar(_, width, _) => normalized(width, renderExpr)
      case ParameterizedVecLayoutRecord(children) => sum(children.map(logicalWidth))
      case ParameterizedVecLayoutArray(axis, element) => product(Vector(normalized(axis.depth, renderExpr), logicalWidth(element)))
    }
    val packing = mutable.LinkedHashMap.empty[Vector[String], Packing]
    def capture(
        node: ParameterizedVecLayoutNode,
        constant: String,
        strides: Vector[String],
        axes: Vector[ParameterizedVecDimensionShape]
    ): Unit = node match {
      case ParameterizedVecLayoutScalar(path, width, kind) =>
        val field = fields.find(_.path == path).getOrElse(fail(
          "FIELD-PACKING-PATH-MISSING", s"Vec '$baseName' packing tree contains an unretained scalar path '${path.mkString(".")}'", shape.sourceLocation))
        if (packing.contains(path) || field.scalarWidth != normalized(width, renderExpr) ||
            (field.typeObject ne kind) || strides.size != field.retained.dimensions.size ||
            field.retained.dimensions.zip(axes).exists { case (expected, actual) =>
              expected.witnessDepth != actual.witnessDepth || expected.carrierCapacity != actual.carrierCapacity ||
                !ElabInt.equivalentExpression(expected.depth, actual.depth)
            })
          fail("FIELD-PACKING-LAYOUT-MISMATCH", s"Vec '$baseName' packing tree disagrees with retained field '${path.mkString(".")}'", shape.sourceLocation)
        packing(path) = Packing(constant, strides)
      case ParameterizedVecLayoutRecord(children) =>
        var offset = constant
        children.foreach { child =>
          capture(child, offset, strides, axes)
          offset = sum(Vector(offset, logicalWidth(child)))
        }
      case ParameterizedVecLayoutArray(axis, element) =>
        capture(element, constant, strides :+ logicalWidth(element), axes :+ axis)
    }
    capture(shape.elementLayout, "0", Vector.empty, Vector.empty)
    if (packing.keySet.toSet != fields.map(_.path).toSet)
      fail("FIELD-PACKING-COVERAGE-MISMATCH", s"Vec '$baseName' packing tree does not cover every retained scalar field", shape.sourceLocation)
    val expectedFieldAtLeaf = fields.flatMap(field => field.leafIndices.map(_ -> field)).toMap
    var nativeOrdinal = 0
    def validateNativeOrder(node: ParameterizedVecLayoutNode): Unit = node match {
      case ParameterizedVecLayoutScalar(path, width, kind) =>
        val expected = expectedFieldAtLeaf.getOrElse(nativeOrdinal, fail(
          "FIELD-PACKING-CARRIER-ORDER-MISMATCH", s"Vec '$baseName' packing tree exceeds its native carrier leaves", shape.sourceLocation))
        if (expected.path != path || (expected.typeObject ne kind) ||
            !ElabInt.equivalentExpression(expected.retained.width, width))
          fail("FIELD-PACKING-CARRIER-ORDER-MISMATCH",
            s"Vec '$baseName' packing tree changes native scalar leaf $nativeOrdinal", shape.sourceLocation)
        nativeOrdinal += 1
      case ParameterizedVecLayoutRecord(children) => children.foreach(validateNativeOrder)
      case ParameterizedVecLayoutArray(axis, element) =>
        (0 until axis.carrierCapacity).foreach(_ => validateNativeOrder(element))
    }
    validateNativeOrder(shape.elementLayout)
    if (nativeOrdinal != shape.elementLeaves.size)
      fail("FIELD-PACKING-CARRIER-ORDER-MISMATCH",
        s"Vec '$baseName' packing tree covers $nativeOrdinal native leaves, expected ${shape.elementLeaves.size}", shape.sourceLocation)
    val elementWidth = logicalWidth(shape.elementLayout)
    val totalWidth = product(Vector(elementWidth, normalized(shape.depth, renderExpr)))
    new Layout(shape, baseName, fields, elementWidth, totalWidth, renderExpr, packing.toMap)
  }

  private def normalized(
      expression: ElaborationIntegerExpression,
      renderExpr: ElaborationIntegerExpression => String
  ): String = {
    val rendered = renderExpr(expression).trim
    if (rendered.isEmpty) fail("FIELD-EXPRESSION-EMPTY", "a retained field geometry expression rendered empty", expression.sourceLocation)
    if (expression.parameters.isEmpty && expression.generateIndex.isEmpty) expression.default.toString
    else rendered
  }

  private def constant(value: String): Option[BigInt] =
    if (value.matches("-?[0-9]+")) Some(BigInt(value)) else None

  private def sum(terms: Vector[String]): String = {
    val meaningful = terms.filterNot(_ == "0")
    if (meaningful.isEmpty) "0"
    else if (meaningful.forall(term => constant(term).nonEmpty)) meaningful.map(BigInt(_)).sum.toString
    else if (meaningful.size == 1) meaningful.head
    else meaningful.map(factor).mkString(" + ")
  }

  private def product(terms: Vector[String]): String = {
    val meaningful = terms.filterNot(_ == "1")
    if (terms.contains("0")) "0"
    else if (meaningful.isEmpty) "1"
    else if (meaningful.forall(term => constant(term).nonEmpty)) meaningful.map(BigInt(_)).product.toString
    else if (meaningful.size == 1) meaningful.head
    else meaningful.map(factor).mkString(" * ")
  }

  private def factor(value: String): String =
    if (PortableIdentifier.pattern.matcher(value).matches() || constant(value).nonEmpty) value else s"($value)"

  private def readableSegment(value: String): String = {
    if (value == null || value.isEmpty) "empty"
    else value.flatMap { character =>
      if ((character >= 'a' && character <= 'z') || (character >= 'A' && character <= 'Z') ||
          (character >= '0' && character <= '9') || character == '_' || character == '$') character.toString
      else "_u" + f"${character.toInt}%04x" + "_"
    }
  }

  private def encodedPath(path: Vector[String]): String =
    if (path.isEmpty) "0" else path.map { segment =>
      segment.length.toString + "_" + segment.map(character => f"${character.toInt}%04x").mkString
    }.mkString("_")

  private def requireIdentifier(name: String, role: String, location: Option[String]): Unit =
    if (name == null || !PortableIdentifier.pattern.matcher(name).matches())
      fail("FIELD-NAME-INVALID", s"$role '$name' is not a portable Verilog identifier", location)

  private def fail(code: String, detail: String, sourceLocation: Option[String]): Nothing =
    ParameterizedVerilogException.fail("SPINAL-PARAMETERIZED-VERILOG-VEC-" + code, detail, sourceLocation)
}
