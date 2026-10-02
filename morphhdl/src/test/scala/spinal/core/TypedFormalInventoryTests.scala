package spinal.core

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.collection.JavaConverters._
import scala.collection.mutable
import morphhdl.MorphVerilog
import morphhdl.frontend.HdlInt
import org.scalatest.funsuite.AnyFunSuite

object TypedFormalInventoryFixture {
  final class Leaf(width: ElabInt, count: ElabInt, mode: ElabInt) extends Component {
    setDefinitionName("TypedFormalInventoryLeaf")
    val words = in(Vec(UInt(width bits), count))
    val result = out(UInt(width bits))
    ElabControl.selectSymbolic(mode.elabEq(1), "typed-inventory", 1) {
      val selected = UInt(width bits).setName("selected_first").dontSimplifyIt()
      selected := words(0)
      result := selected
    } {
      val selected = UInt(width bits).setName("selected_second").dontSimplifyIt()
      selected := ~words(0)
      result := selected
    }
  }

  final class Top(check: Leaf => Unit) extends Component {
    setDefinitionName("TypedFormalInventoryTop")
    val width = HdlInt.param("WIDTH", 5, 1, 8).asElabInt
    val count = HdlInt.param("COUNT", 2, 1, 3).asElabInt
    val mode = HdlInt.param("MODE", 0, 0, 1).asElabInt
    val words = in(Vec(UInt(width bits), count))
    val result = out(UInt(width bits))
    val child = ElabFormalComponent.parameters(Vector(
      ElabFormalComponent.Parameter(width, "WIDTH", 1, 8),
      ElabFormalComponent.Parameter(count, "COUNT", 1, 3),
      ElabFormalComponent.Parameter(mode + 1, "MODE", 1, 2))) { values =>
      new Leaf(values(0), values(1), values(2))
    }
    child.words := words
    result := child.result
    check(child)
  }

  final class ConcreteLeaf extends Component {
    setDefinitionName("TypedFormalInventoryConcreteLeaf")
    val input = in(Bool())
    val result = out(Bool())
    result := ~input
  }

  final class ConcreteTop(check: ConcreteLeaf => Unit) extends Component {
    setDefinitionName("TypedFormalInventoryConcreteTop")
    val input = in(Bool())
    val result = out(Bool())
    // Both definition formals are intentionally unused by this child's
    // otherwise entirely concrete body. Inventory authentication must still
    // precede the early decision whether hierarchy needs parameter emission.
    val child = ElabFormalComponent.parameters(Vector(
      ElabFormalComponent.Parameter(ElabInt.literal(5), "WIDTH", 1, 8),
      ElabFormalComponent.Parameter(ElabInt.literal(1), "MODE", 1, 2))) { _ =>
      new ConcreteLeaf
    }
    child.input := input
    result := child.result
    check(child)
  }

  def instanceMap: mutable.Map[ExternalFormalComponentIdentityRef, Vector[ExternalTypedFormalBinding]] = {
    val field = ExternalFormalParameterRegistry.getClass.getDeclaredFields
      .find(_.getName.endsWith("typedInstanceBindings")).get
    field.setAccessible(true)
    field.get(ExternalFormalParameterRegistry)
      .asInstanceOf[mutable.Map[ExternalFormalComponentIdentityRef, Vector[ExternalTypedFormalBinding]]]
  }
}

class TypedFormalInventoryTests extends AnyFunSuite {
  import TypedFormalInventoryFixture._

  private def emitted(check: Leaf => Unit): String = emittedComponent(new Top(check))

  private def emittedComponent(component: => Component): String = {
    val directory = Files.createTempDirectory("typed-formal-inventory-")
    try {
      val config = SpinalConfig(targetDirectory = directory.toString, headerWithDate = false)
      config.netlistFileName = "inventory.v"
      MorphVerilog(config)(component)
      new String(Files.readAllBytes(directory.resolve("inventory.v")), StandardCharsets.UTF_8)
    } finally {
      val stream = Files.walk(directory)
      try stream.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists(_))
      finally stream.close()
    }
  }

  test("one atomic constructor emits a complete width Vec and control inventory") {
    val rtl = emitted { child =>
      val entries = ExternalFormalParameterRegistry.completeTypedBindingsOf(child)
      assert(entries.map(_.binding.formal.name) == Vector("WIDTH", "COUNT", "MODE"))
      assert(entries.map(_.declarationToken).distinct.size == 3)
    }
    val compact = rtl.replaceAll("\\s+", "")
    Vector(".WIDTH(WIDTH)", ".COUNT(COUNT)", ".MODE((MODE+1))")
      .foreach(binding => assert(compact.contains(binding), rtl))
  }

  test("dropped duplicated reordered foreign and copied inventory entries reject without losing the seal") {
    emitted { child =>
      val key = new ExternalFormalComponentIdentityRef(child, null)
      val map = instanceMap
      val original = map(key)
      val foreign = original.head.copy(binding = original.head.binding.copy(
        formal = original.head.binding.formal.copy()))
      val mutations = Vector(original.drop(1), original :+ original.head, original.reverse,
        original.updated(0, foreign), original.updated(0, original.head.copy()), Vector.empty)
      mutations.foreach { entries =>
        map.update(key, entries)
        try {
          val failure = intercept[ParameterizedVerilogException] {
            ExternalFormalParameterRegistry.completeTypedBindingsOf(child)
          }
          assert(failure.code == "SPINAL-ELAB-FORMAL-INVENTORY-IDENTITY-CONFLICT")
        } finally map.update(key, original)
        assert(ExternalFormalParameterRegistry.completeTypedBindingsOf(child)
          .zip(original).forall { case (actual, expected) => actual eq expected })
      }
    }
  }

  test("single-formal registration cannot append to an atomic inventory") {
    emitted { child =>
      val original = ExternalFormalParameterRegistry.completeTypedBindingsOf(child)
      val extra = ElaborationIntegerParameter("EXTRA", 5, 1, 8)
      val failure = intercept[ParameterizedVerilogException] {
        ExternalFormalParameterRegistry.retainTypedComponent(child, extra,
          original.head.binding.actual, Some("<duplicate-batch-slot>"))
      }
      assert(failure.code == "SPINAL-ELAB-FORMAL-TYPED-TOKEN-DUPLICATE")
      assert(ExternalFormalParameterRegistry.completeTypedBindingsOf(child)
        .zip(original).forall { case (actual, expected) => actual eq expected })
    }
  }

  test("publication rejects a cleared inventory without restoring it before emission") {
    val failure = intercept[Exception] {
      emitted { child =>
        instanceMap.update(new ExternalFormalComponentIdentityRef(child, null), Vector.empty)
      }
    }
    assert(failure.getMessage.contains("SPINAL-ELAB-FORMAL-INVENTORY-IDENTITY-CONFLICT"),
      failure.getMessage)
  }

  test("publication authenticates an otherwise concrete child whose complete inventory was cleared") {
    val failure = intercept[Exception] {
      emittedComponent(new ConcreteTop(child =>
        instanceMap.update(new ExternalFormalComponentIdentityRef(child, null), Vector.empty)))
    }
    assert(failure.getMessage.contains("SPINAL-ELAB-FORMAL-INVENTORY-IDENTITY-CONFLICT"),
      failure.getMessage)
  }

  test("invalid or duplicate domains reject the complete call before constructing a child") {
    val actual = HdlInt.param("WIDTH", 5, 1, 8).asElabInt
    val valid = ElabFormalComponent.Parameter(actual, "WIDTH", 1, 8)
    var constructed = false
    val invalid = intercept[ParameterizedVerilogException] {
      ElabFormalComponent.parameters(Vector(valid,
        ElabFormalComponent.Parameter(actual, "OTHER", 1, 4))) { _ =>
        constructed = true
        new Component {}
      }
    }
    assert(invalid.code == "SPINAL-ELAB-FORMAL-DOMAIN-INVALID")
    val duplicate = intercept[ParameterizedVerilogException] {
      ElabFormalComponent.parameters(Vector(valid, valid)) { _ =>
        constructed = true
        new Component {}
      }
    }
    assert(duplicate.code == "SPINAL-ELAB-FORMAL-INVENTORY-DUPLICATE")
    assert(!constructed)
  }

  test("a descriptive foreign root cannot authorize one slot of a typed inventory") {
    val original = HdlInt.param("WIDTH", 5, 1, 8).asElabInt
    val source = original.bits.expression.get
    val foreign = source.copy(parameterRoots = Vector(ElaborationIntegerParameterRoot.fresh("WIDTH", None)))
    var constructed = false
    val failure = intercept[ParameterizedVerilogException] {
      ElabFormalComponent.parameters(Vector(ElabFormalComponent.Parameter(
        ElabInt.fromExpression(foreign), "WIDTH", 1, 8))) { _ =>
        constructed = true
        new Component {}
      }
    }
    assert(failure.code == "SPINAL-ELAB-DOMAIN-EXACT-AUTHORITY-MISSING", failure.getMessage)
    assert(!constructed)
  }
}
