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


def ci_gate_controls():
    """Synthetic negative controls; never compiler/HDL qualification results."""
    import copy
    import xml.etree.ElementTree as ET
    from unittest.mock import patch

    rejected = 0
    def reject(action, label):
        nonlocal rejected
        try:
            action()
        except (RuntimeError, ValueError, ET.ParseError, OSError):
            rejected += 1
        else:
            raise AssertionError('Accepted CI/report mutation: ' + label)

    with tempfile.TemporaryDirectory(prefix='pr190-ci-catalog-controls-') as td:
        fixture = Path(td)
        # Exact workflow deltas: same original command graph and checks, with
        # only the reviewed route, additive core suite, receipt steps and calls.
        paths = (review.PASS_WORKFLOW, review.REGRESSION_WORKFLOW, review.TARGETED_WORKFLOW)
        expected = {}
        for path in paths:
            original = review.git(ROOT, 'show', review.CI_GATE_BASE + ':' + path).decode()
            expected[path] = review.ci_gate_workflow(path, original)
            target = fixture/path;target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(expected[path])
        class Catalog:
            SEQUENTIAL_WIRE_SUITES = review.SEQUENTIAL_REPORTS
        def fixture_git(root,*args): return review.git(ROOT,*args)
        with patch.object(review,'git',side_effect=fixture_git) as mocked_git, patch.object(review,'load',return_value=Catalog):
            # Avoid recursive use of the patched function in this isolated fixture.
            real_git = subprocess.check_output
            mocked_git.side_effect=lambda root,*args: real_git(['git','--literal-pathspecs',*args],cwd=ROOT)
            review.verify_ci_gate_successor(fixture)
            mutations = (
                (review.PASS_WORKFLOW, 'python3 morphhdl/scripts/check-pr190-pr189-source-sync.py', 'true'),
                (review.PASS_WORKFLOW, 'python3 morphhdl/scripts/test-sequential-wire-source-review.py', 'true'),
                (review.PASS_WORKFLOW, 'audit_source='+review.STATIC_BASE, 'audit_source=HEAD'),
                (review.PASS_WORKFLOW, 'python3 morphhdl-passes/scripts/validate_wire_assignment_equivalence.py --self-test', 'true'),
                (review.REGRESSION_WORKFLOW, ' spinal.core.internals.SequentialWireEmitterTests', ''),
                (review.REGRESSION_WORKFLOW, '--project-regressions', '--print-base'),
                (review.REGRESSION_WORKFLOW, '--combine-regressions', '--print-base'),
                (review.REGRESSION_WORKFLOW, 'morph/test', 'morph/testOnly MissingSuite'),
                (review.TARGETED_WORKFLOW, 'needs: [failed-pass-workflow, failed-regression-workflow]', 'needs: source-preflight'),
                (review.TARGETED_WORKFLOW, 'uses: ./.github/workflows/morphhdl-passes.yml', 'uses: ./.github/workflows/sequential-wire-consumers.yml'),
                (review.TARGETED_WORKFLOW, 'pull-requests: read', 'pull-requests: write'),
            )
            for path, old, new in mutations:
                assert old in expected[path], (path,old)
                file=fixture/path
                file.write_text(expected[path].replace(old,new,1))
                try:reject(lambda:review.verify_ci_gate_successor(fixture),path+': '+old)
                finally:file.write_text(expected[path])
            review.verify_ci_gate_successor(fixture)

        name='spinal.core.internals.SequentialWireEmitterTests'
        def xml(name=name,count=6):
            suite=ET.Element('testsuite',name=name,tests=str(count),failures='0',errors='0',skipped='0')
            for index in range(count):ET.SubElement(suite,'testcase',name='case '+str(index))
            return suite
        report=fixture/'suite.xml'
        valid=xml();report.write_bytes(ET.tostring(valid))
        review.sequential_report_record(report,name,6)
        mutations=[]
        for key in ('failures','errors','skipped'):
            value=xml();value.set(key,'1');mutations.append((key,value))
        for tag in ('failure','error','skipped'):
            value=xml();ET.SubElement(value[0],tag);mutations.append(('nested '+tag,value))
        value=xml();value.set('name','wrong-suite');mutations.append(('suite name',value))
        value=xml();value.set('tests','5');mutations.append(('count',value))
        value=xml();value.remove(value[0]);mutations.append(('missing testcase',value))
        value=xml();value[0].set('name',value[1].get('name'));mutations.append(('duplicate case',value))
        value=xml();value[0].attrib.pop('name');mutations.append(('unnamed case',value))
        value=xml();value.tag='testsuites';mutations.append(('aggregate root',value))
        for label,value in mutations:
            report.write_bytes(ET.tostring(value))
            reject(lambda:review.sequential_report_record(report,name,6),label)
        report.write_bytes(b'<malformed')
        reject(lambda:review.sequential_report_record(report,name,6),'malformed XML')
        report.unlink()
        reject(lambda:review.sequential_report_record(report,name,6),'missing XML')
        real=fixture/'real.xml';real.write_bytes(ET.tostring(valid));report.symlink_to(real)
        reject(lambda:review.sequential_report_record(report,name,6),'linked XML');report.unlink()
        # Whole extension: mandatory projects/counts and actual case identities.
        for project,suites in review.SEQUENTIAL_REPORTS.items():
            for suite,count in suites.items():
                file=fixture/project/'target/test-reports'/('TEST-'+suite+'.xml')
                file.parent.mkdir(parents=True,exist_ok=True);file.write_bytes(ET.tostring(xml(suite,count)))
        receipt=review.sequential_report_inventory(fixture,'fixture-not-a-qualified-source')
        base={p:{'tests':2,'suites':['original.a','original.b'],'skipped':0} for p in review.SEQUENTIAL_REPORTS}
        good=review.merge_sequential_inventory(base,receipt)
        assert sum(x['tests'] for x in good.values())==41
        assert sum(x['tests'] for x in base.values())==4, 'merge mutated predecessor receipt'
        for project in review.SEQUENTIAL_REPORTS:
            for kind in ('missing','count','skip','suite','report'):
                value=copy.deepcopy(receipt)
                if kind=='missing':del value['projects'][project]
                elif kind=='count':value['projects'][project]['tests']-=1
                elif kind=='skip':value['projects'][project]['skipped']=1
                elif kind=='suite':value['projects'][project]['suites'].append('unexpected')
                else:value['projects'][project]['reports'].pop(next(iter(review.SEQUENTIAL_REPORTS[project])))
                reject(lambda:review.merge_sequential_inventory(base,value),project+' '+kind)
            for kind in ('overlap','skip','duplicate','missing'):
                value=copy.deepcopy(base)
                if kind=='overlap':value[project]['suites'].append(next(iter(review.SEQUENTIAL_REPORTS[project])))
                elif kind=='skip':value[project]['skipped']=1
                elif kind=='duplicate':value[project]['suites'].append('original.a')
                else:del value[project]
                reject(lambda:review.merge_sequential_inventory(value,receipt),'inherited '+project+' '+kind)
        emitter=fixture/'core/target/test-reports'/('TEST-'+name+'.xml')
        emitter.unlink()
        reject(lambda:review.sequential_report_inventory(fixture,'fixture'),'omitted selective core suite')
    with tempfile.TemporaryDirectory(prefix='pr190-complete-xml-controls-') as td:
        fixture = Path(td)
        for project in review.REGRESSION_PROJECTS:
            report = fixture/project/'target/test-reports/TEST-original.xml'
            report.parent.mkdir(parents=True)
            report.write_bytes(ET.tostring(xml('original', 2)))
        complete = review.actual_regression_summary(fixture)
        assert set(complete) == set(review.REGRESSION_PROJECTS)
        assert sum(row['tests'] for row in complete.values()) == 16
        for project in review.REGRESSION_PROJECTS:
            report = fixture/project/'target/test-reports/TEST-original.xml'
            original = report.read_bytes()
            report.unlink()
            try:reject(lambda:review.actual_regression_summary(fixture), 'missing original project '+project)
            finally:report.write_bytes(original)
        report = fixture/'core/target/test-reports/TEST-original.xml'
        mutated=xml('original',2);ET.SubElement(mutated[0],'skipped')
        report.write_bytes(ET.tostring(mutated))
        reject(lambda:review.actual_regression_summary(fixture), 'hidden inherited skipped case')
    assert rejected==54,rejected
    print('PR190_CI_GATE_CONTROLS_PASS rejected='+str(rejected)+' synthetic_only=true')


def main():
    shell_controls()
    ci_gate_controls()
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
