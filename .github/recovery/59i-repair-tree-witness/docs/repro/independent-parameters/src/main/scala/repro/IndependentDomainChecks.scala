package repro

import spinal.core._
import morphhdl.MorphVerilog
import morphhdl.frontend.HdlInt

/** Public-API domain classification regression, compiled with both plugins.
  *
  * The relation examples intentionally have small domains: an implementation
  * may prove them compositionally or enumerate them under its existing cap.
  * Mixed predicates must be Unknown, not accepted from their default witness.
  * These assertions do not replace the native forged-evidence, branch-owner,
  * child-binding, or publication suites.
  */
object IndependentDomainChecks extends App {
  require(args.length == 1, "Expected output directory")

  def bounds(value: ElabInt, lo: Int, hi: Int, label: String): Unit = {
    assert(value.minimum == BigInt(lo), label + ": minimum")
    assert(value.maximum == BigInt(hi), label + ": maximum")
  }
  def always(value: ElabBool, label: String): Unit = {
    assert(value.isAlwaysTrue, label + ": expected universal truth")
    assert(!value.isAlwaysFalse, label + ": cannot be universally false")
    assert(!value.isSymbolic, label + ": cannot be mixed")
  }
  def never(value: ElabBool, label: String): Unit = {
    assert(value.isAlwaysFalse, label + ": expected universal falsehood")
    assert(!value.isAlwaysTrue, label + ": cannot be universally true")
    assert(!value.isSymbolic, label + ": cannot be mixed")
  }
  def mixed(value: ElabBool, label: String): Unit = {
    assert(value.isSymbolic, label + ": expected a mixed tuple domain")
    assert(!value.isAlwaysTrue, label + ": do not approve the default tuple")
    assert(!value.isAlwaysFalse, label + ": do not reject every tuple")
  }

  class DomainChecks(a: ElabInt, b: ElabInt, c: ElabInt,
                     payload: ElabInt, lanes: ElabInt) extends Component {
    setDefinitionName("IndependentDomainChecks")
    // Equal default values do not make A and B the same declaration root.
    bounds(a + b, 2, 8, "independent sum")
    bounds(a - b, -3, 3, "independent difference")
    mixed(a.elabEq(b), "same defaults, independent roots")

    bounds(a - a, 0, 0, "same-root subtraction")
    bounds((a + 1) - (a + 1), 0, 0, "equivalent derived subtraction")
    always(((a + 1) - 1).elabEq(a), "derived identity")
    always((a + a).elabEq(a * 2), "equivalent same-root expressions")
    always((a + b).elabEq(b + a), "equivalent independent-root expressions")

    // Left roots {A, B}; right roots {A, C}. A must be joined by identity,
    // not treated as two independent values in an operand Cartesian product.
    bounds((a + b) - (a + c), -3, 3, "partial root overlap")
    always(((a + b) - (a + c)).elabEq(b - c), "overlap cancellation")
    bounds((a + b) - (a + b), 0, 0, "same multiroot expression")

    always((a > 0) && (b > 0), "independent positive predicates")
    never((a < 1) || (b < 1), "independent impossible predicates")
    mixed((a <= 2) && (b > 2), "independent mixed conjunction")
    // The default (PAYLOAD_BITS, LIVE_LANES) = (2, 2) is legal, but
    // (1, 2) is illegal. LIVE_LANES == 1 admits every payload in [1, 8].
    mixed(!(lanes > 1) || (payload >= lanes), "conditional payload/lane legality")
    always((a > 2) || !(a > 2), "same-root Boolean complement")
    never((a > 2) && !(a > 2), "same-root Boolean contradiction")

    val record = in Bits((a + b) bits)
    val observed = out Bool()
    observed := record.orR
  }

  val config = SpinalConfig(
    targetDirectory = args(0),
    oneFilePerComponent = false,
    headerWithDate = false,
    headerWithRepoHash = true
  )
  config.netlistFileName = "IndependentDomainChecks.v"
  val report = MorphVerilog(config) {
    new DomainChecks(
      HdlInt.param("A", default = 2, min = 1, max = 4).asElabInt,
      HdlInt.param("B", default = 2, min = 1, max = 4).asElabInt,
      HdlInt.param("C", default = 2, min = 1, max = 4).asElabInt,
      HdlInt.param("PAYLOAD_BITS", default = 2, min = 1, max = 8).asElabInt,
      HdlInt.param("LIVE_LANES", default = 2, min = 1, max = 4).asElabInt
    )
  }
  println(report.generatedSourcesPaths.mkString("\n"))
}
