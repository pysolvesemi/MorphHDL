package spinal.core.internals

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import morphhdl.frontend.HdlInt

class TypedBalancedReductionNestedMechanismTests extends AnyFunSuite {
  for (saturation <- Vector(false, true)) {
    test(s"direct scoped nested native widening and registered captures saturation=$saturation") {
      val root = Files.createTempDirectory("nested-direct-scoped-")
      val file = TypedBalancedReductionNestedMechanismArtifacts.directCandidate(root, saturation)
      val rtl = new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
      for (token <- Vector("INNER", "COUNT", "g_first_capture", "g_second_capture",
        "morphhdl_balanced_", "resultUS", "resultSP", "resultSamples"))
        assert(rtl.contains(token), token + "\n" + rtl)
    }
    test(s"nested widening/capture/native-register child emits every layout and signed mode saturation=$saturation") {
      val root = Files.createTempDirectory("nested-six-mechanisms-")
      for ((layout, signed) <- TypedBalancedReductionNestedMechanismArtifacts.profiles) {
        val file = TypedBalancedReductionNestedMechanismArtifacts.candidate(root, layout, signed, saturation)
        val rtl = new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
        val name = file.getFileName.toString.stripSuffix(".v")
        for (module <- Vector(name, name + "_Child"))
          assert(("(?m)^module\\s+" + module + "\\b").r.findAllIn(rtl).size == 1, rtl)
        for (token <- TypedBalancedReductionNestedMechanismArtifacts.parameterNames ++
          Vector("morphhdl_balanced_", "g_first_capture", "g_second_capture", "resultSamples"))
          assert(rtl.contains(token), token + "\n" + rtl)
        assert(rtl.replaceAll("\\s+", "").contains(".MODE((MODE+1))"), rtl)
        if (layout == "fields") assert(rtl.contains("values_samples"), rtl)
      }
    }
    test(s"ordinary concrete nested hierarchy retains singleton and odd native trees saturation=$saturation") {
      for (n <- Vector(1, 5)) {
        val root = Files.createTempDirectory("nested-six-concrete-")
        val name = "NestedConcrete_" + n
        SpinalVerilog(TypedBalancedReductionNestedMechanismArtifacts.config(root, name)) {
          new BalancedNestedMechanismTop(HdlInt.literal(3), HdlInt.literal(2),
            HdlInt.literal(4), HdlInt.literal(3), HdlInt.literal(n), HdlInt.literal(1),
            saturation, name, name + "_Child")
        }
        val rtl = new String(Files.readAllBytes(root.resolve(name + ".v")), StandardCharsets.UTF_8)
        assert(!rtl.contains("parameter"), rtl)
        assert(rtl.contains("resultSP") && rtl.contains("resultSamples"), rtl)
      }
    }
  }
}
