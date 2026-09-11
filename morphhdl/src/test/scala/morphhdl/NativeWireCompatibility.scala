package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.sys.process._

/** Test-only comparison of the current pipeline with an independently emitted
  * reference. RTL is never normalized to erase an optimization difference.
  */
private[morphhdl] object NativeWireCompatibility {
  private val identifier = "[A-Za-z_][A-Za-z0-9_$]*"
  private def quoted(value: String): String = java.util.regex.Pattern.quote(value)
  private def write(path: Path, value: String): Unit =
    Files.write(path, value.getBytes(StandardCharsets.UTF_8))

  private def run(directory: Path, command: Seq[String]): String = {
    val output = new StringBuilder
    val status = Process(command, directory.toFile).!(ProcessLogger(
      line => output.append(line).append('\n'),
      line => output.append(line).append('\n')))
    require(status == 0, command.mkString(" ") + "\n" + output)
    output.toString
  }

  private def parameterNames(source: String): Set[String] =
    ("parameter\\s+integer\\s+(" + identifier + ")\\s*=").r
      .findAllMatchIn(source).map(_.group(1)).toSet

  def check(directory: Path, candidate: String, reference: String, top: String,
            bindings: Vector[(String, Int)], label: String): Unit = {
    val work = Files.createDirectory(directory.resolve(label))
    write(work.resolve("candidate.v"), candidate)
    write(work.resolve("reference.v"), reference)
    def selected(source: String) = bindings.filter(pair => parameterNames(source)(pair._1))
    Vector("candidate.v" -> candidate, "reference.v" -> reference).foreach {
      case (file, source) =>
        run(work, Seq("iverilog", "-g2001", "-s", top, "-tnull") ++
          selected(source).map { case (name, value) => s"-P$top.$name=$value" } :+ file)
    }
    def prepare(file: String, source: String, name: String): String = {
      val parameters = selected(source).map { case (key, value) => s" -set $key $value" }.mkString
      s"""read_verilog $file
         |${if (parameters.isEmpty) "" else s"chparam$parameters $top"}
         |hierarchy -check -top $top
         |proc
         |memory_map
         |opt_clean
         |rename $top $name
         |design -stash $name
         |""".stripMargin
    }
    write(work.resolve("equivalence.ys"),
      prepare("reference.v", reference, "gold") + prepare("candidate.v", candidate, "gate") +
        """design -reset
          |design -copy-from gold -as gold gold
          |design -copy-from gate -as gate gate
          |equiv_make gold gate equiv
          |hierarchy -check -top equiv
          |equiv_simple -undef -seq 8
          |equiv_induct -undef -seq 8
          |equiv_status -assert
          |""".stripMargin)
    run(work, Seq("yosys", "-s", "equivalence.ys"))
    simulate(work, candidate, reference, top, bindings)
    println(s"NATIVE_WIRE_COMPATIBILITY_PASS $label ${bindings.mkString(",")}")
  }

  private final case class Port(direction: String, range: String, name: String)
  private def ports(source: String, top: String): Vector[Port] = {
    val start = ("(?m)^module " + quoted(top) + "\\b").r.findAllMatchIn(source).toVector
    require(start.size == 1, "one complete test module is required")
    val end = source.indexOf(");", start.head.start)
    require(end >= 0, "missing test module header")
    val header = source.substring(start.head.start, end + 2)
    val result = ("\\b(input|output)\\s+wire\\s+(\\[[^\\]]+\\]\\s+)?(" + identifier + ")\\s*[,)]").r
      .findAllMatchIn(header).map(m => Port(m.group(1), Option(m.group(2)).getOrElse(""), m.group(3))).toVector
    require(result.nonEmpty && result.map(_.name).distinct.size == result.size &&
      "\\b(?:input|output|inout)\\b".r.findAllMatchIn(header).size == result.size,
      "test requires distinct unsigned ANSI ports")
    result
  }

  private def simulate(work: Path, candidate: String, reference: String, top: String,
                       bindings: Vector[(String, Int)]): Unit = {
    val candidatePorts = ports(candidate, top)
    require(candidatePorts.map(p => p.direction -> p.name) ==
      ports(reference, top).map(p => p.direction -> p.name), "reference interface differs")
    def renamed(source: String, name: String): String =
      ("(?m)^module " + quoted(top) + "\\b").r.replaceFirstIn(source, "module " + name)
    write(work.resolve("simulation_candidate.v"), renamed(candidate, "CurrentWireCandidate"))
    write(work.resolve("simulation_reference.v"), renamed(reference, "IndependentWireReference"))
    val inputs = candidatePorts.filter(_.direction == "input")
    val outputs = candidatePorts.filter(_.direction == "output")
    require(outputs.nonEmpty, "comparison requires observable outputs")
    val clocked = inputs.exists(_.name == "clk")
    val reset = inputs.exists(_.name == "reset")
    val declarations = candidatePorts.map { p =>
      if (p.direction == "input") s"reg ${p.range}${p.name};"
      else s"wire ${p.range}current_${p.name}, reference_${p.name};"
    }.mkString("\n")
    def instance(source: String, name: String, instanceName: String, prefix: String): String = {
      val selected = bindings.filter(pair => parameterNames(source)(pair._1))
      val parameters = if (selected.isEmpty) "" else selected.map {
        case (key, value) => s".$key($value)"
      }.mkString(" #(", ",", ")")
      val connections = candidatePorts.map(p => s".${p.name}(${if (p.direction == "input") "" else prefix}${p.name})")
      s"$name$parameters $instanceName (${connections.mkString(",")});"
    }
    val compare = outputs.map(p =>
      s"""if (current_${p.name} !== reference_${p.name}) $$fatal(1, "four-state mismatch: ${p.name}");""").mkString("\n")
    val drive = inputs.filterNot(p => p.name == "clk" || p.name == "reset").zipWithIndex.map {
      case (p, index) => s"""case (iteration % 8)
        |  0: ${p.name} = '0;
        |  1: ${p.name} = '1;
        |  6: ${p.name} = 'x;
        |  7: ${p.name} = 'z;
        |  default: ${p.name} = (iteration * 32'h9e3779b9) ^ ${index * 7919 + 17};
        |endcase""".stripMargin
    }.mkString("\n")
    write(work.resolve("comparison.v"), s"""module NativeWireComparison;
      |${bindings.map { case (key, value) => s"localparam integer $key = $value;" }.mkString("\n")}
      |function integer clog2;
      |  input integer value, minimum_result;
      |  integer remaining, bits;
      |  begin
      |    remaining = value - 1; bits = 0;
      |    while (remaining > 0) begin remaining = remaining >> 1; bits = bits + 1; end
      |    clog2 = bits < minimum_result ? minimum_result : bits;
      |  end
      |endfunction
      |$declarations
      |integer iteration;
      |${instance(candidate, "CurrentWireCandidate", "dut", "current_")}
      |${instance(reference, "IndependentWireReference", "oracle", "reference_")}
      |initial begin
      |${inputs.map(p => s"${p.name} = 0;").mkString("\n")}
      |${candidatePorts.map(p => s"""if ($$bits(dut.${p.name}) != $$bits(oracle.${p.name})) $$fatal(1, "port width mismatch");""").mkString("\n")}
      |${if (reset) "reset = 1;" else ""}
      |${if (clocked) "#1; clk = 1; #1; clk = 0; #1; clk = 1; #1; clk = 0;" else "#1;"}
      |${if (reset) "reset = 0;" else ""}
      |for (iteration = 0; iteration < 128; iteration = iteration + 1) begin
      |$drive
      |#1;
      |${if (clocked) "clk = 1; #1;" else ""}
      |$compare
      |${if (clocked) "clk = 0; #1;" else ""}
      |end
      |$$display("NATIVE_WIRE_FOUR_STATE_PASS");
      |$$finish;
      |end
      |endmodule
      |""".stripMargin)
    run(work, Seq("iverilog", "-g2012", "-s", "NativeWireComparison", "-o", "comparison.out",
      "simulation_candidate.v", "simulation_reference.v", "comparison.v"))
    require(run(work, Seq("vvp", "comparison.out")).contains("NATIVE_WIRE_FOUR_STATE_PASS"))
  }

  /** Return the actual write statement only after proving its RHS is the exact
    * input through unique unsigned full-width continuous wire assignments.
    */
  def memoryWrite(source: String, memory: String, address: String, input: String): String = {
    require((quoted(memory) + "\\[[^\\]]+\\]\\s*<=").r.findAllMatchIn(source).size == 1,
      "expected one whole-word memory write driver")
    val writes = ("(?m)^\\s*(" + quoted(memory + "[" + address + "]") +
      " <= (" + identifier + ");)\\s*$").r.findAllMatchIn(source).toVector
    require(writes.size == 1, "expected one direct whole-word memory write")
    def declaration(name: String): (String, String, String) = {
      val declarations = ("(?m)^\\s*(?:(input|output)\\s+)?(wire|reg)\\s+(\\[[^\\]]+\\]\\s+)?" +
        quoted(name) + "\\s*[,;]\\s*$").r.findAllMatchIn(source).toVector
      require(declarations.size == 1, "missing unique unsigned declaration: " + name)
      val d = declarations.head
      (Option(d.group(1)).getOrElse(""), d.group(2), Option(d.group(3)).getOrElse("").replaceAll("\\s+", ""))
    }
    val inputDeclaration = declaration(input)
    require(inputDeclaration._1 == "input" && inputDeclaration._2 == "wire")
    val storage = ("(?m)^\\s*reg\\s+(\\[[^\\]]+\\]\\s+)?" + quoted(memory) +
      "\\s*\\[[^\\]]+\\]\\s*;\\s*$").r.findAllMatchIn(source).toVector
    require(storage.size == 1 &&
      Option(storage.head.group(1)).getOrElse("").replaceAll("\\s+", "") == inputDeclaration._3,
      "memory element and data input must have the same unsigned symbolic width")
    val visited = scala.collection.mutable.Set.empty[String]
    var current = writes.head.group(2)
    while (current != input) {
      require(visited.add(current), "cyclic memory-data alias")
      val d = declaration(current)
      require(d._1.isEmpty && d._2 == "wire" && d._3 == inputDeclaration._3,
        "memory-data alias is not an unsigned full-width internal wire")
      val drivers = ("(?<![A-Za-z0-9_$])" + quoted(current) +
        "(?:\\[[^\\]]+\\])?\\s*(?:<=|=(?!=))").r.findAllMatchIn(source).toVector
      val assignments = ("(?m)^\\s*assign\\s+" + quoted(current) + "\\s*=\\s*(" +
        identifier + ")\\s*;\\s*$").r.findAllMatchIn(source).toVector
      require(drivers.size == 1 && assignments.size == 1,
        "memory-data alias requires one direct full-width continuous driver")
      current = assignments.head.group(1)
    }
    writes.head.group(1)
  }
}
