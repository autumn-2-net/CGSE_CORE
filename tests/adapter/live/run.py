"""Build and run migration smoke checks only in an explicit isolated Forge server."""
from pathlib import Path
import argparse
import hashlib
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import time
import zipfile

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
OLD_PACKAGE = 'org.gtlcore.gtlcore.integration.ae2.graph.core'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def ensure_stopped(server, port):
    try:
        import psutil
    except ImportError as error:
        raise RuntimeError('Process verification requires Python psutil; no server files were changed') from error
    for process in psutil.process_iter(['pid', 'name']):
        if (process.info['name'] or '').lower() not in ('java', 'java.exe', 'javaw.exe'):
            continue
        try:
            directory = Path(process.cwd()).resolve()
            command = ' '.join(process.cmdline()).replace('\\', '/').lower()
        except psutil.NoSuchProcess:
            continue
        except psutil.AccessDenied as error:
            raise RuntimeError('Cannot verify Java process ' + str(process.pid)) from error
        if directory == server or server.as_posix().lower() in command:
            raise RuntimeError('Selected isolated server is running: PID ' + str(process.pid))
    with socket.socket() as connection:
        connection.settimeout(.5)
        if connection.connect_ex(('127.0.0.1', port)) == 0:
            raise RuntimeError('Selected server port is already listening: ' + str(port))


class Changes:
    def __init__(self, server, output):
        self.server, self.output = server, output
        self.entries = {}

    def checked(self, path):
        path = path.resolve()
        if not path.is_relative_to(self.server):
            raise RuntimeError('Refusing to change a path outside the selected server: ' + str(path))
        return path

    def remember(self, path):
        path = self.checked(path)
        relative = path.relative_to(self.server).as_posix()
        if relative in self.entries:
            return path
        backup = self.output / 'backup' / relative
        if path.exists():
            backup.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(path, backup)
        self.entries[relative] = {'existed': path.exists(), 'sha256': sha(path) if path.exists() else None}
        self.save()
        return path

    def save(self):
        (self.output / 'restore.json').write_text(json.dumps({'server': str(self.server), 'files': self.entries}, indent=2), encoding='utf-8')

    def write(self, path, content):
        path = self.remember(path)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content)

    def remove(self, path):
        path = self.remember(path)
        path.unlink()

    def restore(self):
        for relative, entry in reversed(list(self.entries.items())):
            path = self.checked(self.server / relative)
            if entry['existed']:
                shutil.copy2(self.output / 'backup' / relative, path)
                if sha(path) != entry['sha256']:
                    raise RuntimeError('Restoration hash mismatch: ' + relative)
            elif path.exists():
                path.unlink()


def stages():
    commands = [
        ('forceload add -16 -16 144 48', None, 4),
        ('fill -2 64 -2 5 69 3 minecraft:air', None, 2),
        ('setblock 0 65 0 ae2:creative_energy_cell', None, 1),
        ('setblock 1 65 0 ae2:64k_crafting_storage', None, 1),
        ('setblock 1 65 1 ae2:crafting_accelerator', None, 1),
        ('setblock 1 66 0 ae2:crafting_accelerator', None, 1),
        ('setblock 1 66 1 ae2:crafting_accelerator', None, 1),
        ('setblock 2 65 0 ae2:pattern_provider', None, 1),
        ('setblock 0 65 1 ae2:drive', None, 2),
        ('inventoryreplanprepare', '[Inventory Replan] PREPARED', 3),
        ('inventoryreplanstart', '[Inventory Replan] PASS', 3),
        ('fill 31 63 -2 36 67 3 minecraft:air', None, 2),
        ('graphparallelplace', None, 3),
        ('graphparallelload', '[Graph Parallel] ready seeds=1', 3),
        ('graphparallelstart', '[Graph Parallel] completed templates=', 3),
        ('graphparallelload4', '[Graph Parallel] ready seeds=4', 3),
        ('graphparallelstart', '[Graph Parallel] completed templates=', 3),
        ('aedumpmigrationverify', '[AEDUMP Migration] CAPTURE PASS', 2),
        ('ae dump latest', 'ae-dump-', 3),
        ('aedumpmigrationlimit', '[AEDUMP Migration] AUTO PASS', 3),
        ('ae dump status', None, 2),
    ]
    return commands


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host', type=Path, required=True)
    parser.add_argument('--server', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, required=True, help='JDK for compiling probe; must read the modpack class versions')
    parser.add_argument('--java', type=Path, help='Runtime Java executable; defaults to --java-home/bin/java')
    parser.add_argument('--core-jar', type=Path, required=True)
    parser.add_argument('--aedump-jar', type=Path, required=True)
    parser.add_argument('--forge-args', default='libraries/net/minecraftforge/forge/1.20.1-47.4.16/win_args.txt')
    parser.add_argument('--port', type=int, default=25584)
    parser.add_argument('--label', default=time.strftime('migration-%Y%m%d-%H%M%S'))
    parser.add_argument('--timeout', type=int, default=1000)
    parser.add_argument('--stage-timeout', type=int, default=300)
    parser.add_argument('--work', type=int, default=40_000_000)
    parser.add_argument('--run', action='store_true', help='Install, start checks, stop, and restore jars/config/scripts')
    parser.add_argument('--restore', type=Path, help='Recover only the specified interrupted run restore.json')
    args = parser.parse_args()
    host, server = args.host.resolve(), args.server.resolve()
    if not server.is_relative_to(host / '.local') or not (server / 'mods').is_dir():
        parser.error('Use an explicitly named isolated server under the host checkout .local directory')
    if not re.fullmatch('[A-Za-z0-9_-]+', args.label):
        parser.error('Use an alphanumeric run label')
    ensure_stopped(server, args.port)
    if args.restore:
        manifest = args.restore.resolve()
        content = json.loads(manifest.read_text(encoding='utf-8'))
        if Path(content['server']).resolve() != server or not manifest.is_relative_to(ROOT / 'build/live'):
            parser.error('Restore manifest does not belong to this isolated server/run')
        changes = Changes(server, manifest.parent)
        changes.entries = content['files']
        changes.restore()
        print('Restored jars/config/scripts from ' + str(manifest))
        return
    output = ROOT / 'build/live' / args.label
    output.mkdir(parents=True, exist_ok=False)
    core, addon = args.core_jar.resolve(), args.aedump_jar.resolve()
    if not core.is_file() or not addon.is_file():
        parser.error('Both remapped production JARs must exist')
    forge_args = (server / args.forge_args).resolve()
    if not forge_args.is_relative_to(server) or not forge_args.is_file():
        parser.error('Forge args must be a file inside the selected server')
    scripts = server / 'kubejs/server_scripts'
    inventory = []
    for script in scripts.glob('*.js'):
        content = script.read_text(encoding='utf-8')
        inventory.append({'name': script.name, 'sha256': sha(script), 'old_package': OLD_PACKAGE in content,
                          'java_classes': sorted(set(re.findall(r"Java.loadClass\(['\"]([^'\"]+)", content)))})
    (output / 'script-inventory.json').write_text(json.dumps(inventory, indent=2), encoding='utf-8')
    probe = output / 'graph-server-probe.jar'
    subprocess.run([sys.executable, str(ROOT / 'tests/adapter/tools/graph-server-probe/build.py'), '--server', str(server),
                    '--java-home', str(args.java_home), '--core-jar', str(core), '--output', str(probe)], check=True)
    print('Probe compiled; server is stopped; audit at ' + str(output), flush=True)
    if not args.run:
        return
    changes = Changes(server, output)
    result = {'passed': False, 'native_checks_passed': False, 'server': str(server), 'port': args.port, 'checks': [],
              'core_sha256': sha(core), 'aedump_sha256': sha(addon), 'probe_sha256': sha(probe)}
    process = None
    log = output / 'server.log'
    before_archives = set((server / 'logs/ae-dump').glob('*.zip'))
    started = time.monotonic()
    try:
        ensure_stopped(server, args.port)
        for pattern in ('gtlcore-*.jar', 'ae-dump*.jar', 'graph-server-probe.jar'):
            for old in (server / 'mods').glob(pattern):
                changes.remove(old)
        for source, name in [(core, 'gtlcore-cgse-migration-test.jar'), (addon, 'ae-dump-cgse-migration-test.jar'), (probe, 'graph-server-probe.jar')]:
            changes.write(server / 'mods' / name, source.read_bytes())
        for script in scripts.glob('*.js'):
            content = script.read_text(encoding='utf-8')
            if OLD_PACKAGE in content:
                changes.write(script, content.replace(OLD_PACKAGE, 'org.cgse.core').encode('utf-8'))
        for source in (HERE / 'scripts').glob('*.js'):
            changes.write(scripts / source.name, source.read_bytes())
        source = ROOT / 'tests/adapter/tools/graph-server-probe/graph_parallel_probe.js'
        changes.write(scripts / source.name, source.read_bytes())
        config = server / 'config/gtlcore.yaml'
        text = config.read_text(encoding='utf-8')
        for key, value in {'ae2CraftingEngine': 'GRAPH', 'ae2GraphDiagnosticLogging': 'true', 'ae2GraphPlannerMaxSteps': args.work}.items():
            text, count = re.subn(r'(?m)^' + key + r':.*$', key + ': ' + str(value), text)
            if count != 1:
                raise RuntimeError('Missing or repeated host configuration: ' + key)
        changes.write(config, text.encode('utf-8'))
        changes.remember(server / 'config/aedump-common.toml')
        properties = server / 'server.properties'
        text = properties.read_text(encoding='utf-8')
        for key, value in [('server-port', str(args.port)), ('server-ip', '127.0.0.1')]:
            text, count = re.subn(r'(?m)^' + key + r'=.*$', key + '=' + value, text)
            if count != 1:
                raise RuntimeError('Missing isolated-server property: ' + key)
        changes.write(properties, text.encode('utf-8'))
        java = args.java or args.java_home / 'bin' / ('java.exe' if os.name == 'nt' else 'java')
        (server / 'tmp').mkdir(exist_ok=True)
        with log.open('w', encoding='utf-8') as stream:
            command = [str(java), '-Xms512M', '-Xmx4G', '-Dfile.encoding=UTF-8', '-Dsun.stdout.encoding=UTF-8',
                       '-Dsun.stderr.encoding=UTF-8', '-Djava.io.tmpdir=' + str(server / 'tmp'), '@' + str(forge_args), 'nogui']
            process = subprocess.Popen(command, cwd=server, stdin=subprocess.PIPE, stdout=stream, stderr=subprocess.STDOUT,
                                       text=True, encoding='utf-8', creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
            result['pid'] = process.pid
            print('Isolated Forge server PID ' + str(process.pid), flush=True)
            sequence, stage, sent, offset, progress = stages(), -1, 0, 0, 0
            failures = ['[Inventory Replan] FAIL', '[Graph Parallel] FAILED', '[Graph Parallel] command FAILED',
                        'Parallel production material balance changed', '[AEDUMP Migration] FAIL',
                        'Diagnostic hook failed', 'Mixin apply failed', 'InjectionError', 'InvalidMixinException']
            while process.poll() is None:
                now = time.monotonic()
                if now - started > args.timeout:
                    raise RuntimeError('Live smoke test timed out')
                content = log.read_text(encoding='utf-8', errors='replace')
                if any(marker in content for marker in failures):
                    raise RuntimeError('Native test/instrumentation failure; inspect ' + str(log))
                if now - progress > 25:
                    print('Live stage=' + str(stage) + ' elapsed=' + str(round(now - started)), flush=True)
                    progress = now
                ready = stage < 0 and 'Done (' in content
                if stage >= 0:
                    _, marker, delay = sequence[stage]
                    ready = now - sent > delay and (marker is None or marker in content[offset:])
                    if not ready and now - sent > args.stage_timeout:
                        raise RuntimeError('Stage timed out: ' + sequence[stage][0])
                if ready:
                    if stage >= 0:
                        result['checks'].append(sequence[stage][0])
                    stage += 1
                    if stage == len(sequence):
                        result['native_checks_passed'] = True
                        break
                    offset, sent = len(content), now
                    process.stdin.write(sequence[stage][0] + '\n')
                    process.stdin.flush()
                    print('Command ' + sequence[stage][0], flush=True)
                time.sleep(.5)
            if not result['native_checks_passed']:
                raise RuntimeError('Server exited without completing migration checks')
    except BaseException as error:
        result['error'] = str(error)
        raise
    finally:
        if process is not None and process.poll() is None:
            try:
                process.stdin.write('save-all flush\nstop\n')
                process.stdin.flush()
                process.wait(timeout=45)
            except (subprocess.TimeoutExpired, BrokenPipeError, OSError):
                process.terminate()
                process.wait(timeout=15)
        result['exit'] = None if process is None else process.returncode
        result['elapsed_seconds'] = round(time.monotonic() - started, 3)
        ensure_stopped(server, args.port)
        changes.restore()
        result['restored'] = True
        archive_output = output / 'archives'
        archive_output.mkdir(exist_ok=True)
        archives = sorted(set((server / 'logs/ae-dump').glob('*.zip')) - before_archives)
        for archive in archives:
            shutil.copy2(archive, archive_output / archive.name)
        (output / 'result.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
        print(json.dumps(result), flush=True)
    try:
        validation = subprocess.run([sys.executable, str(ROOT / 'tests/ae-dump/validate_archives.py'), str(archive_output), '--require-complete'],
                                    check=True, capture_output=True, text=True, encoding='utf-8')
        reports = json.loads(validation.stdout)
        triggers = []
        for archive in archives:
            with zipfile.ZipFile(archive) as contents:
                triggers.append(json.loads(contents.read('result.json'))['trigger'])
        if not {'manual', 'automatic'} <= set(triggers):
            raise RuntimeError('Both manual and automatic native AEDUMP archives are required')
        result['archive_validation_passed'] = True
        result['passed'] = True
        result['archives'] = reports
        result['archive_triggers'] = triggers
    except BaseException as error:
        result['passed'] = False
        result['archive_validation_passed'] = False
        result['error'] = str(error)
        raise
    finally:
        (output / 'result.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
        print(json.dumps(result), flush=True)


if __name__ == '__main__':
    main()
