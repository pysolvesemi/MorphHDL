#!/usr/bin/env python3
from pathlib import Path


def replace_once(text: str, old: str, new: str, role: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{role}: expected one exact match, found {count}")
    return text.replace(old, new, 1)


# Retain correlated native range endpoints inside the existing shadow registry.
registry_path = Path(
    "morphruntime/src/main/scala/spinal/core/ExternalNativeIntShadowRegistry.scala"
)
registry = registry_path.read_text(encoding="utf-8")
if "def definitionRangeTracked(" not in registry:
    anchor = """    definition
  }

  /**
    * Resolve one proven native Boolean predicate in canonical definition scope.
"""
    insertion = """    definition
  }

  /**
    * Resolve one compiler-proven descending Scala range while its native
    * formalization boundary is active. The offset and width are lowered from
    * the shared relative-expression graph, and the width domain is evaluated
    * exactly so correlated endpoints such as `(PTR_WIDTH - 1) downto
    * (PTR_WIDTH - 2)` remain a constant two-bit slice.
    */
  def definitionRangeTracked(
      highReference: String,
      highWitness: Int,
      highLiteral: Boolean,
      lowReference: String,
      lowWitness: Int,
      lowLiteral: Boolean,
      sourceLocation: String
  ): Option[(ElaborationIntegerExpression, ElaborationIntegerExpression)] =
    currentBoundary.map { boundary =>
      val high = resolveTracked(
        boundary,
        highWitness,
        highReference,
        highLiteral,
        sourceLocation,
        role = "descending range high endpoint"
      )
      val low = resolveTracked(
        boundary,
        lowWitness,
        lowReference,
        lowLiteral,
        sourceLocation,
        role = "descending range low endpoint"
      )
      val widthRelative = ExternalNativeIntRelativeExpression.Add(
        ExternalNativeIntRelativeExpression.Subtract(
          high.expression,
          low.expression
        ),
        ExternalNativeIntRelativeExpression.Literal(BigInt(1))
      )
      val highDefinition = lowerFinalExpression(
        high.expression,
        boundary.definitionExpression,
        sourceLocation
      )
      val offset = lowerFinalExpression(
        low.expression,
        boundary.definitionExpression,
        sourceLocation
      )

      val domainMinimum = boundary.definitionExpression.minimum
      val domainMaximum = boundary.definitionExpression.maximum
      val domainSize = domainMaximum - domainMinimum + 1
      if (
        domainSize < 1 ||
        domainSize > MaximumStructuralPredicateDomainSize
      ) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-SLICE-DOMAIN-TOO-LARGE",
          s"native parameterized slice requires exact validation over $domainSize definition values; maximum is $MaximumStructuralPredicateDomainSize",
          Option(sourceLocation).filter(_.nonEmpty)
        )
      }

      var root = domainMinimum
      var minimumWidth: Option[BigInt] = None
      var maximumWidth: Option[BigInt] = None
      while (root <= domainMaximum) {
        val highValue = ExternalNativeIntRelativeExpression
          .evaluate(high.expression, root)
          .getOrElse {
            fail(
              "SPINAL-PARAMETERIZED-VERILOG-SLICE-ENDPOINT-UNDEFINED",
              s"native slice high endpoint is undefined at ${boundary.definitionExpression.verilog}=$root",
              Option(sourceLocation).filter(_.nonEmpty)
            )
          }
        val lowValue = ExternalNativeIntRelativeExpression
          .evaluate(low.expression, root)
          .getOrElse {
            fail(
              "SPINAL-PARAMETERIZED-VERILOG-SLICE-ENDPOINT-UNDEFINED",
              s"native slice low endpoint is undefined at ${boundary.definitionExpression.verilog}=$root",
              Option(sourceLocation).filter(_.nonEmpty)
            )
          }
        val currentWidth = highValue - lowValue + 1
        if (lowValue < 0 || currentWidth < 1) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-SLICE-DOMAIN-INVALID",
            s"native descending slice [$highValue:$lowValue] is invalid at ${boundary.definitionExpression.verilog}=$root",
            Option(sourceLocation).filter(_.nonEmpty)
          )
        }
        minimumWidth = Some(minimumWidth.fold(currentWidth)(_.min(currentWidth)))
        maximumWidth = Some(maximumWidth.fold(currentWidth)(_.max(currentWidth)))
        root += 1
      }

      val groupedParameters =
        (highDefinition.parameters ++ offset.parameters).groupBy(_.name)
      groupedParameters.collectFirst {
        case (name, declarations) if declarations.distinct.size != 1 => name
      }.foreach { name =>
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-SCHEMA-CONFLICT",
          s"native parameterized slice carries conflicting declarations for '$name'",
          Option(sourceLocation).filter(_.nonEmpty)
        )
      }
      val width = ElaborationIntegerExpression(
        verilog =
          s"((${highDefinition.verilog}) - (${offset.verilog}) + 1)",
        default = BigInt(highWitness) - BigInt(lowWitness) + 1,
        minimum = minimumWidth.get,
        maximum = maximumWidth.get,
        parameters = groupedParameters.toVector.map(_._2.head).sortBy(_.name),
        sourceLocation = Option(sourceLocation).filter(_.nonEmpty)
      )
      if (
        offset.default != BigInt(lowWitness) ||
        width.default != BigInt(highWitness) - BigInt(lowWitness) + 1
      ) {
        fail(
          "MORPH-FRONTEND-NATIVE-INT-SHADOW-DEFAULT-MISMATCH",
          s"native range witness [$highWitness:$lowWitness] disagrees with symbolic offset ${offset.default} and width ${width.default}",
          Option(sourceLocation).filter(_.nonEmpty)
        )
      }
      retainDefinitionExpressionEvidence(
        offset,
        boundary.structuralPredicateRoot,
        low.expression
      )
      retainDefinitionExpressionEvidence(
        width,
        boundary.structuralPredicateRoot,
        widthRelative
      )
      offset -> width
    }

  /**
    * Resolve one proven native Boolean predicate in canonical definition scope.
"""
    registry = replace_once(
        registry,
        anchor,
        insertion,
        "native range definition capture",
    )
    registry_path.write_text(registry, encoding="utf-8")


# Runtime bridge: execute the exact native slice and attach identity metadata.
runtime_path = Path(
    "morphruntime/src/main/scala/spinal/core/ExternalNativeIntCompilerRuntime.scala"
)
runtime = runtime_path.read_text(encoding="utf-8")
if "def compilerSlice[" not in runtime:
    anchor = """  def compilerCopyShape[T <: Data](source: T)(native: => T): T = {
    val value = native
    if (boundaryActive) ParameterizedWidth.copyShape(source, value) else value
  }

  def compilerHardType[T <: Data](dataType: => T)(native: => HardType[T]): HardType[T] =
"""
    insertion = """  def compilerCopyShape[T <: Data](source: T)(native: => T): T = {
    val value = native
    if (boundaryActive) ParameterizedWidth.copyShape(source, value) else value
  }

  def compilerSlice[T <: BitVector](
      source: T,
      high: Int,
      highReference: String,
      highLiteral: Boolean,
      low: Int,
      lowReference: String,
      lowLiteral: Boolean,
      file: String,
      line: Int
  )(native: => T): T = {
    val result = native
    ExternalNativeIntShadowRegistry
      .definitionRangeTracked(
        highReference,
        high,
        highLiteral,
        lowReference,
        low,
        lowLiteral,
        rendered(file, line)
      )
      .foreach { case (offset, width) =>
        ExternalParameterizedSliceRegistry.attach(
          source,
          result,
          offset,
          width,
          Some(rendered(file, line))
        )
      }
    result
  }

  def compilerHardType[T <: Data](dataType: => T)(native: => HardType[T]): HardType[T] =
"""
    runtime = replace_once(
        runtime,
        anchor,
        insertion,
        "native slice runtime bridge",
    )
    runtime_path.write_text(runtime, encoding="utf-8")


# Compiler bridge: recognize only proven descending ranges in the native
# StreamFifoCC source and retain the source/result object identities.
plugin_path = Path(
    "morphplugin/src/main/scala/morphhdl/compiler/"
    "MorphHdlNativeIntShadowExpressionComponent.scala"
)
plugin = plugin_path.read_text(encoding="utf-8")
if "private def rewriteNativeSlice(" not in plugin:
    anchor = """    private def nativeValueCarrier(
        value: Rewrite,
        prototype: Tree,
        original: Tree,
        role: String
    ): Tree = {
"""
    insertion = """    private def descendingRangeBounds(
        tree: Tree
    ): Option[(Tree, Tree, Name)] = tree match {
      case Apply(Select(high, name), List(low))
          if decoded(name) == "downto" =>
        Some((high, low, name))
      case _ => None
    }

    private def rewriteNativeSlice(
        original: Tree,
        function: Tree,
        range: Tree
    ): Option[Rewrite] =
      descendingRangeBounds(range).map { case (highTree, lowTree, rangeName) =>
        val high = operand(highTree)
        val low = operand(lowTree)
        val sourceTree = function match {
          case Select(base, name) if decoded(name) == "apply" => base
          case other                                           => other
        }
        val transformedSource = super.transform(sourceTree)
        val transformedFunction = function match {
          case Select(_, name) if decoded(name) == "apply" =>
            val selected = Select(transformedSource, name)
            selected.setPos(function.pos)
          case _ => transformedSource
        }
        val transformedRange = Apply(
          Select(high.tree, rangeName),
          List(low.tree)
        )
        transformedRange.setPos(range.pos)
        val native = Apply(transformedFunction, List(transformedRange))
        native.setPos(original.pos)

        val proven = high.intReference.orElse(low.intReference)
        proven match {
          case None => Rewrite(native)
          case Some(reference)
              if (high.intReference.isEmpty && !high.intLiteral) ||
                (low.intReference.isEmpty && !low.intLiteral) =>
            unsupportedInt(
              reference,
              "MORPH-FRONTEND-NATIVE-INT-SLICE-ENDPOINT-UNPROVEN",
              "native descending range requires each endpoint to be a proven symbolic Int or exact integer literal",
              original,
              native
            )
          case Some(_) =>
            Rewrite(
              curriedCall(
                "compilerSlice",
                List(
                  transformedSource,
                  high.tree,
                  Literal(Constant(high.intReference.getOrElse(""))),
                  Literal(Constant(high.intLiteral)),
                  low.tree,
                  Literal(Constant(low.intReference.getOrElse(""))),
                  Literal(Constant(low.intLiteral))
                ) ++ sourceArguments(original),
                native,
                original
              )
            )
        }
      }

    private def nativeValueCarrier(
        value: Rewrite,
        prototype: Tree,
        original: Tree,
        role: String
    ): Tree = {
"""
    plugin = replace_once(
        plugin,
        anchor,
        insertion,
        "native slice compiler helper",
    )

    anchor = """      tree match {
        // ValDef initializers enter rewriteExpression directly and therefore
"""
    insertion = """      tree match {
        case application @ Apply(function, List(range))
            if inNativeStreamFifo &&
              nativeStreamFifoClassName.contains("StreamFifoCC") &&
              descendingRangeBounds(range).nonEmpty =>
          rewriteNativeSlice(application, function, range)
            .getOrElse(Rewrite(super.transform(tree)))
        // ValDef initializers enter rewriteExpression directly and therefore
"""
    plugin = replace_once(
        plugin,
        anchor,
        insertion,
        "native slice compiler dispatch",
    )
    plugin_path.write_text(plugin, encoding="utf-8")


# Final publication integration and metadata inventory.
publication_path = Path(
    "morphhdl/src/main/scala/spinal/core/internals/"
    "MorphHdlExternalParameterizedVerilog.scala"
)
publication = publication_path.read_text(encoding="utf-8")
if "ParameterizedVerilogSlices.rewrite" not in publication:
    old = """          if (requiresExpressionHierarchyRewrite(component)) {
            ExternalParameterizedVerilogNativeFallback.rewrite(
              component,
              withStructure,
              pc,
              canonicalOf
            )
          } else withStructure
"""
    new = """          val withExpressions =
            if (requiresExpressionHierarchyRewrite(component)) {
              ExternalParameterizedVerilogNativeFallback.rewrite(
                component,
                withStructure,
                pc,
                canonicalOf
              )
            } else withStructure
          ParameterizedVerilogSlices.rewrite(component, withExpressions)
"""
    publication = replace_once(
        publication,
        old,
        new,
        "slice publication ordering",
    )

    publication = replace_once(
        publication,
        """        ExternalParameterizedValueRegistry.parametersOf(component) ++
        ParameterizedStructure.parametersOf(component) ++
""",
        """        ExternalParameterizedValueRegistry.parametersOf(component) ++
        ExternalParameterizedSliceRegistry.parametersOf(component) ++
        ParameterizedStructure.parametersOf(component) ++
""",
        "publication parameter inventory",
    )

    publication = replace_once(
        publication,
        """      ExternalParameterizedValueRegistry.parametersOf(component).nonEmpty ||
      ParameterizedVerilogStructural.hasRegions(component) ||
""",
        """      ExternalParameterizedValueRegistry.parametersOf(component).nonEmpty ||
      ExternalParameterizedSliceRegistry.hasSlices(component) ||
      ParameterizedVerilogStructural.hasRegions(component) ||
""",
        "publication metadata gate",
    )

    publication = replace_once(
        publication,
        """  private def requiresPublicationRewrite(component: Component): Boolean =
    ParameterizedVerilogProcesses.hasLoops(component) ||
      ParameterizedVerilogStructural.hasRegions(component) ||
      requiresExpressionHierarchyRewrite(component)
""",
        """  private def requiresPublicationRewrite(component: Component): Boolean =
    ParameterizedVerilogProcesses.hasLoops(component) ||
      ParameterizedVerilogStructural.hasRegions(component) ||
      ExternalParameterizedSliceRegistry.hasSlices(component) ||
      requiresExpressionHierarchyRewrite(component)
""",
        "publication rewrite gate",
    )

    publication = replace_once(
        publication,
        """      ExternalParameterizedValueRegistry.parametersOf(component).nonEmpty ||
      ParameterizedProcess.parametersOf(component).nonEmpty ||
      component.children.exists { child =>
""",
        """      ExternalParameterizedValueRegistry.parametersOf(component).nonEmpty ||
      ExternalParameterizedSliceRegistry.parametersOf(component).nonEmpty ||
      ParameterizedProcess.parametersOf(component).nonEmpty ||
      component.children.exists { child =>
""",
        "expression rewrite slice gate",
    )

    publication = replace_once(
        publication,
        """          ExternalParameterizedMemoryRegistry.parametersOf(child).nonEmpty ||
          ExternalParameterizedValueRegistry.parametersOf(child).nonEmpty
""",
        """          ExternalParameterizedMemoryRegistry.parametersOf(child).nonEmpty ||
          ExternalParameterizedValueRegistry.parametersOf(child).nonEmpty ||
          ExternalParameterizedSliceRegistry.parametersOf(child).nonEmpty
""",
        "child slice metadata gate",
    )
    publication_path.write_text(publication, encoding="utf-8")


fallback_path = Path(
    "morphhdl/src/main/scala/spinal/core/internals/"
    "ExternalParameterizedVerilogNativeFallback.scala"
)
fallback = fallback_path.read_text(encoding="utf-8")
if "ExternalParameterizedSliceRegistry.parametersOf(component)" not in fallback:
    fallback = replace_once(
        fallback,
        """        ParameterizedWidth.parametersOf(component).nonEmpty ||
          ExternalParameterizedMemoryRegistry.parametersOf(component).nonEmpty ||
          ExternalParameterizedValueRegistry.parametersOf(component).nonEmpty ||
""",
        """        ParameterizedWidth.parametersOf(component).nonEmpty ||
          ExternalParameterizedMemoryRegistry.parametersOf(component).nonEmpty ||
          ExternalParameterizedValueRegistry.parametersOf(component).nonEmpty ||
          ExternalParameterizedSliceRegistry.parametersOf(component).nonEmpty ||
""",
        "native fallback support gate",
    )
    fallback = replace_once(
        fallback,
        """        ExternalParameterizedMemoryRegistry.parametersOf(component) ++
        ExternalParameterizedValueRegistry.parametersOf(component) ++
        ParameterizedStructure.parametersOf(component) ++
""",
        """        ExternalParameterizedMemoryRegistry.parametersOf(component) ++
        ExternalParameterizedValueRegistry.parametersOf(component) ++
        ExternalParameterizedSliceRegistry.parametersOf(component) ++
        ParameterizedStructure.parametersOf(component) ++
""",
        "native fallback parameter inventory",
    )
    fallback = replace_once(
        fallback,
        """            child => ParameterizedWidth.parametersOf(child).nonEmpty
""",
        """            child =>
              ParameterizedWidth.parametersOf(child).nonEmpty ||
                ExternalParameterizedSliceRegistry.parametersOf(child).nonEmpty
""",
        "native fallback child support gate",
    )
    fallback_path.write_text(fallback, encoding="utf-8")


boundary_path = Path(
    "morphhdl/scripts/check-native-streamfifocc-parameterization-boundary.sh"
)
boundary = boundary_path.read_text(encoding="utf-8")
if "ExternalParameterizedSliceRegistry" not in boundary:
    boundary = replace_once(
        boundary,
        """grep -Fq 'Set("StreamFifo", "StreamFifoCC")' \\
  morphplugin/src/main/scala/morphhdl/compiler/MorphHdlNativeIntShadowExpressionComponent.scala

""",
        """grep -Fq 'Set("StreamFifo", "StreamFifoCC")' \\
  morphplugin/src/main/scala/morphhdl/compiler/MorphHdlNativeIntShadowExpressionComponent.scala
grep -Fq 'ExternalParameterizedSliceRegistry' \\
  morphruntime/src/main/scala/spinal/core/ExternalParameterizedSliceRegistry.scala
grep -Fq 'ParameterizedVerilogSlices.rewrite' \\
  morphhdl/src/main/scala/spinal/core/internals/MorphHdlExternalParameterizedVerilog.scala

""",
        "slice source-boundary assertions",
    )
    boundary_path.write_text(boundary, encoding="utf-8")
