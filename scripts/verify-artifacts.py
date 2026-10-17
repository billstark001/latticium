import hashlib
import json
from pathlib import Path
import tomllib
from zipfile import ZipFile

root = Path(__file__).resolve().parent.parent
version = next(line.split('=', 1)[1].strip() for line in (root / 'gradle.properties').read_text().splitlines() if line.startswith('mod_version='))
targets = [('versions', name) for name in ('fabric-26.2', 'fabric-26.3', 'neoforge-26.2', 'neoforge-26.3')]
targets += [('integrations', name) for name in ('litematica-fabric-26.2', 'litematica-fabric-26.3', 'forgematica-neoforge-26.2')]
results = []
for kind, name in targets:
    path = root / kind / name / 'build' / 'libs' / f'latticium-{name}-{version}.jar'
    with ZipFile(path) as jar:
        names = set(jar.namelist())
        leaked = [entry for entry in names if entry.endswith('.class') and (
            entry.startswith('io/github/billstark001/minedriver/')
            or entry.startswith('latticiumaudit/')
            or any(probe in entry for probe in ('/NativeAccess', '/NativePlanningProbe', '/NativeRevisionProbe', '/NativePerformanceProbe'))
            or ('/' not in entry and entry != 'module-info.class'))]
        assert not leaked, (name, 'probe/driver classes leaked', leaked)
        if 'neoforge' in name:
            metadata = tomllib.loads(jar.read('META-INF/neoforge.mods.toml').decode())
            assert metadata['mods'][0]['version'] == version
        else:
            metadata = json.loads(jar.read('fabric.mod.json'))
            assert metadata['version'] == version
        if kind == 'versions':
            prefix = 'io/github/billstark001/latticium/'
            for entry in ('planning/Revisions.class', 'planning/Execution.class', 'planning/ActionTransitions.class',
                          'mc/ClientProof.class', 'mc/ClientRevisions.class', 'mc/ui/ProfileExecutionScreen.class',
                          'planning/ServerUpdates.class', 'planning/ActionConfirmation.class', 'planning/AdaptivePlanner.class',
                          'mc/ClientCandidates.class', 'mc/ClientPlanning.class', 'mc/ui/LatticiumScreen.class',
                          'mc/mixin/ClientPacketListenerMixin.class', 'mc/mixin/LevelRevisionMixin.class'):
                assert prefix + entry in names, (name, entry)
            for language in ('en_us', 'zh_cn', 'zh_tw', 'ja_jp'):
                data = json.loads(jar.read(f'assets/latticium/lang/{language}.json'))
                assert 'latticium.screen.title' in data and 'latticium.registry_reload' in data
            assert any('cloth-config-' in entry and entry.endswith('.jar') for entry in names)
            for catalog in ('revision-events.json', 'action-transitions.json', 'prediction-reads.json'):
                assert f'latticium/{catalog}' in names, (name, catalog)
            assert 'LevelRevisionMixin' in json.loads(jar.read('latticium.mixins.json'))['client']
        if name == 'forgematica-neoforge-26.2':
            config = 'latticium-forgematica.mixins.json'
            assert any(item['config'] == config for item in metadata['mixins'])
            mixin = json.loads(jar.read(config))
            assert 'InitializationHandlerMixin' in mixin['client']
            assert 'io/github/billstark001/latticium/bridge/mixin/InitializationHandlerMixin.class' in names
        results.append(dict(target=name, jar=str(path), size=path.stat().st_size,
                            sha256=hashlib.sha256(path.read_bytes()).hexdigest(), status='PASS'))
output = root / 'build' / 'artifact-verification.json'
output.write_text(json.dumps(results, indent=2), encoding='utf-8')
print(f'PASS: {len(results)} exact-version JARs; core/native classes, metadata, languages, Cloth, Forge mixin and probe exclusion verified. {output}')
