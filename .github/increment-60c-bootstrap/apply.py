from pathlib import Path
import hashlib, importlib.util, json, subprocess

root = Path.cwd()
base = 'd0c2d65ed301a7895218a2fe225b2faf4a4bbfe0'
def git(*args):
    return subprocess.check_output(['git', *args])
patch = root / '.github/increment-60c-bootstrap/native.patch'
assert hashlib.sha256(patch.read_bytes()).hexdigest() == '0ec9849b3abd13e069f975eedce7630433ccc27860eed0591a309042d2d9d2a4'
subprocess.run(['git', 'merge-base', '--is-ancestor', base, 'HEAD'], check=True)
subprocess.run(['git', 'apply', '--check', str(patch)], check=True)
subprocess.run(['git', 'apply', '--index', str(patch)], check=True)
expected = {
 'core/src/main/scala/spinal/core/internals/VerilogBase.scala': '20d9542d203af5faf94e132d7514fc9865f799eb',
 'core/src/main/scala/spinal/core/internals/ComponentEmitterVerilog.scala': 'aa5a4d89e70ffe190dd54b469238bdf21a351e34',
 'morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogNativeFallback.scala': 'fea82e74e675dc37f84fcece5350552d1a0dfb0f'
}
assert set(git('diff', '--cached', '--name-only').decode().splitlines()) == set(expected)
for path, sha in expected.items():
    assert git('hash-object', path).decode().strip() == sha, path
spec = importlib.util.spec_from_file_location('audit', root / 'morphhdl/scripts/check-native-source-preservation.py')
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)
path = root / 'morphhdl/contracts/native-source-preservation.json'
manifest = json.loads(path.read_text())
for name in list(expected)[:2]:
    old = git('show', base + ':' + name)
    new = (root / name).read_bytes()
    assert not any(item['path'] == name for item in manifest['entries'])
    edits = []
    for index, (os, oe, ns, ne) in enumerate(audit.stable_changed_spans(old, new)):
        segment = new[ns:ne].decode()
        assert segment
        edits.append(dict(id='signed-declaration-' + Path(name).stem.lower() + '-' + str(index+1),
            kind='backend-isolation', owner='spinal.core.internals.' + Path(name).stem,
            reason='Mode-gated exact declaration occurrence and unsigned transport hook; inherited expression/cast printer is unchanged.',
            baseline_span=dict(start=os, end=oe, sha256=hashlib.sha256(old[os:oe]).hexdigest()),
            approved_span=dict(start=ns, end=ne, sha256=hashlib.sha256(new[ns:ne]).hexdigest()),
            required_exact_text=[dict(side='approved', text=segment, count=1)]))
    def state(data):
        return dict(path=name, mode='100644', blob=hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest(), sha256=hashlib.sha256(data).hexdigest())
    audit.verify_edits(old, new, edits, name)
    manifest['entries'].append(dict(path=name, change='modified', classification='backend-isolation-hook',
        introduced_by=['Increment 60c: native signed declarations with casts retained'],
        reason='Generation-scoped declaration type policy and exact emitter-owned wrapper occurrences; no mode or behavior change for ordinary emitters.',
        baseline=state(old), approved=state(new), edits=edits))
tree = git('write-tree').decode().strip()
for item in manifest['source_roots']:
    if item['path'] == 'core/src/main':
        item['approved_tree'] = git('rev-parse', tree + ':core/src/main').decode().strip()
manifest['entries'].sort(key=lambda item: item['path'])
path.write_text(json.dumps(manifest, indent=2) + '\n')
audit.load_manifest(path)
assert hashlib.sha256(path.read_bytes()).hexdigest() == '6a138cb9394c37679de92fb662405efc34dff28b2dac41f6df451318cd9aa517'
subprocess.run(['git', 'add', '-f', str(path)], check=True)
print('Reviewed native source bytes and exact manifest reproduced')
