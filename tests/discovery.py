"""Discover maintained tests without executing archived campaign scripts.

Python test_*.py files are executable tests. Each directory can register its
argument-dependent drivers in suite.json; no edit to the central runner is needed.
Historical source/data inventories remain separate from executable assertions.
"""
from pathlib import Path
import json
import re
import sys


def java_tests(directory):
    result = []
    for source in sorted(Path(directory).rglob('*.java')):
        if not source.is_file():
            continue
        text = source.read_text(encoding='utf-8')
        if not re.search(r'public\s+static\s+void\s+main\s*\(', text):
            continue
        package = re.search(r'^\s*package\s+([\w.]+)\s*;', text, re.MULTILINE)
        result.append((source, (package[1] + '.' if package else '') + source.stem))
    return result


def verify_manifest(base, records, directories, suffix):
    """New archived inputs must be registered, not silently omitted by all.

    Their oracle, scope and required arguments cannot be safely guessed from a
    filename. Reject a stale manifest and report the exact missing paths.
    """
    base = Path(base).resolve()
    declared = set()
    for record in records:
        path = Path(record['path'])
        if path.is_absolute() or '..' in path.parts:
            raise ValueError('Manifest path must stay within tests: ' + str(path))
        declared.add(path.as_posix())
    actual = {p.relative_to(base).as_posix() for directory in directories
              for p in (base / directory).rglob('*' + suffix) if p.is_file()}
    if declared != actual:
        missing = sorted(str(p) for p in declared - actual)
        unregistered = sorted(str(p) for p in actual - declared)
        raise ValueError('Test manifest differs from files: missing=' + repr(missing) + '; unregistered=' + repr(unregistered))


def extra_tests(base, variables):
    base = Path(base).resolve()
    result = []
    for source in sorted(base.rglob('test_*.py')):
        result.append({'name': 'python/' + source.relative_to(base).as_posix(),
                       'source': source.relative_to(base).as_posix(),
                       'command': [sys.executable, '-m', 'unittest', 'discover', '-s', str(source.parent), '-p', source.name],
                       'timeout_multiplier': 1})
    for manifest in sorted(base.rglob('suite.json')):
        data = json.loads(manifest.read_text(encoding='utf-8'))
        if data.get('schema') != 'cgse-test-suite-v1':
            raise ValueError('Unknown suite schema: ' + str(manifest))
        for test in data['tests']:
            identifier = test['id']
            if not re.fullmatch(r'[A-Za-z0-9_.-]+', identifier):
                raise ValueError('Invalid suite test id: ' + identifier)
            name = manifest.parent.relative_to(base).as_posix() + '/' + identifier
            record = {'name': name, 'source': manifest.relative_to(base).as_posix()}
            if 'requires' in test:
                if not isinstance(test['requires'], str) or not test['requires'].strip():
                    raise ValueError('Missing prerequisite explanation: ' + name)
                record.update(status='NOT_RUN', reason=test['requires'])
            else:
                script = (manifest.parent / test['script']).resolve()
                if not script.is_relative_to(base) or not script.is_file():
                    raise ValueError('Missing test driver within tests: ' + str(script))
                local = dict(variables, test_output=str(Path(variables['output']) / 'extra' / name))
                arguments = [arg.format_map(local) for arg in test.get('arguments', [])]
                command = [sys.executable, str(script), *arguments]
                if test.get('java_home') and variables.get('java_home'):
                    command += ['--java-home', variables['java_home']]
                multiple = test.get('timeout_multiplier', 1)
                if type(multiple) is not int or multiple < 1:
                    raise ValueError('Invalid suite timeout multiplier: ' + name)
                record.update(command=command, timeout_multiplier=multiple)
            result.append(record)
    names = [r['name'] for r in result]
    if len(set(names)) != len(names):
        raise ValueError('Duplicate discovered test names')
    return result
