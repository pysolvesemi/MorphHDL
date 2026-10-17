package spinal.core.internals

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

class CanonicalSchemaDiagnosticTests extends AnyFunSuite {
  import MorphHdlExternalParameterizedVerilog._
  private val width = ElaborationIntegerParameter("WIDTH", 8, 1, 64)
  private val port = PortSchema("data", "input", "Bits", 8, None)
  private val baseline = ComponentSchema(Vector(port), Vector(width), Vector("stages: DEPTH x WIDTH"))

  test("schema diagnostics identify default domain and missing parameter boundaries") {
    for (other <- Vector(width.copy(default = 9), width.copy(minimum = 2), width.copy(maximum = 63))) {
      val message = schemaDifference(baseline, baseline.copy(parameters = Vector(other)))
      assert(message.startsWith("parameter 'WIDTH':"), message)
      assert(message.contains("left=WIDTH(default=8, domain=[1, 64])"), message)
      assert(message.contains(s"right=WIDTH(default=${other.default}, domain=[${other.minimum}, ${other.maximum}])"), message)
    }
    assert(schemaDifference(baseline, baseline.copy(parameters = Vector.empty)).contains("right=<missing>"))
  }

  test("schema diagnostics identify port direction type width presence and retained geometry") {
    for ((other, field) <- Vector(
      port.copy(direction = "output") -> "direction",
      port.copy(dataClass = "UInt") -> "type",
      port.copy(concreteWidth = 9) -> "concrete width",
      port.copy(retained = Some(ElaborationIntegerExpression("WIDTH", 8, 1, 64, Vector(width)))) -> "retained expression"
    )) {
      val message = schemaDifference(baseline, baseline.copy(ports = Vector(other)))
      assert(message.startsWith(s"port 'data' $field:"), message)
      assert(!message.contains("@"), message)
    }
    assert(schemaDifference(baseline, baseline.copy(ports = Vector.empty)) ==
      "port 'data' presence: left=true; right=false")
  }

  test("schema diagnostics bound multiline Vec evidence and identify missing entries") {
    val changed = baseline.copy(vecs = Vector("different\n" + "x" * 1000))
    val message = schemaDifference(baseline, changed)
    assert(message.startsWith("Vec schema entry 0:"), message)
    assert(message.length < 600 && !message.contains("\n") && message.endsWith("..."), message)
    assert(schemaDifference(baseline, baseline.copy(vecs = Vector.empty)).endsWith("right=None"))
  }

  test("schema diagnostic selection is deterministic without changing schema equality") {
    val a = width.copy(name = "A")
    val z = width.copy(name = "Z")
    val left = baseline.copy(parameters = Vector(z, a))
    val right = baseline.copy(parameters = Vector(z.copy(maximum = 32), a.copy(maximum = 16)))
    val expected = schemaDifference(left, right)
    assert(expected.startsWith("parameter 'A':"), expected)
    assert(schemaDifference(left.copy(parameters = left.parameters.reverse),
      right.copy(parameters = right.parameters.reverse)) == expected)
    // Equal presentation cannot become evidence for merging unequal metadata.
    val e = ElaborationIntegerExpression("WIDTH", 8, 1, 64, Vector(width))
    val first = baseline.copy(ports = Vector(port.copy(retained = Some(e))))
    val second = baseline.copy(ports = Vector(port.copy(retained = Some(e.copy(sourceLocation = Some("other.scala:1"))))))
    assert(first != second)
    assert(schemaDifference(first, second).contains("authenticated expression metadata differs"))
    assert(schemaDifference(left, left.copy(parameters = left.parameters.reverse)) == "ordered schema entries differ")
  }
}
