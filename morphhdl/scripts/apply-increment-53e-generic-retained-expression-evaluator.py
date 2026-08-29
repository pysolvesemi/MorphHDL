#!/usr/bin/env python3
from pathlib import Path

path = Path(
    "morphhdl/src/main/scala/spinal/core/internals/"
    "ExternalParameterizedVerilogNativeFallback.scala"
)
value = path.read_text()

# Strict parser for the compiler-owned retained integer-expression IR. This is
# not a Verilog/signal-name parser: identifiers are accepted only when they are
# declared formal parameters of the exact bounded width expression.
marker = '''      /** Exact bounded evaluation; unsupported or unproven nodes return None. */
'''
parser = '''      private final class RetainedWidthExpressionParser(
          input: String,
          bindings: Map[String, BigInt]
      ) {
        private var index = 0

        def parse(): Option[BigInt] =
          try {
            val value = parseAddSubtract()
            skipWhitespace()
            if (index == input.length) Some(value) else None
          } catch {
            case _: IllegalArgumentException => None
            case _: ArithmeticException      => None
          }

        private def parseAddSubtract(): BigInt = {
          var value = parseMultiplyDivide()
          var continue = true
          while (continue) {
            skipWhitespace()
            if (consume('+')) value += parseMultiplyDivide()
            else if (consume('-')) value -= parseMultiplyDivide()
            else continue = false
          }
          value
        }

        private def parseMultiplyDivide(): BigInt = {
          var value = parseUnary()
          var continue = true
          while (continue) {
            skipWhitespace()
            if (consume('*')) value *= parseUnary()
            else if (consume('/')) {
              val divisor = parseUnary()
              if (divisor == 0) invalid()
              value /= divisor
            } else if (consume('%')) {
              val divisor = parseUnary()
              if (divisor == 0) invalid()
              value %= divisor
            } else continue = false
          }
          value
        }

        private def parseUnary(): BigInt = {
          skipWhitespace()
          if (consume('+')) parseUnary()
          else if (consume('-')) -parseUnary()
          else parsePrimary()
        }

        private def parsePrimary(): BigInt = {
          skipWhitespace()
          if (consume('(')) {
            val value = parseAddSubtract()
            skipWhitespace()
            require(consume(')'))
            value
          } else if (index < input.length && input.charAt(index).isDigit) {
            parseInteger()
          } else {
            val identifier = parseIdentifier()
            skipWhitespace()
            if (consume('(')) {
              val arguments = Vector.newBuilder[BigInt]
              skipWhitespace()
              if (!consume(')')) {
                arguments += parseAddSubtract()
                skipWhitespace()
                while (consume(',')) {
                  arguments += parseAddSubtract()
                  skipWhitespace()
                }
                require(consume(')'))
              }
              evaluateFunction(identifier, arguments.result())
            } else bindings.getOrElse(identifier, invalid())
          }
        }

        private def parseInteger(): BigInt = {
          val start = index
          while (index < input.length && input.charAt(index).isDigit) index += 1
          BigInt(input.substring(start, index))
        }

        private def parseIdentifier(): String = {
          if (
            index >= input.length ||
            !(input.charAt(index).isLetter || input.charAt(index) == '_')
          ) invalid()
          val start = index
          index += 1
          while (
            index < input.length &&
            (input.charAt(index).isLetterOrDigit || input.charAt(index) == '_')
          ) index += 1
          input.substring(start, index)
        }

        private def evaluateFunction(
            name: String,
            arguments: Vector[BigInt]
        ): BigInt = name match {
          case "morphhdl_address_width" if arguments.size == 1 =>
            val value = arguments.head
            if (value <= 0) invalid()
            BigInt(math.max(1, (value - 1).bitLength))
          case "morphhdl_ceil_log2" if arguments.size == 1 =>
            val value = arguments.head
            if (value <= 0) invalid()
            BigInt((value - 1).bitLength)
          case "clog2" if arguments.size == 2 =>
            val value = arguments(0)
            val minimum = arguments(1)
            if (value <= 0 || minimum < 0) invalid()
            BigInt((value - 1).bitLength).max(minimum)
          case "min" if arguments.size == 2 => arguments(0).min(arguments(1))
          case "max" if arguments.size == 2 => arguments(0).max(arguments(1))
          case _ => invalid()
        }

        private def skipWhitespace(): Unit =
          while (index < input.length && input.charAt(index).isWhitespace) index += 1

        private def consume(value: Char): Boolean = {
          skipWhitespace()
          if (index < input.length && input.charAt(index) == value) {
            index += 1
            true
          } else false
        }

        private def require(value: Boolean): Unit = if (!value) invalid()

        private def invalid(): Nothing =
          throw new IllegalArgumentException("unsupported retained width expression")
      }

      private def evaluateRetainedSyntax(
          expression: WidthRetained,
          root: ParameterizedStructure.StructuralPredicateRoot,
          value: BigInt
      ): Option[BigInt] = {
        if (root.parameters.size != 1) return None
        val parameter = root.parameters.head
        if (
          root.verilog != parameter.name ||
          root.default != parameter.default ||
          root.minimum != parameter.minimum ||
          root.maximum != parameter.maximum ||
          !expression.parameters.forall(candidate => candidate == parameter)
        ) return None
        new RetainedWidthExpressionParser(
          expression.render,
          Map(parameter.name -> value)
        ).parse().filter(result =>
          result >= expression.minimum && result <= expression.maximum
        )
      }

'''
if "private final class RetainedWidthExpressionParser(" not in value:
    if value.count(marker) != 1:
        raise SystemExit("retained-expression parser marker is ambiguous")
    value = value.replace(marker, parser + marker, 1)

old = '''        case retained: WidthRetained =>
          Option(retainedOrigins.get(retained)).flatMap { origin =>
            ExternalNativeIntShadowRegistry
              .definitionExpressionRoot(origin)
              .filter(originRoot => sameRootSchema(originRoot, root))
              .flatMap(originRoot =>
                ExternalNativeIntShadowRegistry.evaluateDefinitionExpression(
                  origin,
                  originRoot,
                  value
                )
              )
          }
'''
new = '''        case retained: WidthRetained =>
          Option(retainedOrigins.get(retained)).flatMap { origin =>
            ExternalNativeIntShadowRegistry
              .definitionExpressionRoot(origin)
              .filter(originRoot => sameRootSchema(originRoot, root))
              .flatMap(originRoot =>
                ExternalNativeIntShadowRegistry.evaluateDefinitionExpression(
                  origin,
                  originRoot,
                  value
                )
              )
          }.orElse(evaluateRetainedSyntax(retained, root, value))
'''
if old in value:
    value = value.replace(old, new, 1)

old = '''        val roots = (provenanceRoots(left) ++ provenanceRoots(right)).toVector
        if (roots.isEmpty) return false
        val root = roots.head
        if (!roots.forall(candidate => sameRootSchema(candidate, root))) return false
'''
new = '''        val roots = (provenanceRoots(left) ++ provenanceRoots(right)).toVector
        val root =
          if (roots.nonEmpty) {
            val selected = roots.head
            if (!roots.forall(candidate => sameRootSchema(candidate, selected)))
              return false
            selected
          } else if (schemas.size == 1) {
            val parameter = schemas.head._2.head
            new ParameterizedStructure.StructuralPredicateRoot(
              parameter.name,
              parameter.default,
              parameter.minimum,
              parameter.maximum,
              Vector(parameter)
            )
          } else return false
'''
if old in value:
    value = value.replace(old, new, 1)

path.write_text(value)
