"""Export canonical count models for preserved benchmark comparison drivers."""
import argparse
import json
from pathlib import Path
import re

BASE = Path(__file__).resolve().parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--case', default='.', help='Regex matching canonical ID or original name')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    manifest = json.loads((BASE / 'manifest.json').read_text(encoding='utf-8'))
    selected = []
    for entry in manifest['cases']:
        if entry['kind'] != 'count-model' or not re.search(args.case, entry['id'] + ' ' + ' '.join(entry['names'])):
            continue
        case = json.loads((BASE.parent / entry['path']).read_text(encoding='utf-8'))
        selected.append({**case['model'], 'id': case['id'], 'expected': case['expected'],
                         'source': case['origins']})
    if not selected:
        parser.error('No count models selected')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(selected, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(f'Exported {len(selected)} count models to {args.output}')


if __name__ == '__main__':
    main()
