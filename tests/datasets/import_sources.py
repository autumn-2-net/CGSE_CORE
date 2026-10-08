"""Import complete source captures as deterministic, content-deduplicated gzip files."""
from pathlib import Path
import argparse
import gzip
import hashlib
import json
import shutil
import os
from scenario_sources import classify, provenance

BASE = Path(__file__).resolve().parent
CAMPAIGNS = ('mixed-jei-stress-20261007', 'miracle-cycle-20261002',
             'linear-search-20261002', 'pruned-search-20261002', 'quantity-regression-20261002',
             'fluid-tail-20261001', 'green-cell-20261002')


def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1 << 20), b''):
            value.update(block)
    return value.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=Path, help='Original core-ae-cycle checkout; read only')
    parser.add_argument('--inventory', type=Path, help='Optional prior migration inventory; filesystem discovery still checks all campaigns')
    args = parser.parse_args()
    source = args.source.resolve()
    entries, content = [], {}
    files = set()
    for campaign in CAMPAIGNS:
        area = source / '.local' / campaign
        files.update(p for p in area.iterdir() if p.is_file() and p.suffix in ('.json', '.jsonl', '.py', '.java'))
        for subtree in ('server/local', 'configured-repro', 'cases'):
            files.update(p for p in (area / subtree).rglob('*') if p.is_file() and p.suffix in ('.json', '.jsonl'))
        # Keep the interpretation used to derive the executable catalogs alongside the raw exports.
        files.update(p for name in ('convert.py', 'prepare.py', 'prepare_processing.py', 'prepare_full.py', 'normalize_snapshots.py')
                     if (p := area / name).is_file())
    # Discover catalog-reference scenarios independently of file size and campaign name.
    if args.inventory:
        inventory = json.loads(args.inventory.read_text(encoding='utf-8'))
        candidates = {source / e['source'] for e in inventory['entries'] + inventory['omissions']
                      if e['source'].startswith('.local/') and e['source'].endswith('.json')}
        candidates.update((source / '.local').glob('*.json'))
    else:
        candidates = set()
    for parent, directories, names in os.walk(source / '.local'):
        directories[:] = [d for d in directories if d not in ('.git', 'libraries', 'node_modules', 'assets', 'natives')
                          and not d.endswith('classes')]
        candidates.update(Path(parent) / name for name in names if name.endswith('.json'))
    scenarios, scanned, invalid, large_files = [], {}, [], []
    for path in sorted(candidates):
        if not path.is_file():
            continue
        sha = digest(path)
        size = path.stat().st_size
        profiling = False
        if size > 32 << 20:
            files.add(path)
            with path.open('rb') as stream:
                prefix = stream.read(8192)
            decoded = prefix.decode('utf-16' if prefix.startswith((b'\xff\xfe', b'\xfe\xff')) else 'utf-8-sig', errors='replace')
            profiling = '"recording"' in decoded and 'jdk.ExecutionSample' in decoded
            large_files.append({'source': path.relative_to(source / '.local').as_posix(), 'sha256': sha, 'bytes': size,
                                'role': 'JDK profiling samples (raw, not request cases)' if profiling else 'catalog, network capture or scenario input',
                                'preserved': True})
        if sha not in scanned:
            try:
                scanned[sha] = None if profiling else classify(json.loads(path.read_bytes()))
            except (UnicodeError, json.JSONDecodeError) as error:
                scanned[sha] = None
                invalid.append({'source': path.relative_to(source).as_posix(), 'error': str(error)})
        if scanned[sha]:
            files.add(path)
            name = path.relative_to(source / '.local').as_posix()
            scenarios.append(dict(scanned[sha], source=name, sha256=sha, **provenance(name, scanned[sha])))
    # Keep the actual drivers/generators proving the interpretation of each discovered campaign.
    for path in list(files):
        if path.suffix == '.json':
            files.update(p for p in path.parent.iterdir() if p.is_file() and p.suffix in ('.py', '.java'))
    old_index = BASE / 'index.json'
    if old_index.exists():
        for entry in json.loads(old_index.read_text(encoding='utf-8'))['files']:
            content[entry['sha256']] = {key: entry[key] for key in ('path', 'gzip_sha256', 'gzip_bytes')}
            original = source / entry['source'].removeprefix('core-ae-cycle/')
            if original.is_file():
                files.add(original)
    # Keep previously imported report/provenance objects even if classification becomes more precise.
    for capture in (BASE / 'captures').rglob('*.gz'):
        original = source / '.local' / capture.relative_to(BASE / 'captures').as_posix().removesuffix('.gz')
        if original.is_file():
            files.add(original)
    for path in sorted(files, key=lambda path: (len(path.relative_to(source / '.local').parts), path.as_posix())):
        sha = digest(path)
        entry = {'source': 'core-ae-cycle/' + path.relative_to(source).as_posix(),
                 'sha256': sha, 'bytes': path.stat().st_size}
        if sha in content:
            entry.update(content[sha])
        else:
            relative = Path('captures') / path.relative_to(source / '.local')
            destination = BASE / (relative.as_posix() + '.gz')
            destination.parent.mkdir(parents=True, exist_ok=True)
            with path.open('rb') as src, destination.open('wb') as raw:
                with gzip.GzipFile(filename='', mode='wb', fileobj=raw, compresslevel=9, mtime=0) as compressed:
                    shutil.copyfileobj(src, compressed, 1 << 20)
            values = {'path': destination.relative_to(BASE).as_posix(), 'gzip_sha256': digest(destination),
                      'gzip_bytes': destination.stat().st_size}
            entry.update(values)
            content[sha] = values
        entries.append(entry)
        if len(entries) % 50 == 0:
            print(f"Imported {len(entries)} source paths", flush=True)
    content = {entry['sha256']: {key: entry[key] for key in ('path', 'gzip_sha256', 'gzip_bytes')} for entry in entries}
    manifest = {'schema': 'cgse-complete-datasets-v1', 'compression': {'format': 'gzip', 'mtime': 0, 'level': 9, 'filename': ''},
                'selection': 'Complete root JSON/JSONL and source scripts for seven capture campaigns; server/local, configured-repro and cases JSON/JSONL; every discovered external/embedded catalog-reference scenario and its neighboring drivers/generators; all JSON files above 32 MiB including raw profiling captures.',
                'source_files': len(entries), 'unique_files': len(content),
                'original_bytes': sum(e['bytes'] for e in entries),
                'unique_original_bytes': sum(next(e['bytes'] for e in entries if e['sha256'] == sha) for sha in content),
                'gzip_bytes': sum(e['gzip_bytes'] for e in content.values()), 'files': entries}
    (BASE / 'index.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    scenario_index = {'schema': 'cgse-catalog-reference-scenarios-v1', 'json_paths_scanned': len(candidates),
                      'unique_json_contents_scanned': len(scanned), 'invalid_json': invalid,
                      'large_json_audit': large_files,
                      'source_files': len(scenarios), 'groups': sum(s['groups'] for s in scenarios),
                      'requests': sum(s['requests'] for s in scenarios), 'files': scenarios}
    (BASE / 'scenarios.json').write_text(json.dumps(scenario_index, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({k: v for k, v in manifest.items() if k != 'files'}, ensure_ascii=False, indent=2), flush=True)


if __name__ == '__main__':
    main()
