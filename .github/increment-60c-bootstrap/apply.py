from pathlib import Path
import hashlib, json, subprocess

root = Path.cwd()
base = 'd0c2d65ed301a7895218a2fe225b2faf4a4bbfe0'
def git(*args):
    return subprocess.check_output(['git', *args])
subprocess.run(['git', 'merge-base', '--is-ancestor', base, 'HEAD'], check=True)
expected = {
 'core/src/main/scala/spinal/core/internals/VerilogBase.scala': '20d9542d203af5faf94e132d7514fc9865f799eb',
 'core/src/main/scala/spinal/core/internals/ComponentEmitterVerilog.scala': 'aa5a4d89e70ffe190dd54b469238bdf21a351e34',
 'morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogNativeFallback.scala': 'fea82e74e675dc37f84fcece5350552d1a0dfb0f'
}
for name, sha in expected.items():
    assert git('hash-object', name).decode().strip() == sha, name
canonical = root / 'morphhdl/contracts/native-source-preservation.json'
assert hashlib.sha256(canonical.read_bytes()).hexdigest() == '6a138cb9394c37679de92fb662405efc34dff28b2dac41f6df451318cd9aa517'
manifest = json.loads(canonical.read_text())
name = 'morphhdl/contracts/increment-55-native-change-review.json'
review = json.loads(git('show', base + ':' + name))
for entry in manifest['entries']:
    if entry['path'] not in list(expected)[:2]:
        continue
    assert not any(item['path'] == entry['path'] for item in review['files'])
    policy = dict(path=entry['path'], baseline_path=entry['baseline']['path'])
    policy.update({key: entry[key] for key in ('change', 'classification', 'introduced_by', 'reason')})
    policy['edits'] = [{key: value for key, value in edit.items()
                       if key not in ('baseline_span', 'approved_span')} for edit in entry['edits']]
    review['files'].append(policy)
review['files'].sort(key=lambda item: item['path'])
path = root / name
path.write_text(json.dumps(review, indent=2) + '\n')
assert hashlib.sha256(path.read_bytes()).hexdigest() == 'cf48d238e1496c15d45226eebd942f6c7cb7d9dbe7ea48d643ce1dc483ecd6ac'
subprocess.run(['git', 'add', '-f', str(path)], check=True)
assert git('diff', '--cached', '--name-only').decode().splitlines() == [name]
print('Reviewed native edit policy recorded without changing any source bytes')
