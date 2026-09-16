#!/usr/bin/env python3
"""Apply the pinned compact patch plus explicit publication-boundary repairs.

One-shot, exact-input maintenance; not a validator or automatic source approval.
The original patch is authenticated by its immutable Git blob. Every additional
edit below must uniquely match; all existing source gates still run on the
resulting candidate before its conditional publication to the same PR branch.
"""
import hashlib
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]
QUALIFIED = "0a13d7fb01413d6d5f9f2bb8885f8abf109cb264"
RESTORED = "96fa69762c682204c0476ac272d350d9ca1a5190"
TRANSPORT_PARENT = "393cfd73935177fa02ee371aea2e102d10e71a56"
TEMPLATE = "6e632df8900ee7760a384c6a572825acfd012ac6"
PATH = "repro/cdc-independent-parameters/prepare_compact_timeout.py"
BLOB = "d8de2bf69e18ca295c877834c71f6abfa8882ba5"


def git(*args):
    return subprocess.check_output(["git", "--literal-pathspecs", *args], cwd=ROOT, timeout=120)


def exact(text, old, new):
    if text.count(old) != 1:
        raise RuntimeError("compact correction does not uniquely match its pinned template: " + old[:100])
    return text.replace(old, new, 1)


def main():
    if git("rev-parse", RESTORED + "^{tree}") != git("rev-parse", QUALIFIED + "^{tree}"):
        raise RuntimeError("restoration differs from the source/consumer-qualified tree")
    git("merge-base", "--is-ancestor", TEMPLATE, RESTORED)
    if git("show", "-s", "--format=%P", "HEAD").decode().strip() != TRANSPORT_PARENT:
        raise RuntimeError("repair is not the exact expected transport child")
    raw = git("show", TEMPLATE + ":" + PATH)
    actual = hashlib.sha1(b"blob " + str(len(raw)).encode() + b"\0" + raw).hexdigest()
    if actual != BLOB:
        raise RuntimeError("immutable reviewed compact patch differs")
    text = raw.decode("utf-8")
    text = exact(text,
        'COMPILER = {PRODUCT, PERMIT, LEGALITY, HDL, BRIDGE, ISSUER, STRUCTURE, FALLBACK}',
        'NATIVE_WIDTH = "morphhdl/src/main/scala/spinal/core/internals/NativePublicationWidth.scala"\n'
        'COMPILER = {PRODUCT, PERMIT, LEGALITY, HDL, BRIDGE, ISSUER, STRUCTURE, FALLBACK, NATIVE_WIDTH}')
    text = exact(text,
        'require(git("show", "-s", "--format=%P", "HEAD").decode().strip() == BASE, "wrong transport parent")',
        'require(git("show", "-s", "--format=%P", "HEAD").decode().strip() == "' + TRANSPORT_PARENT + '", "wrong transport parent")')
    text = exact(text,
        '    if (!allStatementsOf(component).exists(_ eq declaration))',
        '    if ((declaration.component ne component) || !allStatementsOf(component).exists(_ eq declaration))')
    edits = []
    edits.append(("NATIVE_WIDTH", """      val owned = ElaborationProductDomain.owner(width, role, width.sourceLocation) {
        (root, universe) => ParameterizedStructure.exactDeclarationDomainOf(
          component, declaration, root, universe, role, width.sourceLocation).values
      }.get
      return Evidence(owned.roots, owned.schemas, owned.rootValues, Map.empty, Some(owned))""", """      val owned = ElaborationProductDomain.ownerWithCompact(width, role, width.sourceLocation)(
        (root, universe) => ParameterizedStructure.exactDeclarationDomainOf(
          component, declaration, root, universe, role, width.sourceLocation).values,
        (root, _) => ParameterizedStructure.requireCompactDeclarationOwner(
          component, declaration, root, role, width.sourceLocation)
      ).get
      // Product comparisons use the exact root/schema/scope OwnerProof, not
      // a finite root table. Asking for rootValues would enumerate a compact
      // declaration or reject valid compact-derived native packed widths.
      return Evidence(owned.roots, owned.schemas, Vector.empty, Map.empty, Some(owned))"""))
    marker = "  private[core] def isRetained(value: ElaborationIntegerExpression): Boolean = certificate(value).nonEmpty"
    edits.append(("PRODUCT", marker, """  /** Classification only: the publication caller must separately validate
    * the exact live owner. Public schemas or equal names cannot mint this bit.
    */
  private[core] def hasCompactParameter(value: ElaborationIntegerExpression,
      parameter: ElaborationIntegerParameter): Boolean =
    certificate(value).exists(_.axes.exists(axis => axis.compact &&
      (axis.schema eq parameter) && axis.root.isAuthoritativeSchema(parameter)))

""" + marker))
    marker = "    private def validateParameters(): Unit = {"
    edits.append(("FALLBACK", marker, """    /** A large integer domain is not a large hardware width. Admission here
      * requires a genuine compact certificate and a real native consumer, not
      * a name/schema match or an unused requirement. Every physical width is
      * still checked independently by validateWidths below.
      */
    private def hasCompactParameterOwner(parameter: ElaborationIntegerParameter): Boolean = {
      val widthOwner = declarations.distinct.exists {
        case value: BitVector => ParameterizedWidth.expressionOf(value).exists { expression =>
          if (!ElaborationProductDomain.hasCompactParameter(expression, parameter)) false
          else {
            NativePublicationWidth.validate(expression, component, value, "compact integer parameter width owner")
            true
          }
        }
        case _ => false
      }
      widthOwner || ExternalParameterizedValueRegistry.valuesOf(component).exists { case (value, record) =>
        if (!ElaborationProductDomain.hasCompactParameter(record.expression, parameter)) false
        else {
          validateRetainedValueProjection(component, value, record)
          true
        }
      }
    }

""" + marker))
    edits.append(("FALLBACK",
        "          parameter.maximum > BigInt(pc.config.bitVectorWidthMax)\n",
        "          parameter.maximum > BigInt(Int.MaxValue) ||\n"
        "          (parameter.maximum > BigInt(pc.config.bitVectorWidthMax) && !hasCompactParameterOwner(parameter))\n"))
    fixture = repr("repro/cdc-independent-parameters/src/main/scala/CompactTimeout.scala")
    marker = '  assert(controls == 9, "compact rejection inventory changed")'
    edits.append((fixture, marker, """  // This is separate from the nine original authority controls: accepting
  // compact integer values must not raise the physical bit-vector width cap.
  var physicalWidthRejected = false
  try {
    MorphVerilog(SpinalConfig(targetDirectory = args(0) + "/oversized-packed-width",
      headerWithDate = false, headerWithRepoHash = true)) {
      new Component {
        val bound = timeout
        val din = in Bits(bound bits)
        val dout = out Bits(bound bits)
        dout := din
      }
    }
  } catch {
    case error: Exception if Option(error.getMessage).exists(
      _.contains("SPINAL-PARAMETERIZED-VERILOG-EXPRESSION-DOMAIN-TOO-LARGE")) =>
      physicalWidthRejected = true
  }
  assert(physicalWidthRejected, "compact integer admission bypassed the physical packed-width cap")
  println("COMPACT_PHYSICAL_WIDTH_REJECTION_PASS")
""" + marker))
    correction = "".join("    replace(" + path + ", " + repr(old) + ", " + repr(new) + ")\n"
                         for path, old, new in edits) + "\n"
    text = exact(text, '    scope_text = (ROOT / SCOPE).read_text()',
                 correction + '    scope_text = (ROOT / SCOPE).read_text()')
    namespace = {"__name__": "pr189_pinned_compact_template", "__file__": str(ROOT / PATH)}
    exec(compile(text, TEMPLATE + ":" + PATH + ":publication-corrections", "exec"), namespace)
    if namespace["BASE"] != QUALIFIED:
        raise RuntimeError("template qualification anchor differs")
    namespace["BASE"] = RESTORED
    print("PR189_COMPACT_TEMPLATE", TEMPLATE, "RESTORED_PARENT", RESTORED,
          "TRANSPORT_PARENT", TRANSPORT_PARENT, "QUALIFIED_TREE", QUALIFIED, flush=True)
    namespace["main"]()


if __name__ == "__main__":
    main()
