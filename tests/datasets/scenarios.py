"""Replay archived stock/request groups with their original embedded or explicitly linked catalogs."""
from pathlib import Path
import argparse
import collections
import gzip
import hashlib
import json
import os
import shutil
import struct
import subprocess
import sys

from run import BASE, ROOT, PREFIX, verify, write_catalog, sha256
from scenario_sources import records, is_scenario


def main():
    sys.stdout.reconfigure(errors='replace')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', default='linear-search-20261002/all-linear.json')
    parser.add_argument('--list', action='store_true', help='List preserved scenario files and catalog associations')
    parser.add_argument('--group', action='append', help='Exact original group/case id; repeatable')
    parser.add_argument('--limit-groups', type=int, default=3, help='First N groups when --group is absent; 0 selects all')
    parser.add_argument('--request-limit', type=int, default=0, help='Optional prefix of each original request sequence; 0 preserves all')
    parser.add_argument('--assert-known-feasible', action='store_true', help='Require SAT for requests <= original known_feasible_max')
    parser.add_argument('--milliseconds', type=int, help='Explicit timeout override; default retains each original budget')
    parser.add_argument('--work', type=int, help='Explicit work override; default retains each original budget')
    parser.add_argument('--memory-mib', type=int, help='Explicit memory override; default retains each original budget')
    parser.add_argument('--java-home', default=os.environ.get('JAVA_HOME'))
    parser.add_argument('--heap', default='3g')
    parser.add_argument('--timeout', type=int, default=600, help='Process wall limit in seconds')
    parser.add_argument('--no-compile', action='store_true')
    parser.add_argument('--output', type=Path, default=ROOT / 'build/dataset-scenarios')
    args = parser.parse_args()
    descriptors = json.loads((BASE / 'scenarios.json').read_text(encoding='utf-8'))['files']
    if args.list:
        for entry in descriptors:
            print(entry['source'], entry['groups'], 'groups', entry['requests'], 'requests;', entry['catalog_policy'])
        return
    descriptor = next((entry for entry in descriptors if entry['source'] == args.source), None)
    if descriptor is None:
        parser.error('Unknown scenario source; use --list')
    if descriptor['has_native_orders']:
        parser.error('This capture specifies live AE provider orders; use its preserved native driver, not a guessed portable order')
    if args.limit_groups < 0 or args.request_limit < 0:
        parser.error('Selection limits must be nonnegative')
    for name in ('work', 'memory_mib'):
        if getattr(args, name) is not None and getattr(args, name) <= 0:
            parser.error(name + ' must be positive')
    if args.milliseconds is not None and args.milliseconds < 0:
        parser.error('milliseconds must be nonnegative')
    index = {entry['source']: entry for entry in json.loads((BASE / 'index.json').read_text(encoding='utf-8'))['files']}
    used = []

    def read(source):
        entry = index[PREFIX + source]
        verify(entry)
        used.append(entry)
        with gzip.open(BASE / entry['path'], 'rt', encoding='utf-8') as stream:
            return json.load(stream)

    original = read(args.source)
    if used[0]['sha256'] != descriptor['sha256']:
        raise ValueError('Scenario descriptor does not match preserved source')
    groups = [(index, group) for index, group in enumerate(records(original, descriptor['container'])) if is_scenario(group)]
    if args.group:
        wanted = set(args.group)
        groups = [(index, group) for index, group in groups if group.get('id') in wanted]
        missing = wanted - {group.get('id') for _, group in groups}
        if missing:
            parser.error('Unknown group ids: ' + repr(sorted(missing)))
    elif args.limit_groups:
        groups = groups[:args.limit_groups]
    if not groups:
        parser.error('No selected groups')
    global_catalog = None
    if any('catalog' not in group for _, group in groups):
        if not descriptor['global_catalog']:
            parser.error('No proven external catalog association; capture is preserved but replay refuses substitution')
        global_catalog = read(descriptor['global_catalog'])
    output = args.output.resolve()
    classes = output / 'classes'
    classes.mkdir(parents=True, exist_ok=True)
    transfer = output / 'scenarios.bin'
    selected = []
    with transfer.open('wb') as stream:
        def integer(value): stream.write(struct.pack('>i', int(value)))
        def number(value): stream.write(struct.pack('>q', int(value)))
        def boolean(value): stream.write(struct.pack('>?', bool(value)))
        def text(value):
            value = str(value).encode('utf-8'); integer(len(value)); stream.write(value)
        text('CGSE_SCENARIOS_V1'); integer(len(groups))
        for ordinal, group in groups:
            # QuantitySweep selects group.catalog as a complete replacement, not a partial merge.
            source_catalog = group.get('catalog', global_catalog)
            if not isinstance(source_catalog, dict) or 'recipes' not in source_catalog:
                parser.error('Missing executable catalog for group ' + str(group.get('id')))
            catalog = dict(source_catalog)
            if 'stock' in group:
                catalog['stock'] = group['stock']
            for key in ('parallelism', 'max_extra_copies'):
                if key in group:
                    catalog[key] = group[key]
            # The original QuantitySweep explicitly passed true,true to GraphPlanningWork.
            catalog['preserve_seeds'] = True
            catalog['force_craft'] = True
            requests = group['requests'] if 'requests' in group else [{'amount': group['amount']}]
            if args.request_limit:
                requests = requests[:args.request_limit]
            identifier = str(group.get('id', ordinal))
            text(identifier); text(group.get('kind', 'captured-network-request')); text(group['target'])
            write_catalog(stream, catalog)
            milliseconds = args.milliseconds if args.milliseconds is not None else int(group.get('timeout', 0))
            work = args.work if args.work is not None else int(group.get('work', 20_000_000))
            memory = args.memory_mib << 20 if args.memory_mib is not None else int(group.get('memory', 128 << 20))
            number(milliseconds); number(work); number(memory); boolean(group.get('fallback', False))
            integer(len(requests))
            maximum = int(group.get('known_feasible_max', 0))
            for request in requests:
                amount = int(request['amount'])
                if not 0 < amount < 2**63:
                    parser.error('Request amount must fit positive signed long')
                expected = 'SAT' if args.assert_known_feasible and amount <= maximum else 'UNKNOWN'
                number(amount); boolean(request.get('cold', False)); text(request.get('phase', 'captured')); text(expected)
            selected.append({'id': identifier, 'source_ordinal': ordinal, 'kind': group.get('kind'),
                             'catalog': 'embedded group.catalog' if 'catalog' in group else descriptor['global_catalog'],
                             'recipes': len(catalog['recipes']), 'stock_keys': len(catalog.get('stock', {})),
                             'catalog_sha256': hashlib.sha256(json.dumps(source_catalog, sort_keys=True, separators=(',', ':')).encode()).hexdigest(),
                             'known_feasible_max': group.get('known_feasible_max'), 'requests': requests,
                             'fallback': group.get('fallback', False),
                             'budget': {'milliseconds': milliseconds, 'work': work, 'memory_bytes': memory}})
    run = {'schema': 'cgse-scenario-replay-v1', 'source_descriptor': descriptor, 'sources': used,
           'selected': selected, 'assert_known_feasible': args.assert_known_feasible, 'transfer_sha256': sha256(transfer),
           'semantics': 'Original catalog replacement, producer/slot order, stock override, catalyst limits, budgets, request sequence, warm/cold compilers and conditional fallback. Unknown truth remains UNKNOWN.'}
    (output / 'run.json').write_text(json.dumps(run, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

    def java(name):
        if args.java_home:
            executable = Path(args.java_home) / 'bin' / (name + ('.exe' if os.name == 'nt' else ''))
            if executable.is_file(): return str(executable)
        executable = shutil.which(name)
        if not executable: parser.error('Cannot find ' + name + '; specify --java-home')
        return executable

    if not args.no_compile:
        sources = sorted((ROOT / 'src/main/java').rglob('*.java')) + sorted((BASE / 'java').rglob('*.java'))
        source_list = output / 'sources.args'
        source_list.write_text('\n'.join('"' + path.as_posix() + '"' for path in sources), encoding='utf-8')
        subprocess.run([java('javac'), '--release', '17', '-encoding', 'UTF-8', '-d', str(classes), '@' + str(source_list)], check=True)
        subprocess.run([java('java'), '-ea', '-cp', str(classes), 'org.cgse.core.DatasetTransportTest'], check=True)
    with (output / 'replay.log').open('w', encoding='utf-8') as log:
        result = subprocess.run([java('java'), '-ea', '-Dfile.encoding=UTF-8', '-Xmx' + args.heap, '-cp', str(classes),
                                 'org.cgse.core.ScenarioReplay', str(transfer), str(output / 'results.jsonl')],
                                cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, timeout=args.timeout)
    rows = [json.loads(line) for line in (output / 'results.jsonl').read_text(encoding='utf-8').splitlines()]
    summary = {'groups': len(selected), 'requests': len(rows), 'expected_rows': sum(len(g['requests']) for g in selected),
               'exit_code': result.returncode, 'assessments': dict(collections.Counter(r['assessment'] for r in rows)),
               'results': dict(collections.Counter(r['result'] for r in rows)),
               'fallback_results': dict(collections.Counter(r['fallback']['result'] for r in rows if 'fallback' in r))}
    (output / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n', encoding='utf-8')
    print((output / 'replay.log').read_text(encoding='utf-8', errors='replace'), end='')
    print(json.dumps(summary, indent=2), flush=True)
    if result.returncode or len(rows) != summary['expected_rows']:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
