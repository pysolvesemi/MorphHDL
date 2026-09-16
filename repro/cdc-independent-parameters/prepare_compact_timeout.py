#!/usr/bin/env python3
"""Apply the pinned compact patch plus exact native-width owner corrections.

This is a one-shot, input-bound CI repair, not an automatic source approval.
The original full patch remains authenticated by its immutable Git blob. The
additional edits below retain compact OwnerProof objects instead of requesting
finite rootValues, and keep the actual native Component identity mandatory.
"""
import hashlib
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]
QUALIFIED = "0a13d7fb01413d6d5f9f2bb8885f8abf109cb264"
RESTORED = "96fa69762c682204c0476ac272d350d9ca1a5190"
TRANSPORT_PARENT = "c7bea5872d35b19553e3313747d448c3ce7e11e0"
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
    # BASE remains the exact restored/qualified source for every compiler-byte
    # and four-path transport-inventory check. Only the expected parent changes.
    text = exact(text,
        'require(git("show", "-s", "--format=%P", "HEAD").decode().strip() == BASE, "wrong transport parent")',
        'require(git("show", "-s", "--format=%P", "HEAD").decode().strip() == "' + TRANSPORT_PARENT + '", "wrong transport parent")')
    text = exact(text,
        '    if (!allStatementsOf(component).exists(_ eq declaration))',
        '    if ((declaration.component ne component) || !allStatementsOf(component).exists(_ eq declaration))')
    old_width = """      val owned = ElaborationProductDomain.owner(width, role, width.sourceLocation) {
        (root, universe) => ParameterizedStructure.exactDeclarationDomainOf(
          component, declaration, root, universe, role, width.sourceLocation).values
      }.get
      return Evidence(owned.roots, owned.schemas, owned.rootValues, Map.empty, Some(owned))"""
    new_width = """      val owned = ElaborationProductDomain.ownerWithCompact(width, role, width.sourceLocation)(
        (root, universe) => ParameterizedStructure.exactDeclarationDomainOf(
          component, declaration, root, universe, role, width.sourceLocation).values,
        (root, _) => ParameterizedStructure.requireCompactDeclarationOwner(
          component, declaration, root, role, width.sourceLocation)
      ).get
      // All product equivalence/difference branches consume this OwnerProof,
      // including its exact root/schema/scope identities. rootValues belongs
      // only to the legacy finite-table path; requesting it here would either
      // enumerate a compact declaration or reject valid native packed widths.
      return Evidence(owned.roots, owned.schemas, Vector.empty, Map.empty, Some(owned))"""
    correction = "    replace(NATIVE_WIDTH, " + repr(old_width) + ", " + repr(new_width) + ")\n\n"
    text = exact(text, '    scope_text = (ROOT / SCOPE).read_text()',
                 correction + '    scope_text = (ROOT / SCOPE).read_text()')
    namespace = {"__name__": "pr189_pinned_compact_template", "__file__": str(ROOT / PATH)}
    exec(compile(text, TEMPLATE + ":" + PATH + ":native-width-correction", "exec"), namespace)
    if namespace["BASE"] != QUALIFIED:
        raise RuntimeError("template qualification anchor differs")
    namespace["BASE"] = RESTORED
    print("PR189_COMPACT_TEMPLATE", TEMPLATE, "RESTORED_PARENT", RESTORED,
          "TRANSPORT_PARENT", TRANSPORT_PARENT, "QUALIFIED_TREE", QUALIFIED, flush=True)
    namespace["main"]()


if __name__ == "__main__":
    main()
