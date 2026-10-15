import argparse
import hashlib
import json
import re
from pathlib import Path
from urllib.parse import unquote

parser = argparse.ArgumentParser(description='Verify fresh issue #2/#3 native reports and complete target coverage.')
parser.add_argument('directories', nargs='+', type=Path)
parser.add_argument('--output', type=Path, default=Path('build/issues-2-3-evidence.json'))
args = parser.parse_args()
records = []
excluded = []
root = Path(__file__).resolve().parent.parent
version = next(line.split('=', 1)[1].strip() for line in (root / 'gradle.properties').read_text().splitlines() if line.startswith('mod_version='))
for directory in args.directories:
    for row in json.loads((directory / 'summary.json').read_text(encoding='utf-8-sig')):
        if row['status'] != 'PASS':
            excluded.append(dict(directory=str(directory), **row))
            continue
        assert row['fresh'] and row['exitCode'] == 0, row
        result = json.loads(Path(row['result']).read_text(encoding='utf-8-sig'))
        assert result['complete'] and result['status'] == 'PASS' and result['runId'] == row['runId']
        assert all(step['status'] == 'PASS' for step in result['steps'])
        log = (directory / (row['target'] + '.log')).read_text(encoding='utf-8-sig')
        record = {key: row[key] for key in ('target', 'scenario', 'runId')}
        record.update(durationSeconds=result['durationSeconds'], mods=result['environment']['mods'],
                      result=row['result'], wrapper=str(directory))
        bridge = row['target'].startswith(('litematica-', 'forgematica-'))
        if row['scenario'] in ('LatticiumScenario', 'StreamingScenario'):
            if row['scenario'] == 'LatticiumScenario':
                assert 'native dependency revisions: 19 cases' in log
                assert set('0123').issubset(re.findall(r'predictor variant=(\d)', log))
                assert 'registered stateful BlockItem axis=x/y/z and throwing modded placement predictor passed' in log
                assert 'sand feed completed four' in log and 'sand mining completed four' in log
                assert 'actual Overworld/Nether/Overworld travel' in log
            streaming = re.search(r'streaming size=1024 cap=16 peak=(\d+) bridge=(\w+) samples=(\d+) jobNanosP50=(\d+) jobNanosP95=(\d+) max=(\d+)', log)
            assert streaming and streaming[2] == str(bridge).lower() and int(streaming[1]) <= 16
            record['streaming'] = dict(zip(('peak', 'samples', 'p50Nanos', 'p95Nanos', 'maxNanos'), map(int, (streaming[1], *streaming.groups()[2:]))))
            profiles = [json.loads(step['result']) for step in result['steps'] if step['command'] == 'profile.stop']
            assert len(profiles) == (2 if row['scenario'] == 'LatticiumScenario' else 1)
            record['streamingProfile'] = profiles[-1]
            if row['scenario'] == 'LatticiumScenario':
                captures = re.findall(r'capture halo=(\d+) cells=(\d+) warmup=10 samples=30 captureNanosP50=(\d+) captureNanosP95=(\d+) scanNanosP95=(\d+) allocatedBytesP95=(\d+)', log)
                assert len(captures) == 3
                record['capture'] = [dict(zip(('halo', 'cells', 'p50Nanos', 'p95Nanos', 'scanP95Nanos', 'allocatedP95Bytes'), map(int, values))) for values in captures]
                if bridge:
                    assert 'actual blueprint container edit: metadata unchanged, fresh target changed, immutable old slice retained, revision short-lived' in log
                    assert 'read-only real schematics: 54 files loaded' in log
            waits = re.search(r'streamed tile wait samples=(\d+) nanosP50=(\d+) nanosP95=(\d+) max=(\d+)', log)
            if waits:
                assert int(waits[1]) == 128
                record['tileWait'] = dict(zip(('samples', 'p50Nanos', 'p95Nanos', 'maxNanos'), map(int, waits.groups())))
        elif row['scenario'] == 'DedicatedScenario':
            assert ('dedicated refill bridge=' + str(bridge).lower()) in log
            assert 'real travel unload/reload and server-authoritative final states passed' in log
        elif row['scenario'] == 'PlanningScenario':
            assert set(re.findall(r'predictor variant=(\d)', log)) == set('01234')
            assert re.search(r'predictor variant=4 .*retryPeak=1', log)
            assert sorted(map(int, re.findall(r'knownCells=(\d+)', log))) == [4096, 5832, 32768]
            captures = re.findall(r'capture halo=(\d+) cells=(\d+) warmup=10 samples=30 captureNanosP50=(\d+) captureNanosP95=(\d+) scanNanosP95=(\d+) allocatedBytesP95=(\d+)', log)
            assert len(captures) == 3
            record['capture'] = [dict(zip(('halo', 'cells', 'p50Nanos', 'p95Nanos', 'scanP95Nanos', 'allocatedP95Bytes'), map(int, values))) for values in captures]
        elif row['scenario'] == 'JarInstallScenario':
            assert 'ordinary artifact mod location=' in log and 'disabled profiles retained zero tasks' in log
            assert 'latticiumaudit' not in result['environment']['mods']
            record['modLocation'] = re.search(r'ordinary artifact mod location=(.*?) disabled profiles', log)[1]
            target = row['target']
            loader = 'neoforge' if 'neoforge' in target else 'fabric'
            main = loader + '-' + target[-4:]
            run = root / ('integrations' if bridge else 'versions') / target / 'build/minedriver/runs/mineDriverCheck' / row['runId'] / 'mods'
            main_name = f'latticium-{main}-{version}.jar'
            assert f"/minedriver/runs/mineDriverCheck/{row['runId']}/mods/{main_name}" in unquote(record['modLocation']).replace('\\', '/')
            record['installedArtifacts'] = []
            for kind, installed in [('versions', main)] + ([('integrations', target)] if bridge else []):
                name = f'latticium-{installed}-{version}.jar'
                built = root / kind / installed / 'build/libs' / name
                digest = hashlib.sha256(built.read_bytes()).hexdigest()
                assert hashlib.sha256((run / name).read_bytes()).hexdigest() == digest, (target, 'installed JAR differs', name)
                record['installedArtifacts'].append(dict(target=installed, sha256=digest))
        records.append(record)

expected = {'fabric-26.2', 'fabric-26.3', 'neoforge-26.2', 'neoforge-26.3',
            'litematica-fabric-26.2', 'litematica-fabric-26.3', 'forgematica-neoforge-26.2'}
for scenario in ('LatticiumScenario', 'PlanningScenario', 'DedicatedScenario', 'JarInstallScenario'):
    actual = {r['target'] for r in records if r['scenario'] == scenario}
    assert actual == expected, (scenario, 'missing', expected - actual)
assert {r['target'] for r in records if 'tileWait' in r} == expected, 'Final scheduler streaming coverage incomplete'
output = args.output
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(json.dumps(dict(records=records, excludedFailures=excluded), indent=2), encoding='utf-8')
print(f'PASS: {len(records)} fresh native reports; required scenario markers checked; {output}')
