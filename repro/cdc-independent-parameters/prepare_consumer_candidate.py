#!/usr/bin/env python3
"""Temporary exact source patch preparation. Stage objects only: NEVER update refs."""
import json
import os
from pathlib import Path
import subprocess
import urllib.request

BASE = '448dfdf29839bfde4f8ff1f4833f3bb01180a56d'
REPO = 'pysolvesemi/MorphHDL'
root = Path.cwd()
changed = []

def replace(path, old, new):
    file = root / path
    text = file.read_text()
    assert text.count(old) == 1, (path, text.count(old), old[:120])
    file.write_text(text.replace(old, new))
    if path not in changed:
        changed.append(path)

def add(path, text):
    file = root / path
    assert not file.exists(), path
    file.parent.mkdir(parents=True, exist_ok=True)
    file.write_text(text)
    changed.append(path)

head = subprocess.check_output(['git','rev-parse','HEAD'], text=True).strip()
subprocess.run(['git','merge-base','--is-ancestor',BASE,head], check=True)
subprocess.run(['git','diff','--exit-code',BASE,'--','core','morphruntime','morphhdl','frontend'], check=True)
add('core/src/main/scala/spinal/core/ElaborationPublicationValue.scala', '''package spinal.core

/** Consumer-specific publication authority; never grants a finite structural domain. */
private[spinal] object ElaborationPublicationValue {
  def projected(value: ElabInt, role: String, failureCode: String,
                requireProjectedExactExtrema: Boolean): ElaborationIntegerExpression = {
    if (ElaborationProductDomain.isRetained(value.expression)) {
      // Authenticate before projection, so a copied public summary cannot be laundered.
      ElaborationProductDomain.requireInteger(value.expression, role)
      val expression = value.projectedExpression(role)
      ElaborationProductDomain.requireInteger(expression, role)
      expression
    } else value.authoritativeProjectedExpression(role, failureCode, requireProjectedExactExtrema)
  }
}
''')
replace('core/src/main/scala/spinal/core/internals/ParameterizedBlackBoxGeneric.scala',
'''    val expression = value.authoritativeProjectedExpression(
      role,
''', '''    val expression = ElaborationPublicationValue.projected(
      value,
      role,
''')
replace('morphruntime/src/main/scala/spinal/core/ElabValue.scala',
'''    val expression = value.authoritativeProjectedExpression(
      role = "typed UInt value",
''', '''    val expression = ElaborationPublicationValue.projected(
      value,
      role = "typed UInt value",
''')
replace('morphruntime/src/main/scala/spinal/core/ExternalParameterizedValueRegistry.scala',
'''  ): Option[ElaborationExactDomain[BigInt]] =
    ElabInt.requireAuthoritativeIntegerDomain(
''', '''  ): Option[ElaborationExactDomain[BigInt]] =
    if (ElaborationProductDomain.isRetained(expression)) {
      // The identity-bound enclosure proves unsigned representability without
      // pretending that a product expression has one finite declaration axis.
      ElaborationProductDomain.requireInteger(expression, "retained UInt expression")
      None
    } else ElabInt.requireAuthoritativeIntegerDomain(
''')
replace('morphruntime/src/main/scala/spinal/core/ExternalParameterizedValueRegistry.scala',
'''    val minimumWidth = retainedWidth
      .map(_.minimum)
''', '''    retainedWidth.foreach(width => ElaborationWidthAuthority.requireAuthoritative(
      width, "retained UInt carrier width", "SPINAL-PARAMETERIZED-VERILOG-VALUE-WIDTH-INSUFFICIENT"))
    val minimumWidth = retainedWidth
      .map(_.minimum)
''')
hierarchy = 'morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogHierarchy.scala'
text = (root / hierarchy).read_text()
start = text.index('        val ownerEvaluation = ParameterizedStructure.projectedChildEvaluationOf(')
end = text.index('        value.name -> ExpressionBinding(value.expression)', start)
old = text[start:end]
new = '''        if (ElaborationProductDomain.isRetained(value.expression)) {
          ElaborationProductDomain.owner(value.expression, role, value.sourceLocation) { (root, universe) =>
            ParameterizedStructure.exactChildDomainOf(
              parent, blackBox, root, universe, role, value.sourceLocation).values
          }.get
        } else {
''' + '\n'.join('  ' + line if line else '' for line in old.rstrip('\n').split('\n')) + '\n        }\n'
replace(hierarchy, old, new)
old = '''          ElabInt.requireAuthoritativeIntegerDomain(
            expression,
            s"BlackBox port '$name' width of instance '$instanceName'",
            "SPINAL-PARAMETERIZED-VERILOG-BLACKBOX-PORT-WIDTH-DOMAIN-INVALID",
            requireExactExtrema = false
          )'''
new = '''          if (ElaborationProductDomain.isRetained(expression)) {
            val role = s"BlackBox port '$name' width of instance '$instanceName'"
            ElaborationProductDomain.owner(expression, role, expression.sourceLocation) { (root, universe) =>
              ParameterizedStructure.exactChildDomainOf(
                parent, blackBox, root, universe, role, expression.sourceLocation).values
            }.get
          } else {
''' + '\n'.join('  ' + line for line in old.split('\n')) + '\n          }'
replace(hierarchy, old, new)
replace('morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogNativeFallback.scala',
'''    if (record.expression.exactDomain.isEmpty) return
    val role =
''', '''    if (ElaborationProductDomain.isRetained(record.expression)) {
      val source = record.sourceLocation.orElse(record.expression.sourceLocation)
      val role = "retained symbolic UInt value"
      val owned = ElaborationProductDomain.owner(record.expression, role, source) { (root, universe) =>
        ParameterizedStructure.exactDeclarationDomainOf(
          component, value, root, universe, role, source).values
      }.get
      val (minimum, maximum) = owned.publicationRange
      val minimumWidth = ParameterizedWidth.expressionOf(value).map { width =>
        // A valid carrier can be literal, single-root, or compositional. Use
        // the existing common owner validator instead of demanding a product
        // certificate from every carrier. The authenticated lower enclosure
        // is conservative even when its owner has a narrower exact domain.
        NativePublicationWidth.validate(width, component, value, role + " carrier width")
        width.minimum
      }.getOrElse(BigInt(value.getBitsWidth))
      if (minimum < 0) fail("SPINAL-PARAMETERIZED-VERILOG-VALUE-DOMAIN-UNSUPPORTED",
        s"$role reaches negative value $minimum in its live owner", source)
      if (minimumWidth < 1 || BigInt(maximum.bitLength) > minimumWidth)
        fail("SPINAL-PARAMETERIZED-VERILOG-VALUE-WIDTH-INSUFFICIENT",
          s"$role reaches $maximum outside its live carrier minimum width $minimumWidth", source)
      return
    }
    if (record.expression.exactDomain.isEmpty) return
    val role =
''')
subprocess.run(['git','diff','--check'], check=True)
# Create only Git objects in this same authorized repository. No ref/PR/merge writes.
token = os.environ['GH_OBJECT_TOKEN']
def post(endpoint, payload):
    assert endpoint in ('git/blobs','git/trees','git/commits'), endpoint
    request = urllib.request.Request('https://api.github.com/repos/' + REPO + '/' + endpoint,
        data=json.dumps(payload).encode(), headers={'Authorization':'Bearer ' + token,
        'Accept':'application/vnd.github+json','Content-Type':'application/json'}, method='POST')
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)
entries = []
for path in changed:
    blob = post('git/blobs', {'content':(root/path).read_text(),'encoding':'utf-8'})['sha']
    entries.append({'path':path,'mode':'100644','type':'blob','sha':blob})
# Remove staging-only machinery from the candidate source tree.
for path in ('repro/cdc-independent-parameters/prepare_consumer_candidate.py', '.github/workflows/pr189-consumer-candidate.yml'):
    entries.append({'path':path,'mode':'100644','type':'blob','sha':None})
base_tree = subprocess.check_output(['git','rev-parse','HEAD^{tree}'], text=True).strip()
tree = post('git/trees', {'base_tree':base_tree,'tree':entries})['sha']
source = post('git/commits', {'message':'WIP: authenticate independent BlackBox and UInt consumers for PR189',
    'tree':tree,'parents':[head]})['sha']
print('PR189_CANDIDATE_SOURCE', source, 'TREE', tree, 'PARENT', head)
with open(os.environ['GITHUB_OUTPUT'],'a') as output:
    output.write('head=' + source + '\n')
    output.write('tree=' + tree + '\n')
