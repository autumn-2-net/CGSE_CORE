"""Verify the portable Java module and every managed source in a GTL checkout."""
from pathlib import Path
import argparse
import re
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host', type=Path)
    args = parser.parse_args()
    sources = sorted((ROOT / 'src/main/java/org/cgse/core').glob('*.java'))
    problems = []
    for source in sources:
        text = source.read_text('utf8')
        if not text.startswith('package org.cgse.core;'):
            problems.append(source.name + ': unexpected package')
        for name in re.findall(r'^import\s+(?:static\s+)?([^;]+);', text, re.M):
            if not name.startswith(('java.', 'org.cgse.core.')) or name.startswith('java.awt.'):
                problems.append(source.name + ': nonportable import ' + name)
        if any(value in text for value in ('org.gtlcore.', 'appeng.', 'net.minecraft.', 'com.gregtechceu.')):
            problems.append(source.name + ': host package reference')
    if problems:
        raise SystemExit('\n'.join(problems))
    print(f'Portable module: {len(sources)} Java sources, no game/desktop dependencies')
    if args.host:
        subprocess.run([sys.executable, str(ROOT / 'tools/sync_gtl.py'), '--target', str(args.host)], check=True)
        old = args.host / 'src/main/java/org/gtlcore/gtlcore/integration/ae2/graph/core'
        if old.is_dir() and any(old.glob('*.java')):
            raise SystemExit('Legacy core source tree is still present in host')
        for source in (args.host / 'src/main/java').rglob('*.java'):
            if 'org.gtlcore.gtlcore.integration.ae2.graph.core' in source.read_text('utf8'):
                raise SystemExit('Legacy core reference: ' + str(source))
        print('Host namespace and managed source boundaries passed')


if __name__ == '__main__':
    main()
