package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.collection.JavaConverters._

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.internals.{DataAssignmentStatement, WhenStatement}

import morphhdl.frontend._

object NativeProcessTreeCaptureSmoke {
  private def retainWidthMetadata(width: HdlInt): Unit = {
    val parameterInput = in(morphhdl.frontend.Bits(width bits))
    parameterInput.setName("parameterInput")
    val parameterOutput = out(morphhdl.frontend.Bits(width bits))
    parameterOutput.setName("parameterOutput")
    parameterOutput := parameterInput
  }

  private def expectCaptureFailure(code: String)(body: => Unit): Unit = {
    var observed = false
    try body
    catch {
      case error: ParameterizedVerilogException if error.code == code =>
        observed = true
    }
    require(observed, s"expected structural capture failure $code")
  }

  private def expectFailureText(text: String)(body: => Unit): Unit = {
    var observed = false
    try body
    catch {
      case error: Throwable
          if Option(error.getMessage).exists(_.contains(text)) =>
        observed = true
    }
    require(observed, s"expected failure containing $text")
  }

  final class ProcessTreeTop(enabled: HdlBool) extends Component {
    setDefinitionName("NativeProcessTreeTop")

    val condition = in(Bool())
    val selector = in(spinal.core.UInt(2 bits))
    val payload = in(morphhdl.frontend.Bits(8 bits))
    val observed = out(morphhdl.frontend.Bits(8 bits))
    observed := payload

    enabled.generateIf("g_process_enabled", "g_process_disabled") {
      val whenValue = morphhdl.frontend.Bits(8 bits)
      whenValue.setName("enabledWhenValue")
      whenValue := spinal.core.B(0, 8 bits)
      when(condition) {
        whenValue := payload
      }

      val switchValue = morphhdl.frontend.Bits(8 bits)
      switchValue.setName("enabledSwitchValue")
      switchValue := spinal.core.B(0, 8 bits)
      switch(selector) {
        is(spinal.core.U(0, 2 bits)) {
          switchValue := payload
        }
      }

      val initializedValue =
        spinal.core.Reg(morphhdl.frontend.Bits(8 bits)) init (
          spinal.core.B(0, 8 bits)
        )
      initializedValue.setName("enabledInitializedValue")
      when(condition) {
        initializedValue := payload
      }
    }.otherwise {
      val whenValue = morphhdl.frontend.Bits(8 bits)
      whenValue.setName("disabledWhenValue")
      whenValue := spinal.core.B(0, 8 bits)
      when(condition) {
        whenValue := ~payload
      }

      val switchValue = morphhdl.frontend.Bits(8 bits)
      switchValue.setName("disabledSwitchValue")
      switchValue := spinal.core.B(0, 8 bits)
      switch(selector) {
        is(spinal.core.U(1, 2 bits)) {
          switchValue := ~payload
        }
      }
    }
  }

  final class NestedUnsupportedStatementTop(width: HdlInt) extends Component {
    setDefinitionName("NestedUnsupportedStatementTop")
    retainWidthMetadata(width)

    val condition = in(Bool())
    val payload = in(morphhdl.frontend.Bits(8 bits))
    expectCaptureFailure(
      "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SCALA-SIDE-EFFECT-UNSUPPORTED"
    ) {
      ParameterizedStructure.captureBlock(this, Some("nested-unsupported")) {
        val leaked = morphhdl.frontend.Bits(8 bits)
        leaked.setName("leakedUnsupportedValue")
        when(condition) {
          leaked := payload
          assumeInitial(condition)
        }
      }
    }

    val recovered = out(morphhdl.frontend.Bits(8 bits))
    recovered.setName("unsupportedRecovered")
    recovered := payload
  }

  final class NestedScopeThrowRollbackTop(width: HdlInt) extends Component {
    setDefinitionName("NestedScopeThrowRollbackTop")
    retainWidthMetadata(width)

    val condition = in(Bool())
    val payload = in(morphhdl.frontend.Bits(8 bits))
    expectFailureText("intentional-nested-scope-throw") {
      ParameterizedStructure.captureBlock(this, Some("nested-scope-throw")) {
        when(condition) {
          val leaked = morphhdl.frontend.Bits(8 bits)
          leaked.setName("leakedNestedScopeValue")
          leaked := payload
          throw new IllegalStateException("intentional-nested-scope-throw")
        }
      }
    }

    val recovered = out(morphhdl.frontend.Bits(8 bits))
    recovered.setName("nestedScopeRecovered")
    recovered := payload
  }

  final class ForeignStatementOwnershipTop(width: HdlInt) extends Component {
    setDefinitionName("ForeignStatementOwnershipTop")
    retainWidthMetadata(width)

    val condition = in(Bool())
    val payload = in(morphhdl.frontend.Bits(8 bits))
    val foreignTarget = morphhdl.frontend.Bits(8 bits)
    foreignTarget := payload

    val foreignAssignment = foreignTarget.head match {
      case value: DataAssignmentStatement => value
      case value =>
        throw new IllegalStateException(
          s"expected a data assignment, received ${value.getClass.getSimpleName}"
        )
    }

    expectCaptureFailure(
      "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-PROCESS-FOREIGN-OWNERSHIP"
    ) {
      ParameterizedStructure.captureBlock(this, Some("foreign-process-owner")) {
        val capturedTree = new WhenStatement(condition)
        dslBody.append(capturedTree)
        foreignAssignment.removeStatementFromScope()
        capturedTree.whenTrue.append(foreignAssignment)
      }
    }

    foreignTarget.setAsVital()
    foreignTarget.dontSimplifyIt()
    val recovered = out(morphhdl.frontend.Bits(8 bits))
    recovered := foreignTarget
  }

  final class ForeignInitializerTargetTop(width: HdlInt) extends Component {
    setDefinitionName("ForeignInitializerTargetTop")
    retainWidthMetadata(width)

    val condition = in(Bool())
    val payload = in(morphhdl.frontend.Bits(8 bits))
    val foreignRegister = spinal.core.Reg(morphhdl.frontend.Bits(8 bits))
    foreignRegister.setName("foreignRegister")
    expectCaptureFailure(
      "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-INITIALIZER-FOREIGN-TARGET"
    ) {
      ParameterizedStructure.captureBlock(this, Some("foreign-init-owner")) {
        foreignRegister.init(0)
      }
    }
    var initializerCount = 0
    foreignRegister.foreachStatements(_ => initializerCount += 1)
    require(
      initializerCount == 0,
      "foreign initializer remained in its target DLC after rollback"
    )
    when(condition) {
      foreignRegister := payload
    }
    val recovered = out(morphhdl.frontend.Bits(8 bits))
    recovered := foreignRegister
  }

  final class ExistingTreeInsertionRollbackTop(width: HdlInt)
      extends Component {
    setDefinitionName("ExistingTreeInsertionRollbackTop")
    retainWidthMetadata(width)

    val condition = in(Bool())
    val payload = in(morphhdl.frontend.Bits(8 bits))
    val originalTarget = morphhdl.frontend.Bits(8 bits)
    originalTarget.setName("originalTreeTarget")
    originalTarget := payload
    val recoveredTarget = morphhdl.frontend.Bits(8 bits)
    recoveredTarget.setName("insertedTargetRecovered")
    val existingTree = new WhenStatement(condition)
    dslBody.append(existingTree)
    existingTree.whenTrue.on {
      originalTarget := payload
    }

    expectCaptureFailure(
      "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-PROCESS-FOREIGN-OWNERSHIP"
    ) {
      ParameterizedStructure.captureBlock(this, Some("existing-tree-insert")) {
        existingTree.whenTrue.on {
          recoveredTarget := ~payload
        }
      }
    }
    recoveredTarget := payload
    originalTarget.setAsVital()
    originalTarget.dontSimplifyIt()
    recoveredTarget.setAsVital()
    recoveredTarget.dontSimplifyIt()

    val recovered = out(morphhdl.frontend.Bits(8 bits))
    recovered := recoveredTarget
  }

  final class ForeignMemoryPortRollbackTop(width: HdlInt) extends Component {
    setDefinitionName("ForeignMemoryPortRollbackTop")
    retainWidthMetadata(width)

    val address = in(spinal.core.UInt(2 bits))
    val memory = spinal.core.Mem(morphhdl.frontend.Bits(8 bits), 4)
    memory.setName("transactionMemory")
    expectCaptureFailure(
      "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-FOREIGN-MEMORY-PORT-UNSUPPORTED"
    ) {
      ParameterizedStructure.captureBlock(this, Some("foreign-memory-port")) {
        val leaked = memory.readAsync(address)
        leaked.setName("leakedMemoryRead")
      }
    }
    val recoveredRead = memory.readAsync(address)
    recoveredRead.setName("recoveredMemoryRead")
    val recovered = out(morphhdl.frontend.Bits(8 bits))
    recovered := recoveredRead
  }

  final class DetachedContainerRollbackTop(width: HdlInt) extends Component {
    setDefinitionName("DetachedContainerRollbackTop")
    retainWidthMetadata(width)

    val address = in(spinal.core.UInt(2 bits))
    val payload = in(morphhdl.frontend.Bits(8 bits))
    val target = morphhdl.frontend.Bits(8 bits)
    target.setName("detachedAssignmentTarget")
    val memory = spinal.core.Mem(morphhdl.frontend.Bits(8 bits), 4)
    memory.setName("detachedPortMemory")

    expectFailureText("intentional-detached-container-rollback") {
      ParameterizedStructure.captureBlock(this, Some("detached-containers")) {
        target := ~payload
        val leakedAssignment = target.head match {
          case value: DataAssignmentStatement => value
          case value =>
            throw new IllegalStateException(
              s"expected detached data assignment, received ${value.getClass.getSimpleName}"
            )
        }
        leakedAssignment.removeStatementFromScope()
        val leakedRead = memory.readAsync(address)
        leakedRead.setName("detachedLeakedMemoryRead")
        val leakedPort = memory.head
        require(leakedPort ne null, "expected one new native memory port")
        leakedPort.removeStatementFromScope()
        throw new IllegalStateException(
          "intentional-detached-container-rollback"
        )
      }
    }

    var assignmentCount = 0
    target.foreachStatements(_ => assignmentCount += 1)
    require(assignmentCount == 0, "detached assignment leaked after rollback")
    var portCount = 0
    memory.foreachStatements(_ => portCount += 1)
    require(portCount == 0, "detached memory port leaked after rollback")

    target := payload
    val recoveredRead = memory.readAsync(address)
    recoveredRead.setName("detachedRecoveredMemoryRead")
    val recoveredTarget = out(morphhdl.frontend.Bits(8 bits))
    recoveredTarget := target
    val recoveredMemory = out(morphhdl.frontend.Bits(8 bits))
    recoveredMemory := recoveredRead
  }

  final class ForeignOwnerChild extends Component {
    setDefinitionName("ForeignOwnerChild")

    val din = in(morphhdl.frontend.Bits(8 bits))
    din.setName("childDin")
    val dout = out(morphhdl.frontend.Bits(8 bits))
    dout.setName("childDout")
    dout := din
    val ownedAssignment = dout.head match {
      case value: DataAssignmentStatement => value
      case value =>
        throw new IllegalStateException(
          s"expected child data assignment, received ${value.getClass.getSimpleName}"
        )
    }
  }

  final class FreshChildOwnershipTop(width: HdlInt) extends Component {
    setDefinitionName("FreshChildOwnershipTop")
    retainWidthMetadata(width)
    val payload = in(morphhdl.frontend.Bits(8 bits))
    ParameterizedStructure.captureBlock(this, Some("fresh-child-owner")) {
      val child = new ForeignOwnerChild
      child.din := payload
    }
    val recovered = out(morphhdl.frontend.Bits(8 bits))
    recovered := payload
  }

  final class DetachedFreshChild extends Component {
    setDefinitionName("DetachedFreshChild")
    val value = morphhdl.frontend.Bits(8 bits)
    value := spinal.core.B(0, 8 bits)
    value.head match {
      case assignment: DataAssignmentStatement =>
        assignment.removeStatementFromScope()
      case other =>
        throw new IllegalStateException(
          s"expected child data assignment, received ${other.getClass.getSimpleName}"
        )
    }
  }

  final class DetachedFreshChildRollbackTop(width: HdlInt) extends Component {
    setDefinitionName("DetachedFreshChildRollbackTop")
    retainWidthMetadata(width)
    expectCaptureFailure(
      "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-PROCESS-FOREIGN-OWNERSHIP"
    ) {
      ParameterizedStructure.captureBlock(this, Some("detached-child-owner")) {
        new DetachedFreshChild
      }
    }
    val recovered = out(morphhdl.frontend.Bits(8 bits))
    recovered := spinal.core.B(0, 8 bits)
  }

  final class ForeignComponentStatementRollbackTop(width: HdlInt)
      extends Component {
    setDefinitionName("ForeignComponentStatementRollbackTop")
    retainWidthMetadata(width)

    val condition = in(Bool())
    val payload = in(morphhdl.frontend.Bits(8 bits))
    val child = new ForeignOwnerChild
    child.din := payload

    expectCaptureFailure(
      "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-PROCESS-FOREIGN-OWNERSHIP"
    ) {
      ParameterizedStructure.captureBlock(this, Some("foreign-component-owner")) {
        val capturedTree = new WhenStatement(condition)
        dslBody.append(capturedTree)
        child.ownedAssignment.removeStatementFromScope()
        capturedTree.whenTrue.append(child.ownedAssignment)
      }
    }

    val recovered = out(morphhdl.frontend.Bits(8 bits))
    recovered := child.dout
  }

  final class PreexistingOrderRollbackTop(width: HdlInt) extends Component {
    setDefinitionName("PreexistingOrderRollbackTop")
    retainWidthMetadata(width)

    val payload = in(morphhdl.frontend.Bits(8 bits))
    val first = morphhdl.frontend.Bits(8 bits)
    first.setName("preexistingFirst")
    first := payload
    val second = morphhdl.frontend.Bits(8 bits)
    second.setName("preexistingSecond")
    second := ~payload
    val firstAssignment = first.head match {
      case value: DataAssignmentStatement => value
      case value =>
        throw new IllegalStateException(
          s"expected first data assignment, received ${value.getClass.getSimpleName}"
        )
    }

    expectCaptureFailure(
      "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-PREEXISTING-GRAPH-MUTATED"
    ) {
      ParameterizedStructure.captureBlock(this, Some("preexisting-order")) {
        firstAssignment.removeStatementFromScope()
        dslBody.append(firstAssignment)
        val captured = morphhdl.frontend.Bits(8 bits)
        captured.setName("reorderedCaptureLeak")
        captured := payload
      }
    }

    first.setAsVital()
    first.dontSimplifyIt()
    second.setAsVital()
    second.dontSimplifyIt()
    val firstRecovered = out(morphhdl.frontend.Bits(8 bits))
    firstRecovered := first
    val secondRecovered = out(morphhdl.frontend.Bits(8 bits))
    secondRecovered := second
  }

  final class CrossComponentCaptureEntryTop(width: HdlInt) extends Component {
    setDefinitionName("CrossComponentCaptureEntryTop")
    retainWidthMetadata(width)

    val payload = in(morphhdl.frontend.Bits(8 bits))
    val child = new ForeignOwnerChild
    child.din := payload
    var bodyExecuted = false
    expectCaptureFailure(
      "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-COMPONENT-CONTEXT-MISMATCH"
    ) {
      ParameterizedStructure.captureBlock(child, Some("foreign-entry")) {
        bodyExecuted = true
        val leaked = morphhdl.frontend.Bits(8 bits)
        leaked := payload
      }
    }
    require(!bodyExecuted, "cross-component capture body executed")
    val recovered = out(morphhdl.frontend.Bits(8 bits))
    recovered := child.dout
  }

  final class RollbackGrandchild extends Component {
    setDefinitionName("RollbackGrandchild")
    val din = in(morphhdl.frontend.Bits(8 bits))
    val dout = out(morphhdl.frontend.Bits(8 bits))
    dout := din
  }

  final class ForeignComponentStateRollbackTop(width: HdlInt)
      extends Component {
    setDefinitionName("ForeignComponentStateRollbackTop")
    retainWidthMetadata(width)

    val payload = in(morphhdl.frontend.Bits(8 bits))
    val child = new ForeignOwnerChild
    child.din := payload
    expectFailureText("intentional-foreign-component-state") {
      ParameterizedStructure.captureBlock(this, Some("foreign-state")) {
        child.dslBody.on {
          val leakedIo = out(morphhdl.frontend.Bits(8 bits))
          leakedIo.setName("leakedForeignChildIo")
          val leakedChild = new RollbackGrandchild
          leakedChild.din := child.din
        }
        throw new IllegalStateException("intentional-foreign-component-state")
      }
    }
    val recovered = out(morphhdl.frontend.Bits(8 bits))
    recovered := child.dout
  }

  final class StructuralStorageRollbackTop(enabled: HdlBool)
      extends Component {
    setDefinitionName("StructuralStorageRollbackTop")

    val condition = in(Bool())
    val payload = in(morphhdl.frontend.Bits(8 bits))
    val observed = out(morphhdl.frontend.Bits(8 bits))
    observed := payload

    expectCaptureFailure(
      "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SCALA-SIDE-EFFECT-UNSUPPORTED"
    ) {
      ParameterizedStructure.captureBlock(this, Some("labels-rollback")) {
        enabled.generateIf("g_reused_yes", "g_reused_no") {
          val leaked = morphhdl.frontend.Bits(8 bits)
          leaked.setName("leakedRegisteredRegion")
          leaked := payload
        }.otherwise {
          val leaked = morphhdl.frontend.Bits(8 bits)
          leaked.setName("leakedRegisteredOtherwise")
          leaked := ~payload
        }
        assumeInitial(condition)
      }
    }

    expectFailureText("intentional-pending-rollback") {
      ParameterizedStructure.captureBlock(this, Some("pending-rollback")) {
        enabled.generateIf("g_pending_yes", "g_pending_no") {
          val leaked = morphhdl.frontend.Bits(8 bits)
          leaked.setName("leakedPendingRegion")
          leaked := payload
        }
        throw new IllegalStateException("intentional-pending-rollback")
      }
    }

    enabled.generateIf("g_reused_yes", "g_reused_no") {
      val value = morphhdl.frontend.Bits(8 bits)
      value.setName("reusedYesValue")
      value := payload
    }.otherwise {
      val value = morphhdl.frontend.Bits(8 bits)
      value.setName("reusedNoValue")
      value := ~payload
    }
    enabled.generateIf("g_pending_yes", "g_pending_no") {
      val value = morphhdl.frontend.Bits(8 bits)
      value.setName("pendingYesValue")
      value := payload
    }.otherwise {
      val value = morphhdl.frontend.Bits(8 bits)
      value.setName("pendingNoValue")
      value := ~payload
    }
  }

  def processTreeComponent(): Component = {
    val enabled = HdlBool.param("ENABLED", default = true)
    new ProcessTreeTop(enabled)
  }

  def storageRollbackComponent(): Component = {
    val enabled = HdlBool.param("ENABLED", default = true)
    new StructuralStorageRollbackTop(enabled)
  }

  def widthParameterizedComponent(factory: HdlInt => Component): Component = {
    val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 32)
    factory(width)
  }

  def emit(directory: Path, filename: String, component: => Component): String = {
    Files.createDirectories(directory)
    val config = SpinalConfig(targetDirectory = directory.toString)
    config.netlistFileName = filename
    MorphVerilog(config)(component)
    new String(
      Files.readAllBytes(directory.resolve(filename)),
      StandardCharsets.UTF_8
    )
  }
}

class NativeProcessTreeCaptureTests extends AnyFunSuite {
  import NativeProcessTreeCaptureSmoke._

  test("native when and switch process trees relocate wholly into parameter alternatives") {
    withTemporaryDirectory { directory =>
      val verilog = emit(
        directory,
        "native_process_tree.v",
        processTreeComponent()
      )

      assert(verilog.contains("module NativeProcessTreeTop #("))
      assert(verilog.contains("parameter integer ENABLED = 1"))
      assert(
        verilog.contains(
          "if ((ENABLED == 1)) begin : g_process_enabled"
        )
      )
      val elseMarker = "end else begin : g_process_disabled"
      assert(verilog.contains(elseMarker))
      val enabledStart = verilog.indexOf(
        "if ((ENABLED == 1)) begin : g_process_enabled"
      )
      val disabledStart = verilog.indexOf(elseMarker, enabledStart)
      val generateEnd = verilog.indexOf("  endgenerate", disabledStart)
      assert(enabledStart >= 0 && disabledStart > enabledStart)
      assert(generateEnd > disabledStart)
      val enabledAlternative = verilog.substring(enabledStart, disabledStart)
      val disabledAlternative = verilog.substring(disabledStart, generateEnd)
      val enabledNames = Vector(
        "enabledWhenValue",
        "enabledSwitchValue",
        "enabledInitializedValue"
      )
      val disabledNames = Vector(
        "disabledWhenValue",
        "disabledSwitchValue"
      )
      enabledNames.foreach { name =>
        assert(enabledAlternative.contains(name), s"missing enabled $name")
        assert(!disabledAlternative.contains(name), s"$name escaped to disabled")
      }
      disabledNames.foreach { name =>
        assert(disabledAlternative.contains(name), s"missing disabled $name")
        assert(!enabledAlternative.contains(name), s"$name escaped to enabled")
      }

      val generateStart = verilog.indexOf("  generate")
      val processStarts = "(?m)^\\s*always @\\(\\*\\) begin$".r
        .findAllMatchIn(verilog)
        .map(_.start)
        .toVector
      assert(processStarts.size == 4, verilog)
      assert(
        processStarts.forall(index => index > generateStart && index < generateEnd),
        "captured native process escaped its parameterized generate region"
      )
      assert("(?m)^\\s*case\\(".r.findAllMatchIn(verilog).size == 2)
      assert(occurrences(enabledAlternative, "always @(*) begin") == 2)
      assert(occurrences(disabledAlternative, "always @(*) begin") == 2)
      assert(occurrences(enabledAlternative, "case(") == 1)
      assert(occurrences(disabledAlternative, "case(") == 1)
      assert(enabledAlternative.contains("always @(posedge "))
      assert(!disabledAlternative.contains("always @(posedge "))
    }
  }

  test("unsupported statements nested in native process trees fail closed") {
    withTemporaryDirectory { directory =>
      val verilog = emit(
        directory,
        "nested_unsupported.v",
        widthParameterizedComponent(new NestedUnsupportedStatementTop(_))
      )
      assert(verilog.contains("module NestedUnsupportedStatementTop"))
      assert(verilog.contains("unsupportedRecovered"))
      assert(!verilog.contains("leakedUnsupportedValue"))
    }
  }

  test("nested process throws restore the complete entry scope context") {
    withTemporaryDirectory { directory =>
      val verilog = emit(
        directory,
        "nested_scope_throw.v",
        widthParameterizedComponent(new NestedScopeThrowRollbackTop(_))
      )
      assert(verilog.contains("module NestedScopeThrowRollbackTop"))
      assert(verilog.contains("nestedScopeRecovered"))
      assert(!verilog.contains("leakedNestedScopeValue"))
      assert(!verilog.contains("always @(*) begin"))
    }
  }

  test("captured process trees cannot adopt statements owned before capture") {
    withTemporaryDirectory { directory =>
      val verilog = emit(
        directory,
        "foreign_process_owner.v",
        widthParameterizedComponent(new ForeignStatementOwnershipTop(_))
      )
      assert(verilog.contains("module ForeignStatementOwnershipTop"))
      assert(verilog.contains("foreignTarget"))
      assert(!verilog.contains("always @(*) begin"))
    }
  }

  test("captured initializers reject targets declared outside their structural block") {
    withTemporaryDirectory { directory =>
      val verilog = emit(
        directory,
        "foreign_initializer_target.v",
        widthParameterizedComponent(new ForeignInitializerTargetTop(_))
      )
      assert(verilog.contains("module ForeignInitializerTargetTop"))
      assert(verilog.contains("foreignRegister"))
      assert(occurrences(verilog, "always @(posedge ") == 1)
    }
  }

  test("insertion into an existing process tree rolls back before graph reuse") {
    withTemporaryDirectory { directory =>
      val verilog = emit(
        directory,
        "existing_tree_insertion.v",
        widthParameterizedComponent(new ExistingTreeInsertionRollbackTop(_))
      )
      assert(verilog.contains("module ExistingTreeInsertionRollbackTop"))
      assert(verilog.contains("originalTreeTarget"))
      assert(verilog.contains("insertedTargetRecovered"))
      assert(occurrences(verilog, "always @(*) begin") == 1)
    }
  }

  test("foreign memory ports roll back both scope and memory DLC ownership") {
    withTemporaryDirectory { directory =>
      val verilog = emit(
        directory,
        "foreign_memory_port.v",
        widthParameterizedComponent(new ForeignMemoryPortRollbackTop(_))
      )
      assert(verilog.contains("module ForeignMemoryPortRollbackTop"))
      assert(verilog.contains("transactionMemory"))
      assert(verilog.contains("recoveredMemoryRead"))
      assert(!verilog.contains("leakedMemoryRead"))
    }
  }

  test("detached new assignments and ports restore exact pre-capture DLC state") {
    withTemporaryDirectory { directory =>
      val verilog = emit(
        directory,
        "detached_containers.v",
        widthParameterizedComponent(new DetachedContainerRollbackTop(_))
      )
      assert(verilog.contains("module DetachedContainerRollbackTop"))
      assert(verilog.contains("detachedAssignmentTarget"))
      assert(verilog.contains("detachedRecoveredMemoryRead"))
      assert(!verilog.contains("detachedLeakedMemoryRead"))
    }
  }

  test("foreign component statements restore their original owner and order") {
    withTemporaryDirectory { directory =>
      val verilog = emit(
        directory,
        "foreign_component_statement.v",
        widthParameterizedComponent(new ForeignComponentStatementRollbackTop(_))
      )
      assert(verilog.contains("module ForeignComponentStatementRollbackTop"))
      assert(verilog.contains("module ForeignOwnerChild"))
      assert(!verilog.contains("always @(*) begin"))
      val childStart = verilog.indexOf("module ForeignOwnerChild")
      val childEnd = verilog.indexOf("endmodule", childStart)
      assert(childStart >= 0 && childEnd > childStart)
      val childModule = verilog.substring(childStart, childEnd)
      assert(childModule.contains("assign childDout = childDin;"), childModule)
      assert(occurrences(childModule, "assign childDout = childDin;") == 1)
    }
  }

  test("fresh child statements retain their exact child process owner") {
    withTemporaryDirectory { directory =>
      val verilog = emit(
        directory,
        "fresh_child_owner.v",
        widthParameterizedComponent(new FreshChildOwnershipTop(_))
      )
      assert(verilog.contains("module FreshChildOwnershipTop"))
      assert(verilog.contains("module ForeignOwnerChild"))
      assert(verilog.contains("assign childDout = childDin;"))
    }
  }

  test("detached fresh-child assignments still fail closed and roll back") {
    withTemporaryDirectory { directory =>
      val verilog = emit(
        directory,
        "detached_fresh_child.v",
        widthParameterizedComponent(new DetachedFreshChildRollbackTop(_))
      )
      assert(verilog.contains("module DetachedFreshChildRollbackTop"))
      assert(!verilog.contains("module DetachedFreshChild ("))
      assert(verilog.contains("recovered"))
    }
  }

  test("pre-existing statement order is validated and restored transactionally") {
    withTemporaryDirectory { directory =>
      val verilog = emit(
        directory,
        "preexisting_order.v",
        widthParameterizedComponent(new PreexistingOrderRollbackTop(_))
      )
      assert(verilog.contains("module PreexistingOrderRollbackTop"))
      assert(verilog.contains("preexistingFirst"))
      assert(verilog.contains("preexistingSecond"))
      assert(!verilog.contains("reorderedCaptureLeak"))
      val first = verilog.indexOf("assign preexistingFirst")
      val second = verilog.indexOf("assign preexistingSecond")
      assert(first >= 0 && second > first, verilog)
    }
  }

  test("cross-component capture entry fails before executing or mutating the body") {
    withTemporaryDirectory { directory =>
      val verilog = emit(
        directory,
        "cross_component_entry.v",
        widthParameterizedComponent(new CrossComponentCaptureEntryTop(_))
      )
      assert(verilog.contains("module CrossComponentCaptureEntryTop"))
      assert(verilog.contains("assign childDout = childDin;"))
    }
  }

  test("rollback restores children and IO on every pre-existing component") {
    withTemporaryDirectory { directory =>
      val verilog = emit(
        directory,
        "foreign_component_state.v",
        widthParameterizedComponent(new ForeignComponentStateRollbackTop(_))
      )
      assert(verilog.contains("module ForeignComponentStateRollbackTop"))
      assert(verilog.contains("module ForeignOwnerChild"))
      assert(!verilog.contains("RollbackGrandchild"))
      assert(!verilog.contains("leakedForeignChildIo"))
      assert(verilog.contains("assign childDout = childDin;"))
    }
  }

  test("failed nested registrations restore labels pending continuations and graph state") {
    withTemporaryDirectory { directory =>
      val verilog = emit(
        directory,
        "structural_storage_rollback.v",
        storageRollbackComponent()
      )
      Vector(
        "g_reused_yes",
        "g_reused_no",
        "g_pending_yes",
        "g_pending_no"
      ).foreach(label => assert(occurrences(verilog, label) == 1, label))
      Vector(
        "reusedYesValue",
        "reusedNoValue",
        "pendingYesValue",
        "pendingNoValue"
      ).foreach(name => assert(verilog.contains(name), name))
      assert(!verilog.contains("leakedRegisteredRegion"))
      assert(!verilog.contains("leakedRegisteredOtherwise"))
      assert(!verilog.contains("leakedPendingRegion"))
    }
  }

  private def occurrences(value: String, needle: String): Int =
    value.sliding(needle.length).count(_ == needle)

  private def withTemporaryDirectory(body: Path => Unit): Unit = {
    val directory = Files.createTempDirectory("morphhdl-process-tree-test-")
    try body(directory)
    finally {
      val stream = Files.walk(directory)
      try {
        stream.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach {
          path => Files.deleteIfExists(path)
        }
      } finally stream.close()
    }
  }
}
