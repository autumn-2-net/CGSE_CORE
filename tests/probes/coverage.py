"""Inventory historical execution gaps without treating compilation as a test run.

Example: python tests/probes/coverage.py --results build/tests --output build/coverage
Only explicitly supplied result directories are read. Historical input replays are
related coverage, never evidence that an obsolete program's assertions ran.
"""
import argparse
import collections
import csv
import hashlib
import json
from pathlib import Path

TESTS = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--results', action='append', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    probes = json.loads((TESTS/'probes/manifest.json').read_text())['probes']
    migration = {r['id']: r for r in json.loads((TESTS/'provenance/migrated-probes.json').read_text())['entries']}
    baseline = {r['id']: r for r in json.loads((TESTS/'provenance/historical-coverage.json').read_text())['programs']}
    observations = collections.defaultdict(list)
    evidence = {}
    for directory in args.results:
        paths = [directory] if directory.is_file() else sorted(directory.rglob('results.json'))
        for path in paths:
            raw = path.read_bytes()
            data = json.loads(raw)
            evidence[str(path.resolve())] = hashlib.sha256(raw).hexdigest()
            for result in data.get('results', []):
                if 'name' not in result: continue
                log = path.parent / result.get('log', 'results.json')
                observations[result['name']].append({**result, 'evidence': str(path.resolve()),
                    'observed_mtime_ns': log.stat().st_mtime_ns if log.is_file() else path.stat().st_mtime_ns})
    rows = []
    for probe in probes:
        original = (TESTS/probe['path']).read_bytes().replace(b'\r\n',b'\n')
        replacement = migration.get(probe['id'])
        if replacement and hashlib.sha256(original).hexdigest() != replacement['original_sha256_lf']:
            raise ValueError('Archived source changed: '+probe['id'])
        if replacement and replacement['source_artifact_sha256'] != probe['sha256']:
            raise ValueError('Source-artifact provenance differs: '+probe['id'])
        observed = list(observations[probe['id']])
        if replacement and probe['main']:
            for result in observations[replacement['class'].rsplit('.',1)[-1]]:
                observed.append({**result, 'migration': replacement['replacement']})
        latest = max(observed, key=lambda r:r['observed_mtime_ns']) if observed else None
        if latest:
            if latest.get('mode') in ('compile-only','helper-compile'):
                status = 'COMPILED_ONLY' if latest['status']=='PASS' else 'COMPILE_FAILED'
            else:
                status = ('MIGRATED_' if latest.get('migration') else 'ORIGINAL_')+latest['status']
        elif not probe['main']:
            status = 'HELPER_NOT_EXECUTABLE'
        elif probe.get('tier') == 'parameterized':
            status = 'NOT_RUN_ARGUMENTS_OR_CAMPAIGN_REQUIRED'
        elif probe.get('tier') == 'historical':
            status = 'NOT_RUN_HISTORICAL_PROGRAM'
        elif probe['external_imports']:
            status = 'NOT_RUN_EXTERNAL_DEPENDENCY'
        else:
            status = 'NOT_RUN_STANDALONE'
        old = baseline.get(probe['id'], {})
        rows.append({'id':probe['id'], 'path':probe['path'], 'tier':probe.get('tier'),
            'main':probe['main'], 'prior_compile':old.get('compile','COMPILED' if probe.get('baseline_compiled') else 'NOT_RECORDED'),
            'prior_execution':old.get('execution','NOT_RECORDED'), 'prior_role':old.get('role'),
            'status':status, 'replacement':replacement['replacement'] if replacement else None,
            'migration_contract':replacement.get('migration') if replacement else None,
            'coverage_note':probe.get('coverage_note'), 'related_entries':probe.get('current_entrypoints',[]),
            'current_evidence':latest, 'observations':observed})
    # Some host-only probes live outside the portable manifest. Keep them
    # visible instead of losing those gaps at the inventory join.
    indexed = {r['id'] for r in probes}
    host_only = [r for key,r in baseline.items() if key not in indexed]
    summary = {'total':len(rows),'statuses':dict(collections.Counter(r['status'] for r in rows)),
        'prior_execution':dict(collections.Counter(r['prior_execution'] for r in rows)),
        'policy':'No evidence is NOT_RUN; successful compilation is COMPILED_ONLY. A migrated run covers its documented contract, not arbitrary old internals. UNKNOWN/TIMEOUT are not passes.',
        'evidence_sha256':evidence, 'outside_portable_manifest':host_only, 'programs':rows}
    (args.output/'coverage.json').write_text(json.dumps(summary,indent=2)+'\n',encoding='utf-8')
    fields=['id','tier','main','prior_compile','prior_execution','prior_role','status','replacement','migration_contract','coverage_note']
    with (args.output/'coverage.csv').open('w',encoding='utf-8-sig',newline='') as stream:
        writer=csv.DictWriter(stream,fields,extrasaction='ignore');writer.writeheader();writer.writerows(rows)
    print(json.dumps({k:v for k,v in summary.items() if k not in ('programs','evidence_sha256')},indent=2))


if __name__=='__main__': main()
