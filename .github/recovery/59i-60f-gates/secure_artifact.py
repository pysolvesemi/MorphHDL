#!/usr/bin/env python3
"""Keep the exact repair; strip API credentials before artifact redirects."""
from __future__ import annotations
import hashlib, importlib.util, os, urllib.request, urllib.parse
from pathlib import Path
HERE=Path(__file__).resolve().parent
STAGER='3e50f1abd8f18fbd7fc43f1035afeb591eeac477'

class ArtifactRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        url=urllib.parse.urlsplit(newurl)
        if url.scheme!='https' or not url.hostname or url.username or url.password:
            raise RuntimeError('Unsafe artifact redirect refused')
        redirected=super().redirect_request(req,fp,code,msg,headers,newurl)
        if redirected is not None:
            for key in list(redirected.headers)+list(redirected.unredirected_hdrs):
                if key.lower() in ('authorization','proxy-authorization','cookie'):
                    redirected.remove_header(key)
        return redirected

def configure():
    path=HERE/'stage.py'
    if not path.is_file() or path.is_symlink(): raise RuntimeError('Regular immutable stager required')
    raw=path.read_bytes()
    if hashlib.sha1(b'blob '+str(len(raw)).encode()+b'\0'+raw).hexdigest()!=STAGER:
        raise RuntimeError('Reviewed stager changed')
    spec=importlib.util.spec_from_file_location('exact_60f_stager',path)
    m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
    def artifact(out):
        file=out/'original-regressions.zip'
        if not file.exists():
            m.require(os.environ.get('GITHUB_REPOSITORY')==m.REPO,'wrong artifact repository')
            request=urllib.request.Request('https://api.github.com/repos/'+m.REPO+'/actions/artifacts/'+str(m.ARTIFACT)+'/zip',
                headers={'Authorization':'Bearer '+os.environ['GH_TOKEN'],'Accept':'application/vnd.github+json'})
            with urllib.request.build_opener(ArtifactRedirect()).open(request,timeout=120) as response:
                raw=response.read()
            m.require(m.digest(raw)==m.ARTIFACT_SHA,'original artifact checksum mismatch')
            tmp=out/'artifact-download.tmp';tmp.write_bytes(raw);tmp.replace(file)
        m.require(file.is_file() and not file.is_symlink() and m.digest(file.read_bytes())==m.ARTIFACT_SHA,
            'original artifact checksum mismatch')
        return file
    m.artifact=artifact
    return m
if __name__=='__main__':configure().main()
