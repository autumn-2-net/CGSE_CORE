"""Copy portable CGSE sources to a GTL checkout, with conflict detection and rollback.

Default is a read-only comparison. --apply is explicit. The previous source
baseline comes from the CGSE-Commit trailer in host history. GTL adapter files
are not owned here. No bookkeeping files are created in the host checkout.
"""
from pathlib import Path
import argparse
import hashlib
import json
import os
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = 'tools/gtl-sync.json'
TRAILER = 'CGSE-Commit'


def canonical(value):
    return value.replace(b'\r\n', b'\n')


def digest(value):
    return hashlib.sha256(canonical(value)).hexdigest()


def safe(root, relative):
    path = (root / relative).resolve()
    if path == root or not path.is_relative_to(root):
        raise ValueError('Path escapes project: ' + str(relative))
    return path


def git(root, *arguments, data=None):
    result = subprocess.run(['git', '-C', str(root), *arguments], input=data, capture_output=True)
    if result.returncode:
        raise ValueError('Git operation failed: ' + result.stderr.decode('utf8', errors='replace').strip())
    return result.stdout


def revision(value):
    if not re.fullmatch(r'[0-9a-fA-F]{40}|[0-9a-fA-F]{64}', value):
        raise ValueError('CGSE revision must be a full commit hash: ' + value)
    actual = git(ROOT, 'rev-parse', '--verify', value + '^{commit}').decode().strip()
    if actual != value.lower():
        raise ValueError('CGSE revision must identify a commit, not a tag object')
    return actual


def host_revision(target):
    # Follow this branch's synchronization history rather than a marker inherited
    # through an unrelated merged branch. --from-revision handles deliberate imports.
    output = git(target, 'log', '--first-parent', '--format=%(trailers:key=CGSE-Commit,valueonly)')
    values = output.decode('utf8').split()
    return revision(values[0]) if values else None


def mapped_path(mapping, source):
    relative = Path(source).relative_to(mapping['source'])
    return (Path(mapping['target']) / relative).as_posix()


def entry(source, value):
    return dict(source=source, sha256=digest(value))


def snapshot(commit):
    """Reconstruct the previous source baseline from immutable Git objects."""
    commit = revision(commit)
    manifest = json.loads(git(ROOT, 'show', commit + ':' + MANIFEST))
    roots = [mapping['source'] for mapping in manifest['roots']]
    objects = {}
    for record in git(ROOT, 'ls-tree', '-r', '-z', commit, '--', *roots).split(b'\0'):
        if not record:
            continue
        metadata, path = record.split(b'\t', 1)
        mode, kind, oid = metadata.split()
        if mode not in (b'100644', b'100755') or kind != b'blob':
            raise ValueError('Managed source must be a regular file: ' + path.decode('utf8'))
        name = path.decode('utf8')
        safe(ROOT, name)
        objects[name] = oid
    names = sorted(objects)
    blobs = git(ROOT, 'cat-file', '--batch', data=b''.join(objects[name] + b'\n' for name in names))
    values, offset = {}, 0
    for name in names:
        end = blobs.index(b'\n', offset)
        oid, kind, size = blobs[offset:end].split()
        if oid != objects[name] or kind != b'blob':
            raise ValueError('Unexpected source object: ' + name)
        offset = end + 1
        length = int(size)
        values[name] = blobs[offset:offset + length]
        offset += length
        if blobs[offset:offset + 1] != b'\n':
            raise ValueError('Truncated source object: ' + name)
        offset += 1
    entries = {}
    for mapping in manifest['roots']:
        prefix = mapping['source'].rstrip('/') + '/'
        for source, value in values.items():
            if not source.startswith(prefix):
                continue
            rel = mapped_path(mapping, source)
            safe(ROOT, rel)
            if rel in entries:
                raise ValueError('Duplicate managed target: ' + rel)
            entries[rel] = entry(source, value)
    return {'files': entries}


def plan(target, manifest, state):
    updates, problems, entries = {}, [], {}
    old_files = state.get('files', {})
    for mapping in manifest['roots']:
        source_root = safe(ROOT, mapping['source'])
        for source in sorted(source_root.rglob('*')):
            if not source.is_file():
                continue
            safe(ROOT, source.relative_to(ROOT))
            rel = (Path(mapping['target']) / source.relative_to(source_root)).as_posix()
            if rel in entries:
                raise ValueError('Duplicate managed target: ' + rel)
            path = safe(target, rel)
            updated = canonical(source.read_bytes())
            current = canonical(path.read_bytes()) if path.is_file() else None
            previous = old_files.get(rel)
            entries[rel] = entry(source.relative_to(ROOT).as_posix(), updated)
            if current == updated:
                continue
            try:
                if current is None:
                    if previous:
                        raise ValueError('Managed source was deleted locally')
                    updates[rel] = updated
                elif previous:
                    if digest(current) == previous['sha256']:
                        updates[rel] = updated
                    else:
                        raise ValueError('Managed source has local changes')
                elif digest(current) == manifest['bootstrap'].get(rel):
                    updates[rel] = updated
                else:
                    raise ValueError('Unrecognized host contents; refusing first-install overwrite')
            except ValueError as error:
                problems.append(rel + ': ' + str(error))
    retired = dict(manifest['retired'])
    for rel, previous in old_files.items():
        if rel not in entries:
            retired[rel] = previous['sha256']
    for rel, expected in retired.items():
        if rel in entries:
            continue
        path = safe(target, rel)
        if path.is_file():
            if digest(path.read_bytes()) == expected:
                updates[rel] = None
            else:
                problems.append(rel + ': modified obsolete source; refusing removal')
    return updates, problems, entries


def apply(target, updates):
    autocrlf = False
    if (target / '.git').exists():
        config = subprocess.run(['git', '-C', str(target), 'config', '--get', 'core.autocrlf'], capture_output=True, text=True)
        autocrlf = config.stdout.strip().lower() == 'true'
    # Every path/conflict is checked before any file changes. Roll back all writes
    # if an I/O error occurs, including deletions.
    before = {}
    try:
        for rel, value in updates.items():
            path = safe(target, rel)
            before[rel] = path.read_bytes() if path.exists() else None
            if value is None:
                path.unlink()
            else:
                if autocrlf or b'\r\n' in (before[rel] or b''):
                    value = canonical(value).replace(b'\n', b'\r\n')
                path.parent.mkdir(parents=True, exist_ok=True)
                fd, temporary = tempfile.mkstemp(prefix='.cgse-', dir=path.parent)
                try:
                    with os.fdopen(fd, 'wb') as output:
                        output.write(value)
                    os.replace(temporary, path)
                finally:
                    if os.path.exists(temporary):
                        os.unlink(temporary)
    except BaseException:
        for rel, value in reversed(list(before.items())):
            path = safe(target, rel)
            if value is None:
                path.unlink(missing_ok=True)
            else:
                path.write_bytes(value)
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--target', required=True, type=Path)
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('--from-revision', help='Previous CGSE full commit hash; otherwise read the host CGSE-Commit trailer')
    args = parser.parse_args()
    target = args.target.resolve()
    if not (target / 'build.gradle').is_file() or not (target / 'src/main/java').is_dir():
        parser.error('Target must be an existing GTL project')
    manifest = json.loads((ROOT / MANIFEST).read_text('utf8'))
    current_revision = git(ROOT, 'rev-parse', '--verify', 'HEAD').decode().strip()
    baseline = revision(args.from_revision) if args.from_revision else host_revision(target)
    state = snapshot(baseline) if baseline else {}
    if args.apply:
        paths = [MANIFEST, *[mapping['source'] for mapping in manifest['roots']]]
        if git(ROOT, 'status', '--porcelain', '--untracked-files=all', '--', *paths).strip():
            raise ValueError('Commit managed CGSE sources before applying; the commit hash must identify the copied code')
    updates, problems, entries = plan(target, manifest, state)
    for problem in problems:
        print('CONFLICT ' + problem)
    for rel, value in sorted(updates.items()):
        print(('REMOVE ' if value is None else 'COPY ') + rel)
    if problems:
        raise SystemExit(2)
    if args.apply:
        apply(target, updates)
    print(f'{len(entries)} managed files; {len(updates)} changes; ' + ('applied' if args.apply else 'read-only'))
    print('Add this trailer to the GTL synchronization commit:\n\n' + TRAILER + ': ' + current_revision)
    if updates and not args.apply:
        raise SystemExit(1)


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError) as error:
        raise SystemExit(str(error))
