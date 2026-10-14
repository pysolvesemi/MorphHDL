package morphhdl

import java.nio.file.{Files, Path, Paths}
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib.RecursiveUnsignedSum
import morphhdl.frontend.HdlInt

object RecursiveUnsignedSumArtifactWriter {
  def generate(directory: Path, default: Int, split: Boolean = false): String = {
    Files.createDirectories(directory)
    MorphVerilog(SpinalConfig(targetDirectory = directory.toString, oneFilePerComponent = split))(
      new RecursiveUnsignedSum(ElabInt.literal(default), ElabInt.literal(32), ElabInt.literal(36), 17, 32, 36))
    new String(Files.readAllBytes(directory.resolve("RecursiveUnsignedSum.v")), "UTF-8")
  }
  def main(args: Array[String]): Unit = {
    require(args.length == 1, "supply an output directory")
    for (default <- Seq(1,16); split <- Seq(false,true))
      generate(Paths.get(args(0)).resolve(s"default-$default-split-$split"), default, split)
  }
}

private object RecursiveUnsignedSumNegativeFixture {
  final class Broken(mode: String) extends Component {
    setDefinitionName("BrokenRecursiveSum")
    @dontName val count = HdlInt.param("COUNT", default=3, min=1, max=8).asElabInt
    val terms = in Bits((count * 4) bits)
    val sum = out UInt(8 bits)
    class Reference(next: ElabInt, wrongWidth: Boolean = false) extends BlackBox {
      setBlackBoxName("BrokenRecursiveSum")
      addGeneric("COUNT", next)
      val terms = in Bits((next * 4 + (if(wrongWidth) 1 else 0)) bits)
      val sum = out UInt(8 bits)
    }
    def step(): Unit = {
      val left = new Reference(if(mode=="missing-base") (count+1)/2 else count/2)
      val right = new Reference(if(mode=="nondecreasing") count else if(mode=="out-of-domain") count-2 else if(mode=="missing-base") (count+1)/2 else count-count/2,
        mode=="geometry")
      left.terms := 0
      right.terms := 0
      sum := left.sum + right.sum
      if(mode=="competing") sum := left.sum
    }
    if(mode=="missing-base") step()
    else ElabControl.selectSymbolic(count <= 1, "RecursiveSumNegative", 1) { sum := 0 } { step() }
  }
}

object NativeRecursiveTreeArtifactWriter {
  val kinds = Seq("uint", "bits", "signed", "bundle", "bridge", "register", "sync")
  def generate(directory: Path, default: Int, kind: String, guardedPairs: Boolean = false): String = {
    Files.createDirectories(directory)
    val config = SpinalConfig(targetDirectory=directory.toString, headerWithDate=false,
      flags=scala.collection.mutable.HashSet[Any](VerilogAggregateOptions(
        preserveConstantVecs=true, preserveConstantLoops=true,
        vecLayout=VerilogAggregateOptions.UnpackedArray)))
    config.netlistFileName = "tree.v"
    val clock = ClockDomainConfig(resetKind=ASYNC, resetActiveLevel=LOW)
    def count = ElabInt.literal(default)
    MorphVerilog(config) {
      import nativeapplication.NativeRecursiveTreeFixture
      kind match {
        case "signed" => new NativeRecursiveTreeFixture(HardType(SInt(8 bits)), 8, count,
          "TypedSignedTree", clockConfig=clock, guardedPairs=guardedPairs, operation=(a: SInt,b: SInt) => Mux(a > b,a,b))
        case "bits" => new NativeRecursiveTreeFixture(HardType(Bits(8 bits)), 8, count,
          "TypedBitsTree", clockConfig=clock, guardedPairs=guardedPairs, operation=(a: Bits,b: Bits) => a | b)
        case "bundle" =>
          case class Word() extends Bundle { val value=UInt(8 bits); val valid=Bool() }
          new NativeRecursiveTreeFixture(HardType(Word()), 8, count, "TypedBundleTree", clockConfig=clock, guardedPairs=guardedPairs,
            operation=(a: Word,b: Word) => { val r=Word(); r.value := a.value+b.value; r.valid := a.valid && b.valid; r })
        case "bridge" | "register" => new NativeRecursiveTreeFixture(HardType(UInt(8 bits)), 8, count,
          "TypedBridgedTree", clockConfig=clock, guardedPairs=guardedPairs, operation=(a: UInt,b: UInt) => a+b,
          levelBridge=(a: UInt,level: Int) => if(kind=="bridge") a+U(level+1,8 bits)
            else if(level==1) RegNext(a) init(0) else a)
        case "sync" => new NativeRecursiveTreeFixture(HardType(UInt(8 bits)), 8, count,
          "TypedSyncTree", clockConfig=ClockDomainConfig(resetKind=SYNC, resetActiveLevel=HIGH), guardedPairs=guardedPairs,
          operation=(a: UInt,b: UInt) => a+b)
        case "uint" => new NativeRecursiveTreeFixture(HardType(UInt(8 bits)), 8, count,
          "TypedUnsignedTree", clockConfig=clock, guardedPairs=guardedPairs, operation=(a: UInt,b: UInt) => a-b)
        case _ => throw new IllegalArgumentException("unknown recursive fixture")
      }
    }
    val files = directory.toFile.listFiles().filter(_.getName.endsWith(".v"))
    assert(files.length == 1)
    new String(Files.readAllBytes(files.head.toPath), "UTF-8")
  }
  def main(args: Array[String]): Unit = {
    require(args.length == 1, "supply an output directory")
    for(guarded <- Seq(false,true); default <- Seq(1,5); kind <- kinds)
      generate(Paths.get(args(0)).resolve(if(guarded) "guarded" else "ordinary").resolve(s"default-$default/$kind"), default, kind, guarded)
  }
}

class RecursiveUnsignedSumTests extends AnyFunSuite {
  for(unpacked <- Seq(false,true))
  test(s"two-term recursive domain retains exhaustive guarded driver unpacked=$unpacked") {
    MorphVerilog(SpinalConfig(targetDirectory=Files.createTempDirectory("two-term-recursive-").toString,
      flags=scala.collection.mutable.HashSet[Any](VerilogAggregateOptions(
        preserveConstantVecs=true,preserveConstantLoops=true,
        vecLayout=if(unpacked) VerilogAggregateOptions.UnpackedArray else VerilogAggregateOptions.PackedVector)))) {
      new nativeapplication.NativeRecursiveTreeFixture(HardType(UInt(8 bits)),2,ElabInt.literal(1),"TwoTermSum",
        guardedPairs=true,operation=(a: UInt,b: UInt) => a+b)
    }
  }

  for(neverTrue <- Seq(false,true))
  test(s"finite affine guards omit only domain-empty alternatives neverTrue=$neverTrue") {
    val directory=Files.createTempDirectory("affine-empty-alternative-")
    MorphVerilog(SpinalConfig(targetDirectory=directory.toString,
      flags=scala.collection.mutable.HashSet[Any](VerilogAggregateOptions(
        preserveConstantVecs=true,preserveConstantLoops=true,vecLayout=VerilogAggregateOptions.UnpackedArray)))) {
      new Component {
        @dontName val count=HdlInt.param("COUNT",2,2,2).asElabInt
        val lanes=in(Vec(UInt(8 bits),count))
        val combined=out(Vec(UInt(8 bits),(count+1)/2))
        ElabFiniteRange.foreach((count+1)/2,"combine") { i =>
          i.whenAffineLessThan(2,if(neverTrue) 2 else 1,count,"pair") {
            if(neverTrue) sys.error("proved empty true branch was elaborated")
            i(combined) := i.affine(lanes,2,0)+i.affine(lanes,2,1)
          } {
            if(!neverTrue) sys.error("proved empty false branch was elaborated")
            i(combined) := i.affine(lanes,2,0)
          }
        }
      }
    }
  }

  for(cycle <- Seq(false, true))
  test(s"guarded affine bounds cannot authorize same-loop dependencies cycle=$cycle") {
    val error = intercept[MorphVerilogException] {
      MorphVerilog(SpinalConfig(targetDirectory=Files.createTempDirectory("guarded-dependency-").toString,
        flags=scala.collection.mutable.HashSet[Any](VerilogAggregateOptions(
          preserveConstantVecs=true,preserveConstantLoops=true,vecLayout=VerilogAggregateOptions.UnpackedArray)))) {
        new Component {
          @dontName val count=HdlInt.param("COUNT",4,2,8).asElabInt
          val input=in(UInt(8 bits))
          val entries=Vec(UInt(8 bits),count)
          val output=out Bits((count*8) bits)
          ElabFiniteRange.foreach(count,"cells") { i =>
            i.whenAffineLessThan(1,1,count,"next") {
              i(entries) := i.affine(entries,1,1)
            } {
              i(entries) := (if(cycle) entries(0) ^ U(1,8 bits) else input)
            }
          }
          output := entries.asBits
        }
      }
    }
    assert(error.getMessage.contains("SPINAL-ELAB-FINITE-GUARDED-DEPENDENCY-UNSUPPORTED"), error.getMessage)
  }

  for(mode <- Seq("control", "unguarded", "wrong-guard", "missing-tail", "overlap", "foreign-bound", "overflow", "escaped"))
  test(s"guarded affine pair loop domain case=$mode") {
    def generate(): Unit = {
      MorphVerilog(SpinalConfig(targetDirectory=Files.createTempDirectory("guarded-affine-negative-").toString,
        flags=scala.collection.mutable.HashSet[Any](VerilogAggregateOptions(
          preserveConstantVecs=true,preserveConstantLoops=true,vecLayout=VerilogAggregateOptions.UnpackedArray)))) {
        new Component {
          @dontName val count=HdlInt.param("COUNT",4,1,8).asElabInt
          @dontName val other=HdlInt.param("OTHER",4,1,8).asElabInt
          val lanes=in(Vec(UInt(8 bits),count))
          val combined=out(Vec(UInt(8 bits),(count+1)/2))
          @dontName var escaped: ElabFiniteIndex = null
          ElabFiniteRange.foreach((count+1)/2,"combine") { i =>
            escaped=i
            if(mode=="unguarded") i(combined) := i.affine(lanes,2,1)
            else i.whenAffineLessThan(if(mode=="overflow") Int.MaxValue else 2,
              if(mode=="wrong-guard") 0 else 1,if(mode=="foreign-bound") other else count,"pair") {
              val target=i(combined)
              target := i.affine(lanes,2,0)-i.affine(lanes,2,1)
              if(mode=="overlap") target := 0
            } {
              if(mode!="missing-tail") i(combined) := i.affine(lanes,2,0)
            }
          }
          if(mode=="escaped") {
            val invalid=out(UInt(8 bits))
            invalid := escaped.affine(lanes,2,0)
          }
        }
      }
    }
    if(mode == "control") generate() else intercept[MorphVerilogException](generate())
  }

  for(guarded <- Seq(false,true); default <- Seq(1, 5); kind <- NativeRecursiveTreeArtifactWriter.kinds)
  test(s"typed recursive interface retains clocked payload kind=$kind default=$default guarded=$guarded") {
    def generate(): String = NativeRecursiveTreeArtifactWriter.generate(
      Files.createTempDirectory("typed-recursive-tree-"), default, kind, guarded)
    val rtl=generate()
    assert("(?m)^module ".r.findAllIn(rtl).size == 1, rtl)
    assert("(?m)^\\s*Typed[A-Za-z]+Tree #".r.findAllIn(rtl).size == 1, rtl)
    assert(rtl.contains(".COUNT") && rtl.contains(".INITIAL_COUNT") && rtl.contains(".PIPELINE_MASK"),rtl)
    if(guarded) { assert(rtl.contains("begin : g_combine_"),rtl); assert(!rtl.contains("wire [7:0] paired"),rtl) }
    assert(rtl == generate())
  }

  for(mode <- Seq("duplicate", "foreign", "expression", "escaped", "nondecreasing", "unguarded", "geometry", "name"))
  test(s"typed recursive declaration rejects $mode") {
    intercept[MorphVerilogException] {
      MorphVerilog(SpinalConfig(targetDirectory=Files.createTempDirectory("typed-recursive-negative-").toString)) {
        new Component {
          setDefinitionName("TypedNegative")
          @dontName val count=HdlInt.param("COUNT",3,1,8).asElabInt
          class Ports(n: ElabInt, extra: Int=0) extends Bundle {
            val input=in Bits((n*4+extra) bits)
            val output=out Bits(4 bits)
          }
          var calls=0
          @dontName val recursive=ElabRecursiveComponent(Vector(if(mode=="expression") count+1 else count)) { v =>
            calls+=1; new Ports(v.head, if(mode=="geometry" && calls>1) 1 else 0)
          }
          val io=recursive.ports
          def child(): Unit = {
            val next=count/2
            val r=mode match {
              case "duplicate" => recursive(count -> next,count -> next)
              case "foreign" => recursive((count+0) -> next)
              case "nondecreasing" => recursive()
              case "name" => setDefinitionName("Changed"); recursive(count -> next)
              case _ => recursive(count -> next)
            }
            r.ports.input := 0
            io.output := r.ports.output
          }
          if(mode=="escaped") {
            val foreign = new Component { child() }
          } else if(mode=="unguarded") child()
          else ElabControl.selectSymbolic(count <= 1,"TypedNegative",1) { io.output := 0 } { child() }
        }
      }
    }
  }

  for(default <- Seq(1,16); split <- Seq(false,true))
  test(s"one recursive unsigned definition retains both branches default=$default split=$split") {
    val dir = Files.createTempDirectory("recursive-sum-")
    val rtl = RecursiveUnsignedSumArtifactWriter.generate(dir,default,split)
    assert("(?m)^module RecursiveUnsignedSum".r.findAllIn(rtl).size == 1)
    assert("(?m)^\\s*RecursiveUnsignedSum #".r.findAllIn(rtl).size == 2, rtl)
    assert(rtl.contains(".COUNT      ((COUNT / 2)"),rtl)
    assert(rtl.contains(".COUNT      ((COUNT - (COUNT / 2))"),rtl)
    assert(rtl.contains("terms[0 +: ((COUNT / 2) * TERM_WIDTH)]"),rtl)
    assert(rtl.contains("terms[((COUNT / 2) * TERM_WIDTH) +: ((COUNT - (COUNT / 2)) * TERM_WIDTH)]"),rtl)
    assert(rtl.contains(") left (") && rtl.contains(") right ("),rtl)
    assert(rtl.contains("COUNT must be in 1..17"),rtl)
    assert(!rtl.contains("$fatal") && rtl.contains("$finish"),rtl)
    assert(!rtl.contains("posedge") && !rtl.contains("negedge"),rtl)
    assert(rtl == RecursiveUnsignedSumArtifactWriter.generate(Files.createTempDirectory("recursive-sum-repeat-"),default,split))
  }

  for (reverse <- Seq(false, true); split <- Seq(false, true))
  test(s"recursive unsigned children share one definition reverse=$reverse split=$split") {
    val dir = Files.createTempDirectory("recursive-parent-")
    MorphVerilog(SpinalConfig(targetDirectory=dir.toString, oneFilePerComponent=split)) {
      new Component {
        val a = in Bits(ElabInt.literal(3*4) bits)
        val b = in Bits(ElabInt.literal(16*4) bits)
        val x = out UInt(ElabInt.literal(8) bits)
        val y = out UInt(ElabInt.literal(8) bits)
        val reductions = (if(reverse) Seq(16,3) else Seq(3,16)).map { n =>
          n -> new RecursiveUnsignedSum(ElabInt.literal(n),ElabInt.literal(4),ElabInt.literal(8),17,32,36)
        }.toMap
        val first = reductions(3)
        val second = reductions(16)
        first.terms := a; second.terms := b
        x := first.sum; y := second.sum
      }
    }
    val files = dir.toFile.listFiles().filter(_.getName.endsWith(".v"))
    val all = files.map(f => new String(Files.readAllBytes(f.toPath), "UTF-8")).mkString("\n")
    assert("(?m)^module RecursiveUnsignedSum".r.findAllIn(all).size == 1, all)
    assert(all.contains(".COUNT"), all)
  }

  test("recursive unsigned component retains a symbolic parent count binding") {
    val dir = Files.createTempDirectory("recursive-symbolic-parent-")
    MorphVerilog(SpinalConfig(targetDirectory=dir.toString)) {
      new Component {
        setDefinitionName("SymbolicSumParent")
        @dontName val count = HdlInt.param("TERMS",3,1,17).asElabInt
        val terms = in Bits((count * 4) bits)
        val sum = out UInt(ElabInt.literal(8) bits)
        val child = new RecursiveUnsignedSum(count,ElabInt.literal(4),ElabInt.literal(8),17,32,36)
        child.terms := terms
        sum := child.sum
      }
    }
    val rtl = new String(Files.readAllBytes(dir.resolve("SymbolicSumParent.v")),"UTF-8")
    assert("\\.COUNT\\s*\\(TERMS\\s*\\)".r.findFirstIn(rtl).nonEmpty,rtl)
  }

  test("recursive unsigned components reject conflicting definition domains") {
    intercept[MorphVerilogException] {
      MorphVerilog(SpinalConfig(targetDirectory=Files.createTempDirectory("recursive-conflict-").toString)) {
        new Component {
          val terms = in Bits(ElabInt.literal(12) bits)
          val sums = out Bits(ElabInt.literal(16) bits)
          val first = new RecursiveUnsignedSum(ElabInt.literal(3),ElabInt.literal(4),ElabInt.literal(8),17,32,36)
          val second = new RecursiveUnsignedSum(ElabInt.literal(3),ElabInt.literal(4),ElabInt.literal(8),16,32,36)
          first.terms := terms; second.terms := terms
          sums := first.sum.asBits ## second.sum.asBits
        }
      }
    }
  }

  for((count,term,sum) <- Seq((0,4,8),(-1,4,8),(18,4,8),(3,0,8),(3,-1,8),(3,33,8),(3,4,0),(3,4,-1),(3,4,37)))
  test(s"recursive unsigned API rejects invalid dimensions $count $term $sum") {
    intercept[MorphVerilogException] {
      MorphVerilog(SpinalConfig(targetDirectory=Files.createTempDirectory("recursive-invalid-").toString))(
        new RecursiveUnsignedSum(ElabInt.literal(count),ElabInt.literal(term),ElabInt.literal(sum),17,32,36))
    }
  }

  for(mode <- Seq("nondecreasing","out-of-domain","geometry","missing-base","competing"))
  test(s"recursive unsigned child validation rejects $mode") {
    val error = intercept[MorphVerilogException] {
      MorphVerilog(SpinalConfig(targetDirectory=Files.createTempDirectory("recursive-invalid-child-").toString))(
        new RecursiveUnsignedSumNegativeFixture.Broken(mode))
    }
    if(mode=="geometry") assert(error.getMessage.contains("RECURSION-PORT-SCHEMA-MISMATCH"))
    if(mode=="nondecreasing") assert(error.getMessage.contains("RECURSION-METRIC-NONDECREASING"))
    if(mode=="missing-base") assert(error.getMessage.contains("RECURSION-BINDING-UNPROVEN"))
  }
}
