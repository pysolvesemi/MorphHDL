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
TRANSPORT_PARENT = "96ede5c280dfe50ef263f2675dd6a3fffd2c58a3"
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
        'REGRESSION = "morphhdl/src/test/scala/spinal/core/internals/ParameterizedVerilogTests.scala"\n'
        'AUDIT = "morphhdl/scripts/test-increment-59h-inherited-source-scope.py"\n'
        'COMPILER = {PRODUCT, PERMIT, LEGALITY, HDL, BRIDGE, ISSUER, STRUCTURE, FALLBACK, NATIVE_WIDTH}')
    text = exact(text,
        'require(git("show", "-s", "--format=%P", "HEAD").decode().strip() == BASE, "wrong transport parent")',
        'require(git("show", "-s", "--format=%P", "HEAD").decode().strip() == "' + TRANSPORT_PARENT + '", "wrong transport parent")')
    text = exact(text,
        '    for path in COMPILER | {BUILD, SCOPE, POLICY, NATIVE, WA, CONTRACT}:',
        '    for path in COMPILER | {BUILD, SCOPE, POLICY, NATIVE, WA, CONTRACT, REGRESSION, AUDIT}:')
    text = exact(text,
        '    expected_scope = set(old_scope.SUCCESSOR_PATHS) | TRANSPORT | COMPILER',
        '    expected_scope = set(old_scope.SUCCESSOR_PATHS) | TRANSPORT | COMPILER | {REGRESSION, AUDIT}')
    text = exact(text,
        'require(changed(BASE) == TRANSPORT | COMPILER | {BUILD, SCOPE, POLICY, NATIVE, WA, CONTRACT}, "unexpected final scope")',
        'require(changed(BASE) == TRANSPORT | COMPILER | {BUILD, SCOPE, POLICY, NATIVE, WA, CONTRACT, REGRESSION, AUDIT}, "unexpected final scope")')
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
    fixture_path = "repro/cdc-independent-parameters/src/main/scala/CompactTimeout.scala"
    fixture = repr(fixture_path)
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
    # Preserve the original positive rewrite and both stale-RHS rejection
    # assertions. Only repair their setup: raw parameter summaries no longer
    # authorize a retained UInt carrier before the emitter-lineage boundary.
    edits.append(("REGRESSION", """      val binaryWidth = ElaborationIntegerParameter("BINARY_WIDTH", 4, 2, 8)
      val hexWidth = ElaborationIntegerParameter("HEX_WIDTH", 8, 6, 8)""", """      // Authenticated widths let this test reach emitted-lineage validation;
      // raw public schemas must not be used to bypass carrier authority.
      val binaryWidth = HdlInt.param("BINARY_WIDTH", 4, 2, 8).asElabInt
      val hexWidth = HdlInt.param("HEX_WIDTH", 8, 6, 8).asElabInt"""))
    edits.append(("REGRESSION", '.UInt(ParameterizedBitCount(4, binaryWidth))', '.UInt(binaryWidth.bits)'))
    edits.append(("REGRESSION", '.UInt(ParameterizedBitCount(8, hexWidth))', '.UInt(hexWidth.bits)'))
    # The compact owner implementation makes ParameterizedStructure part of
    # the exact WA08 overlay. Its existing inside-span mutation now reaches
    # that outer byte-authentication boundary first, just like a suffix edit.
    # Keep the actual mutation, nonzero exit and exact path diagnostic. The
    # unchanged pre-rollout 59h replay still exercises the original span error.
    edits.append(("AUDIT",
        '            if mutation == "suffix" and relative in overlay_paths:',
        '            # Both edits must be rejected before historical projection.\n'
        '            # The frozen pre-rollout replay retains the inner span check.\n'
        '            if mutation in ("suffix", "inside") and relative in overlay_paths:'))
    correction = "".join("    replace(" + path + ", " + repr(old) + ", " + repr(new) + ")\n"
                         for path, old, new in edits) + "\n"
    text = exact(text, '    scope_text = (ROOT / SCOPE).read_text()',
                 correction + '    scope_text = (ROOT / SCOPE).read_text()')
    # Every new or repaired regression is part of the exact candidate tree.
    text = exact(text,
        'compiler_source = commit("PR189: retain compact declaration intervals without finite-table authority", COMPILER | {BUILD, SCOPE, POLICY})',
        'compiler_source = commit("PR189: retain compact declaration intervals without finite-table authority", COMPILER | {BUILD, SCOPE, POLICY, REGRESSION, AUDIT, ' + fixture + '})')
    text = exact(text,
        '    candidate = commit("PR189: seal compact timeout candidate for targeted CI only", {WA, CONTRACT})',
        '    candidate = commit("PR189: seal compact timeout candidate for targeted CI only", {WA, CONTRACT})\n'
        '    require(not git("status", "--porcelain", "--untracked-files=all"), "candidate differs from committed compiler or regression sources")')
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
