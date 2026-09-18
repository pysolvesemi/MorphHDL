package spinal.core.internals

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.IdentityHashMap
import scala.collection.mutable.ArrayBuffer
import morphhdl.MorphVerilog
import morphhdl.frontend.HdlInt
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib._

final case class BalancedLocalEnableRecord(uw: HdlInt, sw: HdlInt, bw: HdlInt) extends Bundle {
  val unsigned = UInt(uw bits)
  val signed = SInt(sw bits)
  val bitsValue = Bits(bw bits)
  val valid = Bool()
}

private final class BalancedLocalEnablePublic(width: HdlInt, count: HdlInt,
    moduleName: String, asynchronousLow: Boolean) extends Component {
  setDefinitionName(moduleName)
  val clk = in(Bool()).setName("clk")
  val reset = in(Bool()).setName("reset")
  val values = in(Vec(BalancedLocalEnableRecord(width, width, width), count)).setName("values")
  val result = out(BalancedLocalEnableRecord(width, width, width)).setName("result")
  val domain = ClockDomain(clock = clk, reset = reset, config = ClockDomainConfig(
    clockEdge = if (asynchronousLow) FALLING else RISING,
    resetKind = if (asynchronousLow) ASYNC else SYNC,
    resetActiveLevel = if (asynchronousLow) LOW else HIGH))
  val area = new ClockingArea(domain) {
    val reduced = values.reduceBalancedTree(
      (a: BalancedLocalEnableRecord, b: BalancedLocalEnableRecord) => {
        val r = cloneOf(a)
        r.unsigned := a.unsigned + b.unsigned
        r.signed := a.signed + b.signed
        r.bitsValue := a.bitsValue ^ b.bitsValue
        r.valid := a.valid | b.valid
        r
      },
      (value: BalancedLocalEnableRecord, _: Int) => {
        val r = cloneOf(value)
        r.unsigned := RegNextWhen(value.unsigned, value.valid) init U(3)
        r.signed := RegNextWhen(value.signed, value.bitsValue(0)) init S(-2)
        r.bitsValue := RegNextWhen(value.bitsValue, value.signed.msb) init B(1)
        r.valid := RegNextWhen(value.valid, value.unsigned(0) ^ value.bitsValue.msb) init True
        r
      })
    result := reduced
  }
}

class TypedBalancedReductionCompositeLocalEnableTests extends AnyFunSuite {
  private def native[T <: Data]: ElabBalancedReduction.Native[T] =
    (values, operation, bridge) => new TraversableOnceAnyPimped[T](values).reduceBalancedTree(operation, bridge)

  private def details(error: Throwable): String =
    if (error == null) "" else Option(error.getMessage).getOrElse("") + "\n" + details(error.getCause)

  test("same-composite cross-field controls replay with field-local data and exact latency") {
    val width = HdlInt.param("WIDTH", 5, 3, 16)
    val count = HdlInt.param("COUNT", 1, 1, 5)
    var stageCounts = Vector.empty[Int]
    var allLocal = false
    var latencies = Vector.empty[Int]
    var registerCounts = Vector.empty[Int]
    SpinalConfig(targetDirectory = Files.createTempDirectory("balanced-local-enable-certificate-").toString,
      headerWithDate = false, headerWithRepoHash = false).generateVerilog(new Component {
      val values = Vec(BalancedLocalEnableRecord(width, width, width), count)
      values.vec.foreach(_.flatten.foreach {
        case value: UInt => value := 0
        case value: SInt => value := 0
        case value: Bits => value := 0
        case value: Bool => value := False
      })
      val certificate = TypedBalancedReductionCompositeReplay.capture(values,
        (a: BalancedLocalEnableRecord, b: BalancedLocalEnableRecord) => {
          val r = cloneOf(a)
          r.unsigned := a.unsigned + b.unsigned
          r.signed := a.signed + b.signed
          r.bitsValue := a.bitsValue ^ b.bitsValue
          r.valid := a.valid | b.valid
          r
        },
        (value: BalancedLocalEnableRecord, _: Int) => {
          val r = cloneOf(value)
          r.unsigned := RegNextWhen(value.unsigned, value.valid) init U(3)
          r.signed := RegNextWhen(value.signed, value.bitsValue(0)) init S(-2)
          r.bitsValue := RegNextWhen(value.bitsValue, value.signed.msb) init B(1)
          r.valid := RegNextWhen(value.valid, value.unsigned(0) ^ value.bitsValue.msb) init True
          r
        }, native[BalancedLocalEnableRecord])
      for (activeCount <- 1 to 5) certificate.replay(values.vec.take(activeCount).toVector)
      stageCounts = certificate.stages.map(_.registerCountPerRow)
      allLocal = certificate.stages.forall(_.bridges.head.hasLocalEnables)
      latencies = (1 to 5).map(certificate.latencyFor).toVector
      registerCounts = certificate.captured.rows.map(_.bridge.declarations.count(_.isReg))
      certificate.requireFreshness()
    })
    assert(stageCounts == Vector(1, 1, 1))
    assert(allLocal)
    assert(latencies == (1 to 5).map(value => (BigInt(value) - 1).bitLength).toVector)
    assert(registerCounts.forall(_ == 4))
  }

  for ((name, asynchronousLow) <- Vector("sync_high" -> false, "async_low" -> true);
       split <- Vector(false, true)) {
    test("public local enables preserve reset semantics: " + name + ", split=" + split) {
      val directory = Files.createTempDirectory("balanced-local-enable-public-" + name + "-")
      val top = "BalancedLocalEnable_" + name
      val config = SpinalConfig(targetDirectory = directory.toString, bitVectorWidthMax = 4096,
        oneFilePerComponent = split, headerWithDate = false, headerWithRepoHash = false)
      if (!split) config.netlistFileName = top + ".v"
      MorphVerilog(config) {
        new BalancedLocalEnablePublic(HdlInt.param("WIDTH", 5, 3, 16),
          HdlInt.param("COUNT", 1, 1, 5), top, asynchronousLow)
      }
      val path = directory.resolve(top + ".v")
      assert(Files.isRegularFile(path))
      val rtl = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
      assert(rtl.contains("parameter") && rtl.contains("COUNT") && rtl.contains("WIDTH"), rtl)
      assert(rtl.contains("generate") && rtl.contains("always"), rtl)
      assert(rtl.contains(if (asynchronousLow) "negedge clk" else "posedge clk"), rtl)
      if (asynchronousLow) assert(rtl.contains("negedge reset"), rtl)
    }
  }

  test("external and registered peer controls remain rejected") {
    val width = HdlInt.param("WIDTH", 5, 3, 16)
    val count = HdlInt.param("COUNT", 1, 1, 3)
    val external = intercept[Exception] {
      SpinalConfig(targetDirectory = Files.createTempDirectory("balanced-local-enable-external-").toString,
        headerWithDate = false, headerWithRepoHash = false).generateVerilog(new Component {
        val values = Vec(BalancedLocalEnableRecord(width, width, width), count)
        values.vec.foreach(_.flatten.foreach {
          case value: UInt => value := 0
          case value: SInt => value := 0
          case value: Bits => value := 0
          case value: Bool => value := False
        })
        val foreign = Bool(); foreign := False
        TypedBalancedReductionCompositeReplay.capture(values,
          (a: BalancedLocalEnableRecord, b: BalancedLocalEnableRecord) => {
            val r = cloneOf(a)
            r.unsigned := a.unsigned + b.unsigned
            r.signed := a.signed + b.signed
            r.bitsValue := a.bitsValue ^ b.bitsValue
            r.valid := a.valid | b.valid
            r
          },
          (value: BalancedLocalEnableRecord, _: Int) => {
            val r = cloneOf(value)
            r.unsigned := RegNextWhen(value.unsigned, foreign) init U(3)
            r.signed := RegNextWhen(value.signed, value.bitsValue(0)) init S(-2)
            r.bitsValue := RegNextWhen(value.bitsValue, value.signed.msb) init B(1)
            r.valid := RegNextWhen(value.valid, value.unsigned(0)) init False
            r
          }, native[BalancedLocalEnableRecord])
      })
    }
    assert(details(external).contains("BRIDGE") ||
      details(external).contains("GRAPH-EXTERNAL-READ"), details(external))

    val registered = intercept[Exception] {
      SpinalConfig(targetDirectory = Files.createTempDirectory("balanced-local-enable-peer-").toString,
        headerWithDate = false, headerWithRepoHash = false).generateVerilog(new Component {
        val values = Vec(BalancedLocalEnableRecord(width, width, width), count)
        values.vec.foreach(_.flatten.foreach {
          case value: UInt => value := 0
          case value: SInt => value := 0
          case value: Bits => value := 0
          case value: Bool => value := False
        })
        TypedBalancedReductionCompositeReplay.capture(values,
          (a: BalancedLocalEnableRecord, b: BalancedLocalEnableRecord) => {
            val r = cloneOf(a)
            r.unsigned := a.unsigned + b.unsigned
            r.signed := a.signed + b.signed
            r.bitsValue := a.bitsValue ^ b.bitsValue
            r.valid := a.valid | b.valid
            r
          },
          (value: BalancedLocalEnableRecord, _: Int) => {
            val r = cloneOf(value)
            val peer = RegNext(value.valid) init False
            r.unsigned := RegNextWhen(value.unsigned, peer) init U(3)
            r.signed := RegNextWhen(value.signed, value.bitsValue(0)) init S(-2)
            r.bitsValue := RegNextWhen(value.bitsValue, value.signed.msb) init B(1)
            r.valid := RegNextWhen(value.valid, value.unsigned(0)) init False
            r
          }, native[BalancedLocalEnableRecord])
      })
    }
    assert(details(registered).contains("BRIDGE-CONTROL-REGISTER"), details(registered))
  }

  test("composite controls may read the current driver within each two-register data chain") {
    val width = HdlInt.param("WIDTH", 5, 3, 16)
    val count = HdlInt.param("COUNT", 1, 1, 3)
    nativeComponent {
      val values = Vec(BalancedLocalEnableRecord(width, width, width), count)
      values.vec.foreach(_.flatten.foreach {
        case value: UInt => value := 0
        case value: SInt => value := 0
        case value: Bits => value := 0
        case value: Bool => value := False
      })
      val certificate = TypedBalancedReductionCompositeReplay.capture(values,
        (a: BalancedLocalEnableRecord, b: BalancedLocalEnableRecord) => {
          val r = cloneOf(a)
          r.unsigned := a.unsigned + b.unsigned
          r.signed := a.signed + b.signed
          r.bitsValue := a.bitsValue ^ b.bitsValue
          r.valid := a.valid | b.valid
          r
        },
        (value: BalancedLocalEnableRecord, _: Int) => {
          val r = cloneOf(value)
          val u = RegNext(value.unsigned) init U(0)
          val s = RegNext(value.signed) init S(0)
          val b = RegNext(value.bitsValue) init B(0)
          val f = RegNext(value.valid) init False
          r.unsigned := RegNextWhen(u, u(0) ^ value.valid) init U(0)
          r.signed := RegNextWhen(s, s.msb ^ value.unsigned(0)) init S(0)
          r.bitsValue := RegNextWhen(b, b.msb ^ value.signed(0)) init B(0)
          r.valid := RegNextWhen(f, f ^ value.bitsValue(0)) init False
          r
        }, native[BalancedLocalEnableRecord])
      assert(certificate.stages.map(_.registerCountPerRow) == Vector(2, 2))
      assert(certificate.captured.rows.forall(_.bridge.declarations.count(_.isReg) == 8))
      assert(certificate.stages.forall(_.bridges.forall(_.hasLocalEnables)))
      for (n <- 1 to 3) {
        certificate.replay(values.vec.take(n).toVector)
        assert(certificate.latencyFor(n) == 2 * (BigInt(n) - 1).bitLength)
      }
      certificate.requireFreshness()
    }
  }

  test("local controls preserve independent field widths through every odd-tail replay") {
    val uw = HdlInt.param("UW", 5, 3, 16)
    val sw = HdlInt.param("SW", 7, 3, 16)
    val bw = HdlInt.param("BW", 3, 1, 8)
    val count = HdlInt.param("COUNT", 1, 1, 5)
    nativeComponent {
      val values = Vec(BalancedLocalEnableRecord(uw, sw, bw), count)
      values.vec.foreach(_.flatten.foreach {
        case value: UInt => value := 0
        case value: SInt => value := 0
        case value: Bits => value := 0
        case value: Bool => value := False
      })
      val certificate = TypedBalancedReductionCompositeReplay.capture(values,
        (a: BalancedLocalEnableRecord, b: BalancedLocalEnableRecord) => {
          val r = cloneOf(a)
          r.unsigned := a.unsigned + b.unsigned
          r.signed := a.signed + b.signed
          r.bitsValue := a.bitsValue ^ b.bitsValue
          r.valid := a.valid | b.valid
          r
        },
        (value: BalancedLocalEnableRecord, _: Int) => {
          val r = cloneOf(value)
          r.unsigned := RegNextWhen(value.unsigned, value.valid) init U(3)
          r.signed := RegNextWhen(value.signed, value.bitsValue(0)) init S(-2)
          r.bitsValue := RegNextWhen(value.bitsValue, value.signed.msb) init B(1)
          r.valid := RegNextWhen(value.valid, value.unsigned.msb) init False
          r
        }, native[BalancedLocalEnableRecord])
      assert(certificate.captured.shape.elementLeaves.map(_.width.verilog) == Vector("UW", "SW", "BW", "1"))
      for (n <- 1 to 5) {
        val result = certificate.replay(values.vec.take(n).toVector)
        assert(result.flatten.map(_.getBitsWidth).toVector == Vector(5, 7, 3, 1))
        assert(certificate.latencyFor(n) == (BigInt(n) - 1).bitLength)
      }
      certificate.requireFreshness()
    }
  }

  /** Construct actual native callbacks without replacing the production proof
    * or using a Python emulation of it. These assertions run before normalizing
    * native graphs, so they can compare the precise operand identities. */
  private def nativeComponent(body: => Unit): Unit = {
    SpinalConfig(targetDirectory = Files.createTempDirectory("local-enable-native-").toString,
      headerWithDate = false, headerWithRepoHash = false).generateVerilog(new Component {
      val anchor = out Bool()
      anchor := False
      body
    })
  }

  private def record(inputs: Vector[BaseType])(body: => BaseType): UnvalidatedBalancedCallback = {
    var result: BaseType = null
    val block = ParameterizedStructure.captureBlock(Component.current, None) { result = body }
    val statements = ArrayBuffer.empty[Statement]
    block.statements.foreach { statement =>
      statements += statement
      statement match {
        case tree: TreeStatement => tree.walkStatements(statements += _)
        case _ =>
      }
    }
    UnvalidatedBalancedCallback(0, inputs, result, block.declarations,
      statements.collect { case a: AssignmentStatement => a }.toVector, statements.toVector)
  }

  private def inputs(): Vector[BaseType] = {
    val word = UInt(5 bits); word := 0
    val peer = Bits(3 bits); peer := 0
    val flag = Bool(); flag := False
    Vector(word, peer, flag)
  }

  private def dataWrite(value: BaseType): DataAssignmentStatement = {
    val writes = ArrayBuffer.empty[DataAssignmentStatement]
    value.foreachStatements {
      case data: DataAssignmentStatement => writes += data
      case _ =>
    }
    assert(writes.size == 1)
    writes.head
  }

  private def enableCondition(value: BaseType): Expression =
    dataWrite(value).parentScope.parentStatement.asInstanceOf[WhenStatement].cond

  private def bitSources(condition: Expression): Vector[Expression] = {
    val found = ArrayBuffer.empty[Expression]
    val seen = new IdentityHashMap[Expression, java.lang.Boolean]()
    def visit(value: Expression): Unit = {
      if (seen.put(value, java.lang.Boolean.TRUE) != null) return
      value match {
        case access: BitVectorBitAccessFixed => found += access.source
        case leaf: BaseType => leaf.foreachStatements(a => visit(a.source))
        case other => other.foreachExpression(visit)
      }
    }
    visit(condition)
    found.toVector
  }

  private def rejected(code: String)(body: => Any): Unit = {
    val error = intercept[IllegalArgumentException](body)
    assert(error.getMessage.contains(code), error.getMessage)
  }

  for (original <- Vector(false, true)) {
    test("two-register bit enable retains " + (if (original) "original input" else "current data") + " identity") {
      nativeComponent {
        val source = inputs()
        val evidence = source.map(TypedBalancedReductionValueEvidence.input)
        val word = source(0).asInstanceOf[UInt]
        val callback = record(source) {
          val first = RegNextWhen(word, source(2).asInstanceOf[Bool]) init U(0)
          RegNextWhen(first, (if (original) word else first)(0)) init U(0)
        }
        val proof = TypedBalancedReductionBridgeReplay.certify(callback, evidence(0), evidence)
        val replacement = inputs()
        val replayed = proof.replayWithWidth(replacement(0), evidence(0).width,
          replacement, evidence.map(_.width))
        assert(proof.registerCount == 2)
        val previous = dataWrite(replayed).source.asInstanceOf[BaseType]
        val sources = bitSources(enableCondition(replayed))
        assert(sources.size == 1)
        assert(sources.head eq (if (original) replacement(0) else previous))
        assert(previous ne replacement(0))
        proof.validateFreshness()
      }
    }

    test("two-register Bool enable retains " + (if (original) "original input" else "current data") + " identity") {
      nativeComponent {
        val source = inputs()
        val evidence = source.map(TypedBalancedReductionValueEvidence.input)
        val flag = source(2).asInstanceOf[Bool]
        val callback = record(source) {
          val first = RegNext(flag) init False
          RegNextWhen(first, if (original) flag else first) init False
        }
        val proof = TypedBalancedReductionBridgeReplay.certify(callback, evidence(2), evidence)
        val replacement = inputs()
        val replayed = proof.replayWithWidth(replacement(2), evidence(2).width,
          replacement, evidence.map(_.width))
        val previous = dataWrite(replayed).source.asInstanceOf[BaseType]
        assert(enableCondition(replayed) eq (if (original) replacement(2) else previous))
        assert(previous ne replacement(2))
      }
    }
  }

  test("behavior equality cannot conflate current and original input enables") {
    nativeComponent {
      val source = inputs()
      val evidence = source.map(TypedBalancedReductionValueEvidence.input)
      val word = source(0).asInstanceOf[UInt]
      def capture(original: Boolean) = {
        val callback = record(source) {
          val first = RegNext(word) init U(0)
          RegNextWhen(first, (if (original) word else first)(0)) init U(0)
        }
        TypedBalancedReductionBridgeReplay.certify(callback, evidence(0), evidence)
      }
      val original = capture(true)
      val current = capture(false)
      assert(!original.sameBehavior(current))
      assert(!current.sameBehavior(original))
      assert(original.sameBehavior(capture(true)))
      assert(current.sameBehavior(capture(false)))
    }
  }

  test("mixed enables retain current driver, original self and original peer simultaneously") {
    nativeComponent {
      val source = inputs()
      val evidence = source.map(TypedBalancedReductionValueEvidence.input)
      val word = source(0).asInstanceOf[UInt]
      val peer = source(1).asInstanceOf[Bits]
      val callback = record(source) {
        val first = RegNext(word) init U(0)
        RegNextWhen(first, word(0) ^ first.msb ^ peer(1)) init U(0)
      }
      val proof = TypedBalancedReductionBridgeReplay.certify(callback, evidence(0), evidence)
      val replacement = inputs()
      val replayed = proof.replayWithWidth(replacement(0), evidence(0).width,
        replacement, evidence.map(_.width))
      val previous = dataWrite(replayed).source.asInstanceOf[BaseType]
      val sources = bitSources(enableCondition(replayed))
      assert(sources.size == 3)
      for (value <- Vector(replacement(0), replacement(1), previous))
        assert(sources.count(_ eq value) == 1)
    }
  }

  test("the original scalar entry point does not silently admit original-input cross-stage captures") {
    nativeComponent {
      val source = UInt(5 bits); source := 0
      val evidence = TypedBalancedReductionValueEvidence.input(source)
      val callback = record(Vector(source)) {
        val first = RegNext(source) init U(0)
        RegNextWhen(first, source(0)) init U(0)
      }
      rejected("BRIDGE-ENABLE") { TypedBalancedReductionBridgeReplay.certify(callback, evidence) }
    }
  }

  test("original composite controls are never captured external signals") {
    nativeComponent {
      val source = inputs()
      val evidence = source.map(TypedBalancedReductionValueEvidence.input)
      val foreign = Bool(); foreign := False
      val callback = record(source) {
        RegNextWhen(source(0).asInstanceOf[UInt], foreign) init U(0)
      }
      rejected("GRAPH-EXTERNAL-READ") {
        TypedBalancedReductionBridgeReplay.certify(callback, evidence(0), evidence)
      }
    }
  }

  test("duplicate or reordered captured control identities are rejected") {
    nativeComponent {
      val source = inputs()
      val evidence = source.map(TypedBalancedReductionValueEvidence.input)
      val callback = record(source) {
        RegNextWhen(source(0).asInstanceOf[UInt], source(2).asInstanceOf[Bool]) init U(0)
      }
      rejected("CONTROL-IDENTITY") {
        TypedBalancedReductionBridgeReplay.certify(callback, evidence(0), Vector(evidence(0), evidence(1), evidence(1)))
      }
      rejected("CONTROL-BINDING") {
        TypedBalancedReductionBridgeReplay.certify(callback, evidence(0), evidence.reverse)
      }
    }
  }

  test("a composite proof cannot be replayed through the single-scalar API") {
    nativeComponent {
      val source = inputs()
      val evidence = source.map(TypedBalancedReductionValueEvidence.input)
      val callback = record(source) {
        RegNextWhen(source(0).asInstanceOf[UInt], source(2).asInstanceOf[Bool]) init U(0)
      }
      val proof = TypedBalancedReductionBridgeReplay.certify(callback, evidence(0), evidence)
      rejected("CONTROL-ARITY") { proof.replay(source(0)) }
      rejected("CONTROL-ARITY") { proof.replayWithWidth(source(0), evidence(0).width) }
      val replacement = inputs()
      rejected("CONTROL-BINDING") {
        proof.replayWithWidth(replacement(0), evidence(0).width, replacement.dropRight(1), evidence.map(_.width))
      }
    }
  }

  test("peer fixed-bit bounds use the peer width rather than the data width") {
    nativeComponent {
      val source = inputs()
      val evidence = source.map(TypedBalancedReductionValueEvidence.input)
      val callback = record(source) {
        RegNextWhen(source(0).asInstanceOf[UInt], source(1).asInstanceOf[Bits](1)) init U(0)
      }
      val proof = TypedBalancedReductionBridgeReplay.certify(callback, evidence(0), evidence)
      val narrow = Bits(1 bits); narrow := 0
      val replacement = inputs().updated(1, narrow)
      rejected("ENABLE-WIDTH") {
        proof.replayWithWidth(replacement(0), evidence(0).width, replacement,
          evidence.map(_.width).updated(1, ElabInt.literal(1).expression))
      }
    }
  }

  test("peer control freshness is checked after proof reuse") {
    nativeComponent {
      val source = inputs()
      val evidence = source.map(TypedBalancedReductionValueEvidence.input)
      val callback = record(source) {
        RegNextWhen(source(0).asInstanceOf[UInt], source(1).asInstanceOf[Bits](0)) init U(0)
      }
      val proof = TypedBalancedReductionBridgeReplay.certify(callback, evidence(0), evidence)
      proof.validateFreshness()
      val peer = source(1).asInstanceOf[Bits]
      peer.setWidth(4)
      rejected("VALUE-EVIDENCE") { proof.validateFreshness() }
      peer.setWidth(3)
      proof.validateFreshness()
    }
  }

  test("mutated enable predicates and reset clocks cannot reuse a composite-control proof") {
    nativeComponent {
      val source = inputs()
      val evidence = source.map(TypedBalancedReductionValueEvidence.input)
      val callback = record(source) {
        RegNextWhen(source(0).asInstanceOf[UInt], source(2).asInstanceOf[Bool]) init U(0)
      }
      val proof = TypedBalancedReductionBridgeReplay.certify(callback, evidence(0), evidence)
      val when = callback.statements.collectFirst { case value: WhenStatement => value }.get
      val saved = when.cond
      when.cond = new BoolLiteral(false)
      rejected("GRAPH-CHANGED") { proof.validateFreshness() }
      when.cond = saved
      proof.validateFreshness()
      val register = callback.result.asInstanceOf[BaseType]
      val clock = register.clockDomain
      register.clockDomain = clock.copy(config = clock.config.copy(resetActiveLevel = LOW))
      rejected("GRAPH-CHANGED") { proof.validateFreshness() }
      register.clockDomain = clock
      proof.validateFreshness()
    }
  }
}
