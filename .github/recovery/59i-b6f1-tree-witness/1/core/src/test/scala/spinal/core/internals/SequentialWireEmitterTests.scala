package spinal.core.internals

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

class SequentialWireEmitterTests extends AnyFunSuite {
  private object KeepExpression extends SpinalTag
  private final class TaggedResize extends ResizeUInt with SpinalTagReady

  private def emit(enabled: Boolean, shape: String): String = {
    val dir = Files.createTempDirectory("sequential-emitter-")
    var source: UInt = null
    var other: UInt = null
    var state: UInt = null
    try {
      val base = SpinalConfig(targetDirectory = dir.toString, headerWithDate = false)
      // Insert the carrier at the actual late boundary: an earlier native pass
      // cannot see this expression, so this isolates the emitter obligation.
      base.phasesInserters += { phases: ArrayBuffer[Phase] =>
        val index = phases.indexWhere(_.isInstanceOf[PhaseVerilog])
        require(index >= 0)
        phases.insert(index, new Phase {
          override def hasNetlistImpact: Boolean = true
          override def impl(pc: PhaseContext): Unit = {
            val inner: Expression with WidthProvider = shape match {
              case "arithmetic" =>
                val add = new Operator.UInt.Add
                add.left = source
                add.right = other
                add
              case _ =>
                val resize = if (shape == "tagged") new TaggedResize else new ResizeUInt
                resize.input = source
                resize.size = if (shape == "narrow") 17 else 18
                if (shape == "tagged") resize.asInstanceOf[TaggedResize].addTag(KeepExpression)
                resize
            }
            // Reuse the exact inner identity in both priority assignments.
            state.foreachStatements {
              case assignment: DataAssignmentStatement =>
                val outer = new ResizeUInt
                outer.input = inner
                outer.size = 13
                assignment.source = outer
              case _ =>
            }
          }
        })
      }
      SpinalVerilog(VerilogEmitterExpressionInlining.configure(base, enabled)) {
        new Component {
          setDefinitionName("SequentialEmitterFixture")
          source = in UInt(18 bits)
          other = in UInt(18 bits)
          source.setName("source")
          other.setName("other")
          val enable, priority = in Bool()
          val result = out UInt(13 bits)
          state = Reg(UInt(13 bits)) init(0)
          state.setName("state")
          when(enable) { state := source.resized }
          when(priority) { state := source.resized }
          result := state
        }
      }
      new String(Files.readAllBytes(dir.resolve("SequentialEmitterFixture.v")), StandardCharsets.UTF_8)
    } finally {
      val paths = Files.walk(dir)
      try paths.iterator.asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists(_))
      finally paths.close()
    }
  }

  test("late shared identity-sized unsigned carriers inline at sequential slices") {
    val v = emit(enabled = true, shape = "identity")
    assert(!v.contains("assign _zz_"), v)
    assert(v.sliding("state <= source[12:0];".length).count(_ == "state <= source[12:0];") == 2, v)
    assert(v.contains("always @(posedge clk or posedge reset)"), v)
    assert(v.contains("state <= 13'h0;"), v)
  }
  test("disabled policy keeps historical late carriers") {
    val v = emit(enabled = false, shape = "identity")
    assert(v.contains("assign _zz_"), v)
  }
  for (shape <- Vector("arithmetic", "narrow", "tagged")) {
    test(s"late $shape select base is not mistaken for a direct exact alias") {
      val v = emit(enabled = true, shape = shape)
      assert(v.contains("assign _zz_"), v)
      assert(!v.contains("(source + other)["), v)
    }
  }
  test("direct select-base proof is exact in native kind width and metadata") {
    val dir = Files.createTempDirectory("sequential-emitter-types-")
    try {
      SpinalVerilog(SpinalConfig(targetDirectory = dir.toString)) {
        new Component {
          val u = in UInt(18 bits)
          val s = in SInt(18 bits)
          val b = in Bits(18 bits)
          val result = out Bits(18 bits)
          result := b
          def uint(input: Expression with WidthProvider, size: Int): ResizeUInt = {
            val r = new ResizeUInt; r.input = input; r.size = size; r
          }
          def proof(e: Expression) = VerilogEmitterExpressionInlining.directSelectBase(this, e)
          assert(proof(uint(u, 18)).contains(u))
          assert(proof(uint(uint(u, 18), 18)).contains(u))
          assert(proof(uint(u, 17)).isEmpty)
          assert(proof(uint(u, 19)).isEmpty)
          assert(proof(uint(s, 18)).isEmpty)
          assert(proof(uint(b, 18)).isEmpty)
          val signed = new ResizeSInt; signed.input = s; signed.size = 18
          assert(proof(signed).isEmpty)
          val bits = new ResizeBits; bits.input = b; bits.size = 18
          assert(proof(bits).contains(b))
          val tagged = new TaggedResize; tagged.input = u; tagged.size = 18
          tagged.addTag(KeepExpression)
          assert(proof(tagged).isEmpty)
          u.addAttribute("keep")
          assert(proof(uint(u, 18)).contains(u)) // Source identity is not erased.
        }
      }
    } finally {
      val paths = Files.walk(dir)
      try paths.iterator.asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists(_))
      finally paths.close()
    }
  }
}
