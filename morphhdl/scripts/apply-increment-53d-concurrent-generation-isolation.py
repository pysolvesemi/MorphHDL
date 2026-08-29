#!/usr/bin/env python3
from pathlib import Path
from textwrap import dedent


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"expected exactly one {label}, found {count}")
    return text.replace(old, new, 1)


morph = Path("morphhdl/src/main/scala/morphhdl/MorphVerilog.scala")
text = morph.read_text(encoding="utf-8")
text = replace_once(
    text,
    "object MorphVerilog {\n",
    dedent('''
        object MorphVerilog {
          /**
            * One single-source generation is a transaction across native SpinalHDL
            * elaboration and several MorphHDL exact-object registries. Individual
            * registry operations are synchronized, but their capture, rewrite and
            * cleanup phases must not interleave with another generation in the same
            * JVM. The monitor is deliberately process-wide and reentrant; ordinary
            * concrete SpinalVerilog generation remains outside this boundary.
            */
          private val singleSourceGenerationMonitor = new AnyRef
    ''').lstrip(),
    "MorphVerilog object declaration",
)
old_run = """  private def runSingleSource[T <: Component](
      config: SpinalConfig,
      component: => T
  ): Either[MorphVerilogFailure, MorphSingleSourceVerilogReport] =
    createSingleSourceDirectory() match {
"""
new_run = """  private def runSingleSource[T <: Component](
      config: SpinalConfig,
      component: => T
  ): Either[MorphVerilogFailure, MorphSingleSourceVerilogReport] =
    singleSourceGenerationMonitor.synchronized {
      runSingleSourceExclusive(config, component)
    }

  private def runSingleSourceExclusive[T <: Component](
      config: SpinalConfig,
      component: => T
  ): Either[MorphVerilogFailure, MorphSingleSourceVerilogReport] =
    createSingleSourceDirectory() match {
"""
text = replace_once(text, old_run, new_run, "single-source generation entry")
if text.count("singleSourceGenerationMonitor.synchronized") != 1:
    raise SystemExit("single-source transaction monitor is not used exactly once")
if text.count("runSingleSourceExclusive") != 2:
    raise SystemExit("exclusive single-source helper is incomplete")
morph.write_text(text, encoding="utf-8")

regression = Path(
    "morphhdl/src/test/scala/morphhdl/ConcurrentMorphVerilogIsolationTests.scala"
)
regression.write_text(
    dedent('''
        package morphhdl

        import java.nio.charset.StandardCharsets
        import java.nio.file.{Files, Path}
        import java.util.concurrent.{CountDownLatch, Executors}

        import scala.collection.JavaConverters._
        import scala.concurrent.duration._
        import scala.concurrent.{Await, ExecutionContext, Future}

        import org.scalatest.funsuite.AnyFunSuite
        import spinal.core.SpinalConfig

        import morphhdl.frontend.HdlInt

        /**
          * Regression for the complete single-source generation transaction.
          * StreamFifo constructor-depth capture and StreamWidthAdapter widthOf
          * capture intentionally execute from independent worker threads in one JVM.
          */
        class ConcurrentMorphVerilogIsolationTests extends AnyFunSuite {
          private final case class Generated(
              kind: String,
              parameterNames: Vector[String],
              verilog: String
          )

          test("parallel native library generations keep independent symbolic state") {
            val root = Files.createTempDirectory("morphhdl-concurrent-generation-")
            val executor = Executors.newFixedThreadPool(4)
            implicit val executionContext: ExecutionContext =
              ExecutionContext.fromExecutorService(executor)
            val start = new CountDownLatch(1)

            try {
              val work = Vector.tabulate(4) { index =>
                Future {
                  start.await()
                  val directory = root.resolve(s"run-$index")
                  Files.createDirectories(directory)
                  val config = SpinalConfig(targetDirectory = directory.toString)

                  if ((index & 1) == 0) {
                    config.netlistFileName = "fifo.v"
                    val depth = HdlInt.param(
                      "DEPTH",
                      default = BigInt(5),
                      min = BigInt(1),
                      max = BigInt(8)
                    )
                    val report = MorphVerilog(config) {
                      new NativeParameterizedStreamFifoHarness(depth)
                    }
                    Generated(
                      "fifo",
                      report.parameters.map(_.name),
                      read(directory.resolve("fifo.v"))
                    )
                  } else {
                    config.netlistFileName = "adapter.v"
                    val report = MorphVerilog(config) {
                      ParameterizedStreamWidthAdapterSmoke.component()
                    }
                    Generated(
                      "adapter",
                      report.parameters.map(_.name),
                      read(directory.resolve("adapter.v"))
                    )
                  }
                }
              }

              start.countDown()
              val generated = Await.result(Future.sequence(work), 20.minutes)
              assert(generated.count(_.kind == "fifo") == 2)
              assert(generated.count(_.kind == "adapter") == 2)

              generated.foreach {
                case Generated("fifo", names, verilog) =>
                  assert(names == Vector("DEPTH"))
                  assert(verilog.contains("parameter integer DEPTH = 5"))
                  assert(verilog.contains(".DEPTH(DEPTH)"))
                  assert(verilog.contains("clog2(DEPTH, 1)"))
                case Generated("adapter", names, verilog) =>
                  assert(names == Vector("DOWN_WIDTH", "EQ_WIDTH", "UP_WIDTH"))
                  assert(verilog.contains("parameter integer EQ_WIDTH = 8"))
                  assert(verilog.contains("parameter integer DOWN_WIDTH = 12"))
                  assert(verilog.contains("parameter integer UP_WIDTH = 12"))
                  assert(verilog.contains(".WIDTH(EQ_WIDTH)"))
                  assert(verilog.contains(".INPUT_WIDTH(DOWN_WIDTH)"))
                  assert(verilog.contains(".OUTPUT_WIDTH(UP_WIDTH)"))
                case unexpected =>
                  fail(s"unexpected concurrent generation result: $unexpected")
              }
            } finally {
              executor.shutdownNow()
              deleteRecursively(root)
            }
          }

          private def read(path: Path): String =
            new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

          private def deleteRecursively(root: Path): Unit = {
            if (root != null && Files.exists(root)) {
              val paths = Files.walk(root)
              try {
                paths.iterator().asScala.toVector
                  .sortBy(_.getNameCount)
                  .reverse
                  .foreach(path => Files.deleteIfExists(path))
              } finally paths.close()
            }
          }
        }
    ''').lstrip(),
    encoding="utf-8",
)

workflow = Path(".github/workflows/morphhdl-native-stream-width-adapter.yml")
value = workflow.read_text(encoding="utf-8")
if "morphhdl.ConcurrentMorphVerilogIsolationTests" not in value:
    lines = value.splitlines()
    indexes = [
        index
        for index, line in enumerate(lines)
        if "morphhdl.GenericNativeDefinitionBoundaryTests" in line
    ]
    if len(indexes) != 1:
        raise SystemExit(
            f"expected one native-boundary workflow marker, found {len(indexes)}"
        )
    lines.insert(
        indexes[0] + 1,
        "            morphhdl.ConcurrentMorphVerilogIsolationTests \\",
    )
    value = "\n".join(lines) + "\n"
workflow.write_text(value, encoding="utf-8")

doc = Path("docs/morphhdl/increment-53d-native-streamwidth-adapter.md")
value = doc.read_text(encoding="utf-8")
section = dedent('''

    ## Concurrent generation isolation closure

    A single-source MorphHDL generation is one transaction spanning native
    SpinalHDL elaboration, exact-object symbolic capture, graph rewrite and
    registry cleanup. Those phases now execute under one reentrant process-wide
    monitor, so SBT may continue running independent test suites in parallel
    without allowing two parameterized generations to exchange constructor or
    `widthOf(Data)` evidence. Ordinary concrete `SpinalVerilog` generation is
    not serialized by this MorphHDL boundary.

    A dedicated dual-Scala regression starts native StreamFifo depth capture and
    native StreamWidthAdapter width-function capture from independent worker
    threads in the same JVM. Each result must retain only its own public
    parameters and exact native-library geometry.
''').rstrip() + "\n"
if "## Concurrent generation isolation closure" not in value:
    value = value.rstrip() + section
doc.write_text(value, encoding="utf-8")
