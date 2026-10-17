"""Conservative binary API gate for independently replaceable engine releases."""
from pathlib import Path
import argparse
import subprocess
import tempfile


def compare(baseline, current):
    missing = baseline - current
    old_types = {line.split()[1] for line in baseline if line.startswith('T ')}
    # New abstract obligations would break an already compiled host implementation.
    abstract = {line for line in current - baseline if line.startswith('M ')
                and line.split()[1] in old_types and int(line.rsplit(' ', 1)[1]) & 1024}
    return sorted(missing | abstract)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--java', default='java')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    config = dict(line.split('=', 1) for line in (root / 'gradle/cgse.properties').read_text().splitlines()
                  if line and not line.startswith('#'))
    baseline = root / 'api' / (config['compatibility'] + '.txt')
    before = set(baseline.read_text(encoding='utf-8').splitlines())
    with tempfile.TemporaryDirectory(prefix='cgse-api-') as temporary:
        output = Path(temporary) / 'api.txt'
        subprocess.run([args.java, str(root / 'tools/release/ApiSurface.java'), str(args.jar.resolve()), str(output)], check=True)
        after = set(output.read_text(encoding='utf-8').splitlines())
    broken = compare(before, after)
    if broken:
        raise SystemExit('Incompatible API change; preserve the old contract or start a new compatibility line:\n' + '\n'.join(broken))
    print(f'PASS binary API {config["compatibility"]}: {len(before)} baseline entries preserved')


if __name__ == '__main__':
    main()
