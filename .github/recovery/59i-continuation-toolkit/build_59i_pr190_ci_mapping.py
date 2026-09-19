from pathlib import Path
import hashlib,json,subprocess,fnmatch,yaml
root=Path('/workspace/scratch/68d458e0e937/59i-dev')
base='4b8a86e25f5a1a3f0cb4c37dc537a8dd8aa7b097'
feature='agent/increment-59i-combined-reduction-closure'
def git(*a):return subprocess.check_output(['git',*a],cwd=root,text=True).strip()
head=git('rev-parse','HEAD')
changed=set(git('diff','--name-only',base+'...HEAD').splitlines())
changed.update(git('diff','--name-only').splitlines())
changed.update(git('ls-files','--others','--exclude-standard').splitlines())
branch_gated={'increment-53d-typed-streamwidthadapter.yml','increment-53e-typed-streamfifo.yml','increment-53f-typed-primitives.yml','increment-53g-production-retirement.yml','increment-54-typed-layering-ir.yml','morphhdl-passes.yml'}
routed={'increment-55-concrete-compatibility-audit.yml','increment-56-native-typed-library-surface.yml','increment-57-broad-native-library-migration.yml','increment-57a-typed-streamfifocc.yml','increment-57b-streamfifocc-payload-width-formal.yml','increment-58-legacy-retirement.yml','increment-59-typed-blackbox-generics.yml','increment-59a-recursive-verilog-module.yml'}
applicable=[];excluded=[]
for p in sorted((root/'.github/workflows').glob('*.yml')):
 v=yaml.safe_load(p.read_text());on=v.get('on',v.get(True,{}))
 if not isinstance(on,dict) or 'pull_request' not in on:continue
 pr=on['pull_request'] or {}
 if not isinstance(pr,dict):continue
 branches=pr.get('branches',[])
 if branches and not any(fnmatch.fnmatchcase('parameterized-verilog',b) for b in branches):continue
 paths=pr.get('paths')
 matches=sorted(s for s in changed if paths is None or any(fnmatch.fnmatchcase(s,x) for x in paths))
 if not matches and p.name != 'sequential-wire-consumers.yml':
  excluded.append({'workflow':p.name,'reason':'PR path filter does not match current PR source delta.'});continue
 if p.name in branch_gated:
  excluded.append({'workflow':p.name,'reason':'Explicit root-job PR branch predicates exclude '+feature+'.','root_job_conditions':{j:x.get('if','true') for j,x in v['jobs'].items() if not x.get('needs')}});continue
 if p.name in routed:
  assert subprocess.run(['git','cat-file','-e',base+':.github/workflows/'+p.name],cwd=root,capture_output=True).returncode==0
  excluded.append({'workflow':p.name,'reason':'Immutable-base route emits run=false for PR because this workflow already exists at target '+base+'. Historical obligations execute through inherited gates.'});continue
 entry={'workflow':p.name,'path':p.relative_to(root).as_posix(),'name':v['name'],'workflow_sha256':hashlib.sha256(p.read_bytes()).hexdigest(),'dispatch_supported_by_source':'workflow_dispatch' in on,'dispatch_inputs':{},'matched_path_count':len(matches),'matched_path_examples':matches[:3],'jobs':[]}
 if p.name=='sequential-wire-consumers.yml':entry['integration_requirement']='Qualify actual native sequential consumers on the combined 59i/PR190 source, even though target-owned files do not appear in the feature PR diff.'
 for j,x in v['jobs'].items():
  entry['jobs'].append({'id':j,'name':x.get('name',j),'needs':x.get('needs',[]),'if':x.get('if','true'),'matrix':x.get('strategy',{}).get('matrix',{}),'timeout_minutes':x.get('timeout-minutes',360)})
 if p.name=='cdc-independent-parameter-consumers.yml':
  entry['dispatch_inputs']={'mode':'checks'}
  entry['required_jobs']=[j for j in v['jobs'] if j!='repair-reviewed-source']
  entry['intentionally_skipped_jobs']={'repair-reviewed-source':'PR189 repair is not part of59i qualification.'}
 elif p.name=='increment-60a-sint-baseline.yml':
  entry['required_jobs']=[j for j in v['jobs'] if j!='seal']
  entry['intentionally_skipped_jobs']={'seal':'One-time60a seal publishes only on60a feature-branch push; excluded on59i PR and manual qualification.'}
 else:entry['required_jobs']=list(v['jobs'])
 applicable.append(entry)
result={'schema':1,'repository':'pysolvesemi/MorphHDL','pr':177,'feature_branch':feature,'target_branch':'parameterized-verilog','target_snapshot':base,'local_source_head_at_mapping':head,'includes_uncommitted_final_source':bool(git('status','--porcelain')),'final_implementation_head':None,'planning_only':True,'remote_ci_dispatched':False,'sequence':['Finish failed local-enable diagnostic replacement already recorded at run35458181915 on exact PR190-integrated unsealed source.','Seal final implementation and pass increment-59i-local-enable-committed-head.yml on its exact commit.','Only after remaining failed requirement passes, qualify all applicable workflows below at final implementation commit; retain existing successful same-source run rather than duplicate.','After all applicable final-head checks pass, update TODO and complete PR; do not manually dispatch duplicate broad post-merge CI.'],'refresh_before_dispatch':['Refresh PR head/target and compare workflow hashes after source seal.','Query actual runs/attempts on final head and reuse same-source in-progress or successful runs.','Do not promote earlier-source passes to final-head success.','Do not dispatch excluded legacy or repair/promotion workflows merely because workflow_dispatch exists.'],'expected_inventory':{'test_cases':2307,'suites':230,'local_enable_cases':31,'basis':'source-derived expected inventory, not executed Scala results'},'applicable_workflows':applicable,'excluded_pr_workflows':excluded}
p=Path('/workspace/scratch/68d458e0e937/59i-pr190-full-ci-workflow-map.json');p.write_text(json.dumps(result,indent=2)+'\n')
print(p)
print('applicable',len(applicable),'excluded',len(excluded))
print('\n'.join(v['workflow'] for v in applicable))
