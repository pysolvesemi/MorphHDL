package spinal.core.internals

import java.nio.file.Files
import morphhdl.frontend.HdlInt
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib._

/** Composite tests consume the real native algorithm and its recorded statements. */
class TypedBalancedReductionCompositeTests extends AnyFunSuite {
  private def native[T <: Data]: ElabBalancedReduction.Native[T] =
    (values, operation, bridge) => new TraversableOnceAnyPimped[T](values).reduceBalancedTree(operation, bridge)

  private def withRecords(maximum: Int = 5)(body: Vec[BalancedCompositeRecord] => Unit): Unit = {
    val key = HdlInt.param("KEY_W", 5, 1, 32)
    val tag = HdlInt.param("TAG_W", 5, 1, 32)
    val coordinate = HdlInt.param("COORD_W", 5, 1, 32)
    SpinalConfig(targetDirectory = Files.createTempDirectory("balanced-composite-").toString,
      headerWithDate = false, headerWithRepoHash = false).generateVerilog(new Component {
      val packed = in Bits(20 * maximum bits)
      val records = Vec(BalancedCompositeRecord(key, tag, coordinate), HdlInt.param("COUNT", 1, 1, maximum))
      records.vec.zipWithIndex.foreach { case (record, index) =>
        record.assignFromBits(packed(index * 20, 20 bits))
      }
      body(records)
      val anchor = out Bool()
      anchor := False
    })
  }

  private def choose(a: BalancedCompositeRecord, b: BalancedCompositeRecord): BalancedCompositeRecord =
    Mux(a.key <= b.key, a, b)

  private def register(value: BalancedCompositeRecord): BalancedCompositeRecord = {
    val result = cloneOf(value)
    result.setAsReg()
    result := value
    result.key.init(U(0))
    result.tag.init(B(0))
    result.x.init(U(0))
    result.y.init(U(0))
    result
  }

  private def capture(records: Vec[BalancedCompositeRecord],
      operation: (BalancedCompositeRecord, BalancedCompositeRecord) => BalancedCompositeRecord = choose _,
      bridge: (BalancedCompositeRecord, Int) => BalancedCompositeRecord = (value: BalancedCompositeRecord, _: Int) => value
  ): TypedBalancedReductionCompositeReplay.Certificate[BalancedCompositeRecord] =
    TypedBalancedReductionCompositeReplay.capture(records, operation, bridge, native[BalancedCompositeRecord])

  private def detail(error: Throwable): String =
    if (error == null) "" else Option(error.getMessage).getOrElse("") + "\n" + detail(error.getCause)

  private def reject(code: String)(body: Vec[BalancedCompositeRecord] => Unit): Unit = {
    val error = intercept[Exception] { withRecords()(body) }
    assert(detail(error).contains(code), detail(error))
  }

  test("complete records retain independent width authorities and native odd-tail topology") {
    withRecords() { records =>
      val certificate = capture(records)
      val leaves = certificate.captured.shape.elementLeaves
      assert(leaves.map(_.path) == Vector("key", "tag", "x", "y"))
      assert(leaves.map(_.width.verilog) == Vector("KEY_W", "TAG_W", "COORD_W", "COORD_W"))
      assert(leaves.head.width.parameters.head ne leaves(1).width.parameters.head)
      assert(certificate.captured.rows.count(_.operator.isDefined) == 4)
      assert(certificate.captured.rows.map(_.level) == Vector(0, 0, 0, 1, 1, 2))
      assert(certificate.captured.rows.filter(_.operator.isEmpty).map(row => (row.level, row.index)) == Vector((0, 2), (1, 1)))
      certificate.requireFreshness()
    }
  }

  test("ordinary Vecs cannot forge recursive packed transport authority") {
    assertDoesNotCompile("spinal.core.ParameterizedVec.claimRecursiveTransport(null)")
    withRecords() { records =>
      assert(!TypedBalancedReductionBackend.ownsRecursiveTransport(records))
    }
  }

  test("singleton defaults can replay every admitted count without rerunning Scala callbacks") {
    withRecords() { records =>
      var operators = 0
      var bridges = 0
      val certificate = capture(records, (a, b) => { operators += 1; choose(a, b) },
        (value, _) => { bridges += 1; value })
      for (count <- 1 to 5) {
        val result = certificate.replay(records.vec.take(count).toVector)
        assert(result.flattenLocalName == records.vec.head.flattenLocalName)
        assert(result.flatten.map(_.getBitsWidth) == Seq(5, 5, 5, 5))
        assert(certificate.latencyFor(count) == 0)
        if (count == 1) assert(result eq records.vec.head)
      }
      assert(operators == 4 && bridges == 6)
    }
  }

  test("singleton-only composite domains never execute either callback") {
    withRecords(1) { records =>
      val certificate = capture(records,
        (_, _) => fail("singleton operator executed"),
        (_, _) => fail("singleton bridge executed"))
      assert(certificate.captured.rows.isEmpty && certificate.stages.isEmpty)
      assert(certificate.replay(records.vec.toVector) eq records.vec.head)
      assert(certificate.latencyFor(1) == 0)
    }
  }

  test("whole-record native selectors preserve complete tag and coordinate result leaves") {
    withRecords() { records =>
      val certificate = capture(records)
      certificate.captured.rows.flatMap(_.operator).foreach { callback =>
        assert(callback.result.flattenLocalName == Seq("key", "tag", "x", "y"))
        assert(callback.result.flatten.forall(leaf => callback.assignments.exists(_.finalTarget eq leaf)))
        assert(callback.assignments.count(_.source.isInstanceOf[BinaryMultiplexer]) == 4)
      }
    }
  }

  test("all record fields cross register bridges in lockstep including singleton and odd tails") {
    withRecords() { records =>
      val certificate = capture(records, bridge = (value, _) => register(value))
      assert(certificate.stages.map(_.registerCountPerRow) == Vector(1, 1, 1))
      for (count <- 1 to 5) {
        assert(certificate.latencyFor(count) == (BigInt(count) - 1).bitLength)
        val result = certificate.replay(records.vec.take(count).toVector)
        assert(result.flatten.size == 4)
      }
      certificate.captured.rows.foreach(row => assert(row.bridge.declarations.count(_.isReg) == 4))
    }
  }

  test("a fresh complete assignment bridge preserves identity latency") {
    withRecords() { records =>
      val certificate = capture(records, bridge = (value, _) => {
        val result = cloneOf(value)
        result := value
        result
      })
      assert(certificate.stages.forall(_.registerCountPerRow == 0))
      certificate.replay(records.vec.toVector)
    }
  }

  test("a callback missing any record field is rejected before replay") {
    reject("DRIVER") { records =>
      capture(records, (a, b) => {
        val result = cloneOf(a)
        result.key := a.key + b.key
        result.tag := a.tag ^ b.tag
        result.x := a.x + b.x
        result
      })
    }
  }

  test("a callback cannot remove a named field from its returned record shape") {
    reject("RESULT-SHAPE") { records =>
      capture(records, (a, b) => {
        val result = cloneOf(a)
        result := a
        result.elements.remove(3)
        result
      })
    }
  }

  test("a returned recursive Bundle shape cannot contain itself") {
    reject("SHAPE-CYCLE") { records =>
      capture(records, (a, b) => {
        val result = cloneOf(a)
        result := a
        result.elements += (("cycle", result))
        result
      })
    }
  }

  test("a callback cannot expose a result field as an input port") {
    reject("DECLARATION") { records =>
      capture(records, (a, b) => {
        val result = cloneOf(a)
        result := a
        result.tag.asInput()
        result
      })
    }
  }

  test("cyclic cross-field native graphs are rejected before replay") {
    reject("CYCLE") { records =>
      capture(records, (a, b) => {
        val result = cloneOf(a)
        result.key := a.key + b.key
        result.tag := a.tag ^ b.tag
        result.x := result.y + a.x
        result.y := result.x + b.y
        result
      })
    }
  }

  test("a partial tag assignment cannot stand in for complete record ownership") {
    reject("ASSIGNMENT-SHAPE") { records =>
      capture(records, (a, b) => {
        val result = cloneOf(a)
        result.key := a.key + b.key
        result.tag(0) := a.tag(0) ^ b.tag(0)
        result.x := a.x + b.x
        result.y := a.y + b.y
        result
      })
    }
  }

  test("a foreign scalar read cannot be hidden in an otherwise complete record") {
    reject("EXTERNAL-READ") { records =>
      val foreign = in Bits(5 bits)
      capture(records, (a, b) => {
        val result = cloneOf(a)
        result.key := a.key + b.key
        result.tag := a.tag ^ foreign
        result.x := a.x + b.x
        result.y := a.y + b.y
        result
      })
    }
  }

  test("writing any operand field rejects the whole callback") {
    reject("CALLBACK-EXTERNAL-WRITE") { records =>
      capture(records, (a, b) => { a.tag := b.tag; choose(a, b) })
    }
  }

  test("independent equal-default width roots cannot be substituted in a result") {
    reject("WIDTH") { records =>
      val foreignTag = HdlInt.param("TAG_W", 5, 1, 32)
      capture(records, (a, b) => {
        val result = BalancedCompositeRecord(a.keyWidth, foreignTag, a.coordWidth)
        result.key := a.key + b.key
        result.tag := a.tag ^ b.tag
        result.x := a.x + b.x
        result.y := a.y + b.y
        result
      })
    }
  }

  test("a bridge cannot exchange equally wide coordinate fields") {
    reject("BRIDGE-CROSS-FIELD") { records =>
      capture(records, bridge = (value, _) => {
        val result = cloneOf(value)
        result.key := value.key
        result.tag := value.tag
        result.x := value.y
        result.y := value.x
        result
      })
    }
  }

  test("one registered field cannot create a staggered record pipeline") {
    reject("BRIDGE-LATENCY") { records =>
      capture(records, bridge = (value, _) => {
        val result = cloneOf(value)
        result.key.setAsReg()
        result.key := value.key
        result.key.init(U(0))
        result.tag := value.tag
        result.x := value.x
        result.y := value.y
        result
      })
    }
  }

  test("a later callback cannot quietly reverse deterministic tie selection") {
    reject("OPERATOR-NONUNIFORM") { records =>
      var calls = 0
      capture(records, (a, b) => {
        calls += 1
        if (calls == 2) Mux(a.key < b.key, a, b) else choose(a, b)
      })
    }
  }

  test("native leaf rewiring after capture invalidates the complete certificate") {
    withRecords() { records =>
      val certificate = capture(records, (a, b) => {
        val result = cloneOf(a)
        result.key := a.key + b.key
        result.tag := a.tag ^ b.tag
        result.x := a.x + b.x
        result.y := a.y + b.y
        result
      })
      val addition = certificate.captured.rows.head.operator.get.assignments.collectFirst {
        case assignment if assignment.source.isInstanceOf[Operator.UInt.Add] => assignment.source.asInstanceOf[Operator.UInt.Add]
      }.get
      val original = addition.right
      addition.right = addition.left
      val error = intercept[IllegalArgumentException] { certificate.requireFreshness() }
      assert(error.getMessage.contains("GRAPH-CHANGED"), error.getMessage)
      addition.right = original
      certificate.requireFreshness()
    }
  }

  test("recursive nested records retain UInt SInt Bits Bool and complete leaf paths") {
    val uw = HdlInt.param("U_W", 5, 1, 32)
    val sw = HdlInt.param("S_W", 5, 1, 32)
    val bw = HdlInt.param("BITS_W", 5, 1, 32)
    val tw = HdlInt.param("TAG_W", 5, 1, 32)
    SpinalConfig(targetDirectory = Files.createTempDirectory("balanced-nested-composite-").toString)
      .generateVerilog(new Component {
        val words = Vec(BalancedCompositeNested(uw, sw, bw, tw), HdlInt.param("COUNT", 1, 1, 3))
        words.vec.foreach(_.flatten.foreach {
          case value: UInt => value := 0
          case value: SInt => value := 0
          case value: Bits => value := 0
          case value: Bool => value := False
        })
        val certificate = TypedBalancedReductionCompositeReplay.capture(words,
          (a: BalancedCompositeNested, b: BalancedCompositeNested) => Mux(a.payload.unsigned <= b.payload.unsigned, a, b),
          (value: BalancedCompositeNested, _: Int) => value, native[BalancedCompositeNested])
        assert(certificate.captured.shape.elementLeaves.size == 17)
        val paths = certificate.captured.shape.elementLeaves.map(_.path)
        assert(paths.contains("payload_unsigned") && paths.contains("lanes_1_signed") && paths.contains("grid_1_1"))
        val result = certificate.replay(words.vec.toVector)
        assert(result.flatten.map(_.getTypeObject).toSet == Set(TypeUInt, TypeSInt, TypeBits, TypeBool))
      })
  }
  private def withCounted(body: Vec[BalancedCompositeCountedRecord] => Unit): Unit = {
    val uw = HdlInt.param("U_W", 5, 1, 32)
    val sw = HdlInt.param("S_W", 5, 1, 32)
    val bw = HdlInt.param("BITS_W", 5, 1, 32)
    val tw = HdlInt.param("TAG_W", 5, 1, 32)
    val inner = HdlInt.param("INNER", 1, 1, 3)
    val rows = HdlInt.param("GRID_R", 1, 1, 3)
    val columns = HdlInt.param("GRID_C", 1, 1, 3)
    SpinalConfig(targetDirectory = Files.createTempDirectory("balanced-symbolic-nested-").toString)
      .generateVerilog(new Component {
        val values = Vec(BalancedCompositeCountedRecord(uw, sw, bw, tw, inner, rows, columns),
          HdlInt.param("COUNT", 1, 1, 3))
        values.vec.foreach(_.flatten.foreach {
          case leaf: UInt => leaf := 0
          case leaf: SInt => leaf := 0
          case leaf: Bits => leaf := 0
          case leaf: Bool => leaf := False
        })
        body(values)
      })
  }

  test("nested Vec dimensions retain independent count roots beside independent leaf widths") {
    withCounted { values =>
      val certificate = TypedBalancedReductionCompositeReplay.capture(values,
        (a: BalancedCompositeCountedRecord, b: BalancedCompositeCountedRecord) => Mux(a.key <= b.key, a, b),
        (value: BalancedCompositeCountedRecord, _: Int) => value, native[BalancedCompositeCountedRecord])
      val expressions = certificate.captured.shape.elementLayout.expressions.map(_.verilog).toSet
      assert(Set("INNER", "GRID_R", "GRID_C", "U_W", "S_W", "BITS_W", "TAG_W").subsetOf(expressions))
      assert(certificate.captured.shape.elementLeaves.size == 23)
      for (count <- 1 to 3) {
        val result = certificate.replay(values.vec.take(count).toVector)
        assert(ParameterizedVec.shapeOf(result.samples).get.depth.verilog == "INNER")
        assert(ParameterizedVec.shapeOf(result.grid).get.depth.verilog == "GRID_R")
        assert(ParameterizedVec.shapeOf(result.grid.vec.head).get.depth.verilog == "GRID_C")
      }
    }
  }

  test("an always active nested lane cannot depend on a lane absent at smaller inner counts") {
    val error = intercept[Exception] {
      withCounted { values =>
        TypedBalancedReductionCompositeReplay.capture(values,
          (a: BalancedCompositeCountedRecord, b: BalancedCompositeCountedRecord) => {
            val result = cloneOf(a)
            result.key := a.key + b.key
            result.tag := a.tag ^ b.tag
            result.samples.vec.zipWithIndex.foreach { case (sample, index) =>
              sample.unsigned := a.samples.vec(if (index == 0) 2 else index).unsigned
              sample.signed := a.samples.vec(index).signed
              sample.bitsValue := a.samples.vec(index).bitsValue
              sample.valid := a.samples.vec(index).valid
            }
            result.grid.vec.zipWithIndex.foreach { case (row, i) =>
              row.vec.zipWithIndex.foreach { case (flag, j) => flag := a.grid.vec(i).vec(j) }
            }
            result
          }, (value: BalancedCompositeCountedRecord, _: Int) => value, native[BalancedCompositeCountedRecord])
      }
    }
    assert(detail(error).contains("INACTIVE-DEPENDENCY"), detail(error))
  }


  test("scalar widening and composite selection publish together with independent exact results") {
    val directory = Files.createTempDirectory("balanced-mixed-publication-")
    val rtl = directory.resolve("BalancedMixedPublication.v")
    val config = SpinalConfig(targetDirectory = directory.toString,
      headerWithDate = false)
    config.netlistFileName = rtl.getFileName.toString
    val width = HdlInt.param("WIDTH", 3, 1, 5)
    val count = HdlInt.param("COUNT", 1, 1, 5)
    val keyWidth = HdlInt.param("KEY_W", 3, 1, 5)
    val tagWidth = HdlInt.param("TAG_W", 4, 1, 5)
    val coordinateWidth = HdlInt.param("COORD_W", 2, 1, 5)
    morphhdl.MorphVerilog(config) {
      new Component {
        setDefinitionName("BalancedMixedPublication")
        val words = in(Vec(UInt(width bits), count)).setName("words")
        val records = in(Vec(BalancedCompositeRecord(keyWidth, tagWidth, coordinateWidth), count))
          .setName("records")
        val product = out(UInt()).setName("product")
        val selected = out(BalancedCompositeRecord(keyWidth, tagWidth, coordinateWidth)).setName("selected")
        val sum = out(UInt()).setName("sum")
        // Both sides of the composite record exercise shared capture ordinals
        // and sequential template extraction while preserving separate layouts.
        product := words.reduceBalancedTree((a: UInt, b: UInt) => a * b)
        selected := records.reduceBalancedTree((a: BalancedCompositeRecord, b: BalancedCompositeRecord) =>
          Mux(a.key <= b.key, a, b))
        sum := words.reduceBalancedTree((a: UInt, b: UInt) => a +^ b)
      }
    }
    val text = new String(Files.readAllBytes(rtl), java.nio.charset.StandardCharsets.UTF_8)
    for (ordinal <- 1 to 3)
      assert(text.contains(s"morphhdl_balanced_${ordinal}_stage_0"), text)
    for (name <- Vector("product", "sum")) {
      val port = text.linesIterator.find(line => line.contains("output") &&
        ("\\b" + name + "\\b").r.findFirstIn(line).nonEmpty).getOrElse(fail(text))
      assert(port.contains("WIDTH") && port.contains("COUNT"), port)
    }
    def run(arguments: Seq[String]): String = {
      val output = new StringBuilder
      val status = scala.sys.process.Process(arguments).!(scala.sys.process.ProcessLogger(
        line => output.append(line).append('\n'), line => output.append(line).append('\n')))
      assert(status == 0, arguments.mkString(" ") + "\n" + output)
      output.toString
    }
    // The selected record uses unequal independent leaf widths. A tie in the
    // last pair must retain the earlier record's tag and both coordinates.
    for ((width, count) <- Vector(3 -> 1, 3 -> 3, 5 -> 5)) {
      run(Seq("verilator", "--lint-only", "--language", "1364-2001", "--top-module",
        "BalancedMixedPublication", s"-GWIDTH=$width", s"-GCOUNT=$count", rtl.toString))
      val values = Vector.tabulate(count)(index => BigInt(index + 2))
      val keyWidth = 3
      val tagWidth = 4
      val coordinateWidth = 2
      val leafWidth = keyWidth + tagWidth + 2 * coordinateWidth
      val recordValues = Vector.tabulate(count) { index =>
        val key = if (index >= count - 2) 0 else count - index
        val tag = index + 1
        val x = index % 4
        val y = (index + 2) % 4
        Vector(key, tag, x, y)
      }
      val winner = recordValues.minBy(_.head)
      val packedWords = values.zipWithIndex.foldLeft(BigInt(0)) {
        case (packed, (value, index)) => packed | (value << (width * index))
      }
      val packedRecords = recordValues.zipWithIndex.foldLeft(BigInt(0)) {
        case (packed, (record, index)) =>
          val leaf = BigInt(record(0)) | (BigInt(record(1)) << keyWidth) |
            (BigInt(record(2)) << (keyWidth + tagWidth)) |
            (BigInt(record(3)) << (keyWidth + tagWidth + coordinateWidth))
          packed | (leaf << (leafWidth * index))
      }
      val productWidth = width * count
      val sumWidth = width + BigInt(count - 1).bitLength
      val bench = directory.resolve(s"mixed_w${width}_n$count.v")
      val source = s"""module mixed_tb;
  reg [${width * count - 1}:0] words;
  reg [${leafWidth * count - 1}:0] records;
  wire [${productWidth - 1}:0] product;
  wire [${sumWidth - 1}:0] sum;
  wire [2:0] selected_key;
  wire [3:0] selected_tag;
  wire [1:0] selected_x, selected_y;
  BalancedMixedPublication #(.WIDTH($width), .COUNT($count)) dut(
    .words(words), .records(records), .product(product), .sum(sum),
    .selected_key(selected_key), .selected_tag(selected_tag), .selected_x(selected_x), .selected_y(selected_y));
  initial begin
    words = ${width * count}'h${packedWords.toString(16)};
    records = ${leafWidth * count}'h${packedRecords.toString(16)};
    #1;
    if (product !== ${productWidth}'h${values.product.toString(16)} || sum !== ${sumWidth}'h${values.sum.toString(16)} ||
        selected_key !== 3'd${winner(0)} || selected_tag !== 4'd${winner(1)} ||
        selected_x !== 2'd${winner(2)} || selected_y !== 2'd${winner(3)}) begin
      $$display("MIXED_FAIL"); $$finish;
    end
    $$display("MIXED_PASS"); $$finish;
  end
endmodule
"""
      Files.write(bench, source.getBytes(java.nio.charset.StandardCharsets.UTF_8))
      val binary = directory.resolve(s"mixed_w${width}_n$count.vvp")
      run(Seq("iverilog", "-g2001", "-s", "mixed_tb", "-o", binary.toString, rtl.toString, bench.toString))
      val output = run(Seq("vvp", binary.toString))
      assert(output.contains("MIXED_PASS") && !output.contains("MIXED_FAIL"), output)
    }
  }
}
