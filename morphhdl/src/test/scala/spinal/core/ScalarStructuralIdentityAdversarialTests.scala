package spinal.core

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.collection.JavaConverters._

import org.scalatest.funsuite.AnyFunSuite

import morphhdl.MorphVerilog
import morphhdl.frontend._
import spinal.core.internals.Operator

private object ScalarStructuralIdentityAdversarialFixture {
  final class ScalarSink extends Component {
    setDefinitionName("ScalarStructuralIdentitySink")

    val din = in(Bool()).setName("din")
    val observed = out(Bool()).setName("observed")
    observed := din
  }

  final class EscapingCapturedScalarOperator(mode: HdlInt) extends Component {
    setDefinitionName("EscapingCapturedScalarOperator")

    val left = in(Bool()).setName("left")
    val right = in(Bool()).setName("right")
    val observed = out(Bool()).setName("observed")
    var escaped: Bool = null

    if ((mode > 0).named("g_scalar_nested", "g_scalar_bypass")) {
      escaped = (left && right)
        .setName("escaped_scalar_and")
        .dontSimplifyIt()
      val unrelated = (left ^ right)
        .setName("branch_only_scalar_xor")
        .dontSimplifyIt()

      val escapingSink = new ScalarSink
      escapingSink.setName("escaping_sink")
      escapingSink.din := escaped

      val unrelatedSink = new ScalarSink
      unrelatedSink.setName("unrelated_sink")
      unrelatedSink.din := unrelated
    } else {
      val bypass = new ScalarSink
      bypass.setName("bypass_sink")
      bypass.din := left
    }

    require(escaped != null, "scalar fixture retained no escaping operator")
    observed := escaped
  }

  final class RemovedCapturedScalarOperatorIdentity(mode: HdlInt) extends Component {
    setDefinitionName("RemovedCapturedScalarOperatorIdentityMustFailClosed")

    val left = in(Bool()).setName("left")
    val right = in(Bool()).setName("right")
    val observed = out(Bool()).setName("observed")
    var retained: Bool = null

    if ((mode > 0).named("g_stale_scalar", "g_fresh_scalar")) {
      retained = (left && right)
        .setName("stale_scalar_operator")
        .dontSimplifyIt()

      val sink = new ScalarSink
      sink.setName("stale_sink")
      sink.din := retained
    } else {
      val sink = new ScalarSink
      sink.setName("fresh_sink")
      sink.din := left
    }

    require(retained != null, "stale scalar fixture retained no operator")
    val record = ParameterizedStructure
      .regionsOf(this)
      .flatMap(ParameterizedStructure.allBlocks)
      .flatMap(_.scalarOperators)
      .find(_.result eq retained)
      .getOrElse {
        throw new IllegalStateException(
          "stale scalar fixture retained no exact operator record"
        )
      }

    record.assignment.removeStatement()
    retained.compositeAssign = null
    retained.allowOverride()

    // Restore the exact result, operands and native spelling with unrelated
    // assignment/operator identities. Identity replacement must not authorize
    // the stale captured record.
    val replacement = new Operator.Bool.And
    replacement.left = left.asInstanceOf[replacement.T]
    replacement.right = right.asInstanceOf[replacement.T]
    retained.assignFrom(replacement)
    observed := retained
  }

  /** A child is closed before its parent asks it to extend two exact branch
    * owners. This matches native helpers such as StreamFifo formal inspection:
    * both late assignments target one module-scope result, but their owner
    * domains are disjoint and complete.
    */
  final class LateOwnerExtensionChild(control: ElabInt) extends Component {
    setDefinitionName("LateScalarOwnerExtensionChild")

    val left = in(Bool()).setName("left")
    val right = in(Bool()).setName("right")
    val selected = out(Bool()).setName("selected")
    private var oneOwner: ParameterizedStructuralOwner = null
    private var otherOwner: ParameterizedStructuralOwner = null

    ElabControl.selectSymbolic(
      control > 1,
      "late-scalar-owner-extension",
      1
    )({
      oneOwner = ParameterizedStructure.currentOwner(
        control,
        "late scalar one owner"
      )
      val keep = Bool().setName("late_one_keep")
      keep := left
      keep.dontSimplifyIt()
    })({
      otherOwner = ParameterizedStructure.currentOwner(
        control,
        "late scalar other owner"
      )
      val keep = Bool().setName("late_other_keep")
      keep := right
      keep.dontSimplifyIt()
    })

    def attachLateOwners(): Unit = {
      ParameterizedStructure.requireOwnerCoverage(
        this,
        control,
        Seq(oneOwner, otherOwner),
        "late scalar owner coverage"
      )
      ParameterizedStructure.captureInto(
        oneOwner,
        control,
        "late scalar one extension"
      ) {
        selected := left && right
      }
      ParameterizedStructure.captureInto(
        otherOwner,
        control,
        "late scalar other extension"
      ) {
        selected := left ^ right
      }
    }
  }

  final class LateOwnerExtensionTop(mode: HdlInt) extends Component {
    setDefinitionName("LateScalarOwnerExtensionTop")

    val left = in(Bool()).setName("left")
    val right = in(Bool()).setName("right")
    val observed = out(Bool()).setName("observed")
    val child = ElabFormalComponent.parameter(
      actual = mode.asElabInt,
      name = "MODE",
      minimum = BigInt(1),
      maximum = BigInt(2)
    )(formal => new LateOwnerExtensionChild(formal))
    child.setName("child")
    child.left := left
    child.right := right
    child.attachLateOwners()
    observed := child.selected
  }
}

final class ScalarStructuralIdentityAdversarialTests extends AnyFunSuite {
  import ScalarStructuralIdentityAdversarialFixture._

  test("an exact escaping scalar operator is promoted without overclaiming a same-operands peer") {
    withTemporaryDirectory { directory =>
      val config = morphConfig(directory, "escaping_captured_scalar_operator.v")
      MorphVerilog(config) {
        new EscapingCapturedScalarOperator(mode())
      }

      val verilog = read(directory.resolve(config.netlistFileName))
      val module = moduleBody(verilog, "EscapingCapturedScalarOperator")
      val generate = module.indexOf("generate")
      assert(generate >= 0, module)
      val prefix = module.substring(0, generate).replaceAll("\\s+", "")
      val generated = module.substring(generate).replaceAll("\\s+", "")

      assert(prefix.contains("wireescaped_scalar_and;"), module)
      assert(prefix.contains("assignescaped_scalar_and=(left&&right);"), module)
      assert(prefix.contains("assignobserved=escaped_scalar_and;"), module)
      assert(!generated.contains("wireescaped_scalar_and;"), module)
      assert(!generated.contains("assignescaped_scalar_and=(left&&right);"), module)

      assert(!prefix.contains("wirebranch_only_scalar_xor;"), module)
      assert(!prefix.contains("assignbranch_only_scalar_xor=(left^right);"), module)
      assert(generated.contains("wirebranch_only_scalar_xor;"), module)
      assert(
        generated.contains("assignbranch_only_scalar_xor=(left^right);"),
        module
      )
    }
  }

  test("a removed scalar-operator driver cannot be replaced by the same result operands and text") {
    withTemporaryDirectory { directory =>
      val config = morphConfig(directory, "removed_scalar_operator_driver.v")
      MorphVerilog.tryGenerate(config) {
        new RemovedCapturedScalarOperatorIdentity(mode())
      } match {
        case Left(failure) =>
          assert(
            failure.detail.contains(
              "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SCALAR-OPERATOR-IDENTITY-STALE"
            ),
            failure.detail
          )
        case Right(report) =>
          fail(s"Expected stale scalar identity failure, received $report")
      }
      assert(!Files.exists(directory.resolve(config.netlistFileName)))
    }
  }

  test("late exact owner extensions preserve scalar operands and authorize only disjoint assignments") {
    withTemporaryDirectory { directory =>
      val config = morphConfig(directory, "late_scalar_owner_extension.v")
      MorphVerilog(config) {
        new LateOwnerExtensionTop(lateMode())
      }

      val verilog = read(directory.resolve(config.netlistFileName))
      val child = moduleBody(verilog, "LateScalarOwnerExtensionChild")
        .replaceAll("\\s+", "")
      assert(child.contains("if(((MODE)>(1)))begin:"), child)
      assert(child.contains("assign_zz_selected=(left&&right);"), child)
      assert(child.contains("elsebegin:"), child)
      assert(child.contains("assign_zz_selected_1=(left^right);"), child)
      assert(
        child
          .sliding("always@(*)beginselected=".length)
          .count(
            _ == "always@(*)beginselected="
          ) == 2,
        child
      )
    }
  }

  private def mode(): HdlInt =
    HdlInt.param("MODE", default = 1, min = 0, max = 1)

  private def lateMode(): HdlInt =
    HdlInt.param("MODE", default = 1, min = 1, max = 2)

  private def morphConfig(directory: Path, fileName: String): SpinalConfig = {
    val config = SpinalConfig(targetDirectory = directory.toString)
    config.netlistFileName = fileName
    config
  }

  private def moduleBody(verilog: String, definitionName: String): String = {
    val start = verilog.indexOf(s"module $definitionName")
    val end = verilog.indexOf("endmodule", start)
    assert(start >= 0 && end > start, verilog)
    verilog.substring(start, end)
  }

  private def read(path: Path): String =
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

  private def withTemporaryDirectory[A](body: Path => A): A = {
    val directory = Files.createTempDirectory("morphhdl-scalar-identity-test-")
    try body(directory)
    finally deleteTree(directory)
  }

  private def deleteTree(root: Path): Unit =
    if (Files.exists(root)) {
      val stream = Files.walk(root)
      try {
        stream.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach { path =>
          Files.deleteIfExists(path)
        }
      } finally stream.close()
    }
}
