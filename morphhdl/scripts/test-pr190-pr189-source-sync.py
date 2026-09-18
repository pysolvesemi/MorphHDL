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


def cdc_shell_contract(source):
    """The compact job's outer script needs Bash, not container-default sh."""
    job_marker = "\n  cdc-compact:\n"
    if source.count(job_marker) != 1:
        raise AssertionError('Missing or ambiguous compact job')
    job = source.split(job_marker, 1)[1]
    # Steps are fixed-indentation YAML in this reviewed workflow. Bound the
    # check to the exact execution step, so another step cannot supply shell.
    step_marker = '    - name: Execute unchanged compact and record compiler checks plus all source groups\n'
    if job.count(step_marker) != 1:
        raise AssertionError('Missing or ambiguous compact execution step')
    step = job.split(step_marker, 1)[1].split('\n    - ', 1)[0]
    shells = [line for line in step.splitlines() if line.lstrip().startswith('shell:')]
    if shells != ['      shell: bash']:
        raise AssertionError('Compact execution step must select Bash explicitly')


def shell_controls():
    source = (ROOT/'.github/workflows/sequential-source-review-targeted.yml').read_text()
    cdc_shell_contract(source)
    marker = '    - name: Execute unchanged compact and record compiler checks plus all source groups\n'
    selected = marker + '      shell: bash\n'
    assert source.count(selected) == 1
    mutations = (
        source.replace(selected, marker),
        source.replace(selected, marker + '      shell: sh\n'),
        source.replace(selected, marker + '      shell: bash\n      shell: sh\n'),
    )
    for mutation in mutations:
        try:
            cdc_shell_contract(mutation)
        except AssertionError:
            pass
        else:
            raise AssertionError('Accepted broken container shell configuration')
    subprocess.run(['bash', '-e', '-o', 'pipefail', '-c',
                    'set -euo pipefail; values=(compact record); test "${#values[@]}" = 2'], check=True)
    failed = subprocess.run(['bash', '-e', '-o', 'pipefail', '-c',
                             'false | cat; exit 0'], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    assert failed.returncode != 0, 'Bash pipeline failures must propagate'
    print('PR190_CDC_SHELL_CONTROLS_PASS rejected=3 bash_startup=pass pipefail=pass')


def main():
    shell_controls()
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
