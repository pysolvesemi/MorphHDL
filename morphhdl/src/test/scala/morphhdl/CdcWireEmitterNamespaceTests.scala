package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.collection.JavaConverters._
import scala.sys.process.{Process, ProcessLogger}
import morphhdl.frontend.HdlInt
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

class CdcWireEmitterNamespaceTests extends AnyFunSuite {
  test("slice helpers and arguments avoid every retained module parameter name") {
    val directory = Files.createTempDirectory("cdc-wire-emitter-namespace-")
    try {
      MorphVerilog(MorphWireAssignmentPasses(SpinalConfig(targetDirectory = directory.toString,
        oneFilePerComponent = true, headerWithDate = false))) {
        new Component {
          setDefinitionName("CdcWireEmitterNamespace")
          val width: ElabInt = HdlInt.param("value", 48, 40, 80).asElabInt
          // These unrelated live parameters are not part of the sliced source
          // width, so reserving only that width's parameters is insufficient.
          val otherWidth: ElabInt = HdlInt.param("_morphhdl_slice", 3, 2, 4).asElabInt
          val thirdWidth: ElabInt = HdlInt.param("_morphhdl_slice_1", 3, 2, 4).asElabInt
          val payload = in UInt(width bits)
          val offset = in UInt(4 bits)
          val expected = in UInt(23 bits)
          val other = in Bits(otherWidth bits)
          val third = in Bits(thirdWidth bits)
          val echoed = out Bits(otherWidth bits)
          val thirdEchoed = out Bits(thirdWidth bits)
          val mismatch = out Bool()
          echoed := other
          thirdEchoed := third
          mismatch := (payload >> offset).resize(23) =/= expected
        }
      }
      val rtl = directory.resolve("CdcWireEmitterNamespace.v")
      val source = new String(Files.readAllBytes(rtl), StandardCharsets.UTF_8)
      val parameters = Set("value", "_morphhdl_slice", "_morphhdl_slice_1")
      val functions = "(?m)^\\s*function\\s+\\[[^\\]]+\\]\\s+(\\w+);".r
        .findAllMatchIn(source).map(_.group(1)).toVector
      assert(functions.nonEmpty, source)
      assert(functions.forall(name => !parameters(name)), source)
      assert(!source.contains("] value;"), source)
      // The focused HDL workflow installs and verifies Icarus explicitly;
      // generic Scala-only test environments still exercise namespace safety.
      val hasIcarus = scala.util.Try(Process(Seq("iverilog", "-V")).!(
        ProcessLogger(_ => ())) == 0).getOrElse(false)
      if (hasIcarus) {
        val diagnostic = new StringBuilder
        val exit = Process(Seq("iverilog", "-g2001", "-s", "CdcWireEmitterNamespace",
          "-o", directory.resolve("compiled.vvp").toString, rtl.toString)).!(
          ProcessLogger(line => diagnostic.append(line).append('\n')))
        assert(exit == 0, diagnostic.toString + "\n" + source)
      } else info("Icarus unavailable: namespace assertions passed; Verilog-2001 parsing was not checked locally")
    } finally {
      val paths = Files.walk(directory)
      try paths.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists(_))
      finally paths.close()
    }
  }
}
