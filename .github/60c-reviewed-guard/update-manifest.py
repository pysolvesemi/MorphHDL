from pathlib import Path
import hashlib, importlib.util, json, subprocess, os
root=Path.cwd()
def git(*args): return subprocess.check_output(['git',*args])
spec=importlib.util.spec_from_file_location('audit',root/'morphhdl/scripts/check-native-source-preservation.py')
audit=importlib.util.module_from_spec(spec);spec.loader.exec_module(audit)
path=root/'morphhdl/contracts/native-source-preservation.json'
manifest=json.loads(path.read_text())
review_path=root/'morphhdl/contracts/increment-55-native-change-review.json'
review=json.loads(review_path.read_text())
for name in ['core/src/main/scala/spinal/core/internals/ComponentEmitterVerilog.scala','core/src/main/scala/spinal/core/internals/VerilogBase.scala']:
    entry=next(item for item in manifest['entries'] if item['path']==name)
    original_root=os.environ.get('ORIGINAL_60B_ROOT')
    old=(Path(original_root)/name).read_bytes() if original_root else git('show','d0c2d65ed301a7895218a2fe225b2faf4a4bbfe0:'+name)
    new=(root/name).read_bytes()
    assert hashlib.sha256(old).hexdigest()==entry['baseline']['sha256']
    edits=[]
    for index,(os_,oe,ns,ne) in enumerate(audit.stable_changed_spans(old,new)):
        segment=new[ns:ne].decode();assert segment
        edits.append(dict(id='signed-declaration-'+Path(name).stem.lower()+'-'+str(index+1),
          kind='backend-isolation',owner='spinal.core.internals.'+Path(name).stem,
          reason='Mode-gated exact scalar, wrapper and function declaration occurrences; inherited expression/cast printer is unchanged.',
          baseline_span=dict(start=os_,end=oe,sha256=hashlib.sha256(old[os_:oe]).hexdigest()),
          approved_span=dict(start=ns,end=ne,sha256=hashlib.sha256(new[ns:ne]).hexdigest()),
          required_exact_text=[dict(side='approved',text=segment,count=1)]))
    audit.verify_edits(old,new,edits,name)
    entry['edits']=edits
    entry['approved'].update(blob=hashlib.sha1(b'blob '+str(len(new)).encode()+b'\0'+new).hexdigest(),sha256=hashlib.sha256(new).hexdigest())
    reviewed=next(item for item in review['files'] if item['path']==name)
    reviewed['edits']=[{key:value for key,value in edit.items() if key not in ('baseline_span','approved_span')} for edit in edits]
    subprocess.run(['git','add',name],check=True)
tree=git('write-tree').decode().strip()
for item in manifest['source_roots']:
    if item['path']=='core/src/main':item['approved_tree']=git('rev-parse',tree+':core/src/main').decode().strip()
path.write_text(json.dumps(manifest,indent=2)+'\n')
review_path.write_text(json.dumps(review,indent=2)+'\n')
audit.load_manifest(path)
subprocess.run(['git','add','-f',str(path),str(review_path)],check=True)
print('Exact native declaration occurrence edits and reproducible policy validated')
