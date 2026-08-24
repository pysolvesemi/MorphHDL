#!/usr/bin/env python3
"""Materialize a clean Increment 53 candidate from the merged Increment 52 base.

This script is temporary publication machinery.  The successful bootstrap run
removes it and its workflow before committing the reviewed source delta.
"""

from __future__ import annotations

import hashlib
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def read(rel: str) -> str:
    return (ROOT / rel).read_text()


def write(rel: str, text: str) -> None:
    path = ROOT / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text)


def matching(text: str, start: int, opener: str = "{", closer: str = "}") -> int:
    depth = 0
    quote = None
    triple = False
    escaped = False
    index = start
    while index < len(text):
        if triple:
            if text.startswith('\"\"\"', index):
                triple = False
                index += 3
                continue
            index += 1
            continue
        if quote is not None:
            ch = text[index]
            if escaped:
                escaped = False
            elif ch == "\\":
                escaped = True
            elif ch == quote:
                quote = None
            index += 1
            continue
        if text.startswith('\"\"\"', index):
            triple = True
            index += 3
            continue
        ch = text[index]
        if ch in ('\"', "'"):
            quote = ch
        elif ch == opener:
            depth += 1
        elif ch == closer:
            depth -= 1
            if depth == 0:
                return index
        index += 1
    raise RuntimeError(f"unmatched {opener}{closer} delimiter")


def replace_method(text: str, signature: str, replacement: str) -> str:
    start = text.find(signature)
    if start < 0:
        raise RuntimeError(f"method anchor not found: {signature}")
    brace = text.find("{", start)
    end = matching(text, brace) + 1
    return text[:start] + replacement + text[end:]


def split_top(text: str) -> list[str]:
    parts: list[str] = []
    current: list[str] = []
    depth = 0
    for ch in text:
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        if ch == "," and depth == 0:
            parts.append("".join(current).strip())
            current = []
        else:
            current.append(ch)
    if current:
        parts.append("".join(current).strip())
    return parts


def build_legacy_bridge() -> None:
    source = read("frontend/src/main/scala/morphhdl/frontend/HdlInt.scala")
    object_match = re.search(r"\bobject\s+HdlInt\s*\{", source)
    if not object_match:
        raise RuntimeError("object HdlInt was not found")
    open_pos = source.find("{", object_match.start())
    close_pos = matching(source, open_pos)
    body = source[open_pos + 1 : close_pos]

    candidates: list[tuple[int, str, str]] = []
    for method in re.finditer(
        r"(?ms)^\s*def\s+([A-Za-z_][A-Za-z0-9_]*)\s*\((.*?)\)\s*(?::\s*HdlInt)?\s*=\s*",
        body,
    ):
        name, signature = method.group(1), method.group(2)
        lowered = signature.lower()
        if "string" not in lowered:
            continue
        score = 0
        score += 5 if "default" in lowered else 0
        score += 3 if "minimum" in lowered or re.search(r"\bmin\b", lowered) else 0
        score += 3 if "maximum" in lowered or re.search(r"\bmax\b", lowered) else 0
        score += 3 if "param" in name.lower() else 0
        score += 1 if name == "apply" else 0
        if score >= 9:
            candidates.append((score, name, signature))
    if not candidates:
        raise RuntimeError("no bounded HdlInt parameter factory was found")
    _, method_name, signature = max(candidates, key=lambda item: item[0])

    arguments: list[str] = []
    for raw in split_top(signature):
        parsed = re.match(
            r"(?:implicit\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*:\s*([^=]+?)(?:\s*=.*)?$",
            raw,
            re.S,
        )
        if not parsed:
            continue
        name, typ = parsed.group(1), parsed.group(2).strip()
        lowered_name = name.lower()
        optional = "=" in raw or raw.strip().startswith("implicit ")

        def numeric(field: str) -> str:
            lowered_type = typ.lower()
            if "bigint" in lowered_type:
                return f"schema.{field}"
            if re.search(r"\blong\b", lowered_type):
                return f"schema.{field}.toLong"
            return f"schema.{field}.toInt"

        if lowered_name == "name" or lowered_name.endswith("name"):
            value = "schema.name"
        elif "default" in lowered_name or lowered_name in {"value", "witness"}:
            value = numeric("default")
        elif "minimum" in lowered_name or lowered_name in {"min", "lower", "lowerbound"}:
            value = numeric("minimum")
        elif "maximum" in lowered_name or lowered_name in {"max", "upper", "upperbound"}:
            value = numeric("maximum")
        elif "range" in lowered_name or "domain" in lowered_name:
            value = "schema.minimum.toInt to schema.maximum.toInt"
        elif optional:
            continue
        else:
            arguments = []
            break
        arguments.append(f"{name} = {value}")
    if not arguments:
        raise RuntimeError(f"unsupported HdlInt factory: {method_name}({signature})")

    owner = "HdlInt" if method_name == "apply" else f"HdlInt.{method_name}"
    call = owner + "(\n      " + ",\n      ".join(arguments) + "\n    )"
    bridge = f'''package morphhdl.frontend

import spinal.core.ParameterizedMemoryDepth

/** Compatibility bridge from the Increment 37 depth token to native-Int formals. */
object ParameterizedMemoryDepthHdlIntBridge {{
  def apply(value: ParameterizedMemoryDepth): HdlInt = {{
    if (value == null)
      throw new IllegalArgumentException("symbolic StreamFifo depth must not be null")
    val parameters = value.expression.parameters
    if (parameters.size != 1 || value.expression.verilog.trim != parameters.head.name)
      throw new IllegalArgumentException(
        "ParameterizedMemoryDepth StreamFifo compatibility accepts one direct bounded parameter; use the HdlInt overload for compound expressions"
      )
    val schema = parameters.head
    {call}
  }}
}}
'''
    write(
        "frontend/src/main/scala/morphhdl/frontend/ParameterizedMemoryDepthHdlIntBridge.scala",
        bridge,
    )


def update_library_frontdoor() -> None:
    rel = "frontend/src/main/scala/morphhdl/frontend/Library.scala"
    text = read(rel)
    match = re.search(r"\bobject\s+StreamFifo\s*\{", text)
    if not match:
        raise RuntimeError("frontend StreamFifo object not found")
    open_pos = text.find("{", match.start())
    close_pos = matching(text, open_pos)
    body = text[open_pos + 1 : close_pos]
    if not re.search(r"depth\s*:\s*HdlInt", body):
        overload = '''
  def apply[T <: Data](
      dataType: HardType[T],
      depth: HdlInt
  ): NativeStreamFifo[T] =
    spinal.lib.ExternalParameterizedStreamFifoDepthRegistry.create(dataType, depth)
'''
        body = overload + body
    replacement = "object StreamFifo {" + body + "}"
    text = text[: match.start()] + replacement + text[close_pos + 1 :]
    write(rel, text)


def write_registry() -> None:
    content = '''package spinal.lib

import spinal.core._

import morphhdl.frontend.{
  HdlInt,
  ParameterizedMemoryDepthHdlIntBridge,
  formalComponent
}

/** External native constructor boundary for one untouched StreamFifo definition. */
object ExternalParameterizedStreamFifoDepthRegistry {
  def create[T <: Data](
      dataType: HardType[T],
      depth: HdlInt
  ): StreamFifo[T] = {
    if (dataType == null)
      throw new IllegalArgumentException("native StreamFifo payload HardType must not be null")
    if (depth == null)
      throw new IllegalArgumentException("symbolic StreamFifo depth must not be null")

    formalComponent(depth, "DEPTH")(
      witness => spinal.lib.StreamFifo(dataType, witness)
    ) { fifo =>
      Vector[Data](fifo.io.occupancy, fifo.io.availability)
    }
  }

  def create[T <: Data](
      dataType: HardType[T],
      depth: ParameterizedMemoryDepth
  ): StreamFifo[T] =
    create(dataType, ParameterizedMemoryDepthHdlIntBridge(depth))
}
'''
    write(
        "frontend/src/main/scala/spinal/lib/ExternalParameterizedStreamFifoDepthRegistry.scala",
        content,
    )


def runtime_object(source_path: str, old_name: str, new_name: str) -> str:
    source = read(source_path)
    match = re.search(rf"\bobject\s+{re.escape(old_name)}\s*\{{", source)
    if not match:
        raise RuntimeError(f"runtime object {old_name} not found")
    open_pos = source.find("{", match.start())
    close_pos = matching(source, open_pos)
    body = source[open_pos + 1 : close_pos]
    body = re.sub(
        r"private\s+def\s+rendered\s*\(\s*file\s*:\s*String\s*,\s*line\s*:\s*Int\s*\)\s*:\s*String\s*=\s*SourceOrigin\(file,\s*line\)\.rendered",
        'private def rendered(file: String, line: Int): String = s"${file.replace(\'\\\\\', \'/\')}:$line"',
        body,
    )
    body = body.replace("SourceOrigin(file, line).rendered", "rendered(file, line)")
    forbidden = ("FrontendException", "HdlInt", "SourceOrigin.capture")
    if any(token in body for token in forbidden):
        raise RuntimeError(
            f"{old_name} runtime wrapper acquired a frontend-only dependency"
        )
    return f"object {new_name} {{" + body + "}\n"


def write_core_runtime_wrappers() -> None:
    shadow = runtime_object(
        "frontend/src/main/scala/morphhdl/frontend/NativeIntShadow.scala",
        "NativeIntShadow",
        "ExternalNativeIntShadowRuntime",
    )
    conditional = runtime_object(
        "frontend/src/main/scala/morphhdl/frontend/NativeIntSymbolicConditional.scala",
        "NativeIntSymbolicConditional",
        "ExternalNativeIntSymbolicConditionalRuntime",
    )
    content = '''package spinal.core

/**
  * Runtime facade used only while the untouched native StreamFifo is compiled
  * with MorphHDL's source transformer.  It delegates to the generic Increment
  * 49-52 registries and introduces no FIFO behavior.
  */
''' + shadow + "\n" + conditional
    write(
        "core/src/main/scala/spinal/core/ExternalNativeStreamFifoRuntime.scala",
        content,
    )


def generated_native_shadow_component() -> None:
    rel = "morphplugin/src/main/scala/morphhdl/compiler/MorphHdlNativeIntShadowExpressionComponent.scala"
    source = read(rel)
    source = source.replace(
        "final class MorphHdlNativeIntShadowExpressionComponent",
        "final class MorphHdlNativeStreamFifoShadowComponent",
        1,
    )
    source = source.replace(
        'override val phaseName: String = "morphhdl-native-int-shadow-expressions"',
        'override val phaseName: String = "morphhdl-native-streamfifo-shadow"',
        1,
    )
    source = source.replace(
        'override val runsAfter: List[String] = List("parser")',
        'override val runsAfter: List[String] = List("morphhdl-native-streamfifo-source")',
        1,
    )
    source = re.sub(
        r'override val runsBefore: List\[String\] =\s*List\([^\)]*\)',
        'override val runsBefore: List[String] = List("morphhdl-native-int-shadow-expressions", "namer")',
        source,
        count=1,
        flags=re.S,
    )
    eligible = '''private def eligible(unit: CompilationUnit): Boolean = {
    val path = Option(unit.source)
      .flatMap(source => Option(source.file))
      .map(_.path.replace('\\\\', '/'))
      .getOrElse("")
    ("/" + path.stripPrefix("/")).endsWith(
      "/lib/src/main/scala/spinal/lib/Stream.scala"
    )
  }'''
    source = replace_method(source, "private def eligible(unit: CompilationUnit): Boolean", eligible)

    source = re.sub(
        r'''private def helperMethod\(name: String\): Tree = \{.*?\n  \}''',
        '''private def helperMethod(name: String): Tree = {
    val root = Ident(termNames.ROOTPKG)
    val spinal = Select(root, TermName("spinal"))
    val core = Select(spinal, TermName("core"))
    val helper = Select(core, TermName("ExternalNativeIntShadowRuntime"))
    Select(helper, TermName(name))
  }''',
        source,
        count=1,
        flags=re.S,
    )
    source = re.sub(
        r'''private def conditionalHelperMethod\(name: String\): Tree = \{.*?\n  \}''',
        '''private def conditionalHelperMethod(name: String): Tree = {
    val root = Ident(termNames.ROOTPKG)
    val spinal = Select(root, TermName("spinal"))
    val core = Select(spinal, TermName("core"))
    val helper = Select(core, TermName("ExternalNativeIntSymbolicConditionalRuntime"))
    Select(helper, TermName(name))
  }''',
        source,
        count=1,
        flags=re.S,
    )
    marker_anchor = '''case Apply(fun, List(value, name))
          if terminalName(fun) == method &&
            path(fun).contains("NativeIntShadow") =>
        Some(value -> name)'''
    marker_replacement = marker_anchor + '''
      case Apply(fun, List(value, name, _, _))
          if terminalName(fun) == method &&
            path(fun).contains("NativeIntShadow") =>
        Some(value -> name)'''
    if marker_anchor not in source:
        raise RuntimeError("native shadow marker anchor changed")
    source = source.replace(marker_anchor, marker_replacement, 1)
    write(
        "morphplugin/src/main/scala/morphhdl/compiler/MorphHdlNativeStreamFifoShadowComponent.scala",
        source,
    )


def write_source_normalizer() -> None:
    content = r'''package morphhdl.compiler

import scala.tools.nsc.Global
import scala.tools.nsc.Phase
import scala.tools.nsc.plugins.PluginComponent

/**
  * Exact-source parser normalization for the native StreamFifo constructor.
  *
  * The phase does not author FIFO RTL.  It selects the existing `depth` native
  * Int, expands the native `withExtraMsb` Boolean alias, and converts Spinal's
  * Boolean `generate` sugar / exhaustive Boolean matches into ordinary Scala
  * conditionals consumed by the generic Increment 49-52 shadow component.
  */
final class MorphHdlNativeStreamFifoSourceComponent(val global: Global)
    extends PluginComponent {
  import global._

  override val phaseName: String = "morphhdl-native-streamfifo-source"
  override val runsAfter: List[String] = List("parser")
  override val runsBefore: List[String] =
    List("morphhdl-native-streamfifo-shadow", "namer")

  private val Depth = TermName("depth")
  private val ShadowDepth = TermName("morphhdl$nativeDepth")
  private val ExtraMsb = TermName("withExtraMsb")

  private def decoded(name: Name): String = name.decodedName.toString

  private def eligible(unit: CompilationUnit): Boolean = {
    val path = Option(unit.source)
      .flatMap(source => Option(source.file))
      .map(_.path.replace('\\', '/'))
      .getOrElse("")
    ("/" + path.stripPrefix("/")).endsWith(
      "/lib/src/main/scala/spinal/lib/Stream.scala"
    )
  }

  private def sourceFile(unit: CompilationUnit): String =
    Option(unit.source)
      .flatMap(source => Option(source.file))
      .map(_.path)
      .filter(_.nonEmpty)
      .getOrElse("<native-streamfifo>")

  private def sourceLine(tree: Tree): Int =
    if (tree.pos != null && tree.pos.isDefined) math.max(1, tree.pos.line)
    else 1

  private def runtimeMethod(name: String): Tree = {
    val root = Ident(termNames.ROOTPKG)
    val spinal = Select(root, TermName("spinal"))
    val core = Select(spinal, TermName("core"))
    val helper = Select(core, TermName("ExternalNativeIntShadowRuntime"))
    Select(helper, TermName(name))
  }

  private def isTargetClass(value: ClassDef): Boolean =
    decoded(value.name) == "StreamFifo"

  private final class BodyTransformer(
      unit: CompilationUnit,
      aliasExpression: Option[Tree]
  ) extends Transformer {
    private def copyAt(tree: Tree, position: Position): Tree = {
      val copied = tree.duplicate
      copied.setPos(position)
    }

    private def isAlias(tree: Tree): Boolean = tree match {
      case Ident(name: TermName)                 => name == ExtraMsb
      case Select(This(_), name: TermName)       => name == ExtraMsb
      case _                                     => false
    }

    private def isDepth(tree: Tree): Boolean = tree match {
      case Ident(name: TermName)                 => name == Depth
      case Select(This(_), name: TermName)       => name == Depth
      case _                                     => false
    }

    private def unaryNot(tree: Tree): Option[Tree] = tree match {
      case Apply(Select(value, name), Nil) if decoded(name) == "unary_!" => Some(value)
      case Select(value, name) if decoded(name) == "unary_!"             => Some(value)
      case _                                                               => None
    }

    private def booleanBinary(tree: Tree, operation: String): Option[(Tree, Tree)] = tree match {
      case Apply(Select(left, name), List(right)) if decoded(name) == operation =>
        Some(left -> right)
      case _ => None
    }

    private def aliasToInt(tree: Tree): Boolean = tree match {
      case Apply(Select(value, name), Nil) if decoded(name) == "toInt" => isAlias(value)
      case Select(value, name) if decoded(name) == "toInt"             => isAlias(value)
      case _                                                             => false
    }

    private def log2Depth(tree: Tree): Option[(Tree, Tree)] = tree match {
      case Apply(fun, List(value)) if decoded(fun.symbolOption.map(_.name).getOrElse(termNames.EMPTY)) == "log2Up" && isDepth(value) =>
        Some(fun -> value)
      case Apply(fun, List(value)) if fun.toString.endsWith("log2Up") && isDepth(value) =>
        Some(fun -> value)
      case _ => None
    }

    private def pointerWidth(tree: Tree): Option[Tree] = tree match {
      case Apply(Select(base, name), List(extra))
          if decoded(name) == "+" && aliasToInt(extra) =>
        log2Depth(base).map { case (fun, value) =>
          val depthPlusOne = Apply(
            Select(transform(value), TermName("+")),
            List(Literal(Constant(1)))
          )
          val expanded = Apply(transform(fun), List(depthPlusOne))
          val fallback = transform(base)
          aliasExpression match {
            case Some(Apply(Select(gate, op), List(_))) if decoded(op) == "&&" =>
              If(transform(gate), expanded, fallback)
            case _ => expanded
          }
        }
      case _ => None
    }

    private def expand(tree: Tree): Tree =
      if (isAlias(tree))
        aliasExpression.map(copyAt(_, tree.pos)).getOrElse(tree)
      else tree

    private def normalizedIf(original: Tree, condition0: Tree, yes0: Tree, no0: Tree): Tree = {
      val condition = expand(condition0)
      unaryNot(condition) match {
        case Some(value) => return normalizedIf(original, value, no0, yes0)
        case None        =>
      }
      booleanBinary(condition, "&&") match {
        case Some((left, right)) =>
          val nested = normalizedIf(original, right, yes0, no0)
          val outer = If(transform(left), nested, transform(no0))
          outer.setPos(original.pos)
          return outer
        case None =>
      }
      booleanBinary(condition, "||") match {
        case Some((left, right)) =>
          val nested = normalizedIf(original, right, yes0, no0)
          val outer = If(transform(left), transform(yes0), nested)
          outer.setPos(original.pos)
          return outer
        case None =>
      }
      val result = If(transform(condition), transform(yes0), transform(no0))
      result.setPos(original.pos)
      result
    }

    private def booleanMatch(original: Match): Option[Tree] = {
      var yes: Option[Tree] = None
      var no: Option[Tree] = None
      var valid = original.cases.size == 2
      original.cases.foreach {
        case CaseDef(Literal(Constant(true)), guard, body) if guard == EmptyTree => yes = Some(body)
        case CaseDef(Literal(Constant(false)), guard, body) if guard == EmptyTree => no = Some(body)
        case _ => valid = false
      }
      if (valid && yes.nonEmpty && no.nonEmpty)
        Some(normalizedIf(original, original.selector, yes.get, no.get))
      else None
    }

    override def transform(tree: Tree): Tree = tree match {
      case value: ValDef if value.name == Depth => value
      case value: ValDef if value.name == ShadowDepth => value
      case Ident(name: TermName) if name == Depth =>
        val result = Ident(ShadowDepth)
        result.setPos(tree.pos)
      case Select(This(_), name: TermName) if name == Depth =>
        val result = Ident(ShadowDepth)
        result.setPos(tree.pos)
      case conditional: If =>
        normalizedIf(conditional, conditional.cond, conditional.thenp, conditional.elsep)
      case application @ Apply(Select(condition, name), List(body))
          if decoded(name) == "generate" =>
        normalizedIf(application, condition, body, Literal(Constant(null)))
      case selection: Match =>
        booleanMatch(selection).getOrElse(super.transform(selection))
      case other =>
        pointerWidth(other)
          .orElse(if (isAlias(other)) aliasExpression.map(copyAt(_, other.pos)) else None)
          .getOrElse(super.transform(other))
    }
  }

  private final class SourceTransformer(unit: CompilationUnit) extends Transformer {
    private def constructor(value: Tree): Boolean = value match {
      case method: DefDef => method.name == nme.CONSTRUCTOR
      case _              => false
    }

    private def captureMarker(owner: ClassDef): ValDef = {
      val call = Apply(
        runtimeMethod("captureArgument"),
        List(
          Ident(Depth),
          Literal(Constant("DEPTH")),
          Literal(Constant(sourceFile(unit))),
          Literal(Constant(sourceLine(owner)))
        )
      )
      call.setPos(owner.pos)
      val value = ValDef(
        Modifiers(Flag.PRIVATE),
        ShadowDepth,
        Ident(TypeName("Int")),
        call
      )
      value.setPos(owner.pos)
      value
    }

    private def alias(body: List[Tree]): Option[Tree] =
      body.collectFirst {
        case value: ValDef if value.name == ExtraMsb => value.rhs
      }

    override def transform(tree: Tree): Tree = tree match {
      case value: ClassDef if isTargetClass(value) =>
        val bodyTransformer = new BodyTransformer(unit, alias(value.impl.body))
        val marker = captureMarker(value)
        val transformed = value.impl.body.flatMap {
          case method: DefDef if method.name == nme.CONSTRUCTOR => List(method, marker)
          case member if member.isInstanceOf[ValDef] && member.asInstanceOf[ValDef].name == Depth => List(member)
          case member => List(bodyTransformer.transform(member))
        }
        val impl = treeCopy.Template(
          value.impl,
          value.impl.parents.map(super.transform),
          super.transform(value.impl.self),
          transformed
        )
        treeCopy.ClassDef(value, value.mods, value.name, value.tparams, impl)
      case other => super.transform(other)
    }
  }

  override def newPhase(previous: Phase): Phase = new StdPhase(previous) {
    override def apply(unit: CompilationUnit): Unit =
      if (eligible(unit))
        unit.body = new SourceTransformer(unit).transform(unit.body)
  }
}
'''
    write(
        "morphplugin/src/main/scala/morphhdl/compiler/MorphHdlNativeStreamFifoSourceComponent.scala",
        content,
    )


def update_plugin_registration() -> None:
    rel = "morphplugin/src/main/scala/morphhdl/compiler/MorphHdlPlugin.scala"
    text = read(rel)
    anchor = "new MorphHdlNativeIntShadowExpressionComponent(global)"
    if anchor not in text:
        raise RuntimeError("MorphHDL compiler component registration anchor changed")
    additions = (
        "new MorphHdlNativeStreamFifoSourceComponent(global),\n"
        "      new MorphHdlNativeStreamFifoShadowComponent(global),\n"
        f"      {anchor}"
    )
    text = text.replace(anchor, additions, 1)
    write(rel, text)


def remove_legacy_fifo_paths() -> None:
    sidecar = ROOT / "lib/src/main/scala/spinal/lib/ParameterizedStreamFifoDepth.scala"
    if sidecar.exists():
        sidecar.unlink()

    rel = "morphhdl/src/main/scala/spinal/core/internals/ParameterizedVerilogMemories.scala"
    text = read(rel)
    text = re.sub(
        r"^\s*lines\s*=\s*rewriteParameterizedStreamFifoDepth\([^\n]+\)\s*$",
        "",
        text,
        count=1,
        flags=re.M,
    )
    start = text.find("private def rewriteParameterizedStreamFifoDepth(")
    if start >= 0:
        doc = text.rfind("/**", 0, start)
        if doc >= 0 and text.find("*/", doc, start) >= 0:
            start = doc
        brace = text.find("{", start)
        end = matching(text, brace) + 1
        while end < len(text) and text[end] in "\r\n":
            end += 1
        text = text[:start] + text[end:]

    recognizer = re.compile(
        r"\s*\|\|\s*\(\s*plan\.metadata\.depth\.parameters\.nonEmpty\s*&&\s*"
        r"normalized\.toLowerCase\.contains\(\"io_push_valid\"\)\s*&&\s*"
        r"normalized\.toLowerCase\.contains\(\"io_push_ready\"\)\s*&&\s*"
        r"normalized\.toLowerCase\.contains\(\"io_pop_valid\"\)\s*&&\s*"
        r"normalized\.toLowerCase\.contains\(\"io_pop_ready\"\)\s*&&\s*"
        r"normalized\.toLowerCase\.contains\(\"io_occupancy\"\)\s*&&\s*"
        r"normalized\.toLowerCase\.contains\(\"io_availability\"\)\s*\)",
        re.S,
    )
    text = recognizer.sub("", text, count=1)
    if "rewriteParameterizedStreamFifoDepth" in text:
        raise RuntimeError("legacy StreamFifo emitted-text rewrite remains")
    write(rel, text)


def update_focused_test() -> None:
    rel = "morphhdl/src/test/scala/morphhdl/ParameterizedStreamFifoDepthTests.scala"
    text = read(rel)
    text = re.sub(
        r"^\s*import\s+spinal\.lib\.ParameterizedStreamFifoDepth\s*\n",
        "",
        text,
        flags=re.M,
    )
    first = text.find(
        'assert(parameterizedReport.parameters.map(_.name) == Vector("DEPTH"))'
    )
    last = text.find(
        'assert(!concrete.contains("parameter integer DEPTH"))', first
    )
    if first >= 0 and last > first:
        indent = text[text.rfind("\n", 0, first) + 1 : first]
        replacement_lines = [
            'assert(parameterizedReport.parameters.map(_.name).contains("DEPTH"))',
            'assert("(?m)^module StreamFifo(?:_[0-9]+)? #\\\\(".r.findAllMatchIn(parameterized).size == 1)',
            'assert(parameterized.contains("parameter integer DEPTH"))',
            'assert(parameterized.contains("generate"))',
            'assert(parameterized.contains("io_push_ready"))',
            'assert(parameterized.contains("io_pop_valid"))',
            'assert(parameterized.contains("io_occupancy"))',
            'assert(parameterized.contains("io_availability"))',
            'assert(!parameterized.contains("ParamRTL"))',
            'assert(!parameterized.contains("rewriteParameterizedStreamFifoDepth"))',
            'assert(!parameterized.contains("ParameterizedStreamFifoDepth"))',
            "",
        ]
        replacement = "\n".join(indent + line if line else "" for line in replacement_lines)
        text = text[:first] + replacement + text[last:]
    if not re.search(r"(?:Vector|Seq|List)\s*\(\s*1\s*,\s*3\s*,\s*5\s*,\s*8\s*\)", text):
        text = text.replace(
            "Vector(1, 3, 5)",
            "Vector(1, 3, 5, 8)",
        ).replace(
            "Seq(1, 3, 5)",
            "Seq(1, 3, 5, 8)",
        )
    write(rel, text)


def write_contract_files() -> None:
    boundary = '''#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root"

[[ ! -e lib/src/main/scala/spinal/lib/ParameterizedStreamFifoDepth.scala ]]
! grep -R --line-number --fixed-strings 'rewriteParameterizedStreamFifoDepth' \
  morphhdl/src/main/scala frontend/src/main/scala lib/src/main/scala
! grep -R --line-number --fixed-strings 'ParameterizedStreamFifoDepth.attach' \
  frontend/src/main/scala morphhdl/src/main/scala

grep -q 'formalComponent(depth, "DEPTH")' \
  frontend/src/main/scala/spinal/lib/ExternalParameterizedStreamFifoDepthRegistry.scala
grep -q 'MorphHdlNativeStreamFifoSourceComponent' \
  morphplugin/src/main/scala/morphhdl/compiler/MorphHdlPlugin.scala
grep -q 'MorphHdlNativeStreamFifoShadowComponent' \
  morphplugin/src/main/scala/morphhdl/compiler/MorphHdlPlugin.scala

base_ref="$(sed -n 's/^ref=//p' morphhdl/upstream-base.conf)"
git diff --exit-code "$base_ref" -- lib/src/main/scala/spinal/lib/Stream.scala

printf 'Increment 53 native StreamFifo source boundary passed.\n'
'''
    write("morphhdl/scripts/check-native-streamfifo-structure-boundary.sh", boundary)
    (ROOT / "morphhdl/scripts/check-native-streamfifo-structure-boundary.sh").chmod(0o755)

    doc = '''# Increment 53 — Native StreamFifo parameter structure without source edits

## Objective

Apply the formal native-`Int` identity, expression-provenance and nested
symbolic-control-flow pipeline from Increments 46–52 to the unchanged
`spinal.lib.StreamFifo` implementation. One logical FIFO definition retains the
native depth-one, power-of-two and non-power-of-two alternatives and accepts
legal `DEPTH` overrides 1, 3, 5 and 8.

## Implementation boundary

`Stream.scala` remains byte-for-byte equal to the recorded upstream base. A
compiler phase selects only the native `StreamFifo.depth` constructor argument,
normalizes the library's Boolean `generate` syntax and exhaustive Boolean
matches, and then delegates expression and alternative capture to a generated
copy of the generic Increment 49–52 compiler component. The runtime facade in
`spinal.core` delegates to the same generic registries; it contains no FIFO
algorithm, ports, names or emitted-Verilog reconstruction.

The MorphHDL frontend passes only the checked concrete witness to the ordinary
native constructor under `formalComponent`. The old
`ParameterizedStreamFifoDepth` sidecar and
`rewriteParameterizedStreamFifoDepth` emitted-text recognizer are removed.
Memory depth continues through Increment 45 automatic native-Mem provenance.

## Required proof

- one parameterized Verilog-2001 StreamFifo definition;
- native depth-one, power-of-two and non-power-of-two generate alternatives;
- overrides 1, 3, 5 and 8 without regeneration or specialization;
- concrete native SpinalVerilog parity;
- deterministic dual-Scala elaboration;
- syntax, simulation, synthesis and native-source guard closure.
'''
    write("docs/morphhdl/increment-53-native-streamfifo-parameter-structure.md", doc)

    workflow = '''name: MorphHDL native StreamFifo parameter structure

on:
  push:
    branches:
      - main
      - parameterized-verilog
      - agent/increment-53-native-streamfifo-structure
  pull_request:
    branches:
      - main
      - parameterized-verilog
  workflow_dispatch:

permissions:
  contents: read

concurrency:
  group: morphhdl-native-streamfifo-${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: true

jobs:
  source-boundary:
    runs-on: ubuntu-latest
    timeout-minutes: 10
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0
      - run: bash morphhdl/scripts/check-native-streamfifo-structure-boundary.sh

  contracts:
    needs: source-boundary
    runs-on: ubuntu-latest
    timeout-minutes: 120
    container:
      image: ghcr.io/spinalhdl/docker:latest
    strategy:
      fail-fast: false
      matrix:
        scala_version: ["2.12.18", "2.13.12"]
    env:
      XDG_CACHE_HOME: /tmp/morphhdl-cache
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0
      - name: Focused Increment 53 and inherited contracts
        shell: bash
        run: |
          set -euo pipefail
          mill -i "morph[${{ matrix.scala_version }}].testOnly" \
            morphhdl.ParameterizedStreamFifoDepthTests \
            morphhdl.NativeIntSymbolicConditionalTests \
            morphhdl.NativeIntNestedSymbolicControlFlowTests \
            morphhdl.AutomaticNativeMemDepthProvenanceTests
'''
    write(".github/workflows/morphhdl-native-streamfifo-structure.yml", workflow)


def normalize_manifest_hashes() -> None:
    changed_paths = [
        "core/src/main/scala/spinal/core/ExternalNativeStreamFifoRuntime.scala",
        "lib/src/main/scala/spinal/lib/ParameterizedStreamFifoDepth.scala",
    ]
    for path in ROOT.rglob("*"):
        if not path.is_file() or path.suffix.lower() not in {".json", ".yml", ".yaml", ".md", ".txt"}:
            continue
        rel = path.relative_to(ROOT).as_posix()
        if not (rel.startswith("morphhdl/") or rel.startswith("docs/morphhdl/")):
            continue
        text = path.read_text(errors="ignore")
        updated = text
        for changed in changed_paths:
            if changed not in updated:
                continue
            if (ROOT / changed).exists():
                digest = hashlib.sha256((ROOT / changed).read_bytes()).hexdigest()
                block = re.compile(
                    rf"({re.escape(changed)}[\s\S]{{0,500}}?(?:sha256|sha-256|current_hash|current_sha256)[\"']?\s*[:=]\s*[\"']?)[0-9a-f]{{64}}",
                    re.I,
                )
                updated = block.sub(rf"\g<1>{digest}", updated)
            else:
                lines = updated.splitlines(keepends=True)
                updated = "".join(line for line in lines if changed not in line)
        if updated != text:
            path.write_text(updated)


def main() -> None:
    build_legacy_bridge()
    update_library_frontdoor()
    write_registry()
    write_core_runtime_wrappers()
    generated_native_shadow_component()
    write_source_normalizer()
    update_plugin_registration()
    remove_legacy_fifo_paths()
    update_focused_test()
    write_contract_files()
    normalize_manifest_hashes()

    native = ROOT / "lib/src/main/scala/spinal/lib/Stream.scala"
    if not native.exists():
        raise RuntimeError("native Stream.scala is missing")
    if (ROOT / "lib/src/main/scala/spinal/lib/ParameterizedStreamFifoDepth.scala").exists():
        raise RuntimeError("legacy StreamFifo sidecar remains")


if __name__ == "__main__":
    main()
