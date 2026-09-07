package morphhdl.passes.api

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import scala.sys.process.{Process, ProcessLogger}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** Historical stage selection must not omit dependencies of the shared pipeline. */
final class NativeRunnerSourceClosureSpec extends AnyFunSuite with Matchers {
  private val scripts = {
    val local = Paths.get("scripts")
    if (Files.isRegularFile(local.resolve("run-wa07-regression.sh"))) local
    else Paths.get("morphhdl-passes", "scripts")
  }
  private val runners = Vector("run-wa06-regression.sh", "run-wa07-regression.sh")
  private val registration =
    "'set morph / Test / unmanagedSourceDirectories += file(\"morphhdl-passes/src/main/scala\")' \\"

  private def read(runner: String): String =
    new String(Files.readAllBytes(scripts.resolve(runner)), StandardCharsets.UTF_8)

  private def hasCompleteSources(script: String): Boolean =
    script.split("\n").iterator.map(_.trim)
      .dropWhile(_ != "sbt -batch \\")
      .takeWhile(!_.contains("morph / Test / runMain"))
      .contains(registration)

  test("historical native runners compile the complete pass workspace before first emission") {
    runners.foreach { runner =>
      withClue(runner + ": shared pipeline dependencies must not be a stale per-file list: ") {
        hasCompleteSources(read(runner)) shouldBe true
      }
    }
  }

  test("source-closure guard rejects missing commented late and partial registration") {
    runners.foreach { runner =>
      val original = read(runner)
      withClue(runner + ": ") {
        hasCompleteSources(original) shouldBe true
        hasCompleteSources(original.replace(registration, "")) shouldBe false
        hasCompleteSources(original.replace(registration, "# " + registration)) shouldBe false
        hasCompleteSources(original.replace(registration, "") + "\n" + registration) shouldBe false
        hasCompleteSources(original.replace("unmanagedSourceDirectories", "unmanagedSources")) shouldBe false
      }
    }
  }
  test("historical native report validation rejects false flag claims and incomplete execution evidence") {
    val output = new StringBuilder
    val log = ProcessLogger(line => { output.append(line).append('\n'); () },
      line => { output.append(line).append('\n'); () })
    val status = Process(Seq("python3", scripts.resolve("test_historical_native_reports.py").toString)).!(log)
    withClue(output.toString) { status shouldBe 0 }
    output.toString should include("WA07B_HISTORICAL_REPORT_PASS reports=3 rejected_mutations=33")
  }
}
