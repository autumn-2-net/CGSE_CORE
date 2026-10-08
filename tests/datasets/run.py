"""Verify preserved captures and replay complete AE/JEI catalogs with standalone CGSE."""
from pathlib import Path
import argparse
import collections
import gzip
import hashlib
import json
import os
import random
import shutil
import struct
import subprocess
import sys

BASE = Path(__file__).resolve().parent
ROOT = BASE.parents[1]
PREFIX = 'core-ae-cycle/.local/'
CAMPAIGN = 'mixed-jei-stress-20261007/'


def sha256(path):
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1 << 20), b''):
            value.update(block)
    return value.hexdigest()


def verify(entry, compressed=True):
    path = BASE / entry['path']
    if compressed and (path.stat().st_size != entry['gzip_bytes'] or sha256(path) != entry['gzip_sha256']):
        raise ValueError('Compressed capture mismatch: ' + entry['path'])
    digest = hashlib.sha256()
    size = 0
    with gzip.open(path, 'rb') as stream:
        for block in iter(lambda: stream.read(1 << 20), b''):
            digest.update(block)
            size += len(block)
    if size != entry['bytes'] or digest.hexdigest() != entry['sha256']:
        raise ValueError('Original capture mismatch: ' + entry['source'])


def write_catalog(stream, model):
    def integer(value): stream.write(struct.pack('>i', int(value)))
    def number(value): stream.write(struct.pack('>q', int(value)))
    def boolean(value): stream.write(struct.pack('>?', bool(value)))
    def text(value):
        encoded = str(value).encode('utf-8')
        integer(len(encoded))
        stream.write(encoded)
    def amounts(values):
        integer(len(values))
        for key, value in values.items(): text(key); number(value)
    amounts(model.get('stock', {}))
    external = model.get('external', [])
    integer(len(external))
    for key in external: text(key)
    boolean(model.get('preserve_seeds', True))
    boolean(model.get('force_craft', True))
    integer(model.get('parallelism', 4096))
    number(model.get('max_extra_copies', 64))
    recipes = model['recipes']
    integer(len(recipes))
    for recipe in recipes:
        text(recipe['id']); text(recipe.get('binding', recipe['id']))
        slots = recipe['slots']
        integer(len(slots))
        for index, slot in enumerate(slots):
            text(slot['key']); number(slot['amount']); integer(slot.get('input_slot', index))
            boolean(slot.get('configuration', False)); boolean(slot.get('reusable', False))
        amounts(recipe['outputs'])
    producers = model.get('producers', {})
    integer(len(producers) if 'producers' in model else -1)
    for key, identifiers in producers.items():
        text(key); integer(len(identifiers))
        for identifier in identifiers: text(identifier)


def main():
    sys.stdout.reconfigure(errors='replace')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', default=os.environ.get('JAVA_HOME'))
    parser.add_argument('--verify-only', action='store_true', help='Check every unique gzip and original-byte SHA-256')
    parser.add_argument('--source', default=CAMPAIGN + 'manual.json', help='Original path below core-ae-cycle/.local/')
    parser.add_argument('--extra', choices=['extra.json', 'extra-configured.json', 'extra-physical.json', 'extra-virtual.json'], default='extra-virtual.json')
    parser.add_argument('--modes', default='manual,random', help='Comma-separated manual/manual-first/extra-first/random')
    parser.add_argument('--target', action='append', help='Exact key or unique resource-name substring; repeatable')
    parser.add_argument('--random-targets', type=int, default=0)
    parser.add_argument('--amounts', default='1,1000')
    parser.add_argument('--seed', type=int, default=73)
    parser.add_argument('--milliseconds', type=int, default=3000)
    parser.add_argument('--work', type=int, default=20_000_000)
    parser.add_argument('--memory-mib', type=int, default=256)
    parser.add_argument('--heap', default='3g')
    parser.add_argument('--timeout', type=int, default=300, help='Process wall limit in seconds')
    parser.add_argument('--require-feasible', action='store_true', help='Require SAT instead of preserving UNKNOWN expected truth')
    parser.add_argument('--no-compile', action='store_true')
    parser.add_argument('--output', type=Path, default=ROOT / 'build/datasets')
    args = parser.parse_args()
    manifest = json.loads((BASE / 'index.json').read_text(encoding='utf-8'))
    entries = {entry['source']: entry for entry in manifest['files']}
    if args.verify_only:
        unique = {entry['sha256']: entry for entry in entries.values()}
        for index, entry in enumerate(unique.values(), 1):
            verify(entry)
            if index % 20 == 0: print(f'Verified {index}/{len(unique)} unique captures', flush=True)
        scenarios = json.loads((BASE / 'scenarios.json').read_text(encoding='utf-8'))
        for descriptor in scenarios['files']:
            entry = entries[PREFIX + descriptor['source']]
            if entry['sha256'] != descriptor['sha256']:
                raise ValueError('Scenario index source mismatch: ' + descriptor['source'])
            if descriptor['global_catalog']:
                entries[PREFIX + descriptor['global_catalog']]
            for evidence in descriptor['evidence']:
                entries[PREFIX + evidence.split(':', 1)[0]]
        for descriptor in scenarios['large_json_audit']:
            entry = entries[PREFIX + descriptor['source']]
            if entry['sha256'] != descriptor['sha256'] or entry['bytes'] != descriptor['bytes']:
                raise ValueError('Large-input audit mismatch: ' + descriptor['source'])
        archived = {path.relative_to(BASE).as_posix() for path in (BASE / 'captures').rglob('*.gz')}
        if archived != {entry['path'] for entry in entries.values()}:
            raise ValueError('Capture directory and source index disagree')
        print(f"Verified {len(unique)} unique captures / {len(entries)} original source paths", flush=True)
        print(f"Verified {len(scenarios['files'])} scenario descriptors and {len(scenarios['large_json_audit'])} complete large-input records", flush=True)
        return
    modes = args.modes.split(',')
    if not modes or any(mode not in ('manual', 'manual-first', 'extra-first', 'random') for mode in modes):
        parser.error('Unknown replay mode')
    amounts = [int(value) for value in args.amounts.split(',')]
    if any(value <= 0 or value > 2**63 - 1 for value in amounts): parser.error('Amounts must fit positive signed long')
    if args.random_targets < 0 or args.work <= 0 or args.milliseconds < 0 or args.memory_mib <= 0:
        parser.error('Invalid request budget or random-target count')
    def read(source):
        entry = entries[PREFIX + source]
        verify(entry)
        with gzip.open(BASE / entry['path'], 'rt', encoding='utf-8') as stream:
            return json.load(stream), entry
    manual, manual_entry = read(args.source)
    extra, extra_entry = read(CAMPAIGN + args.extra)
    if 'recipes' not in manual or any('slots' not in recipe for recipe in manual['recipes']):
        parser.error('--source must select an executable captured catalog with recipes/slots; raw exports remain losslessly archived')
    available = sorted(manual.get('targets', set().union(*(recipe['outputs'] for recipe in manual['recipes']))))
    selectors = args.target
    if not selectors and args.random_targets == 0:
        selectors = [manual['target']] if 'miracle-cycle' in args.source else ['minecraft:iron_ingot', 'gtlcore:miracle_crystal']
    targets = []
    for selector in selectors or []:
        matches = [selector] if selector in available else [key for key in available if 'id:"' + selector + '"' in key]
        if len(matches) != 1:
            parser.error(f'Target selector must resolve to one exact key: {selector!r}; matched {len(matches)}')
        if matches[0] not in targets: targets.append(matches[0])
    remaining = [target for target in available if target not in targets]
    targets.extend(random.Random(args.seed).sample(remaining, min(args.random_targets, len(remaining))))
    requests = [{'target': target, 'amount': amount, 'expected': 'SAT' if args.require_feasible else 'UNKNOWN'}
                for target in targets for amount in amounts]
    if not requests: parser.error('No requests selected')
    output = args.output.resolve()
    classes = output / 'classes'
    classes.mkdir(parents=True, exist_ok=True)
    transfer = output / 'catalog.bin'
    with transfer.open('wb') as stream:
        def integer(value): stream.write(struct.pack('>i', value))
        def number(value): stream.write(struct.pack('>q', value))
        def text(value):
            data = value.encode('utf-8'); integer(len(data)); stream.write(data)
        text('CGSE_DATASET_V2')
        write_catalog(stream, manual); write_catalog(stream, extra)
        for value in (args.milliseconds, args.work, args.memory_mib << 20, args.seed): number(value)
        integer(len(modes))
        for mode in modes: text(mode)
        integer(len(requests))
        for request in requests: text(request['target']); number(request['amount']); text(request['expected'])
    stats = lambda model: {'recipes': len(model['recipes']), 'stock_keys': len(model.get('stock', {})),
                          'slots': sum(len(recipe['slots']) for recipe in model['recipes']),
                          'configuration_slots': sum(bool(slot.get('configuration')) for recipe in model['recipes'] for slot in recipe['slots']),
                          'reusable_slots': sum(bool(slot.get('reusable')) for recipe in model['recipes'] for slot in recipe['slots'])}
    run = {'schema': 'cgse-complete-dataset-replay-v1', 'sources': [manual_entry, extra_entry],
           'manual': stats(manual), 'extra': stats(extra), 'modes': modes, 'seed': args.seed, 'requests': requests,
           'preserve_seeds': manual.get('preserve_seeds', True), 'force_craft': manual.get('force_craft', True),
           'budget': {'milliseconds': args.milliseconds, 'work': args.work, 'memory_bytes': args.memory_mib << 20},
           'transfer_sha256': sha256(transfer)}
    (output / 'run.json').write_text(json.dumps(run, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    def java(name):
        if args.java_home:
            candidate = Path(args.java_home) / 'bin' / (name + ('.exe' if os.name == 'nt' else ''))
            if candidate.is_file(): return str(candidate)
        candidate = shutil.which(name)
        if not candidate: parser.error('Cannot find ' + name + '; specify --java-home')
        return candidate
    if not args.no_compile:
        sources = sorted((ROOT / 'src/main/java').rglob('*.java')) + sorted((BASE / 'java').rglob('*.java'))
        source_list = output / 'sources.args'
        source_list.write_text('\n'.join('"' + path.as_posix() + '"' for path in sources), encoding='utf-8')
        subprocess.run([java('javac'), '--release', '17', '-encoding', 'UTF-8', '-d', str(classes), '@' + str(source_list)], check=True)
        subprocess.run([java('java'), '-ea', '-cp', str(classes), 'org.cgse.core.DatasetTransportTest'], check=True)
    print(json.dumps({'manual': run['manual'], 'extra': run['extra'], 'requests': len(requests), 'modes': modes}), flush=True)
    with (output / 'replay.log').open('w', encoding='utf-8') as log:
        result = subprocess.run([java('java'), '-ea', '-Dfile.encoding=UTF-8', '-Xmx' + args.heap, '-cp', str(classes),
                                 'org.cgse.core.DatasetReplay', str(transfer), str(output / 'results.jsonl')],
                                cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, timeout=args.timeout)
    rows = [json.loads(line) for line in (output / 'results.jsonl').read_text(encoding='utf-8').splitlines()]
    summary = {'requests': len(rows), 'expected_rows': len(modes) * len(requests), 'exit_code': result.returncode,
               'assessments': dict(collections.Counter(row['assessment'] for row in rows)),
               'results': dict(collections.Counter(row['result'] for row in rows)),
               'note': 'UNKNOWN expected truth and INCONCLUSIVE results are retained explicitly, never counted as an infeasibility proof.'}
    (output / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print((output / 'replay.log').read_text(encoding='utf-8', errors='replace'), end='')
    print(json.dumps(summary, indent=2), flush=True)
    if result.returncode != 0 or len(rows) != summary['expected_rows']: raise SystemExit(1)


if __name__ == '__main__':
    main()
