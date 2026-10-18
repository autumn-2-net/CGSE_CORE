"""Validate the published library and optional Forge mod; prepare an exact asset list."""
from pathlib import Path
import argparse
import hashlib
import json
import re
import shutil
import tomllib
import zipfile


def manifest(archive):
    text = archive.read('META-INF/MANIFEST.MF').decode('utf-8').replace('\r\n', '\n')
    return dict(line.split(': ', 1) for line in text.replace('\n ', '').splitlines() if ': ' in line)


def verify(directory, version, compatibility):
    if not re.fullmatch(r'(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)', version):
        raise ValueError('Expected a stable three-part version')
    if not version.startswith(compatibility + '.'):
        raise ValueError('Version outside the configured compatibility line')
    major, minor = map(int, compatibility.split('.'))
    upper = f'{major}.{minor + 1}.0'
    library = directory / f'cgse-core-{version}.jar'
    mod = directory / f'cgse-{version}-forge-1.20.1.jar'
    sources = directory / f'cgse-core-{version}-sources.jar'
    with zipfile.ZipFile(library) as lib, zipfile.ZipFile(mod) as wrapper, zipfile.ZipFile(sources) as src:
        assert lib.testzip() is None and wrapper.testzip() is None and src.testzip() is None
        assert 'org/cgse/core/GraphJobRuntime.class' in lib.namelist()
        assert 'org/cgse/core/EngineInfo.class' in lib.namelist()
        assert 'org/cgse/core/GraphJobRuntime.java' in src.namelist()
        assert manifest(lib)['Implementation-Version'] == version
        assert manifest(lib)['FMLModType'] == 'GAMELIBRARY'
        assert manifest(lib)['Automatic-Module-Name'] == 'org.cgse.engine'
        assert 'META-INF/mods.toml' not in lib.namelist()
        assert all(int.from_bytes(lib.read(name)[6:8], 'big') == 61
                   for name in lib.namelist() if name.endswith('.class')), 'Engine must target Java 17'
        properties = dict(line.split('=', 1) for line in lib.read('META-INF/cgse/engine.properties').decode().splitlines())
        assert properties['version'] == version and properties['compatibility'] == compatibility
        required_diagnostics = {
            'org/cgse/forge/CgseMod.class',
            'org/gtlcore/aedump/AeDump.class',
            'org/gtlcore/aedump/DumpManager.class',
            'org/gtlcore/aedump/DumpArchive.class',
            'org/gtlcore/aedump/DumpMixinPlugin.class',
            'org/gtlcore/aedump/HostAccess.class',
        }
        assert required_diagnostics <= set(wrapper.namelist()), 'Forge mod is missing integrated AE dump'
        assert 'org/gtlcore/aedump/AeDumpMod.class' not in wrapper.namelist(), 'Do not ship the standalone AE dump entry point'
        assert not any(name.startswith('org/cgse/core/') for name in wrapper.namelist()), 'Engine classes belong in the selectable library'
        assert all(name.startswith(('org/gtlcore/aedump/', 'org/cgse/forge/'))
                   for name in wrapper.namelist() if name.endswith('.class')), 'Unexpected bundled game/host class'
        assert manifest(wrapper)['MixinConfigs'] == 'aedump.mixins.json'
        mixins = json.loads(wrapper.read('aedump.mixins.json'))
        assert mixins['required'] and mixins['injectors']['defaultRequire'] == 1
        assert mixins['plugin'] == 'org.gtlcore.aedump.DumpMixinPlugin'
        assert set(mixins['mixins']) == {'RouterMixin', 'LegacyMixin', 'RequestWorkMixin', 'BudgetMixin', 'PlanningWorkMixin'}
        for name in mixins['mixins']:
            assert mixins['package'].replace('.', '/') + '/' + name + '.class' in wrapper.namelist()
        assert not any(name.startswith(('org/gtlcore/aedump/', 'org/cgse/forge/')) for name in lib.namelist())
        config = tomllib.loads(wrapper.read('META-INF/mods.toml').decode('utf-8'))
        assert config['modLoader'] == 'javafml'
        assert len(config['mods']) == 1 and config['mods'][0]['modId'] == 'cgse' and config['mods'][0]['version'] == version
        entries = json.loads(wrapper.read('META-INF/jarjar/metadata.json'))['jars']
        assert len(entries) == 1
        entry = entries[0]
        assert entry['identifier'] == {'group': 'org.cgse', 'artifact': 'cgse-engine'}
        assert entry['version'] == {'artifactVersion': version, 'range': f'[{version},{upper})'}
        assert wrapper.read(entry['path']) == library.read_bytes()
        for archive in (lib, wrapper, src):
            assert 'META-INF/LICENSE' in archive.namelist() and 'META-INF/NOTICE' in archive.namelist()
            assert not any(name.startswith(('.local/', 'tests/', 'logs/', 'evc/', 'aexdump/')) for name in archive.namelist())
    return [mod, library, sources]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--version', required=True)
    parser.add_argument('--libs', type=Path, default=Path('build/libs'))
    parser.add_argument('--output', type=Path, default=Path('build/release'))
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    config = dict(line.split('=', 1) for line in (root / 'gradle/cgse.properties').read_text().splitlines()
                  if line and not line.startswith('#'))
    assets = verify(args.libs, args.version, config['compatibility'])
    args.output.mkdir(parents=True, exist_ok=False)
    sums = []
    for asset in assets:
        shutil.copy2(asset, args.output / asset.name)
        sums.append(hashlib.sha256(asset.read_bytes()).hexdigest() + '  ' + asset.name)
    (args.output / 'SHA256SUMS.txt').write_text('\n'.join(sums) + '\n', encoding='utf-8')
    print(f'PASS CGSE {args.version}: library, Forge mod with AE dump, sources and checksums')


if __name__ == '__main__':
    main()
