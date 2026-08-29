#!/usr/bin/env python3
from pathlib import Path

path = Path(
    "morphplugin/src/main/scala/morphhdl/compiler/"
    "MorphHdlNativeIntShadowExpressionComponent.scala"
)
value = path.read_text()

old = '''    private def hasNativeShapeConstructorParameter(value: ClassDef): Boolean =
      nativeConstructorParameters(value)
        .exists(parameter => decoded(parameter.name) == "dataType")

'''
new = '''    private def nativeShapeConstructorParameter(value: ValDef): Boolean = {
      val candidates = Vector(
        if (value.symbol == null || value.symbol == NoSymbol) NoType
        else value.symbol.info,
        if (value.tpt == null) NoType else value.tpt.tpe
      ).filter(candidate => candidate != null && candidate != NoType)

      candidates.exists { candidate =>
        val dealiased = candidate.dealias
        val symbols = (dealiased.typeSymbol +: dealiased.baseClasses).filter(_ != NoSymbol)
        symbols.exists { symbol =>
          val name = symbol.fullName
          name == "spinal.core.Data" || name == "spinal.core.HardType"
        }
      }
    }

    private def hasNativeShapeConstructorParameter(value: ClassDef): Boolean =
      nativeConstructorParameters(value).exists(nativeShapeConstructorParameter)

'''
if old not in value and new not in value:
    raise SystemExit("typed native shape predicate marker not found")
value = value.replace(old, new, 1)

old_selector = 'parameters.find(parameter => decoded(parameter.name) == "dataType")'
new_selector = 'parameters.find(nativeShapeConstructorParameter)'
if old_selector in value:
    value = value.replace(old_selector, new_selector, 1)
elif new_selector not in value:
    raise SystemExit("native shape constructor selector not found")

path.write_text(value)
