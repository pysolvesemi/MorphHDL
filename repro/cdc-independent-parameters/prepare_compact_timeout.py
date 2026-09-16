#!/usr/bin/env python3
"""Apply the exact reviewed PR189 compact-declaration candidate in CI.

One-shot maintenance, not a validator or automatic approval of arbitrary code.
The input is a four-file transport child of BASE. Compiler edits below are
literal, uniquely matched replacements against BASE. Existing validators run
unchanged after source commits and an explicitly regenerated native/WA08 seal.
"""
from __future__ import annotations
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
BASE = "0a13d7fb01413d6d5f9f2bb8885f8abf109cb264"
TARGET = "27af65abbee0d2334d6be7a6e4e2408b8af32fd9"
TRANSPORT = {
    ".github/workflows/pr189-compact-timeout.yml",
    "repro/cdc-independent-parameters/prepare_compact_timeout.py",
    "repro/cdc-independent-parameters/check_compact_timeout.py",
    "repro/cdc-independent-parameters/src/main/scala/CompactTimeout.scala",
}
PRODUCT = "core/src/main/scala/spinal/core/ElaborationProductDomain.scala"
PERMIT = "core/src/main/scala/spinal/core/ExternalCompilerPermit.scala"
LEGALITY = "core/src/main/scala/spinal/core/NativeSymbolicLegality.scala"
HDL = "frontend/src/main/scala/morphhdl/frontend/HdlInt.scala"
BRIDGE = "frontend/src/main/scala/morphhdl/frontend/StructuralExpressionBridge.scala"
ISSUER = "frontend/src/main/scala/spinal/core/ExternalAnalyzedFrontendPermitIssuer.scala"
STRUCTURE = "morphruntime/src/main/scala/spinal/core/ParameterizedStructure.scala"
FALLBACK = "morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogNativeFallback.scala"
BUILD = "repro/cdc-independent-parameters/build.sbt"
SCOPE = "morphhdl/scripts/check-cdc-successor-source.py"
POLICY = "morphhdl/contracts/increment-55-native-change-review.json"
NATIVE = "morphhdl/contracts/native-source-preservation.json"
WA = "morphhdl/scripts/check-increment-62-wa08-source-overlay.py"
CONTRACT = "morphhdl/contracts/increment-62-wa08-source-overlay.json"
COMPILER = {PRODUCT, PERMIT, LEGALITY, HDL, BRIDGE, ISSUER, STRUCTURE, FALLBACK}


def require(ok, detail):
    if not ok:
        raise RuntimeError("PR189 compact candidate: " + detail)


def git(*args):
    return subprocess.check_output(["git", "--literal-pathspecs", *args], cwd=ROOT, timeout=120)


def changed(base, head="HEAD"):
    return {p.decode() for p in git("diff", "--no-renames", "--name-only", "-z", base, head).split(b"\0") if p}


def load(path, name):
    spec = importlib.util.spec_from_file_location(name, ROOT / path)
    require(spec is not None and spec.loader is not None, "missing module " + path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def replace(path, old, new):
    text = (ROOT / path).read_text()
    require(text.count(old) == 1, "non-unique reviewed edit in " + path + ": " + old[:100])
    (ROOT / path).write_text(text.replace(old, new, 1))


def write_json(path, value):
    (ROOT / path).write_text(json.dumps(value, indent=2) + "\n")


def commit(message, paths):
    git("add", "--", *sorted(paths))
    git("commit", "-q", "-m", message + " [skip ci]")
    return git("rev-parse", "HEAD").decode().strip()


def main():
    transport = git("rev-parse", "HEAD").decode().strip()
    require(git("show", "-s", "--format=%P", "HEAD").decode().strip() == BASE, "wrong transport parent")
    require(changed(BASE) == TRANSPORT, "unexpected transport delta")
    require(not git("status", "--porcelain", "--untracked-files=all"), "dirty input")
    git("merge-base", "--is-ancestor", TARGET, "HEAD")
    for path in COMPILER | {BUILD, SCOPE, POLICY, NATIVE, WA, CONTRACT}:
        require((ROOT / path).read_bytes() == git("show", BASE + ":" + path), "input bytes differ: " + path)
    with tempfile.TemporaryDirectory(prefix="pr189-compact-predecessor-") as temporary:
        checkout = Path(temporary) / "source"
        git("worktree", "add", "--quiet", "--detach", str(checkout), BASE)
        try:
            subprocess.run(["python3", str(checkout / WA)], cwd=checkout, check=True, timeout=300)
        finally:
            git("worktree", "remove", "--force", str(checkout))
    old_overlay = json.loads((ROOT / CONTRACT).read_bytes())
    old_native = json.loads((ROOT / NATIVE).read_bytes())
    old_scope = load(SCOPE, "compact_previous_scope")
    expected_scope = set(old_scope.SUCCESSOR_PATHS) | TRANSPORT | COMPILER

    replace(PRODUCT, '''  private final case class Axis(domain: ElaborationExactDomain[_], values: Vector[BigInt]) {
    def root: Root = domain.root
    def schema: ElaborationIntegerParameter = domain.parameter
  }''', '''  // A compact axis is an authenticated closed interval, never a sampled
  // exact-domain table. Its empty finite-storage vector is NOT an admitted
  // universe; every operation which needs enumeration rejects it explicitly.
  private final case class Axis(domain: Option[ElaborationExactDomain[_]], root: Root,
                                schema: ElaborationIntegerParameter, values: Vector[BigInt]) {
    def compact: Boolean = domain.isEmpty
    def cardinality: BigInt = if (compact) schema.maximum - schema.minimum + 1 else BigInt(values.size)
    def containsValue(value: BigInt): Boolean =
      if (compact) value >= schema.minimum && value <= schema.maximum else values.contains(value)
    def representative: BigInt =
      if (compact || values.contains(schema.default)) schema.default else values.min
    def sameScope(that: Axis): Boolean = compact == that.compact && values == that.values
    def enumerationValues: Vector[BigInt] = {
      if (compact) fail("SPINAL-ELAB-DOMAIN-COMPACT-ENUMERATION-UNSUPPORTED",
        "this consumer requires exhaustive values, not compact declaration authority", root.sourceLocation)
      values
    }
  }
  private object Axis {
    def apply(domain: ElaborationExactDomain[_], values: Vector[BigInt]): Axis =
      new Axis(Some(domain), domain.root, domain.parameter, values)
    def compact(root: Root, schema: ElaborationIntegerParameter): Axis =
      new Axis(None, root, schema, Vector.empty)
  }''')
    replace(PRODUCT, '''  private final case class Leaf(root: Root, values: Map[BigInt, BigInt]) extends Atom {''', '''  private final case class CompactLeaf(root: Root) extends Atom {
    val roots: Set[Root] = Set(root)
    def evaluate(environment: Environment): BigInt = environment(root)
  }
  private final case class Leaf(root: Root, values: Map[BigInt, BigInt]) extends Atom {''')
    replace(PRODUCT, '''previous.values != axis.values''', '''!previous.sameScope(axis)''')
    replace(PRODUCT, '''    val axes = proof.axes.map { axis =>
      val requested = ElaborationDomainContext.admitted(axis.domain)
      if (requested.isEmpty || !requested.subsetOf(axis.values.toSet))
        fail("SPINAL-ELAB-DOMAIN-EVIDENCE-SCOPE-MISMATCH",
          s"$role cannot use '${axis.root.name}' outside its authorized branch", location)
      axis.copy(values = requested.toVector.sorted)
    }''', '''    val axes = proof.axes.map { axis =>
      if (axis.compact) {
        // This first compact ingress deliberately grants no branch projection.
        if (ElaborationDomainContext.hasActiveRestrictions)
          fail("SPINAL-ELAB-DOMAIN-COMPACT-SCOPE-UNSUPPORTED",
            s"$role cannot use a compact declaration under structural restrictions", location)
        axis
      } else {
        val requested = ElaborationDomainContext.admitted(axis.domain.get)
        if (requested.isEmpty || !requested.subsetOf(axis.values.toSet))
          fail("SPINAL-ELAB-DOMAIN-EVIDENCE-SCOPE-MISMATCH",
            s"$role cannot use '${axis.root.name}' outside its authorized branch", location)
        axis.copy(values = requested.toVector.sorted)
      }
    }''')
    replace(PRODUCT, '''    axis.root -> (if (axis.values.contains(axis.schema.default)) axis.schema.default else axis.values.min)''', '''    axis.root -> axis.representative''')
    replace(PRODUCT, '''axes.foldLeft(BigInt(1))((n, axis) => n * axis.values.size)''', '''axes.foldLeft(BigInt(1))((n, axis) => n * axis.cardinality)''')
    replace(PRODUCT, '''  private def atomBounds(value: Atom, axes: Vector[Axis], location: Option[String]): Extrema = value match {
    case leaf: Leaf =>''', '''  private def atomBounds(value: Atom, axes: Vector[Axis], location: Option[String]): Extrema = value match {
    case CompactLeaf(root) =>
      val axis = axes.find(_.root eq root).get
      Extrema(axis.schema.minimum, axis.schema.maximum)
    case leaf: Leaf =>''')
    replace(PRODUCT, '''    def atomEnclosure(value: Atom): Enclosure = value match {
      case leaf: Leaf =>''', '''    def atomEnclosure(value: Atom): Enclosure = value match {
      case CompactLeaf(root) =>
        val axis = proof.axes.find(_.root eq root).get
        Enclosure(axis.schema.minimum, axis.schema.maximum)
      case leaf: Leaf =>''')
    replace(PRODUCT, '''axis.values.partition(value => operands(0).evaluate(Map(root -> value)) != 0)''', '''axis.enumerationValues.partition(value => operands(0).evaluate(Map(root -> value)) != 0)''')
    replace(PRODUCT, '''axis.copy(values = axis.values.filter(n => c.form.evaluate(Map(root -> n)) != 0))''', '''axis.copy(values = axis.enumerationValues.filter(n => c.form.evaluate(Map(root -> n)) != 0))''')
    replace(PRODUCT, '''certificate(value).exists(_.axes.forall(axis => axis.values.toSet == axis.domain.universe))''', '''certificate(value).exists(_.axes.forall(axis => axis.compact || axis.values.toSet == axis.domain.get.universe))''')
    replace(PRODUCT, '''(l.root eq r.root) && (l.schema eq r.schema) && l.values == r.values''', '''(l.root eq r.root) && (l.schema eq r.schema) && l.sameScope(r)''')
    replace(PRODUCT, '''axis.values.contains(entry._2)''', '''axis.containsValue(entry._2)''')
    replace(PRODUCT, '''    val rootValues: Vector[Vector[BigInt]] = proof.axes.map(_.values)''', '''    def rootValues: Vector[Vector[BigInt]] = proof.axes.map(_.enumerationValues)''')
    replace(PRODUCT, '''  private[core] def owner(value: ElaborationIntegerExpression, role: String, location: Option[String])(
      ownerValues: (Root, Set[BigInt]) => Set[BigInt]): Option[OwnerProof] = {''', '''  // Retain the existing JVM/API entrypoint; old finite consumers do not gain
  // compact authority merely because their callback accepts a Set.
  private[core] def owner(value: ElaborationIntegerExpression, role: String, location: Option[String])(
      ownerValues: (Root, Set[BigInt]) => Set[BigInt]): Option[OwnerProof] =
    ownerWithCompact(value, role, location)(ownerValues, (_, _) =>
      fail("SPINAL-ELAB-DOMAIN-COMPACT-OWNER-UNSUPPORTED",
        s"$role has no compact native-owner validator", location))

  private[core] def ownerWithCompact(value: ElaborationIntegerExpression, role: String, location: Option[String])(
      ownerValues: (Root, Set[BigInt]) => Set[BigInt],
      compactOwner: (Root, ElaborationIntegerParameter) => Unit): Option[OwnerProof] = {''')
    replace(PRODUCT, '''    val axes = original.axes.map { axis =>
      val requested = ownerValues(axis.root, axis.domain.universe)
      if (requested.isEmpty || !requested.subsetOf(axis.values.toSet))
        fail("SPINAL-ELAB-DOMAIN-PROJECTION-OWNER-SCOPE-MISMATCH",
          s"$role exceeds its authorized owner scope for '${axis.root.name}'", location)
      axis.copy(values = requested.toVector.sorted)
    }''', '''    val axes = original.axes.map { axis =>
      if (axis.compact) {
        compactOwner(axis.root, axis.schema)
        axis
      } else {
        val requested = ownerValues(axis.root, axis.domain.get.universe)
        if (requested.isEmpty || !requested.subsetOf(axis.values.toSet))
          fail("SPINAL-ELAB-DOMAIN-PROJECTION-OWNER-SCOPE-MISMATCH",
            s"$role exceeds its authorized owner scope for '${axis.root.name}'", location)
        axis.copy(values = requested.toVector.sorted)
      }
    }''')
    replace(PRODUCT, '''  private def merge(sources: Vector[TrustedExpressionEvidence], location: Option[String]): Vector[Axis] = {''', '''  /** The only compact ingress: a one-use permit for a bare frontend
    * declaration AST, not a public expression summary or sampled table.
    */
  private[core] def compactDeclaration(value: ElaborationIntegerExpression,
      sourceIdentity: AnyRef, permit: ExternalCompilerPermit): ElaborationIntegerExpression = {
    ExternalCompilerPermit.requireAnalyzedCompactDeclaration(permit, value, sourceIdentity)
    ElabInt.validateExpression(value, "compact parameter declaration")
    if (ElaborationDomainContext.hasActiveRestrictions)
      fail("SPINAL-ELAB-DOMAIN-COMPACT-SCOPE-UNSUPPORTED",
        "compact declarations cannot be minted in a structural branch", value.sourceLocation)
    val schema = value.parameters match {
      case Vector(parameter) => parameter
      case _ => fail(Missing, "compact ingress needs exactly one declaration", value.sourceLocation)
    }
    val root = value.completedParameterRoots match {
      case Vector(declaration) => declaration
      case _ => fail(Missing, "compact ingress lost its exact root", value.sourceLocation)
    }
    if (schema.minimum < 1 || !schema.maximum.isValidInt ||
        schema.maximum - schema.minimum + 1 <= MaximumJointValues ||
        value.default != schema.default || value.minimum != schema.minimum || value.maximum != schema.maximum ||
        value.verilog != schema.name || root.name != schema.name ||
        value.generateIndex.nonEmpty || value.exactDomain.nonEmpty || value.projectionProvenance.nonEmpty)
      fail("SPINAL-ELAB-DOMAIN-COMPACT-DECLARATION-INVALID",
        "compact ingress requires an unchanged positive Int-sized direct declaration", value.sourceLocation)
    root.bindAuthoritativeSchema(schema, "compact parameter declaration", value.sourceLocation)
    retain(value, TrustedExpressionEvidence(atom(CompactLeaf(root)), Vector(Axis.compact(root, schema))))
    value
  }

  private def merge(sources: Vector[TrustedExpressionEvidence], location: Option[String]): Vector[Axis] = {''')

    replace(PERMIT, '''  private[core] def claimSingleRoot(''', '''  private[core] def claimCompactDeclaration(
      expression: ElaborationIntegerExpression, source: AnyRef
  ): Boolean = synchronized {
    if (consumed || !(kind eq ExternalCompilerPermit.AnalyzedCompactDeclaration) ||
        !(integerExpression eq expression) || !(sourceIdentity eq source)) false
    else { consumed = true; true }
  }

  private[core] def claimSingleRoot(''')
    replace(PERMIT, '''  private[core] case object AnalyzedSingleRoot extends Kind''', '''  private[core] case object AnalyzedSingleRoot extends Kind
  private[core] case object AnalyzedCompactDeclaration extends Kind

  private[core] def analyzedCompactDeclaration(source: AnyRef,
      expression: ElaborationIntegerExpression): ExternalCompilerPermit = {
    require(source != null && expression != null, "compact analyzed source must not be null")
    new ExternalCompilerPermit(AnalyzedCompactDeclaration, source, expression, source)
  }

  private[core] def requireAnalyzedCompactDeclaration(permit: ExternalCompilerPermit,
      expression: ElaborationIntegerExpression, source: AnyRef): Unit = {
    if (permit == null || source == null || expression == null ||
        !permit.claimCompactDeclaration(expression, source))
      ParameterizedVerilogException.fail("SPINAL-ELAB-INT-COMPACT-AUTHORIZATION-MISMATCH",
        "compact declaration received a missing, consumed, copied, stale or foreign frontend permit",
        Option(expression).flatMap(_.sourceLocation))
  }''')

    replace(BRIDGE, '''  def analyzedWidth(
''', '''  /** Authenticate only a bare declaration token. Large arbitrary HdlInt
    * ASTs are not promoted from interval summaries by this ingress.
    */
  def analyzedCompactDeclaration(value: HdlInt): Option[AnalyzedCompactDeclaration] = {
    if (value == null || value.scope.nonEmpty || value.localDeclaration.nonEmpty ||
        value.localParameters.nonEmpty || value.booleanParameters.nonEmpty ||
        value.booleanLocalParameters.nonEmpty || value.formalBinding.nonEmpty) return None
    val token = (value.expression, value.declaration) match {
      case (IntExpr.ParameterRef(name), Some(declaration))
          if name == declaration.declaration.name && value.parameters.size == 1 &&
            value.parameters.exists(_ eq declaration) => declaration
      case _ => return None
    }
    val facts = IntExpressionAnalysis.parameterFacts(token.declaration).getOrElse(return None)
    val minimum = facts.interval.lower.getOrElse(return None)
    val maximum = facts.interval.upper.getOrElse(return None)
    if (maximum - minimum + 1 <= spinal.core.ElabInt.MaximumExactDomainSize) return None
    val expression = integerImpl(value, "compact parameter declaration",
      NativeStructuralFrontend.currentGenerateIndices, allowPortableLogHelper = true)
    Some(new AnalyzedCompactDeclaration(value, expression, AnalyzerSeal))
  }

  def analyzedWidth(
''')
    with (ROOT / BRIDGE).open("a") as file:
        file.write('''
/** One-use analyzer-owned direct declaration. No public factory accepts raw
  * integer metadata to construct this wrapper.
  */
final class AnalyzedCompactDeclaration private[frontend] (
    private val sourceIdentity: AnyRef,
    private val expression: ElaborationIntegerExpression,
    private val analyzerSeal: AnyRef
) {
  private[this] var consumed = false
  def claim(): (AnyRef, ElaborationIntegerExpression) = synchronized {
    if (!StructuralExpressionBridge.authenticates(analyzerSeal) || consumed)
      FrontendException.failAt("MORPH-FRONTEND-COMPACT-AUTHORIZATION-INVALID",
        "compact declaration analysis is foreign or already consumed", SourceOrigin("<compact-declaration>", 1))
    consumed = true
    sourceIdentity -> expression
  }
}
''')
    replace(HDL, '''  def asElabInt: spinal.core.ElabInt = {
''', '''  def asElabInt: spinal.core.ElabInt = {
    StructuralExpressionBridge.analyzedCompactDeclaration(this) match {
      case Some(declaration) =>
        return spinal.core.ExternalAnalyzedFrontendPermitIssuer.compact(declaration)
      case None =>
    }
''')
    replace(ISSUER, '''object ExternalAnalyzedFrontendPermitIssuer {
''', '''object ExternalAnalyzedFrontendPermitIssuer {
  def compact(analyzed: morphhdl.frontend.AnalyzedCompactDeclaration): ElabInt = {
    require(analyzed != null, "compact declaration analysis must not be null")
    val (source, expression) = analyzed.claim()
    val permit = ExternalCompilerPermit.analyzedCompactDeclaration(source, expression)
    ElabInt.fromExpression(ElaborationProductDomain.compactDeclaration(expression, source, permit))
  }

''')

    replace(STRUCTURE, '''  private[core] def exactDeclarationDomainOf(
''', '''  /** Compact publication currently admits only a real module-scope native
    * declaration. Do not fabricate a finite universe for the older projection
    * API or let a captured declaration borrow module-scope authority.
    */
  private[core] def requireCompactDeclarationOwner(component: Component,
      declaration: BaseType, root: ElaborationIntegerParameterRoot,
      role: String, sourceLocation: Option[String]): Unit = {
    if (component == null || declaration == null || root == null)
      fail("SPINAL-ELAB-PROJECTION-OBJECT-NULL", s"$role has a null compact publication owner", sourceLocation)
    if (!allStatementsOf(component).exists(_ eq declaration))
      fail("SPINAL-ELAB-DOMAIN-COMPACT-OWNER-MISSING", s"$role declaration is absent from its exact native owner", sourceLocation)
    if (capturedDeclarations(regionsOf(component)).exists(value => value.declaration eq declaration))
      fail("SPINAL-ELAB-DOMAIN-COMPACT-OWNER-UNSUPPORTED", s"$role needs explicit compact structural-owner projection", sourceLocation)
  }

  private[core] def exactDeclarationDomainOf(
''')
    replace(FALLBACK, '''      val owned = ElaborationProductDomain.owner(record.expression, role, source) { (root, universe) =>
        ParameterizedStructure.exactDeclarationDomainOf(
          component, value, root, universe, role, source).values
      }.get''', '''      val owned = ElaborationProductDomain.ownerWithCompact(record.expression, role, source)(
        (root, universe) => ParameterizedStructure.exactDeclarationDomainOf(
          component, value, root, universe, role, source).values,
        (root, _) => ParameterizedStructure.requireCompactDeclarationOwner(
          component, value, root, role, source)
      ).get''')
    replace(FALLBACK, '''          val proof = ElaborationProductDomain.owner(origin, role, source) {
            (root, universe) => ParameterizedStructure.exactDeclarationDomainOf(
              component, declaration, root, universe, role, source).values
          }.getOrElse {''', '''          val proof = ElaborationProductDomain.ownerWithCompact(origin, role, source)(
            (root, universe) => ParameterizedStructure.exactDeclarationDomainOf(
              component, declaration, root, universe, role, source).values,
            (root, _) => ParameterizedStructure.requireCompactDeclarationOwner(
              component, declaration, root, role, source)
          ).getOrElse {''')
    replace(LEGALITY, '''    ElaborationProductDomain.owner(record.encoded, "symbolic legality publication", record.location) {
      (_, universe) => universe
    }.getOrElse''', '''    // The private obligation registry binds a full-domain predicate to the
    // exact Component. requireSymbolic rejects all structural restrictions.
    ElaborationProductDomain.ownerWithCompact(record.encoded, "symbolic legality publication", record.location)(
      (_, universe) => universe,
      (_, _) => ()
    ).getOrElse''')
    replace(BUILD, '''    scalaVersion := "2.12.18",''', '''    scalaVersion := "2.12.18",
    crossScalaVersions := Seq("2.12.18", "2.13.12"),''')

    scope_text = (ROOT / SCOPE).read_text()
    require(scope_text.count('SUCCESSOR_PATHS = frozenset("""') == 1, "missing exact successor list")
    scope_text, count = re.subn(r'SUCCESSOR_PATHS = frozenset\("""\n.*?\n"""\.split\(\)\)',
        'SUCCESSOR_PATHS = frozenset("""\n' + '\n'.join(sorted(expected_scope)) + '\n""".split())',
        scope_text, count=1, flags=re.S)
    require(count == 1, "cannot extend exact successor inventory")
    (ROOT / SCOPE).write_text(scope_text)
    policy = json.loads((ROOT / POLICY).read_bytes())
    native_changes = {PRODUCT, PERMIT, LEGALITY}
    for entry in policy["files"]:
        if entry["path"] in native_changes:
            require(entry["change"] == "added" and entry["edits"] == [], "unexpected native review form")
            entry["introduced_by"].append("PR189: authentic compact declaration and explicit publication authority")
            entry["reason"] += " PR189 adds a one-use analyzer-bound compact declaration; retains the exhaustive cap and rejects unsupported structural/owner enumeration."
    require(native_changes <= {e["path"] for e in policy["files"]}, "missing native policy entries")
    write_json(POLICY, policy)
    compiler_source = commit("PR189: retain compact declaration intervals without finite-table authority", COMPILER | {BUILD, SCOPE, POLICY})
    native = load("morphhdl/scripts/check-native-source-preservation.py", "compact_native_generator")
    generated = native.generate_manifest_value(ROOT, ROOT / POLICY)
    old_entries = {e["path"]: e for e in old_native["entries"]}
    new_entries = {e["path"]: e for e in generated["entries"]}
    require(set(new_entries) == set(old_entries), "native inventory changed")
    for path, entry in old_entries.items():
        if path not in native_changes:
            require(entry == new_entries[path], "unrelated native review changed: " + path)
    require(old_native["baseline"] == generated["baseline"], "native baseline changed")
    write_json(NATIVE, generated)
    source = commit("PR189: bind native compact-declaration review to exact compiler bytes", {NATIVE})
    require(changed(TARGET) == expected_scope, "candidate successor inventory differs")

    wa = load(WA, "compact_previous_overlay")
    require(wa.digest(wa.normalized_helper((ROOT / WA).read_bytes())) == old_overlay["helper_normalized_sha256"], "WA08 algorithm changed")
    old_records = {e["path"]: e for e in old_overlay["files"]}
    selected = changed(old_overlay["base"]) - {WA, CONTRACT}
    require(set(old_records) <= selected, "inherited reviewed path removed")
    records = []
    for path in sorted(selected):
        row = git("ls-tree", "-z", source, "--", path)
        require(row and row.endswith(b"\0"), "deleted source requires explicit review: " + path)
        metadata, returned = row[:-1].split(b"\t", 1)
        mode, kind, _ = metadata.decode().split()
        require(kind == "blob" and mode in ("100644", "100755") and returned.decode() == path, "unsupported source " + path)
        before_row = git("ls-tree", "-z", old_overlay["base"], "--", path)
        before = wa.digest(git("show", old_overlay["base"] + ":" + path)) if before_row else None
        if path in old_records:
            require(old_records[path]["before_sha256"] == before and old_records[path]["mode"] == mode, "inherited baseline/mode changed")
        records.append({"path": path, "mode": mode, "before_sha256": before,
                        "after_sha256": wa.digest(git("show", source + ":" + path))})
    value = dict(old_overlay, files=records, final_source_commit=source)
    write_json(CONTRACT, value)
    helper, count = re.subn(rb'^CONTRACT_SHA256 = "[^"]+"$',
        ('CONTRACT_SHA256 = "' + wa.digest((ROOT / CONTRACT).read_bytes()) + '"').encode(),
        (ROOT / WA).read_bytes(), count=1, flags=re.M)
    require(count == 1 and wa.digest(wa.normalized_helper(helper)) == old_overlay["helper_normalized_sha256"], "WA08 logic changed")
    (ROOT / WA).write_bytes(helper)
    candidate = commit("PR189: seal compact timeout candidate for targeted CI only", {WA, CONTRACT})
    require(changed(BASE) == TRANSPORT | COMPILER | {BUILD, SCOPE, POLICY, NATIVE, WA, CONTRACT}, "unexpected final scope")
    print("PR189_COMPACT_CANDIDATE " + json.dumps({"input": transport, "compiler_source": compiler_source,
        "source_anchor": source, "candidate": candidate, "tree": git("rev-parse", "HEAD^{tree}").decode().strip(),
        "successor_paths": len(expected_scope), "reviewed_files": len(records)}, sort_keys=True))
    print("Candidate is not qualified or published; execute every targeted check next.")


if __name__ == "__main__":
    main()
