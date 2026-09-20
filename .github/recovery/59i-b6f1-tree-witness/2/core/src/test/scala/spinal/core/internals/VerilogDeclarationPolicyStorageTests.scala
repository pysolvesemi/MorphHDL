package spinal.core.internals

import java.lang.reflect.Modifier
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import VerilogBase._

/** These tests exercise the real native printer's policy seam. The full binary
  * qualification additionally compares its complete API with the upstream JAR.
  */
class VerilogDeclarationPolicyStorageTests extends AnyFunSuite {
  private class Printer extends VerilogBase with DeclarationPolicyOwner

  private class Policy(val owner: VerilogBase, transport: Boolean) extends DeclarationPolicy {
    override def signed(occurrence: DeclarationOccurrence): Boolean = {
      require(occurrence.emitter eq owner)
      true
    }
    override def wrapperRange(occurrence: DeclarationOccurrence): Option[String] = None
    override def unsignedTransport(expression: Expression): Boolean = transport
  }

  test("the historical VerilogBase interface has no abstract policy accessors") {
    val abstractPolicyMethods = classOf[VerilogBase].getDeclaredMethods.filter { method =>
      Modifier.isAbstract(method.getModifiers) && method.getName.contains("declarationPolicy")
    }
    assert(abstractPolicyMethods.isEmpty, abstractPolicyMethods.map(_.toString).mkString("\n"))
  }

  test("ordinary native implementations remain unbound without optional storage") {
    val printer = new VerilogBase {}
    assert(!printer.hasDeclarationPolicy)
    assert(!printer.needsUnsignedTransport(null))
    assert(!printer.literalIsSigned(null))
    assert(printer.emitClockEdge("clock", RISING) == "posedge clock")
    assert(printer.theme.tab == "  ")
  }

  test("binding to an ordinary legacy implementation fails rather than using shared storage") {
    val printer = new VerilogBase {}
    val error = intercept[IllegalArgumentException] {
      printer.bindDeclarationPolicy(new Policy(printer, true))
    }
    assert(error.getMessage.contains("requires DeclarationPolicyOwner"))
    assert(!printer.hasDeclarationPolicy)
  }

  test("a null policy does not consume the emitter's one binding") {
    val printer = new Printer
    intercept[IllegalArgumentException](printer.bindDeclarationPolicy(null))
    assert(!printer.hasDeclarationPolicy)
    printer.bindDeclarationPolicy(new Policy(printer, true))
    assert(printer.hasDeclarationPolicy)
    assert(printer.needsUnsignedTransport(null))
  }

  test("rebinding the same or another policy is rejected without replacing the first policy") {
    val printer = new Printer
    val policy = new Policy(printer, true)
    printer.bindDeclarationPolicy(policy)
    intercept[IllegalArgumentException](printer.bindDeclarationPolicy(policy))
    intercept[IllegalArgumentException](printer.bindDeclarationPolicy(new Policy(printer, false)))
    intercept[IllegalArgumentException](printer.bindDeclarationPolicy(null))
    assert(printer.needsUnsignedTransport(null))
  }

  test("equal emitter objects still have distinct instance-owned policy slots") {
    class EqualPrinter extends Printer {
      override def equals(other: Any): Boolean = other.isInstanceOf[EqualPrinter]
      override def hashCode(): Int = 0
    }
    val first, second = new EqualPrinter
    assert(first == second)
    first.bindDeclarationPolicy(new Policy(first, true))
    assert(!second.hasDeclarationPolicy)
    second.bindDeclarationPolicy(new Policy(second, false))
    assert(first.needsUnsignedTransport(null))
    assert(!second.needsUnsignedTransport(null))
    assert(!(new Printer).hasDeclarationPolicy)
  }

  test("a policy follows its emitter across threads rather than the calling thread") {
    val printer = new Printer
    printer.bindDeclarationPolicy(new Policy(printer, true))
    val error = new AtomicReference[Throwable]()
    val worker = new Thread(new Runnable {
      override def run(): Unit = try {
        assert(printer.hasDeclarationPolicy)
        assert(printer.needsUnsignedTransport(null))
        assert(!(new Printer).hasDeclarationPolicy)
      } catch { case failure: Throwable => error.set(failure) }
    })
    worker.setDaemon(true)
    worker.start()
    worker.join(10000)
    assert(!worker.isAlive)
    if (error.get() != null) throw error.get()
  }

  test("concurrent binding installs exactly one policy on the same emitter") {
    val printer = new Printer
    val ready = new CountDownLatch(2)
    val start = new CountDownLatch(1)
    val installed = new AtomicInteger()
    val rejected = new AtomicInteger()
    val error = new AtomicReference[Throwable]()
    val workers = (0 until 2).map { _ => new Thread(new Runnable {
      override def run(): Unit = try {
        ready.countDown()
        assert(start.await(10, TimeUnit.SECONDS))
        try {
          printer.bindDeclarationPolicy(new Policy(printer, true))
          installed.incrementAndGet()
        } catch { case _: IllegalArgumentException => rejected.incrementAndGet() }
      } catch { case failure: Throwable => error.set(failure) }
    }) }
    workers.foreach { worker => worker.setDaemon(true); worker.start() }
    try assert(ready.await(10, TimeUnit.SECONDS)) finally start.countDown()
    workers.foreach(_.join(10000))
    assert(workers.forall(!_.isAlive))
    if (error.get() != null) throw error.get()
    assert(installed.get() == 1)
    assert(rejected.get() == 1)
    assert(printer.needsUnsignedTransport(null))
  }

  test("the production PhaseVerilog explicitly supplies the optional storage capability") {
    assert(classOf[DeclarationPolicyOwner].isAssignableFrom(classOf[PhaseVerilog]))
  }
}
