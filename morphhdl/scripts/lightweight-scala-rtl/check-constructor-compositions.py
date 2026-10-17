#!/usr/bin/env python3
"""Focused local LWR-13–16 checks against an explicitly supplied built classpath."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time

ROOT = Path(__file__).resolve().parents[3]
SUITES = [
    'ConstructorParameterTests', 'LightweightPortCompositionTests',
    'PackedParameterTests', 'OptionalReductionPolicyTests',
    'LightweightBalancedTreeTests', 'LightweightRecursiveTests',
    'LightweightRecursiveSafetyTests', 'ScalarFormalReuseTests',
]

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--frontend-only', action='store_true', help='Compile and run constructor/domain frontend fixtures')
    parser.add_argument('--scala', choices=['2.12', '2.13'], required=True)
    parser.add_argument('--classpath-file', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--cleanup-binary', type=Path)
    parser.add_argument('--suites', nargs='+', default=SUITES, help='Focused ScalaTest suites under morphhdl')
    args = parser.parse_args()
    cp = args.classpath_file.read_text().strip()
    if not all(Path(p).exists() for p in cp.split(os.pathsep)):
        raise SystemExit('Missing classpath entries; build the requested compiler first')
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=False)
    classes = out/'classes'; classes.mkdir()
    artifacts = out/'artifacts'; artifacts.mkdir()
    reports = out/'xml'; reports.mkdir()
    plugins = [ROOT/f'{m}/target/scala-{args.scala}/{n}_{args.scala}-dev.jar' for m,n in
               [('idslplugin','spinalhdl-idsl-plugin'), ('morphplugin','morphhdl-compiler-plugin')]]
    if args.frontend_only:
        suites = ['morphhdl.compiler.ConstructorParameterSyntaxTests','morphhdl.compiler.InferredParameterSyntaxTests']
        sources = [ROOT/f'morphplugin/src/test/scala/{s.replace(".","/")}.scala' for s in suites]
        sources.append(ROOT/'morphplugin/src/test/scala/morphhdl/compiler/MorphHdlCompilerTestSupport.scala')
        plugin_classes=ROOT/f'morphplugin/target/scala-{args.scala}/classes'
        cp=os.pathsep.join([str(plugin_classes),*[p for p in cp.split(os.pathsep) if not p.startswith(str(ROOT)+os.sep)]])
        compile_options=[]
    else:
        suites=['morphhdl.'+s for s in args.suites]
        sources=[ROOT/f'morphhdl/src/test/scala/morphhdl/{s}.scala' for s in args.suites]
        sources.append(ROOT/'morphhdl/src/test/scala/morphhdl/Increment66ToolEvidence.scala')
        if set(args.suites) & {'GenericExpressionAndStreamTests','CapturedDomainWidthEquivalenceTests'}:
            sources.append(ROOT/'morphhdl/src/test/scala/morphhdl/NativeWireCompatibility.scala')
        compile_options=[*['-Xplugin:'+str(p) for p in plugins],'-Xplugin-require:idsl-plugin','-Xplugin-require:morphhdl']
    sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
    source_paths = subprocess.check_output(['git','ls-files','--cached','--others','--exclude-standard'],cwd=ROOT,text=True).splitlines()
    report = {'base': subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),
              'scala': args.scala, 'frontend_only': args.frontend_only, 'source_sha256': {p:sha(ROOT/p) for p in source_paths if p.endswith('.scala') and (ROOT/p).is_file()},
              'plugins': {str(p.relative_to(ROOT)):sha(p) for p in plugins}, 'commands': []}
    commands = [
        ['java','-Xmx3G','-XX:ActiveProcessorCount=4','-cp',cp,'scala.tools.nsc.Main','-Yrangepos','-classpath',cp,'-d',str(classes),
         *compile_options,*map(str,sources)],
        ['java','-Xmx3G','-XX:ActiveProcessorCount=4','-Djava.io.tmpdir='+str(artifacts),'-cp',str(classes)+os.pathsep+cp,
         'org.scalatest.tools.Runner','-o','-u',str(reports),*[v for s in suites for v in ['-s',s]]],
    ]
    for number, command in enumerate(commands):
        started = time.monotonic()
        with (out/f'{number}.log').open('w') as log:
            result = subprocess.run(command,cwd=ROOT,stdout=log,stderr=subprocess.STDOUT,timeout=600,
                                    env={**os.environ,"MORPHDL_INCREMENT66_EVIDENCE":str(out/"tools")})
        report['commands'].append({'command':command,'returncode':result.returncode,'seconds':time.monotonic()-started,
                                   'log_sha256':sha(out/f'{number}.log')})
        report['artifacts'] = {str(p.relative_to(out)):sha(p) for p in artifacts.rglob('*') if p.is_file() and p.suffix in ['.v','.sv']}
        (out/'results.json').write_text(json.dumps(report,indent=2)+'\n')
        print(number,result.returncode,out/f'{number}.log',flush=True)
        if result.returncode: raise SystemExit(result.returncode)
    if args.cleanup_binary:
        import shutil
        cleaner = args.cleanup_binary.resolve()
        report['cleanup_binary_sha256'] = sha(cleaner)
        selected = {'ScopedBits','SiblingGeometry','ConcretePorts','ConstructorSynchronizer','OneBitChild','PartialChildInput'}
        for rtl in sorted(artifacts.rglob('*.v')):
            if rtl.stem not in selected or not (rtl.parent/'tb.v').exists(): continue
            cleaned=out/'cleaned'/rtl.parent.name; cleaned.mkdir(parents=True)
            shutil.copyfile(rtl.parent/'tb.v',cleaned/'tb.v')
            commands=[([str(cleaner),'--dir',str(cleaned),str(rtl)],ROOT),
                      (['iverilog','-g2001','-s','tb','-o','sim',rtl.name,'tb.v'],cleaned),
                      (['vvp','sim'],cleaned),
                      (['verilator','--lint-only','--language','1364-2001','-DSYNTHESIS','-Wno-fatal',rtl.name],cleaned),
                      (['yosys','-Q','-p',f'read_verilog -DSYNTHESIS {rtl.name}; hierarchy -check -top {rtl.stem}; proc; opt; check -assert; stat'],cleaned)]
            for ordinal,(command,directory) in enumerate(commands):
                log=cleaned/f'{ordinal}.log'
                with log.open('w') as stream:
                    result=subprocess.run(command,cwd=directory,stdout=stream,stderr=subprocess.STDOUT,timeout=120)
                report['commands'].append({'command':command,'cwd':str(directory),'returncode':result.returncode,'log_sha256':sha(log)})
                report.setdefault('cleaned_artifacts',{})[str(cleaned/rtl.name)]=sha(cleaned/rtl.name) if (cleaned/rtl.name).exists() else None
                (out/'results.json').write_text(json.dumps(report,indent=2)+'\n')
                if result.returncode: raise SystemExit(f'Failed {log}')

if __name__ == '__main__': main()
