from pathlib import Path
import ast,difflib,hashlib,json,re,subprocess,sys,os
r=Path(os.environ.get('MORPH_60G_ROOT','.')).resolve(); gate=r/'morphhdl/scripts/check-increment-60g-source-scope.py'; text=gate.read_text()
base=sys.argv[1] if len(sys.argv)>1 else re.search(r'^BASE = "([^"]+)"',text,re.M)[1]
contract=r/'morphhdl/contracts/increment-60g-publication-edits.json'
paths = ['morphhdl/scripts/check-increment-59f-source-scope.py', 'morphhdl/scripts/check-increment-60c-signed-declarations.py', 'morphhdl/scripts/check-increment-60d-pure-sint-casts.py', 'morphhdl/scripts/check-increment-60e-signedness-boundaries.py', 'morphhdl/src/test/scala/nativeapplication/SIntSignedDeclarationsFixture.scala', 'morphhdl/src/test/scala/nativeapplication/SIntSignedVerilogBaselineFixture.scala', 'morphhdl/src/main/scala/spinal/core/internals/MorphHdlSignednessAnalysis.scala', 'morphhdl/src/main/scala/spinal/core/internals/MorphHdlSignedDeclarationPolicy.scala', 'morphhdl/src/main/scala/morphhdl/MorphVerilog.scala', 'morphhdl/src/main/scala/morphhdl/MorphSignedCasts.scala', 'morphhdl/src/main/scala/morphhdl/MorphSignedDeclarations.scala', 'core/src/main/scala/spinal/core/internals/Phase.scala', 'morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedNativeResize.scala', 'morphhdl/scripts/check-increment-59c-source-review.py', 'morphhdl/scripts/check-increment-60f-artifacts.py', 'morphhdl/scripts/check-increment-60f-equivalence-closure.py', 'morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogNativeFallback.scala', 'morphhdl/src/main/scala/spinal/core/internals/ParameterizedVerilogStructural.scala']
newpaths = [
    'morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedNativeResize.scala',
    'morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogNativeFallback.scala',
    'morphhdl/src/main/scala/spinal/core/internals/ParameterizedVerilogStructural.scala'
]
def sha(s): return hashlib.sha256(s.encode()).hexdigest()
def old(p): return subprocess.check_output(['git','show',base+':'+p],cwd=r,text=True)
def edits(a,b):
    al=a.splitlines(keepends=True);bl=b.splitlines(keepends=True)
    for context in range(3,100):
        groups=list(difflib.SequenceMatcher(None,al,bl,autojunk=False).get_grouped_opcodes(context))
        result=[{'before':''.join(al[g[0][1]:g[-1][2]]),'after':''.join(bl[g[0][3]:g[-1][4]])} for g in groups]
        s=a
        if not all(e['before'] and e['after'] and a.count(e['before'])==1 and b.count(e['after'])==1 for e in result): continue
        for e in result: s=s.replace(e['before'],e['after'],1)
        if s!=b:continue
        for e in reversed(result):
            assert s.count(e['after'])==1
            s=s.replace(e['after'],e['before'],1)
        assert s==a
        return result
    raise RuntimeError('could not construct unique spans')
entries=[]
for p in paths:
    a=old(p);b=(r/p).read_text();delta=edits(a,b)
    assert delta,p
    entries.append(dict(path=p,before_sha256=sha(a),after_sha256=sha(b),edits=delta))
raw=json.dumps(dict(base=base,files=entries),indent=2)+'\n';contract.write_text(raw)
text=re.sub(r'^NATIVE_MANIFEST_SHA256 = .*$', 'NATIVE_MANIFEST_SHA256 = '+repr(sha((r/'morphhdl/contracts/native-source-preservation.json').read_text())),text,flags=re.M)
text=re.sub(r'^BASE = .*$',f'BASE = "{base}"',text,flags=re.M)
text=re.sub(r'^CONTRACT_SHA256 = .*$',f'CONTRACT_SHA256 = "{sha(raw)}"',text,flags=re.M)
text=re.sub(r'^PATHS = .*$',f'PATHS = frozenset({paths!r})',text,flags=re.M)
for var in ('PRODUCTION','QUALIFICATION'):
    tree=ast.parse(text);node=next(n for n in tree.body if isinstance(n,ast.Assign) and any(isinstance(t,ast.Name) and t.id==var for t in n.targets))
    values=ast.literal_eval(node.value)
    if var=='PRODUCTION':values.update({p:'' for p in newpaths})
    values={p:sha((r/p).read_text()) for p in values}
    lines=text.splitlines(keepends=True)
    lines[node.lineno-1:node.end_lineno]=[var+' = '+json.dumps(values,indent=4)+'\n']
    text=''.join(lines)
text=text.replace('five-file publication policy','eight-file publication policy').replace('seven publication files','eight publication files').replace('seven-file publication policy','eight-file publication policy').replace('five publication files','eight publication files').replace('six publication files','eight publication files')
gate.write_text(text)
print('Restored exact 60g source ledger:', len(entries), sha(raw))
