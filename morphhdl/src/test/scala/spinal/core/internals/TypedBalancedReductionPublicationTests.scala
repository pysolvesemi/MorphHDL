package spinal.core.internals

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite

class TypedBalancedReductionPublicationTests extends AnyFunSuite {
  private def generate(): String = {
    val path = TypedBalancedReductionPublicationArtifactWriter.candidate(
      Files.createTempDirectory("balanced-publication-"))
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
  }

  test("the public native helper emits independent WIDTH and COUNT parameters") {
    val text = generate()
    assert("(?s).*parameter[^;]*WIDTH[^;]*=.*".r.pattern.matcher(text).matches(), text)
    assert("(?s).*parameter[^;]*COUNT[^;]*=.*".r.pattern.matcher(text).matches(), text)
    assert(text.linesIterator.count(_.trim.startsWith("module BalancedPublication")) == 1, text)
    assert(text.contains("unsignedIn") && text.contains("signedIn") &&
      text.contains("bitsIn") && text.contains("boolIn"), text)
    assert(text.contains("WIDTH") && text.contains("COUNT"), text)
  }

  test("singleton-default publication retains all balanced stages for larger overrides") {
    val text = generate()
    for (tree <- 1 to 5; level <- 0 until 5) {
      assert(text.contains(s"morphhdl_balanced_${tree}_active_$level"), text)
    }
    assert(text.contains("generate") && text.contains("genvar") && text.contains("begin : tail"), text)
    assert(text.contains("+:"), text)
  }

  test("repeated independent publication is byte deterministic") {
    assert(generate() == generate())
  }
}
