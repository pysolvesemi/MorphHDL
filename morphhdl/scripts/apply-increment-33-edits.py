#!/usr/bin/env python3
"""Apply exact, reviewable Increment 33 edits to large existing sources.

This helper is intentionally branch-local and temporary. It avoids replacing
large established source files through GitHub's contents API. Every edit is an
exact one-occurrence replacement and fails closed if the expected source shape
changes.
"""

from pathlib import Path


def replace_once(path: str, old: str, new: str) -> bool:
    target = Path(path)
    text = target.read_text(encoding="utf-8")
    if new in text:
        print(f"[unchanged] {path}")
        return False
    count = text.count(old)
    if count != 1:
        raise SystemExit(
            f"expected exactly one match in {path}, found {count}: {old[:120]!r}"
        )
    target.write_text(text.replace(old, new, 1), encoding="utf-8")
    print(f"[updated] {path}")
    return True


changed = False

changed |= replace_once(
    "core/src/main/scala/spinal/core/ParameterizedStructure.scala",
    "    found.distinct match {\n",
    "    found.distinct.toVector match {\n",
)

changed |= replace_once(
    "core/src/main/scala/spinal/core/internals/ParameterizedVerilogStructural.scala",
    "selection.vector.vec(default).flatten.toVector",
    "selection.vector.vec(default).asInstanceOf[Data].flatten.toVector",
)
changed |= replace_once(
    "core/src/main/scala/spinal/core/internals/ParameterizedVerilogStructural.scala",
    "selection.vector.vec(value).flatten.toVector",
    "selection.vector.vec(value).asInstanceOf[Data].flatten.toVector",
)
changed |= replace_once(
    "core/src/main/scala/spinal/core/internals/ParameterizedVerilogStructural.scala",
    "pc.verilogKeywords(parameter.name)",
    "pc.verilogKeywords.contains(parameter.name)",
)
changed |= replace_once(
    "core/src/main/scala/spinal/core/internals/ParameterizedVerilogStructural.scala",
    "signalNames(parameter.name)",
    "signalNames.contains(parameter.name)",
)

literal_anchor = '''  def param(
      name: String,
'''
literal_at = '''  private[frontend] def literalAt(
      value: BigInt,
      origin: SourceOrigin
  ): HdlInt =
    new HdlInt(
      value,
      Literal(value),
      declaration = None,
      parameters = Set.empty,
      booleanParameters = Set.empty,
      localDeclaration = None,
      localParameters = Set.empty,
      booleanLocalParameters = Set.empty,
      scope = None,
      origin = origin
    )

  def param(
      name: String,
'''
changed |= replace_once(
    "frontend/src/main/scala/morphhdl/frontend/HdlInt.scala",
    literal_anchor,
    literal_at,
)

changed |= replace_once(
    "frontend/src/main/scala/morphhdl/frontend/NativeStructuralFrontend.scala",
    "          val one = HdlInt.literal(BigInt(1))\n",
    "          val one = HdlInt.literalAt(BigInt(1), range.origin)\n",
)

changed |= replace_once(
    "frontend/src/main/scala/morphhdl/frontend/NativeStructuralFrontend.scala",
    '''        new NativeGenerateIfToken(
          component,
          pending = null,
          condition,
          expression = null,
          resolved,
          whenTrueBlock = null,
          origin,
          parameterized = false
        )''',
    '''        new NativeGenerateIfToken(
          component = component,
          pending = null,
          condition = condition,
          expression = null,
          names = resolved,
          whenTrueBlock = null,
          origin = origin,
          parameterized = false
        )''',
)

changed |= replace_once(
    "frontend/src/main/scala/morphhdl/frontend/NativeStructuralFrontend.scala",
    '''        new NativeGenerateCaseToken(
          component,
          pending = null,
          selector,
          expression = null,
          origin,
          parameterized = false
        )''',
    '''        new NativeGenerateCaseToken(
          component = component,
          pending = null,
          selector = selector,
          expression = null,
          origin = origin,
          parameterized = false
        )''',
)

phase_old = '''    val (componentBuilderVerilog, componentResult) =
      try {
        val builder = newBuilder(pc.config)
        (builder, () => builder.result)
      } catch {
        case failure: ParameterizedVerilogException
            if pc.config.parameterizedVerilog &&
              ParameterizedVerilogNativeFallback.supports(failure, component) =>
          val builder = newBuilder(pc.config.copy(parameterizedVerilog = false))
          (
            builder,
            () => {
              val nativeResult = builder.result
              // ClockDomain.external keeps its source signals outside the component.
              // The normal emitter has already pulled top-level input proxies into the
              // component, so expose that proven input view only while validating the
              // bounded native fallback, then restore the source signals unchanged.
              withPulledExternalClockInputs {
                ParameterizedVerilogNativeFallback.rewrite(
                      component,
                      nativeResult,
                      pc,
                      child => Option(emitedComponentRef.get(child)).getOrElse(child)
                    )
              }
            }
          )
      }
'''
phase_new = '''    def canonicalOf(child: Component): Component =
      Option(emitedComponentRef.get(child)).getOrElse(child)

    val (componentBuilderVerilog, componentResult) =
      try {
        val builder = newBuilder(pc.config)
        (
          builder,
          () =>
            ParameterizedVerilogStructural.rewrite(
              component,
              builder.result,
              pc,
              canonicalOf
            )
        )
      } catch {
        case failure: ParameterizedVerilogException
            if pc.config.parameterizedVerilog &&
              ParameterizedVerilogNativeFallback.supports(failure, component) =>
          val builder = newBuilder(pc.config.copy(parameterizedVerilog = false))
          (
            builder,
            () => {
              val nativeResult = builder.result
              // ClockDomain.external keeps its source signals outside the component.
              // The normal emitter has already pulled top-level input proxies into the
              // component, so expose that proven input view only while validating the
              // bounded native fallback, then restore the source signals unchanged.
              val parameterizedResult = withPulledExternalClockInputs {
                ParameterizedVerilogNativeFallback.rewrite(
                  component,
                  nativeResult,
                  pc,
                  canonicalOf
                )
              }
              ParameterizedVerilogStructural.rewrite(
                component,
                parameterizedResult,
                pc,
                canonicalOf
              )
            }
          )
      }
'''
changed |= replace_once(
    "core/src/main/scala/spinal/core/internals/PhaseVerilog.scala",
    phase_old,
    phase_new,
)

print("[result] changed" if changed else "[result] no changes required")
