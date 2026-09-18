#!/usr/bin/env python3
"""Retain the exact sync payload; add the reviewed certificate-chain test link."""
from __future__ import annotations
import base64,hashlib,importlib.util,json,lzma
from pathlib import Path
HERE=Path(__file__).resolve().parent
STAGER='a28cebfc4693ab3724622ca93c171f173a646af8'
CORRECTION='bf2f01ebcbe845a601fd7c4ff09a5e2af0c61818647846b330a7130cd9d2909c'
OLD_SOURCE='a68830a058da93dcc1fb4662d777a309e4986ebd'
OLD_SEAL='ba167f35742e94ed6b1c287fdf0ca8a3e02ee101'
PATH='morphhdl/scripts/test-increment-59i-continuation.py'
def checked(path):
 if not path.is_file() or path.is_symlink():raise RuntimeError('Missing regular sync input')
 return path.read_bytes()
def configure():
 raw=checked(HERE/'stage.py')
 if hashlib.sha1(b'blob '+str(len(raw)).encode()+b'\0'+raw).hexdigest()!=STAGER:raise RuntimeError('Original staging implementation changed')
 spec=importlib.util.spec_from_file_location('exact_sync_stager',HERE/'stage.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
 change=checked(HERE/'correction.json')
 if hashlib.sha256(change).hexdigest()!=CORRECTION:raise RuntimeError('Correction identity changed')
 c=json.loads(change)
 if (c['original_source'],c['original_seal'])!=(OLD_SOURCE,OLD_SEAL) or len(c['files'])!=1 or c['files'][0]['path']!=PATH:raise RuntimeError('Unreviewed correction scope')
 for key in ('source','seal','source_tree','seal_tree'):setattr(m,key.upper(),c['metadata'][key])
 m.MANIFEST_SHA=c['metadata']['manifest_sha256']
 def payload(here):
  compressed=base64.b64decode(checked(here/'payload.xz.b64'),validate=True)
  if hashlib.sha256(compressed).hexdigest()!=m.PAYLOAD_SHA:raise RuntimeError('Original payload changed')
  v=json.loads(lzma.decompress(compressed))
  if (v['source'],v['seal'])!=(OLD_SOURCE,OLD_SEAL) or v['parents']!=[m.PARENT,m.TARGET]:raise RuntimeError('Original payload topology changed')
  if sum(e['path']==PATH for e in v['files'])!=1:raise RuntimeError('Ambiguous corrected file')
  v.update(c['metadata']);v['files']=[c['files'][0] if e['path']==PATH else e for e in v['files']]
  return v
 m.load_payload=payload
 return m
if __name__=='__main__':configure().main()
