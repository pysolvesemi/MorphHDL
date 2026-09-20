package spinal.core

import java.nio.file.Files
import morphhdl.frontend.{HdlInt, StructuralGenerateCaseOps}
import morphhdl.runtime.ParameterizedVerilogMode
import org.scalatest.funsuite.AnyFunSuite

class ParameterizedStructuralLexicalOwnerTests extends AnyFunSuite {
  private def elaborate(body: => Unit): Unit = {
    val config = ParameterizedVerilogMode.enable(
      SpinalConfig(targetDirectory = Files.createTempDirectory("lexical-owner-").toString))
    SpinalVerilog(config) {
      new Component {
        val retained = out(Bool())
        retained := False
        body
      }
    }
    ()
  }

  private def marker(): Unit = {
    val value = Bool()
    value := True
    value.setAsVital()
    value.dontSimplifyIt()
  }

  private def messages(error: Throwable): String = {
    val result = scala.collection.mutable.ArrayBuffer.empty[String]
    var current = error
    while (current != null) {
      result += Option(current.getMessage).getOrElse("")
      current = current.getCause
    }
    result.mkString("\n")
  }

  test("nested lexical owners retain distinct blocks across independent control roots") {
    elaborate {
      val first = HdlInt.param("FIRST", 1, 1, 2).asElabInt
      val second = HdlInt.param("SECOND", 1, 1, 2).asElabInt
      val module = ParameterizedStructure.currentLexicalOwner("module")
      var outer: ParameterizedStructuralLexicalOwner = null
      var inner: ParameterizedStructuralLexicalOwner = null
      ElabControl.selectSymbolic(first.elabEq(1), "lexical-first", 1) {
        outer = ParameterizedStructure.currentLexicalOwner("outer")
        marker()
        ElabControl.selectSymbolic(second.elabEq(1), "lexical-second", 2) {
          inner = ParameterizedStructure.currentLexicalOwner("inner")
          marker()
        } { marker() }
      } { marker() }
      assert(module.isModuleScope)
      assert(ParameterizedStructure.blockOfLexicalOwner(module).isEmpty)
      assert(!outer.isModuleScope && !inner.isModuleScope)
      val outerBlock = ParameterizedStructure.blockOfLexicalOwner(outer).get
      val innerBlock = ParameterizedStructure.blockOfLexicalOwner(inner).get
      assert(outerBlock ne innerBlock)
      assert(outerBlock.regions.flatMap(ParameterizedStructure.allBlocks).exists(_ eq innerBlock))
    }
  }

  test("same-field copies cannot forge issued lexical owner identities") {
    elaborate {
      val owner = ParameterizedStructure.currentLexicalOwner("issued")
      val copied = new ParameterizedStructuralLexicalOwner(
        owner.component, owner.captureId, owner.sourceLocation, owner.role)
      val error = intercept[IllegalArgumentException] {
        ParameterizedStructure.blockOfLexicalOwner(copied)
      }
      assert(error.getMessage.contains("LEXICAL-IDENTITY-MISMATCH"))
    }
  }

  test("readonly validation re-enters and restores the exact retained branch domain") {
    elaborate {
      val count = HdlInt.param("COUNT", 1, 1, 3)
      val domain = count.asElabInt.expression.exactDomain.get
      var owner: ParameterizedStructuralLexicalOwner = null
      var expression: ElaborationIntegerExpression = null
      ElabControl.selectSymbolic(count.asElabInt > 1, "lexical-domain", 1) {
        owner = ParameterizedStructure.currentLexicalOwner("projected width")
        val word = UInt(count bits)
        word := 0
        word.setAsVital()
        word.dontSimplifyIt()
        expression = ParameterizedWidth.expressionOf(word).get
      } { marker() }
      assert(ElaborationDomainContext.admitted(domain) == domain.universe)
      intercept[ParameterizedVerilogException] {
        ElabInt.requireAuthoritativeIntegerDomain(expression, "outside branch",
          "TEST-PROJECTION", requireExactExtrema = false)
      }
      ParameterizedStructure.withLexicalOwnerDomain(owner) {
        assert(ElaborationDomainContext.admitted(domain) == Set(BigInt(2), BigInt(3)))
        assert(ElabInt.requireAuthoritativeIntegerDomain(expression, "inside branch",
          "TEST-PROJECTION", requireExactExtrema = false).nonEmpty)
      }
      assert(ElaborationDomainContext.admitted(domain) == domain.universe)
      intercept[IllegalStateException] {
        ParameterizedStructure.withLexicalOwnerDomain(owner) {
          throw new IllegalStateException("restore after validation rejection")
        }
      }
      assert(ElaborationDomainContext.admitted(domain) == domain.universe)
    }
  }

  test("same-root case choices narrow COUNT before constructing native widths") {
    elaborate {
      val count = HdlInt.param("COUNT", 1, 1, 3)
      val domain = count.asElabInt.expression.exactDomain.get
      val values = scala.collection.mutable.ArrayBuffer.empty[(Int, UInt)]
      def branch(expected: Int): Unit = {
        val word = UInt(count bits)
        word := 0
        word.setAsVital()
        word.dontSimplifyIt()
        assert(word.getBitsWidth == expected)
        values += expected -> word
      }
      count.generateCase
        .choice(BigInt(1), "g_count_one") { branch(1) }
        .choice(BigInt(2), "g_count_two") { branch(2) }
        .default("g_count_three") { branch(3) }
      assert(values.size == 3)
      values.foreach { case (expected, word) =>
        val owner = ParameterizedStructure.exactDeclarationDomainOf(
          Component.current, word, domain.root, domain.universe, "case width", None)
        assert(owner.captured)
        assert(owner.values == Set(BigInt(expected)))
      }
    }
  }

  test("independent case selectors preserve the complete COUNT domain") {
    elaborate {
      val count = HdlInt.param("COUNT", 1, 1, 3)
      val mode = HdlInt.param("MODE", 0, 0, 2)
      val domain = count.asElabInt.expression.exactDomain.get
      val values = scala.collection.mutable.ArrayBuffer.empty[UInt]
      def branch(): Unit = {
        val word = UInt(count bits)
        word := 0
        word.setAsVital()
        word.dontSimplifyIt()
        values += word
      }
      mode.generateCase
        .choice(BigInt(0), "g_mode_zero") { branch() }
        .choice(BigInt(1), "g_mode_one") { branch() }
        .default("g_mode_other") { branch() }
      values.foreach { word =>
        val owner = ParameterizedStructure.exactDeclarationDomainOf(
          Component.current, word, domain.root, domain.universe, "independent case width", None)
        assert(owner.captured && owner.values == domain.universe)
      }
    }
  }

  test("proven unreachable case alternatives execute no hardware callback") {
    elaborate {
      val count = HdlInt.param("COUNT", 1, 1, 2)
      var unreachableCallbacks = 0
      count.generateCase
        .choice(BigInt(1), "g_count_one") { marker() }
        .choice(BigInt(2), "g_count_two") { marker() }
        .choice(BigInt(3), "g_count_impossible") {
          unreachableCallbacks += 1
          marker()
        }
        .default("g_count_unreachable_default") {
          unreachableCallbacks += 1
          marker()
        }
      assert(unreachableCallbacks == 0)
    }
  }

  test("descriptive case selectors cannot authorize exact width projection") {
    elaborate {
      val count = HdlInt.param("COUNT", 1, 1, 3)
      val mode = HdlInt.param("MODE", 0, 0, 1).asElabInt.expression
      val domain = count.asElabInt.expression.exactDomain.get
      var word: UInt = null
      val branch = ParameterizedStructure.captureBlock(Component.current, None) {
        word = UInt(count bits)
        word := 0
        word.setAsVital()
        word.dontSimplifyIt()
      }
      val fallback = ParameterizedStructure.captureBlock(Component.current, None) { marker() }
      val pending = ParameterizedStructure.beginPending(Component.current, "descriptive case", None)
      val descriptive = ElaborationIntegerExpression(mode.verilog, mode.default,
        mode.minimum, mode.maximum, mode.parameters, parameterRoots = mode.parameterRoots)
      ParameterizedStructure.registerCase(pending, descriptive,
        Vector((BigInt(1), "g_descriptive_one", branch)), "g_descriptive_other", fallback, None)
      val error = intercept[ParameterizedVerilogException] {
        ParameterizedStructure.exactDeclarationDomainOf(Component.current, word,
          domain.root, domain.universe, "unproven case width", None)
      }
      assert(error.getMessage.contains("STRUCTURAL-DOMAIN-UNPROVEN"))
    }
  }

  test("typed case publication rejects swapping exactly captured alternatives") {
    val error = intercept[Exception] {
      elaborate {
        val selector = HdlInt.param("COUNT", 1, 1, 2).asElabInt.expression
        val component = Component.current
        val one = ParameterizedStructure.captureExactCaseBlock(component, selector,
          Set(BigInt(1)), isDefault = false, sourceLocation = None) { marker() }
        val two = ParameterizedStructure.captureExactCaseBlock(component, selector,
          Set(BigInt(2)), isDefault = false, sourceLocation = None) { marker() }
        val fallback = ParameterizedStructure.captureExactCaseBlock(component, selector,
          Set(BigInt(1), BigInt(2)), isDefault = true, sourceLocation = None) { marker() }
        val pending = ParameterizedStructure.beginPending(component, "swapped typed case", None)
        ParameterizedStructure.registerCase(pending, selector,
          Vector((BigInt(1), "g_wrong_one", two), (BigInt(2), "g_wrong_two", one)),
          "g_wrong_default", fallback, None)
      }
    }
    assert(messages(error).contains("CASE-BLOCK-DOMAIN-MISMATCH"), messages(error))
  }

  test("a valid typed case selector cannot lend proof to uncaptured branch domains") {
    elaborate {
      val selector = HdlInt.param("MODE", 1, 1, 2).asElabInt.expression
      val count = HdlInt.param("COUNT", 1, 1, 3)
      val domain = count.asElabInt.expression.exactDomain.get
      var word: UInt = null
      val branch = ParameterizedStructure.captureBlock(Component.current, None) {
        word = UInt(count bits)
        word := 0
        word.setAsVital()
        word.dontSimplifyIt()
      }
      val fallback = ParameterizedStructure.captureBlock(Component.current, None) { marker() }
      val pending = ParameterizedStructure.beginPending(Component.current, "unbound typed case", None)
      ParameterizedStructure.registerCase(pending, selector,
        Vector((BigInt(1), "g_unbound_one", branch)), "g_unbound_other", fallback, None)
      val error = intercept[ParameterizedVerilogException] {
        ParameterizedStructure.exactDeclarationDomainOf(Component.current, word,
          domain.root, domain.universe, "unbound case width", None)
      }
      assert(error.getMessage.contains("STRUCTURAL-DOMAIN-UNPROVEN"))
    }
  }

  test("ordinary ancestor index reads retain their original finite token") {
    elaborate {
      val rows = HdlInt.param("ROWS", 2, 1, 3).asElabInt
      val count = HdlInt.param("COUNT", 2, 1, 3).asElabInt
      val values = in(Vec(UInt(8 bits), rows))
      ElabFiniteRange.foreach(rows, "outer read") { row =>
        ElabFiniteRange.foreach(count, "inner read") { _ =>
          val selected = row(values)
          val word = UInt(8 bits)
          word := selected
          word.setAsVital()
          word.dontSimplifyIt()
        }
      }
      val outer = ParameterizedStructure.regionsOf(Component.current).head
        .asInstanceOf[ParameterizedStructure.StructuralFor]
      val inner = outer.body.regions.head.asInstanceOf[ParameterizedStructure.StructuralFor]
      val selection = inner.body.vecIndices.find(_.vector eq values).get
      assert(selection.finiteIndexToken.get eq outer.finiteIndexToken.get)
      assert(selection.finiteIndexToken.get ne inner.finiteIndexToken.get)
    }
  }

  test("an inner loop cannot write through an ancestor finite index") {
    val error = intercept[Exception] {
      elaborate {
        val rows = HdlInt.param("ROWS", 2, 1, 3).asElabInt
        val count = HdlInt.param("COUNT", 2, 1, 3).asElabInt
        val values = out(Vec(UInt(8 bits), rows))
        ElabFiniteRange.foreach(rows, "outer write") { row =>
          ElabFiniteRange.foreach(count, "inner write") { _ =>
            row(values) := 0
          }
        }
      }
    }
    assert(messages(error).contains("VEC-FINITE-INDEX-TOKEN-CONFLICT"), messages(error))
  }

  test("a completed sibling loop cannot lend its index to another loop") {
    val error = intercept[Exception] {
      elaborate {
        val count = HdlInt.param("COUNT", 2, 1, 3).asElabInt
        val values = in(Vec(UInt(8 bits), count))
        var escaped: ElabFiniteIndex = null
        ElabFiniteRange.foreach(count, "completed sibling") { index =>
          escaped = index
          marker()
        }
        ElabFiniteRange.foreach(count, "foreign borrower") { _ =>
          val word = UInt(8 bits)
          word := escaped(values)
          word.setAsVital()
          word.dontSimplifyIt()
        }
      }
    }
    assert(messages(error).contains("VEC-FINITE-INDEX-TOKEN-CONFLICT"), messages(error))
  }

  test("swapping branch bindings cannot replace the issued registered region") {
    elaborate {
      val first = HdlInt.param("FIRST", 1, 1, 2).asElabInt
      val second = HdlInt.param("SECOND", 1, 1, 2).asElabInt
      var outer: ParameterizedStructuralLexicalOwner = null
      var inner: ParameterizedStructuralLexicalOwner = null
      ElabControl.selectSymbolic(first.elabEq(1), "lexical-swap-outer", 1) {
        outer = ParameterizedStructure.currentLexicalOwner("outer")
        marker()
        ElabControl.selectSymbolic(second.elabEq(1), "lexical-swap-inner", 2) {
          inner = ParameterizedStructure.currentLexicalOwner("inner")
          marker()
        } { marker() }
      } { marker() }
      val block = ParameterizedStructure.blockOfLexicalOwner(outer).get
      assert(ParameterizedStructure.blockOfLexicalOwner(inner).nonEmpty)
      val original = block.regions
      val branch = original.head.asInstanceOf[ParameterizedStructure.StructuralIf]
      block.regions = Vector(branch.copy(whenTrue = branch.whenFalse, whenFalse = branch.whenTrue))
      try {
        val error = intercept[IllegalArgumentException] {
          ParameterizedStructure.blockOfLexicalOwner(inner)
        }
        assert(error.getMessage.contains("LEXICAL-REGION-IDENTITY-MISMATCH"))
      } finally block.regions = original
    }
  }

  test("an unchanged region cannot migrate into a sibling lexical owner") {
    elaborate {
      val first = HdlInt.param("FIRST", 1, 1, 2).asElabInt
      val second = HdlInt.param("SECOND", 1, 1, 2).asElabInt
      var source: ParameterizedStructuralLexicalOwner = null
      var destination: ParameterizedStructuralLexicalOwner = null
      var inner: ParameterizedStructuralLexicalOwner = null
      ElabControl.selectSymbolic(first.elabEq(1), "lexical-move-outer", 1) {
        source = ParameterizedStructure.currentLexicalOwner("source")
        marker()
        ElabControl.selectSymbolic(second.elabEq(1), "lexical-move-inner", 2) {
          inner = ParameterizedStructure.currentLexicalOwner("inner")
          marker()
        } { marker() }
      } {
        destination = ParameterizedStructure.currentLexicalOwner("destination")
        marker()
      }
      val from = ParameterizedStructure.blockOfLexicalOwner(source).get
      val to = ParameterizedStructure.blockOfLexicalOwner(destination).get
      val originalFrom = from.regions
      val originalTo = to.regions
      from.regions = Vector.empty
      to.regions = originalTo ++ originalFrom
      try {
        val error = intercept[IllegalArgumentException] {
          ParameterizedStructure.blockOfLexicalOwner(inner)
        }
        assert(error.getMessage.contains("LEXICAL-ANCESTRY-MISMATCH"))
      } finally {
        from.regions = originalFrom
        to.regions = originalTo
      }
    }
  }

  test("detached captures cannot authorize structural publication") {
    elaborate {
      var owner: ParameterizedStructuralLexicalOwner = null
      ParameterizedStructure.captureBlock(Component.current, None) {
        owner = ParameterizedStructure.currentLexicalOwner("detached")
        marker()
      }
      val error = intercept[IllegalArgumentException] {
        ParameterizedStructure.blockOfLexicalOwner(owner)
      }
      assert(error.getMessage.contains("LEXICAL-PUBLICATION-MISMATCH"))
    }
  }

  test("rolled-back capture handles cannot bind later successful captures") {
    elaborate {
      var abandoned: ParameterizedStructuralLexicalOwner = null
      intercept[IllegalArgumentException] {
        ParameterizedStructure.captureBlock(Component.current, None) {
          abandoned = ParameterizedStructure.currentLexicalOwner("abandoned")
          marker()
          throw new IllegalArgumentException("reject representative capture")
        }
      }
      val control = HdlInt.param("CONTROL", 1, 1, 2).asElabInt
      var live: ParameterizedStructuralLexicalOwner = null
      ElabControl.selectSymbolic(control.elabEq(1), "lexical-live", 3) {
        live = ParameterizedStructure.currentLexicalOwner("live")
        marker()
      } { marker() }
      assert(ParameterizedStructure.blockOfLexicalOwner(live).nonEmpty)
      val error = intercept[IllegalArgumentException] {
        ParameterizedStructure.blockOfLexicalOwner(abandoned)
      }
      assert(error.getMessage.contains("LEXICAL-CAPTURE-MISSING"))
    }
  }

  test("child module captures restore the exact enclosing instance owner") {
    elaborate {
      val control = HdlInt.param("CONTROL", 1, 1, 2).asElabInt
      var before: ParameterizedStructuralLexicalOwner = null
      var after: ParameterizedStructuralLexicalOwner = null
      var childOwner: ParameterizedStructuralLexicalOwner = null
      var childRegionOwner: ParameterizedStructuralLexicalOwner = null
      var child: Component = null
      ElabControl.selectSymbolic(control.elabEq(1), "lexical-child", 4) {
        before = ParameterizedStructure.currentLexicalOwner("parent before child")
        child = new Component {
          val retained = out(Bool())
          retained := True
          childOwner = ParameterizedStructure.currentLexicalOwner("child module")
          ParameterizedStructure.captureBlock(this, None) {
            marker()
          }
          val childControl = HdlInt.param("CHILD_CONTROL", 1, 1, 2).asElabInt
          ElabControl.selectSymbolic(childControl.elabEq(1), "lexical-child-region", 1) {
            childRegionOwner = ParameterizedStructure.currentLexicalOwner("child region")
            marker()
          } { marker() }
        }
        after = ParameterizedStructure.currentLexicalOwner("parent after child")
        marker()
      } { marker() }
      assert(childOwner.component eq child)
      assert(childOwner.isModuleScope)
      assert(ParameterizedStructure.blockOfLexicalOwner(childOwner).isEmpty)
      assert(childRegionOwner.component eq child)
      val childBlock = ParameterizedStructure.blockOfLexicalOwner(childRegionOwner).get
      assert(ParameterizedStructure.regionsOf(child)
        .flatMap(ParameterizedStructure.allBlocks).exists(_ eq childBlock))
      val block = ParameterizedStructure.blockOfLexicalOwner(before).get
      assert(ParameterizedStructure.blockOfLexicalOwner(after).contains(block))
      assert(block.children.count(_ eq child) == 1)
      assert(!block.regions.flatMap(ParameterizedStructure.allBlocks).exists(_ eq childBlock))
    }
  }

  test("a captured body cannot borrow a sibling component for native templates") {
    elaborate {
      val sibling = new Component {
        val retained = out(Bool())
        retained := True
      }
      val control = HdlInt.param("CONTROL", 1, 1, 2).asElabInt
      ElabControl.selectSymbolic(control.elabEq(1), "lexical-sibling", 5) {
        val error = intercept[IllegalArgumentException] {
          ParameterizedStructure.captureBlock(sibling, None) {
            marker()
          }
        }
        assert(error.getMessage.contains("CAPTURE-COMPONENT-MISMATCH"))
        marker()
      } { marker() }
    }
  }
}
