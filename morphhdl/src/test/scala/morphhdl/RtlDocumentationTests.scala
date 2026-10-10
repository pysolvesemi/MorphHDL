package morphhdl

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

class RtlDocumentationTests extends AnyFunSuite {
  private def emit(morph: Boolean = false, unpacked: Boolean = false, auto: Boolean = false,
      scaladoc: Boolean = false, prune: Boolean = false, named: Boolean = false, split: Boolean = false)(top: => Component): String = {
    val dir = Files.createTempDirectory("rtl-documentation-")
    val base = RtlDocumentationOptions(SpinalConfig(targetDirectory = dir.toString, headerWithDate = false, removePruned = prune, oneFilePerComponent = split), auto, scaladoc)
    val config = if (morph) MorphAggregateOptions(base, preserveConstantVecs = true,
      preserveConstantLoops = true, vecLayout = if (unpacked) MorphAggregateOptions.UnpackedArray else MorphAggregateOptions.PackedVector) else base
    if (morph) MorphVerilog(if (named) MorphNamedFieldVectors.enable(config) else config)(top) else SpinalVerilog(config)(top)
    new String(Files.readAllBytes(dir.resolve("Top.v")), UTF_8)
  }
  // Keep quoted strings and attributes intact; remove only actual comments.
  private def tokens(source: String): Vector[String] = {
    def directive(comment: String): Boolean =
      "(?i)^(?:verilator|synthesis|synopsys|pragma|cadence|altera|xilinx)\\b.*".r.findFirstIn(comment).nonEmpty
    val out = new StringBuilder
    var i = 0
    while (i < source.length) {
      if (source.startsWith("//", i)) {
        val start = i
        while (i < source.length && source(i) != '\n') i += 1
        val comment = source.substring(start + 2, i).trim
        if (directive(comment)) out.append(source.substring(start, i))
        out.append(' ')
      } else if (source.startsWith("/*", i)) {
        val end = source.indexOf("*/", i + 2); assert(end >= 0)
        if (directive(source.substring(i + 2, end).trim)) out.append(source.substring(i, end + 2))
        out.append(' '); i = end + 2
      } else if (source(i) == '"') {
        out.append(source(i)); i += 1
        var done = false
        while (i < source.length && !done) {
          val c = source(i); out.append(c); i += 1
          if (c == '\\' && i < source.length) { out.append(source(i)); i += 1 }
          else if (c == '"') done = true
        }
        assert(done)
      } else { out.append(source(i)); i += 1 }
    }
    "\"(?:\\\\.|[^\"\\\\])*\"|[A-Za-z_$][A-Za-z0-9_$]*|[0-9]+|[^\\s]".r.findAllIn(out.result()).toVector
  }
  class Scalar(documented: Boolean) extends Component {
    setDefinitionName("Top")
    val data = in Bits(8 bits)
    val result = out Bits(8 bits)
    val count = Reg(Bits(8 bits)) init(0)
    count := data
    result := count
    if (documented) {
      definition.doc("Buffers incoming packets")
      data.doc("Input packet data")
      count.doc("Buffered word\r\nUnicode λ; literal */ // and \"quotes\"")
      count.doc("Second note")
    }
  }
  test("fluent scalar documentation preserves all non-comment tokens") {
    val plain = emit()(new Scalar(false))
    val documented = emit()(new Scalar(true))
    assert(tokens(plain) == tokens(documented))
    assert(documented.contains("// Input packet data\n"), documented)
    assert(documented.indexOf("// Buffered word") < documented.indexOf("count;"), documented)
    assert(documented.contains("// Unicode λ; literal */ // and \"quotes\"\n//") || documented.contains("// Second note"), documented)
    assert(documented.indexOf("Buffers incoming packets") < documented.indexOf("module Top"))
  }
  class Arrays(documented: Boolean) extends Component {
    setDefinitionName("Top")
    val x = in(Vec(Bits(8 bits), 3))
    val y = out(Vec(Bits(8 bits), 3))
    val values = Vec(Bits(8 bits), 3).dontSimplifyIt()
    values := x
    y := values
    if (documented) {
      x.doc("Incoming lanes")
      values.doc("Internal lanes")
      values(1).doc("Special lane")
    }
  }
  test("packed and unpacked publication retain aggregate and element ownership") {
    for (unpacked <- Seq(false, true)) {
      val plain = emit(morph = true, unpacked = unpacked)(new Arrays(false))
      val documented = emit(morph = true, unpacked = unpacked)(new Arrays(true))
      assert(tokens(plain) == tokens(documented))
      assert(documented.sliding("Incoming lanes".length).count(_ == "Incoming lanes") == 1, documented)
      assert(documented.sliding("Internal lanes".length).count(_ == "Internal lanes") == 1, documented)
      assert(documented.contains("Element 1, field 0: Special lane"), documented)
    }
  }
  test("documentation options copy flags and text validation is explicit") {
    val base = SpinalConfig()
    val enabled = RtlDocumentationOptions(base, true, false)
    assert(!RtlDocumentationOptions.of(base).captureScalaComments)
    assert(RtlDocumentationOptions.of(enabled).captureScalaComments)
    intercept[IllegalArgumentException](RtlDocumentation.text(null))
    intercept[IllegalArgumentException](RtlDocumentation.text("bad\u0000text"))
    assert(RtlDocumentation.text("a\r\nb\rc") == "a\nb\nc")
  }
  @doc("Annotated packet module")
  class Annotated extends Component {
    setDefinitionName("Top")
    @doc("Annotated input") val x = in Bits(8 bits)
    @doc("Annotated output") val y = out Bits(8 bits)
    @doc("Annotated register") val saved = Reg(Bits(8 bits)) init(0)
    saved := x
    y := saved
  }
  test("annotations attach to module ports and registers without changing initializer semantics") {
    val rtl = emit()(new Annotated)
    for (note <- Seq("Annotated packet module", "Annotated input", "Annotated output", "Annotated register"))
      assert(rtl.sliding(note.length).count(_ == note) == 1, rtl)
    assert(rtl.indexOf("Annotated packet module") < rtl.indexOf("module Top"), rtl)
  }
  class Child extends Component {
    val x = in Bits(8 bits)
    val y = out Bits(8 bits)
    y := x
  }
  class Parent extends Component {
    setDefinitionName("Top")
    val x = in Bits(8 bits)
    val y = out Bits(8 bits)
    @doc("First instance") val first = new Child
    val second = (new Child).doc("Second instance")
    first.x := x
    second.x := first.y
    y := second.y
    val unused = Bits(8 bits).doc("Removed signal note")
    unused := x
  }
  test("instance documentation stays local and removed signals leave no comment") {
    val rtl = emit(prune = true)(new Parent)
    assert(rtl.contains("First instance") && rtl.contains("Second instance"), rtl)
    assert(!rtl.contains("Removed signal note"), rtl)
    assert("module Child".r.findAllIn(rtl).size == 1, rtl)
  }

  case class Record() extends Bundle { val a = Bits(8 bits); val b = Bits(4 bits) }
  class Records(documented: Boolean) extends Component {
    setDefinitionName("Top")
    val x = in(Vec(Record(), 3))
    val y = out(Vec(Record(), 3))
    val values = Vec(Record(), 3).dontSimplifyIt()
    values := x
    y := values
    if (documented) {
      values.doc("Record bank")
      values(1).doc("Second record")
      values(2).b.doc("Last narrow field")
    }
  }
  test("named field record comments retain aggregate element and field ownership") {
    for (unpacked <- Seq(false, true); split <- Seq(false, true)) {
      val plain = emit(morph = true, unpacked = unpacked, named = true, split = split)(new Records(false))
      val rtl = emit(morph = true, unpacked = unpacked, named = true, split = split)(new Records(true))
      assert(tokens(plain) == tokens(rtl))
      for (note <- Seq("Record bank", "Second record", "Last narrow field"))
        assert(rtl.sliding(note.length).count(_ == note) == 1, rtl)
    }
  }
  test("definition documentation participates in deduplication without leaking instance notes") {
    val rtl = emit()(new Component {
      setDefinitionName("Top")
      val x = in Bits(8 bits)
      val y = out Bits(8 bits)
      val left = new Child
      val right = new Child
      left.definition.doc("Left definition")
      right.definition.doc("Right definition")
      left.x := x
      right.x := left.y
      y := right.y
    })
    assert(rtl.contains("Left definition") && rtl.contains("Right definition"), rtl)
    assert("module Child".r.findAllIn(rtl).size == 2, rtl)
  }

  class Automatic extends Component { // Automatically documented module
    setDefinitionName("Top")
    // Preceding input explanation
    val x = in Bits(8 bits) // Trailing input explanation
    val y = out Bits(8 bits) // Trailing output explanation
    /** Saved packet word. */
    val saved = Reg(Bits(8 bits)) init(0)
    val notHardware = "// not a comment" // Scala-only comment
    saved := x
    y := saved
  }
  test("automatic trailing preceding and Scaladoc capture is independently opt in") {
    val plain = emit()(new Automatic)
    val ordinary = emit(auto = true)(new Automatic)
    val documentation = emit(scaladoc = true)(new Automatic)
    val both = emit(auto = true, scaladoc = true)(new Automatic)
    assert(tokens(plain) == tokens(ordinary) && tokens(plain) == tokens(documentation) && tokens(plain) == tokens(both))
    for (note <- Seq("Automatically documented module", "Preceding input explanation", "Trailing input explanation", "Trailing output explanation")) {
      assert(!plain.contains(note) && ordinary.contains(note) && both.contains(note), ordinary)
      assert(!documentation.contains(note), documentation)
    }
    assert(!ordinary.contains("Saved packet word.") && documentation.contains("Saved packet word."), documentation)
    assert(!both.contains("Scala-only comment") && !both.contains("not a comment"), both)
  }

  class Regions(documented: Boolean, width: ElabInt) extends Component {
    setDefinitionName("Top")
    val enable = in Bool()
    val mode = in UInt(2 bits)
    val data = in(Bits(width bits))
    val result = out Bits(width bits)
    val saved = Reg(Bits(width bits)) init(0)
    def note[T](message: String)(body: => T): T = rtlDoc(if (documented) message else "")(body)
    result := saved
    note("Accept packet") {
      when(enable) {
        note("Store payload") { saved := data }
      } otherwise {
        note("Choose fallback") {
          switch(mode) {
            is(0) { note("Clear payload") { saved := 0 } }
            default { note("Hold payload") { saved := saved } }
          }
        }
      }
    }
    var calls = 0
    val returned = note("Empty region") { calls += 1; 42 }
    assert(calls == 1 && returned == 42)
    try { note("Failed region") { throw new IllegalStateException("expected") } }
    catch { case _: IllegalStateException => () }
  }
  test("hardware region documentation follows conditions and branches without changing tokens") {
    for (morph <- Seq(false, true)) {
      val plain = emit(morph = morph)(new Regions(false, morphhdl.frontend.HdlInt.param("WIDTH", 8, 1, 16).asElabInt))
      val documented = emit(morph = morph)(new Regions(true, morphhdl.frontend.HdlInt.param("WIDTH", 8, 1, 16).asElabInt))
      assert(tokens(plain) == tokens(documented), documented)
      for (note <- Seq("Accept packet", "Store payload", "Choose fallback", "Clear payload", "Hold payload")) {
        assert(documented.sliding(note.length).count(_ == note) == 1, documented)
      }
      assert(!documented.contains("Empty region") && !documented.contains("Failed region"), documented)
      assert(documented.indexOf("Accept packet") < documented.indexOf("if(enable)"), documented)
    }
  }

  class GenerateRegions(documented: Boolean, enable: morphhdl.frontend.HdlBool, lanes: morphhdl.frontend.HdlInt) extends Component {
    import morphhdl.frontend._
    setDefinitionName("Top")
    val data = in(spinal.core.Bits(8 bits))
    val alive = out Bool()
    alive := data.orR
    def note[T](message: String)(body: => T): T = rtlDoc(if (documented) message else "")(body)
    class Sink extends Component {
      val input = in(spinal.core.Bits(8 bits))
      val observed = out Bool()
      observed := input.orR
    }
    def sink(): Unit = {
      val wire = morphhdl.frontend.Bits(8 bits)
      wire := data
      val child = new Sink
      child.input := wire
    }
    note("Select generated hardware") {
      enable.generateIf("g_yes", "g_no") {
        note("Enabled branch") { sink() }
      }.otherwise {
        note("Disabled branch") { sink() }
      }
    }
    note("Replicate hardware") {
      (0 until lanes).named("g_lane", "lane").foreach { lane =>
        note("Lane body") { sink() }
      }
    }
  }
  test("generate headers and branch bodies retain exact documentation ownership") {
    import morphhdl.frontend.{HdlBool, HdlInt}
    def top(documented: Boolean) = new GenerateRegions(documented,
      HdlBool.param("ENABLE", default = true), HdlInt.param("LANES", default = 2, min = 1, max = 4))
    val plain = emit(morph = true)(top(false))
    val documented = emit(morph = true)(top(true))
    assert(tokens(plain) == tokens(documented), documented)
    for (note <- Seq("Select generated hardware", "Enabled branch", "Disabled branch", "Replicate hardware", "Lane body"))
      assert(documented.sliding(note.length).count(_ == note) == 1, documented)
    assert(documented.indexOf("Select generated hardware") < documented.indexOf("if ((ENABLE == 1))"), documented)
    assert(documented.indexOf("Enabled branch") > documented.indexOf("begin : g_yes"), documented)
    assert(documented.indexOf("Disabled branch") > documented.indexOf("begin : g_no"), documented)
    assert(documented.indexOf("Lane body") > documented.indexOf("begin : g_lane"), documented)
  }

  class AutomaticRegions extends Component {
    setDefinitionName("Top")
    val enable = in Bool()
    val data = in Bits(8 bits)
    val result = out Bits(8 bits)
    result := 0
    // Automatic condition
    when(enable) { // Automatic true branch
      result := data
    } otherwise { // Automatic false branch
      result := ~data
    }
  }
  test("automatic hardware control flow comments obey the generation capture flag") {
    val plain = emit()(new AutomaticRegions)
    val documented = emit(auto = true)(new AutomaticRegions)
    assert(tokens(plain) == tokens(documented), documented)
    for (note <- Seq("Automatic condition", "Automatic true branch", "Automatic false branch")) {
      assert(!plain.contains(note), plain)
      assert(documented.sliding(note.length).count(_ == note) == 1, documented)
    }
  }

  class MixedRecords(documented: Boolean) extends Component {
    setDefinitionName("Top")
    val io = new Bundle {
      val first = in(Vec.fill(2, 3)(Bits(8 bits)))
      val second = in(Vec(Bits(8 bits), 3))
      val result = out(Vec(Bits(8 bits), 3))
      val valid = out Bool()
    }
    io.result := io.second
    io.valid := io.first(0)(0).orR
    if (documented) {
      io.doc("Mixed direction interface")
      io.first.doc("Nested input bank")
      io.second(2).doc("Last input element")
    }
  }
  test("nested arrays and mixed direction groups retain one aggregate note across publication plans") {
    for (unpacked <- Seq(false, true); named <- Seq(false, true)) {
      val plain = emit(morph = true, unpacked = unpacked, named = named)(new MixedRecords(false))
      val documented = emit(morph = true, unpacked = unpacked, named = named)(new MixedRecords(true))
      assert(tokens(plain) == tokens(documented), documented)
      for (note <- Seq("Mixed direction interface", "Nested input bank", "Last input element"))
        assert(documented.sliding(note.length).count(_ == note) == 1, documented)
      assert(documented.indexOf("Mixed direction interface") < documented.indexOf("io_first"), documented)
    }
  }
  test("declaration comments coexist with attributes and trailing tool directives") {
    val source = "  (* keep = \"true\" *) wire [7:0] data /* verilator public */;\n"
    val published = RtlDocumentation.publish(source, Map("data" -> Vector("Documented data")))
    assert(published == source.trim.stripSuffix("\n") + " // Documented data\n" ||
      published == source.stripSuffix("\n") + " // Documented data\n", published)
    assert(published.contains("(* keep = \"true\" *)") && published.contains("/* verilator public */"), published)
  }

  class DocumentedProcess(documented: Boolean, lanes: morphhdl.frontend.HdlInt) extends Component {
    import morphhdl.frontend._
    setDefinitionName("Top")
    val din = in(morphhdl.frontend.Bits(32 bits))
    val dout = out(morphhdl.frontend.Bits(32 bits))
    dout := 0
    rtlDoc(if (documented) "Copy lanes" else "") {
      (0 until lanes).named("p_lane", "lane").foreach { lane =>
        rtlDoc(if (documented) "Copy lane payload" else "") {
          val width = HdlInt.literal(8)
          dout(lane * width, width) := din(lane * width, width)
        }
      }
    }
  }
  test("retained procedural loops place header and assignment comments inside the process") {
    import morphhdl.frontend.HdlInt
    def top(doc: Boolean) = new DocumentedProcess(doc, HdlInt.param("LANES", 2, 0, 4))
    val plain = emit(morph = true)(top(false))
    val rtl = emit(morph = true)(top(true))
    assert(tokens(plain) == tokens(rtl), rtl)
    assert(rtl.sliding(10).count(_ == "Copy lanes") == 1, rtl)
    assert(rtl.sliding(17).count(_ == "Copy lane payload") == 1, rtl)
    assert(rtl.indexOf("Copy lanes") < rtl.indexOf("for ("), rtl)
    assert(rtl.indexOf("Copy lane payload") > rtl.indexOf("for ("), rtl)
  }
  class NestedAutomaticGenerate(enable: morphhdl.frontend.HdlBool, lanes: morphhdl.frontend.HdlInt) extends Component {
    import morphhdl.frontend._
    setDefinitionName("Top")
    val data = in(spinal.core.Bits(8 bits))
    val alive = out Bool()
    alive := data.orR
    class Sink extends Component {
      val input = in(spinal.core.Bits(8 bits))
      val observed = out Bool()
      observed := input.orR
    }
    def sink(): Unit = { val child = new Sink; child.input := data }
    // Automatic generated selection
    enable.generateIf("g_yes", "g_no") { // Automatic selected body
      // Automatic nested loop
      (0 until lanes).named("g_lane", "lane").foreach { lane => // Automatic lane body
        sink()
      }
    }.otherwise { // Automatic alternative body
      sink()
    }
  }
  test("automatic nested symbolic generate comments retain headers bodies and branch ownership") {
    import morphhdl.frontend.{HdlBool, HdlInt}
    def top() = new NestedAutomaticGenerate(HdlBool.param("ENABLE", true), HdlInt.param("LANES", 2, 0, 4))
    val plain = emit(morph = true)(top())
    val rtl = emit(morph = true, auto = true)(top())
    assert(tokens(plain) == tokens(rtl), rtl)
    for (note <- Seq("Automatic generated selection", "Automatic selected body", "Automatic nested loop", "Automatic lane body", "Automatic alternative body")) {
      assert(!plain.contains(note), plain)
      assert(rtl.sliding(note.length).count(_ == note) == 1, note + "\n" + rtl)
    }
    assert(rtl.indexOf("Automatic nested loop") > rtl.indexOf("begin : g_yes"), rtl)
    assert(rtl.indexOf("Automatic lane body") > rtl.indexOf("begin : g_lane"), rtl)
    assert(rtl.indexOf("Automatic alternative body") > rtl.indexOf("begin : g_no"), rtl)
  }
  test("token comparison preserves synthesis attributes directives and quoted strings") {
    assert(tokens("wire a; // ordinary") == tokens("wire a;"))
    assert(tokens("wire a; /* verilator public */") != tokens("wire a;"))
    assert(tokens("wire a; // synthesis keep") != tokens("wire a;"))
    assert(tokens("(* keep = \"yes\" *) wire a;") != tokens("wire a;"))
    assert(tokens("\"// literal\"") != tokens("\"\""))
  }

  class PartialGenerate(documented: Boolean, enable: morphhdl.frontend.HdlBool) extends Component {
    import morphhdl.frontend._
    setDefinitionName("Top")
    val data = in(spinal.core.Bits(8 bits))
    val alive = out Bool()
    alive := data.orR
    class Sink extends Component {
      val input = in(spinal.core.Bits(8 bits))
      val observed = out Bool()
      observed := input.orR
    }
    enable.generateIf("g_yes", "g_no") {
      val first = spinal.core.Bits(8 bits).dontSimplifyIt()
      val second = spinal.core.Bits(8 bits).dontSimplifyIt()
      rtlDoc(if (documented) "Only the first driver\nSecond line of driver note" else "") { first := data }
      second := ~data
      val left = new Sink
      val right = new Sink
      if (documented) left.doc("Only the left instance")
      left.input := first
      right.input := second
    }.otherwise {
      val child = new Sink
      child.input := data
    }
  }
  test("partial assignment and instance comments relocate with their exact generated branch owners") {
    import morphhdl.frontend.HdlBool
    def top(doc: Boolean) = new PartialGenerate(doc, HdlBool.param("ENABLE", true))
    val plain = emit(morph = true)(top(false))
    val rtl = emit(morph = true)(top(true))
    assert(tokens(plain) == tokens(rtl), rtl)
    for (note <- Seq("Only the first driver", "Second line of driver note", "Only the left instance")) {
      assert(rtl.sliding(note.length).count(_ == note) == 1, rtl)
      assert(rtl.indexOf(note) > rtl.indexOf("begin : g_yes"), rtl)
      assert(rtl.indexOf(note) < rtl.indexOf("begin : g_no"), rtl)
    }
    assert(!rtl.contains("MORPHHDL_RTL_DOC_"), rtl)
  }

  class BoundaryComments extends Component {
    setDefinitionName("Top")
    // Detached section is not attached

    val x = in Bits(8 bits)
    // Preceding annotated port
    @doc("Explicit annotated port")
    val y = out(
      Bits(8 bits)
    ) // Completed multiline port
    val one = Bits(8 bits); val two = Bits(8 bits) // Ambiguous declarations
    one := x
    two := one
    y := two
    val child = new Child // Automatically documented instance
    child.x := x
    val unused = "// quoted" // Ordinary Scala metadata
  }
  test("automatic attachment respects multiline annotation detached and ambiguous boundaries") {
    val plain = emit()(new BoundaryComments)
    val rtl = emit(auto = true)(new BoundaryComments)
    assert(tokens(plain) == tokens(rtl), rtl)
    for (note <- Seq("Preceding annotated port", "Explicit annotated port", "Completed multiline port", "Automatically documented instance"))
      assert(rtl.sliding(note.length).count(_ == note) == 1, note + "\n" + rtl)
    assert(!rtl.contains("Detached section is not attached") && !rtl.contains("Ambiguous declarations") && !rtl.contains("Ordinary Scala metadata"), rtl)
    assert(rtl.indexOf("Explicit annotated port") < rtl.indexOf("Preceding annotated port"), rtl)
    assert(rtl.indexOf("Preceding annotated port") < rtl.indexOf("Completed multiline port"), rtl)
  }
  class AdvancedRegions(documented: Boolean) extends Component {
    setDefinitionName("Top")
    val a = in Bool()
    val b = in Bool()
    val mode = in UInt(2 bits)
    val data = in Bits(8 bits)
    val y = out Bits(8 bits)
    val z = out Bits(8 bits)
    def note[T](text: String)(body: => T): T = rtlDoc(if (documented) text else "")(body)
    y := 0
    val first = note("First condition") { when(a) { y := data } }
    val next = note("Second condition") { first.elsewhen(b) { y := ~data } }
    next.otherwise { note("Fallback branch") { y := B(3, 8 bits) } }
    try { note("Failed partial assignment") { z := data; throw new IllegalStateException("expected") } }
    catch { case _: IllegalStateException => () }
    val scratch = Bits(8 bits)
    note("Removed assignment") { scratch := data }
    note("Empty unrolled loop") { for (i <- 0 until 0) { scratch := 0 } }
    // Automatic switch selection
    switch(mode) {
      // Automatic multiple labels
      is(0, 1) { note("Multiple-label body") { y := data ^ B(1, 8 bits) } }
      // Automatic default
      default { }
    }
  }
  test("elsewhen switch multiple labels removed regions and exceptions preserve ownership") {
    val plain = emit(prune = true)(new AdvancedRegions(false))
    val rtl = emit(auto = true, prune = true)(new AdvancedRegions(true))
    assert(tokens(plain) == tokens(rtl), rtl)
    for (note <- Seq("First condition", "Second condition", "Fallback branch", "Automatic switch selection", "Automatic multiple labels", "Multiple-label body"))
      assert(rtl.contains(note), note + "\n" + rtl)
    for (note <- Seq("Failed partial assignment", "Removed assignment", "Empty unrolled loop", "Automatic default"))
      assert(!rtl.contains(note), note + "\n" + rtl)
  }
  test("constant typed generate and symbolic zero specialization preserve documentation without inventing code") {
    import morphhdl.frontend.{HdlBool, HdlInt}
    for (constant <- Seq(false, true)) {
      def top(doc: Boolean) = new GenerateRegions(doc,
        if (constant) HdlBool.literal(true) else HdlBool.param("ENABLE", false),
        if (constant) HdlInt.literal(2) else HdlInt.param("LANES", 0, 0, 4))
      val plain = emit(morph = true)(top(false))
      val rtl = emit(morph = true)(top(true))
      assert(tokens(plain) == tokens(rtl), rtl)
      assert(rtl.contains("Replicate hardware") && rtl.contains("Lane body"), rtl)
    }
  }

  test("retained region documentation prevents deduplication of differently documented module bodies") {
    val rtl = emit(morph = true)(new Component {
      setDefinitionName("Top")
      val x = in Bits(32 bits)
      val y = out Bits(32 bits)
      val left = new DocumentedProcess(true, morphhdl.frontend.HdlInt.literal(2))
      val right = new DocumentedProcess(false, morphhdl.frontend.HdlInt.literal(2))
      left.setDefinitionName("Worker", noMerge = false)
      right.setDefinitionName("Worker", noMerge = false)
      left.din := x
      right.din := left.dout
      y := right.dout
    })
    assert("module Worker".r.findAllIn(rtl).size == 2, rtl)
    assert(rtl.sliding(10).count(_ == "Copy lanes") == 1, rtl)
  }
  test("repeated combined generation is deterministic") {
    import morphhdl.frontend.{HdlBool, HdlInt}
    def top() = new NestedAutomaticGenerate(HdlBool.param("ENABLE", true), HdlInt.param("LANES", 2, 0, 4))
    assert(emit(morph = true, auto = true)(top()) == emit(morph = true, auto = true)(top()))
  }

  @doc("Combined documentation example")
  class Combined(enable: morphhdl.frontend.HdlBool, lanes: morphhdl.frontend.HdlInt)
      extends GenerateRegions(true, enable, lanes) {
    // Incoming packet bank
    val packets = in(Vec(Bits(8 bits), 3)) // Packet array payload
    @doc("Buffered packet word")
    val buffered = Reg(Bits(8 bits)) init(0)
    val packetResult = out Bits(8 bits)
    packets.doc("Three packet slots")
    buffered := packets(0)
    packetResult := buffered
  }
  test("combined annotations fluent automatic aggregates hierarchy and generate regions publish together") {
    import morphhdl.frontend.{HdlBool, HdlInt}
    def top() = new Combined(HdlBool.param("ENABLE", true) && (HdlInt.param("MODE", 1, 0, 2) > 0), HdlInt.param("LANES", 2, 0, 3) + HdlInt.literal(1))
    val plain = emit(morph = true)(top())
    val rtl = emit(morph = true, auto = true)(top())
    assert(tokens(plain) == tokens(rtl), rtl)
    for (note <- Seq("Combined documentation example", "Incoming packet bank", "Packet array payload", "Buffered packet word", "Three packet slots", "Select generated hardware", "Lane body"))
      assert(rtl.sliding(note.length).count(_ == note) == 1, note + "\n" + rtl)
  }
  class NestedFiniteRegions(documented: Boolean, rows: ElabInt, count: ElabInt) extends Component {
    setDefinitionName("Top")
    val values = in(Vec(UInt(8 bits), rows))
    val alive = out Bool()
    alive := values(0).orR
    def note[T](text: String)(body: => T): T = rtlDoc(if (documented) text else "")(body)
    note("Outer finite loop") {
      ElabFiniteRange.foreach(rows, "outer_read") { row =>
        note("Inner finite loop") {
          ElabFiniteRange.foreach(count, "inner_read") { _ =>
            note("Inner finite payload") {
              val selected = row(values)
              val word = UInt(8 bits)
              word := selected
              word.setAsVital()
              word.dontSimplifyIt()
            }
          }
        }
      }
    }
  }
  test("nested retained finite loops keep each header and body note once") {
    import morphhdl.frontend.HdlInt
    def top(doc: Boolean) = new NestedFiniteRegions(doc, HdlInt.param("ROWS", 2, 1, 3).asElabInt, HdlInt.param("COUNT", 2, 1, 3).asElabInt)
    val plain = emit(morph = true)(top(false))
    val rtl = emit(morph = true)(top(true))
    assert(tokens(plain) == tokens(rtl), rtl)
    for (note <- Seq("Outer finite loop", "Inner finite loop", "Inner finite payload"))
      assert(rtl.sliding(note.length).count(_ == note) == 1, note + "\n" + rtl)
  }

  class SharedGenerate(documented: Boolean, enable: morphhdl.frontend.HdlBool)
      extends GenerateRegions(false, enable, morphhdl.frontend.HdlInt.literal(2)) {
    import morphhdl.frontend._
    rtlDoc(if (documented) "Shared generated decisions" else "") {
      enable.generateIf("g_first_yes", "g_first_no") { sink() }.otherwise { sink() }
      enable.generateIf("g_second_yes", "g_second_no") { sink() }.otherwise { sink() }
    }
  }
  test("a documented region spanning sibling generate constructs retains each construct owner") {
    import morphhdl.frontend.HdlBool
    def top(doc: Boolean) = new SharedGenerate(doc, HdlBool.param("ENABLE", true))
    val plain = emit(morph = true)(top(false))
    val rtl = emit(morph = true)(top(true))
    assert(tokens(plain) == tokens(rtl), rtl)
    val note = "Shared generated decisions"
    assert(rtl.sliding(note.length).count(_ == note) == 2, rtl)
    assert(rtl.indexOf(note) < rtl.indexOf("begin : g_first_yes"), rtl)
    assert(rtl.lastIndexOf(note) < rtl.indexOf("begin : g_second_yes"), rtl)
  }

  test("documentation signatures distinguish punctuation from independently authored notes") {
    val rtl = emit()(new Component {
      setDefinitionName("Top")
      val x = in Bits(8 bits)
      val y = out Bits(8 bits)
      val left = new Child
      val right = new Child
      left.x.doc("first, second")
      right.x.doc("first").doc("second")
      left.x := x
      right.x := left.y
      y := right.y
    })
    assert("module Child".r.findAllIn(rtl).size == 2, rtl)
    assert(rtl.contains("// first, second"), rtl)
    assert(rtl.contains("// first\n") && rtl.contains("// second\n"), rtl)
  }

  class ConditionalDocumentation(documented: Boolean, lanes: morphhdl.frontend.HdlInt) extends Component {
    import morphhdl.frontend._
    setDefinitionName("Top")
    val din = in(morphhdl.frontend.Bits(8 bits))
    val selected = in(spinal.core.UInt(3 bits))
    val dout = out(morphhdl.frontend.Bits(32 bits))
    dout := 0
    rtlDoc(if (documented) "Select a lane" else "") {
      (0 until lanes).named("selected_lane", "lane").foreach { lane =>
        lane.whenSelected(selected) {
          rtlDoc(if (documented) "Selected lane payload" else "") {
            val width = HdlInt.literal(8)
            dout(lane * width, width) := din
          }
        }
      }
    }
  }
  test("conditional blocks inside retained loops keep payload comments inside the selected branch") {
    import morphhdl.frontend.HdlInt
    def top(doc: Boolean) = new ConditionalDocumentation(doc, HdlInt.param("LANES", 2, 1, 4))
    val plain = emit(morph = true)(top(false))
    val rtl = emit(morph = true)(top(true))
    assert(tokens(plain) == tokens(rtl), rtl)
    for (note <- Seq("Select a lane", "Selected lane payload"))
      assert(rtl.sliding(note.length).count(_ == note) == 1, rtl)
    val condition = "if\\s*\\(".r.findFirstMatchIn(rtl).get.start
    assert(rtl.indexOf("Selected lane payload") > condition, rtl)
  }
  class EnabledDocumentation(documented: Boolean) extends Component {
    setDefinitionName("Top")
    val enable = in Bool()
    val data = in Bits(8 bits)
    val result = out Bits(8 bits)
    val area = new ClockEnableArea(enable) {
      val saved = Reg(Bits(8 bits)) init(0)
      rtlDoc(if (documented) "Capture enabled payload" else "") { saved := data }
      result := saved
    }
  }
  test("region comments remain inside reset and clock enable structure") {
    val plain = emit()(new EnabledDocumentation(false))
    val rtl = emit()(new EnabledDocumentation(true))
    assert(tokens(plain) == tokens(rtl), rtl)
    assert(rtl.contains("if(area_newClockEnable)"), rtl)
    assert(rtl.indexOf("Capture enabled payload") > rtl.indexOf("if(area_newClockEnable)"), rtl)
  }

  test("singleton record element documentation retains its element coordinate") {
    val rtl = emit(morph = true, named = true)(new Component {
      setDefinitionName("Top")
      val x = in(Vec(Record(), 1))
      val y = out(Vec(Record(), 1))
      x(0).doc("Singleton element")
      y := x
    })
    assert(rtl.contains("Element 0, field 0: Singleton element"), rtl)
    assert(rtl.sliding(17).count(_ == "Singleton element") == 1, rtl)
  }

}
