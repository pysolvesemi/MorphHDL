package spinal.core

import java.nio.file.Files

import scala.collection.JavaConverters._

import org.scalatest.funsuite.AnyFunSuite

class NativeIntFormalizationAtomicityTests extends AnyFunSuite {
  private final class RegionRetryProbe extends Component {
    setDefinitionName("NativeIntFormalizationRegionRetryProbe")

    val first = out(Bits(8 bits))
    val second = out(Bits(8 bits))
    first := 0
    second := 0

    val sourceExpression = ElabInt.literal(8).expression
    private val token = ExternalNativeIntFormalizationToken.region(
      this,
      sourceExpression,
      callSite = "native-int-formalization-atomicity",
      valueOrigin = "native-int-formalization-atomicity",
      role = "formalRegion atomicity test"
    )
    private val capture = ExternalNativeIntShadowRegistry.capture(
      this,
      sourceExpression,
      token,
      argumentName = "regionArgument"
    ) {
      first
    }

    val copiedExpressionCode =
      try {
        ExternalNativeIntFormalizationRegistry.attachRegionAtomically(
          this,
          first,
          sourceExpression.copy(),
          formalBinding = None,
          capture = capture
        )
        "<accepted>"
      } catch {
        case error: ParameterizedVerilogException => error.code
      }
    val copiedExpressionRegistriesClean =
      ExternalNativeIntFormalizationRegistry.regionOf(first).isEmpty &&
        ExternalNativeIntShadowRegistry.regionOf(first).isEmpty

    val failedCode =
      try {
        ExternalNativeIntFormalizationRegistry.attachRegionAtomically(
          this,
          second,
          sourceExpression,
          formalBinding = None,
          capture = capture
        )
        "<accepted>"
      } catch {
        case error: ParameterizedVerilogException => error.code
      }
    val failedFormalRegistryClean =
      ExternalNativeIntFormalizationRegistry.regionOf(first).isEmpty &&
        ExternalNativeIntFormalizationRegistry.regionOf(second).isEmpty
    val failedShadowRegistryClean =
      ExternalNativeIntShadowRegistry.regionOf(first).isEmpty &&
        ExternalNativeIntShadowRegistry.regionOf(second).isEmpty

    ExternalNativeIntFormalizationRegistry.attachRegionAtomically(
      this,
      first,
      sourceExpression,
      formalBinding = None,
      capture = capture
    )
    val retryFormalized =
      ExternalNativeIntFormalizationRegistry.regionOf(first).nonEmpty
    val retryShadowed = ExternalNativeIntShadowRegistry.regionOf(first).nonEmpty
    val retainedToken =
      ExternalNativeIntFormalizationRegistry.regionOf(first).get.token
    val replayCode =
      try {
        ExternalNativeIntFormalizationRegistry.attachRegionAtomically(
          this,
          first,
          sourceExpression,
          formalBinding = None,
          capture = capture
        )
        "<accepted>"
      } catch {
        case error: ParameterizedVerilogException => error.code
      }
    val replayRegistriesStable =
      ExternalNativeIntFormalizationRegistry
        .regionOf(first)
        .exists(_.token eq retainedToken) &&
        ExternalNativeIntShadowRegistry
          .regionOf(first)
          .exists(_.boundaryToken eq retainedToken)
  }

  test("shadow-side preflight failure leaves both registries clean and token retryable") {
    val directory = Files.createTempDirectory("native-int-formalization-atomicity-")
    try {
      var probe: RegionRetryProbe = null
      SpinalVerilog(SpinalConfig(targetDirectory = directory.toString)) {
        probe = new RegionRetryProbe
        probe
      }

      assert(probe.failedCode == "MORPH-FRONTEND-NATIVE-INT-SHADOW-RESULT-MISMATCH")
      assert(
        probe.copiedExpressionCode ==
          "MORPH-FRONTEND-NATIVE-INT-FORMALIZATION-TARGET-MISMATCH"
      )
      assert(probe.copiedExpressionRegistriesClean)
      assert(probe.failedFormalRegistryClean)
      assert(probe.failedShadowRegistryClean)
      assert(probe.retryFormalized)
      assert(probe.retryShadowed)
      assert(
        probe.replayCode ==
          "MORPH-FRONTEND-NATIVE-INT-FORMALIZATION-TARGET-MISMATCH"
      )
      assert(probe.replayRegistriesStable)
      assert(
        !retainsExactGraphIdentity(
          probe.retainedToken,
          Vector(probe, probe.first, probe.sourceExpression)
        ),
        "successful formalization token retained its weak-key graph identities"
      )
    } finally {
      val stream = Files.walk(directory)
      try {
        stream.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach { path =>
          Files.deleteIfExists(path)
        }
      } finally stream.close()
    }
  }

  private def retainsExactGraphIdentity(
      token: ExternalNativeIntFormalizationToken,
      forbidden: Vector[AnyRef]
  ): Boolean =
    token.getClass.getDeclaredFields.exists { field =>
      field.setAccessible(true)
      containsExactIdentity(field.get(token), forbidden)
    }

  private def containsExactIdentity(
      value: Any,
      forbidden: Vector[AnyRef]
  ): Boolean = value match {
    case null                                                  => false
    case reference: AnyRef if forbidden.exists(_ eq reference) => true
    case values: Iterable[_] =>
      values.exists(containsExactIdentity(_, forbidden))
    case values: Array[_] =>
      values.exists(containsExactIdentity(_, forbidden))
    case _ => false
  }
}
