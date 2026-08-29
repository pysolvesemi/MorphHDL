#!/usr/bin/env python3
from pathlib import Path

path = Path(
    "morphhdl/src/main/scala/spinal/core/internals/"
    "ExternalParameterizedVerilogNativeFallback.scala"
)
value = path.read_text()

if "provesRetainedArithmeticEquivalence" in value:
    raise SystemExit(0)

captured = '''              val provenCapturedDomainEquivalent =
                isProvenCapturedDomainWidthEquivalence(
                  assignment,
                  targetWidth,
                  sourceWidth
                )
'''
if value.count(captured) != 1:
    raise SystemExit(
        f"generic retained-width proof call marker count={value.count(captured)}"
    )
value = value.replace(
    captured,
    captured + '''              val provenRetainedArithmeticEquivalent =
                widthInference.provesRetainedArithmeticEquivalence(
                  targetWidth,
                  sourceWidth
                )
''',
    1,
)

old_guard = '''                !provenCapturedDomainEquivalent
'''
new_guard = '''                !provenCapturedDomainEquivalent &&
                !provenRetainedArithmeticEquivalent
'''
if value.count(old_guard) < 1:
    raise SystemExit("generic retained-width assignment guard marker missing")
value = value.replace(old_guard, new_guard, 1)

marker = '''      def ofBase(baseType: BaseType): WidthExpr = {
'''
if value.count(marker) != 1:
    raise SystemExit(
        f"generic retained-width evaluator marker count={value.count(marker)}"
    )

implementation = r'''      /**
        * A fail-closed evaluator for MorphHDL's retained native-Int width IR.
        * It accepts only the reviewed arithmetic/helper vocabulary below. The
        * input is a typed WidthExpr rendered by this backend; emitted HDL is
        * never read back, and no component/source/signal name is a key.
        */
      private final class RetainedWidthEvaluator(
          text: String,
          environment: Map[String, BigInt]
      ) {
        private var index = 0

        def result(): Option[BigInt] = {
          val value = parseAdditive()
          skipSpace()
          if (value.nonEmpty && index == text.length) value else None
        }

        private def skipSpace(): Unit =
          while (index < text.length && text.charAt(index).isWhitespace) index += 1

        private def consume(character: Char): Boolean = {
          skipSpace()
          if (index < text.length && text.charAt(index) == character) {
            index += 1
            true
          } else false
        }

        private def parseAdditive(): Option[BigInt] = {
          var current = parseMultiplicative()
          var continue = true
          while (continue && current.nonEmpty) {
            skipSpace()
            if (
              index < text.length &&
              (text.charAt(index) == '+' || text.charAt(index) == '-')
            ) {
              val operation = text.charAt(index)
              index += 1
              val right = parseMultiplicative()
              current = for {
                leftValue <- current
                rightValue <- right
              } yield if (operation == '+') leftValue + rightValue
              else leftValue - rightValue
            } else continue = false
          }
          current
        }

        private def parseMultiplicative(): Option[BigInt] = {
          var current = parseUnary()
          var continue = true
          while (continue && current.nonEmpty) {
            skipSpace()
            if (
              index < text.length &&
              (text.charAt(index) == '*' || text.charAt(index) == '/' ||
                text.charAt(index) == '%')
            ) {
              val operation = text.charAt(index)
              index += 1
              val right = parseUnary()
              current = for {
                leftValue <- current
                rightValue <- right
                result <- operation match {
                  case '*' => Some(leftValue * rightValue)
                  case '/' if rightValue != 0 => Some(leftValue / rightValue)
                  case '%' if rightValue != 0 => Some(leftValue % rightValue)
                  case _ => None
                }
              } yield result
            } else continue = false
          }
          current
        }

        private def parseUnary(): Option[BigInt] = {
          skipSpace()
          if (consume('+')) parseUnary()
          else if (consume('-')) parseUnary().map(-_)
          else parsePrimary()
        }

        private def parsePrimary(): Option[BigInt] = {
          skipSpace()
          if (consume('(')) {
            val value = parseAdditive()
            if (consume(')')) value else None
          } else if (index < text.length && text.charAt(index).isDigit) {
            val start = index
            while (index < text.length && text.charAt(index).isDigit) index += 1
            Some(BigInt(text.substring(start, index)))
          } else if (
            index < text.length &&
            (text.charAt(index).isLetter || text.charAt(index) == '_' ||
              text.charAt(index) == '$')
          ) {
            val start = index
            index += 1
            while (
              index < text.length &&
              (text.charAt(index).isLetterOrDigit || text.charAt(index) == '_' ||
                text.charAt(index) == '$')
            ) index += 1
            val identifier = text.substring(start, index)
            skipSpace()
            if (consume('(')) parseCall(identifier)
            else environment.get(identifier)
          } else None
        }

        private def parseCall(name: String): Option[BigInt] = {
          val arguments = ArrayBuffer.empty[BigInt]
          skipSpace()
          if (!consume(')')) {
            var done = false
            while (!done) {
              parseAdditive() match {
                case Some(argument) => arguments += argument
                case None => return None
              }
              if (consume(')')) done = true
              else if (!consume(',')) return None
            }
          }
          evaluateCall(name, arguments.toVector)
        }

        private def ceilLog2(value: BigInt): Option[BigInt] =
          if (value <= 0) None else Some(BigInt((value - 1).bitLength))

        private def evaluateCall(
            name: String,
            arguments: Vector[BigInt]
        ): Option[BigInt] = (name, arguments) match {
          case (function, Vector(argument))
              if function == "morphhdl_address_width" ||
                function == "addressWidth" =>
            ceilLog2(argument).map(_.max(BigInt(1)))
          case (function, Vector(argument))
              if function == "morphhdl_ceil_log2" ||
                function == "ceilLog2" || function == "log2Up" =>
            ceilLog2(argument)
          case ("log2Down", Vector(argument)) if argument > 0 =>
            Some(BigInt(argument.bitLength - 1))
          case ("min", Vector(left, right)) => Some(left.min(right))
          case ("max", Vector(left, right)) => Some(left.max(right))
          case ("clog2", Vector(argument, minimum)) =>
            ceilLog2(argument).map(_.max(minimum))
          case _ => None
        }
      }

      /**
        * Prove two different symbolic width formulas equal over the complete
        * Cartesian product of their finite declared parameter domains. Every
        * parameter name must map to one identical schema; unsupported syntax,
        * undefined arithmetic and oversized domains reject the proof.
        */
      def provesRetainedArithmeticEquivalence(
          left: WidthExpr,
          right: WidthExpr
      ): Boolean = {
        if (!left.isSymbolic || !right.isSymbolic) return false
        val declarations = (left.parameters ++ right.parameters).groupBy(_.name)
        if (declarations.exists { case (_, schemas) => schemas.distinct.size != 1 })
          return false
        val parameters = declarations.toVector.sortBy(_._1).map(_._2.head)
        if (parameters.isEmpty) return false
        val domainSize = parameters.foldLeft(BigInt(1)) { (size, parameter) =>
          size * (parameter.maximum - parameter.minimum + 1)
        }
        if (
          domainSize < 1 ||
          domainSize > ExternalNativeIntShadowRegistry.MaximumStructuralPredicateDomainSize
        ) return false

        def visit(
            parameterIndex: Int,
            environment: Map[String, BigInt]
        ): Boolean = {
          if (parameterIndex == parameters.size) {
            val leftValue =
              new RetainedWidthEvaluator(left.render, environment).result()
            val rightValue =
              new RetainedWidthEvaluator(right.render, environment).result()
            leftValue.nonEmpty && leftValue == rightValue &&
              leftValue.exists(_ > 0)
          } else {
            val parameter = parameters(parameterIndex)
            var current = parameter.minimum
            while (current <= parameter.maximum) {
              if (
                !visit(
                  parameterIndex + 1,
                  environment.updated(parameter.name, current)
                )
              ) return false
              current += 1
            }
            true
          }
        }

        visit(0, Map.empty[String, BigInt])
      }

'''
value = value.replace(marker, implementation + marker, 1)
path.write_text(value)
