"""The live target ref is authoritative; no moved-ref gate is relaxed."""
import copy
import importlib.util
from pathlib import Path

spec = importlib.util.spec_from_file_location('stage', Path(__file__).with_name('stage.py'))
stage = importlib.util.module_from_spec(spec)
spec.loader.exec_module(stage)
good = {
    '/git/ref/heads/' + stage.FEATURE: {'object': {'sha': stage.BASE}},
    '/git/ref/heads/' + stage.TARGET_BRANCH: {'object': {'sha': stage.TARGET}},
    '/pulls/177': {'state': 'open', 'draft': True, 'merged': False,
                   'head': {'sha': stage.BASE, 'ref': stage.FEATURE},
                   'base': {'sha': 'e0e9f1d7089d3aa513677a2b94c63eb4a7a7791d', 'ref': stage.TARGET_BRANCH}},
}

def run(data):
    remote = object.__new__(stage.Remote)
    remote.api = lambda method, path: copy.deepcopy(data[path])
    remote.identity(stage.BASE)

run(good)
changes = [
    ('/git/ref/heads/' + stage.FEATURE, 'object', 'sha', '0' * 40),
    ('/git/ref/heads/' + stage.TARGET_BRANCH, 'object', 'sha', '0' * 40),
    ('/pulls/177', 'head', 'sha', '0' * 40),
    ('/pulls/177', 'head', 'ref', 'main'),
    ('/pulls/177', 'base', 'ref', 'main'),
    ('/pulls/177', None, 'state', 'closed'),
    ('/pulls/177', None, 'draft', False),
    ('/pulls/177', None, 'merged', True),
]
for endpoint, section, key, value in changes:
    data = copy.deepcopy(good)
    target = data[endpoint] if section is None else data[endpoint][section]
    target[key] = value
    try:
        run(data)
    except RuntimeError:
        pass
    else:
        raise AssertionError((endpoint, section, key))
print('PASS: stale PR-base metadata accepted only with exact live target; all 8 ref/PR mutations rejected')
