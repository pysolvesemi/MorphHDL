#!/usr/bin/env python3
"""Mutation checks of the actual combined HEAD, not either parent projection."""
import importlib.util
from pathlib import Path
import subprocess
import tempfile

ROOT=Path(__file__).resolve().parents[2]
PATH='morphhdl/scripts/check-pr190-pr189-source-sync.py'
s=importlib.util.spec_from_file_location('combined_source',ROOT/PATH)
review=importlib.util.module_from_spec(s);s.loader.exec_module(review)


def main():
    result=review.verify(ROOT)
    paths=sorted(set(result['implementation_paths'])|review.RECONCILED)
    rejected=0
    with tempfile.TemporaryDirectory(prefix='pr190-pr189-mutations-') as td:
        work=Path(td)/'source'
        review.git(ROOT,'worktree','add','--quiet','--detach',str(work),result['head'])
        try:
            for path in paths:
                file=work/path;raw=file.read_bytes()
                try:
                    file.write_bytes(raw+b'\n# deliberate combined-source tampering\n')
                    try:review.verify(work)
                    except RuntimeError:rejected+=1
                    else:raise AssertionError('Accepted source mutation: '+path)
                finally:file.write_bytes(raw)
            # Each compiler family must survive: rolling back either side to
            # the common ancestor must fail even when all other files remain.
            for path in ('core/src/main/scala/spinal/core/internals/VerilogEmitterExpressionInlining.scala',
                         'core/src/main/scala/spinal/core/ElaborationProductDomain.scala'):
                file=work/path;raw=file.read_bytes()
                try:
                    file.write_bytes(review.git(work,'show',review.BASE+':'+path))
                    try:review.verify(work)
                    except RuntimeError:rejected+=1
                    else:raise AssertionError('Accepted parent rollback: '+path)
                finally:file.write_bytes(raw)
            extra=work/'repro/unreviewed-merge/src/main/scala/Unreviewed.scala'
            extra.parent.mkdir(parents=True);extra.write_text('object Unreviewed\n')
            try:
                try:review.verify(work)
                except RuntimeError:rejected+=1
                else:raise AssertionError('Accepted unreviewed root')
            finally:extra.unlink()
            review.verify(work)
        finally:review.git(ROOT,'worktree','remove','--force',str(work))
    assert rejected==len(paths)+3
    print('PR190_PR189_SYNC_MUTATIONS_PASS rejected='+str(rejected))

if __name__=='__main__':main()
