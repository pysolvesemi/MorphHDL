#!/usr/bin/env python3
from pathlib import Path

path = Path(
    "morphhdl/src/main/scala/spinal/core/internals/"
    "ExternalParameterizedVerilogHierarchy.scala"
)
value = path.read_text()

import_marker = "import java.util.regex.{Matcher, Pattern}\n"
if value.count(import_marker) != 1:
    raise SystemExit(
        f"generic packed lineage import marker count={value.count(import_marker)}"
    )
value = value.replace(
    import_marker,
    "import java.util.IdentityHashMap\n" + import_marker,
    1,
)

candidate_old = '''            case (name, port: BitVector)
                if ParameterizedWidth.expressionOf(port).isEmpty =>
'''
candidate_new = '''            case (name, port: BitVector)
                if ExternalFormalParameterRegistry.bindingOf(port).isEmpty =>
'''
if value.count(candidate_old) != 1:
    raise SystemExit(
        f"generic packed lineage candidate marker count={value.count(candidate_old)}"
    )
value = value.replace(candidate_old, candidate_new, 1)

actual_old = '''            .foreach { case (_, grouped) =>
              val actuals = grouped.map(_.actual).distinct
              if (actuals.size != 1) {
                fail(
                  "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-IMPLICIT-FORMAL-AMBIGUOUS",
                  s"native child '$definitionName' instance '$instanceName' maps one packed slot to multiple actual width expressions"
                )
              }
              slots += ImplicitPackedSlot(
                parent = parent,
                child = child,
                definitionName = definitionName,
                ports = grouped.sortBy(_.name),
                actual = actuals.head
              )
            }
'''
actual_new = '''            .foreach { case (signature, grouped) =>
              // Source locations are diagnostics, not expression identity. The
              // group key already proves identical render/default/domain/formal
              // schemas, so select one deterministic source while preserving
              // one canonical semantic expression.
              val source = grouped.flatMap(_.actual.sourceLocation).distinct.sorted.headOption
              val actual = ElaborationIntegerExpression(
                verilog = signature.render,
                default = signature.default,
                minimum = signature.minimum,
                maximum = signature.maximum,
                parameters = signature.parameters,
                sourceLocation = source
              )
              slots += ImplicitPackedSlot(
                parent = parent,
                child = child,
                definitionName = definitionName,
                ports = grouped.sortBy(_.name),
                actual = actual
              )
            }
'''
if value.count(actual_old) != 1:
    raise SystemExit(
        f"generic packed lineage actual normalization marker count={value.count(actual_old)}"
    )
value = value.replace(actual_old, actual_new, 1)

binding_marker = '''  private def bindingExpression(
      value: BindingExpr
  ): ElaborationIntegerExpression = value match {
'''
if value.count(binding_marker) != 1:
    raise SystemExit(
        f"generic packed lineage helper marker count={value.count(binding_marker)}"
    )

helpers = '''  /**
    * Return one exact packed source only through wrappers which preserve both
    * the concrete type width and the complete packed value. This deliberately
    * excludes arithmetic, indexing, slicing, concatenation and any real
    * widening or narrowing conversion: those require their own reviewed
    * symbolic result-width rules.
    */
  private def transparentPackedSource(
      expression: Expression,
      width: Int
  ): Option[BitVector] = expression match {
    case value: BitVector if value.getBitsWidth == width => Some(value)
    case resize: Resize
        if resize.getWidth == width && resize.input != null &&
          resize.input.getWidth == width =>
      transparentPackedSource(resize.input, width)
    case cast: CastBitVectorToBitVector
        if cast.getWidth == width && cast.input != null &&
          cast.input.getWidth == width =>
      transparentPackedSource(cast.input, width)
    case _ => None
  }

  private def distinctPackedIdentities(
      values: Vector[BitVector]
  ): Vector[BitVector] = {
    val seen = new IdentityHashMap[BitVector, java.lang.Boolean]()
    values.filter(value => seen.put(value, java.lang.Boolean.TRUE) == null)
  }

  /**
    * Resolve every immediate parent-side peer of one exact child port through a
    * direct full-packed boundary assignment. Output fanout is supported; every
    * peer must still be a native BitVector of the same concrete witness width.
    */
  private def boundaryPackedPeers(
      parent: Component,
      childPort: BitVector,
      slotIdentity: String
  ): Vector[BitVector] = {
    val peers = ArrayBuffer.empty[BitVector]
    parent.dslBody.walkLeafStatements {
      case assignment: DataAssignmentStatement if childPort.isInput =>
        if ((assignment.target eq childPort) &&
            (assignment.finalTarget eq childPort)) {
          transparentPackedSource(
            assignment.source,
            childPort.getBitsWidth
          ).foreach { peer =>
            if (peer.component == parent) peers += peer
          }
        }
      case assignment: DataAssignmentStatement if childPort.isOutput =>
        assignment.target match {
          case peer: BitVector
              if (assignment.finalTarget eq peer) &&
                peer.component == parent &&
                peer.getBitsWidth == childPort.getBitsWidth =>
            transparentPackedSource(
              assignment.source,
              childPort.getBitsWidth
            ).foreach { source =>
              if (source eq childPort) peers += peer
            }
          case _ =>
        }
      case _ =>
    }

    val result = distinctPackedIdentities(peers.toVector)
    if (result.isEmpty) {
      val name = Option(childPort.getName()).filter(_.nonEmpty).getOrElse("<unnamed>")
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-IMPLICIT-LINEAGE-PORT-EVIDENCE-MISSING",
        s"implicit packed slot '$slotIdentity' reaches child port '$name' without one exact full-packed parent boundary peer"
      )
    }
    result
  }

  private def attachParentActual(
      parentPeer: BitVector,
      actual: ElaborationIntegerExpression,
      slotIdentity: String
  ): Unit = {
    if (!actual.default.isValidInt ||
        parentPeer.getBitsWidth != actual.default.toInt) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-IMPLICIT-LINEAGE-WITNESS-MISMATCH",
        s"parent peer of implicit packed slot '$slotIdentity' has concrete width ${parentPeer.getBitsWidth}, expected ${actual.default}"
      )
    }

    ParameterizedWidth.expressionOf(parentPeer) match {
      case Some(existing)
          if expressionSignature(existing) != expressionSignature(actual) =>
        val name = Option(parentPeer.getName()).filter(_.nonEmpty).getOrElse("<unnamed>")
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-IMPLICIT-LINEAGE-ACTUAL-CONFLICT",
          s"parent peer '$name' of implicit packed slot '$slotIdentity' retains '${existing.verilog}', expected '${actual.verilog}'"
        )
      case Some(_) =>
      case None =>
        ParameterizedWidth.attach(
          parentPeer,
          ParameterizedBitCount(
            value = actual.default.toInt,
            parameter = None,
            sourceLocation = actual.sourceLocation,
            expression = Some(actual)
          )
        )
    }
  }

  /**
    * Discover the exact width-preserving assignment component rooted at the
    * selected child ports. Graph identity, not signal names or equal numeric
    * widths, is the discovery key. The graph is intentionally undirected:
    * width shape is invariant across a reviewed full-packed assignment even
    * though value flow itself remains directed.
    */
  private def implicitPackedLineage(
      component: Component,
      seeds: Vector[BitVector],
      slotIdentity: String,
      promoted: IdentityHashMap[BitVector, String]
  ): Vector[BitVector] = {
    val adjacency =
      new IdentityHashMap[BitVector, ArrayBuffer[BitVector]]()

    def neighbors(value: BitVector): ArrayBuffer[BitVector] = {
      var result = adjacency.get(value)
      if (result == null) {
        result = ArrayBuffer.empty[BitVector]
        adjacency.put(value, result)
      }
      result
    }

    def connect(left: BitVector, right: BitVector): Unit = {
      if ((left ne right) && left.component == component &&
          right.component == component &&
          left.getBitsWidth == right.getBitsWidth) {
        neighbors(left) += right
        neighbors(right) += left
      }
    }

    component.dslBody.walkLeafStatements {
      case assignment: DataAssignmentStatement =>
        assignment.target match {
          case target: BitVector
              if (assignment.finalTarget eq target) &&
                target.component == component =>
            transparentPackedSource(
              assignment.source,
              target.getBitsWidth
            ).foreach(source => connect(target, source))
          case _ =>
        }
      case _ =>
    }

    val queue = scala.collection.mutable.Queue.empty[BitVector]
    val seen = new IdentityHashMap[BitVector, java.lang.Boolean]()
    seeds.foreach { seed =>
      if (seed.component != component) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-IMPLICIT-LINEAGE-OWNER-MISMATCH",
          s"implicit packed slot '$slotIdentity' contains a seed owned by another component"
        )
      }
      if (seen.put(seed, java.lang.Boolean.TRUE) == null) queue.enqueue(seed)
    }

    val result = ArrayBuffer.empty[BitVector]
    while (queue.nonEmpty) {
      val current = queue.dequeue()
      result += current
      val adjacent = adjacency.get(current)
      if (adjacent != null) {
        adjacent.foreach { next =>
          if (seen.put(next, java.lang.Boolean.TRUE) == null) queue.enqueue(next)
        }
      }
    }

    result.foreach { leaf =>
      val existing = promoted.get(leaf)
      if (existing != null && existing != slotIdentity) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-IMPLICIT-LINEAGE-OVERLAP",
          s"one exact packed leaf belongs to both implicit slots '$existing' and '$slotIdentity'"
        )
      }
      promoted.put(leaf, slotIdentity)
    }
    result.toVector
  }

  /**
    * Rebase a concrete native child data path onto one child-local formal. The
    * parent expression remains the instance actual; the child definition sees
    * only the newly allocated formal. Every traversed internal leaf is proven
    * by an exact full-packed assignment path from a selected boundary port.
    */
  private def attachImplicitPackedLineage(
      occurrence: ImplicitPackedSlot,
      binding: ExternalFormalParameterBinding,
      token: ExternalNativeIntFormalizationToken,
      slotIdentity: String,
      promoted: IdentityHashMap[BitVector, String]
  ): Unit = {
    val seedPorts = occurrence.ports.map(_.port)
    val lineage = implicitPackedLineage(
      occurrence.child,
      seedPorts,
      slotIdentity,
      promoted
    )
    val ports = distinctPackedIdentities(
      lineage.filter(_.isIo).sortBy(port =>
        Option(port.getName()).getOrElse("")
      )
    )
    val portSet = new IdentityHashMap[BitVector, java.lang.Boolean]()
    ports.foreach { port =>
      portSet.put(port, java.lang.Boolean.TRUE)
      boundaryPackedPeers(
        occurrence.parent,
        port,
        slotIdentity
      ).foreach(peer =>
        attachParentActual(peer, occurrence.actual, slotIdentity)
      )
    }

    ExternalNativeIntFormalizationRegistry.attachComponent(
      parent = occurrence.parent,
      component = occurrence.child,
      geometry = ports,
      binding = binding,
      token = token
    )

    val formalExpression = ElaborationIntegerExpression(
      verilog = binding.formal.name,
      default = binding.formal.default,
      minimum = binding.formal.minimum,
      maximum = binding.formal.maximum,
      parameters = Vector(binding.formal),
      sourceLocation = binding.sourceLocation
    )
    val formalWidth = ParameterizedBitCount(
      value = binding.formal.default.toInt,
      parameter = Some(binding.formal),
      sourceLocation = binding.sourceLocation,
      expression = Some(formalExpression)
    )

    lineage.foreach { leaf =>
      if (!portSet.containsKey(leaf)) {
        ParameterizedWidth.attach(leaf, formalWidth)
      }
    }
  }

'''
value = value.replace(binding_marker, helpers + binding_marker, 1)

slots_marker = '''  ): Unit = {
    val slots = ArrayBuffer.empty[ImplicitPackedSlot]

'''
slots_replacement = '''  ): Unit = {
    val slots = ArrayBuffer.empty[ImplicitPackedSlot]
    val promotedLineages = new IdentityHashMap[BitVector, String]()

'''
if value.count(slots_marker) != 1:
    raise SystemExit(
        f"generic packed lineage ownership marker count={value.count(slots_marker)}"
    )
value = value.replace(slots_marker, slots_replacement, 1)

attach_old = '''          ExternalNativeIntFormalizationRegistry.attachComponent(
            parent = occurrence.parent,
            component = occurrence.child,
            geometry = occurrence.ports.map(_.port),
            binding = binding,
            token = token
          )
'''
attach_new = '''          attachImplicitPackedLineage(
            occurrence = occurrence,
            binding = binding,
            token = token,
            slotIdentity = s"$definitionName::$key",
            promoted = promotedLineages
          )
'''
if value.count(attach_old) != 1:
    raise SystemExit(
        f"generic packed lineage attachment marker count={value.count(attach_old)}"
    )
value = value.replace(attach_old, attach_new, 1)

path.write_text(value)
