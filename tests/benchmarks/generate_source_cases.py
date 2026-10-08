#!/usr/bin/env python3
# Copyright (c) 2026 autumn
# SPDX-License-Identifier: MPL-2.0
"""Small executable supports behind many unfunded producers; no catalog pruning."""
import argparse
import json
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=Path(__file__).resolve().parents[2] / 'build/source-selection-fixtures')
    parser.add_argument('--counts', default='1000,9000')
    args = parser.parse_args()
    counts = [int(value) for value in args.counts.split(',')]
    if any(value < 1 for value in counts):
        parser.error('Producer counts must be positive')
    args.output.mkdir(parents=True, exist_ok=True)
    for count in counts:
        for shape in ('linear', 'shared', 'growth'):
            recipes = [{'id': 'unfunded' + str(i), 'inputs': {'raw' + str(i): 1}, 'outputs': {'X': 10}} for i in range(count)]
            recipes.append({'id': 'funded', 'inputs': {'ore': 1, 'X': 1} if shape == 'growth' else {'ore': 1},
                            'outputs': {'X': 11 if shape == 'growth' else 10}})
            if shape == 'shared':
                recipes += [{'id': key, 'inputs': {'X': 10}, 'outputs': {key: 1}} for key in ('A', 'B')]
                recipes.append({'id': 'root', 'inputs': {'A': 1, 'B': 1}, 'outputs': {'C': 1}})
            else:
                recipes.append({'id': 'root', 'inputs': {'X': 10}, 'outputs': {'C': 1}})
            name = f'late_{shape}_{count}'
            case = {'name': name, 'target': 'C', 'amount': 1, 'truth': 'SAT',
                    'stock': {'ore': 2 if shape == 'shared' else 1, 'X': 1}, 'recipes': recipes}
            (args.output / (name + '.json')).write_text(json.dumps(case), encoding='utf-8')


if __name__ == '__main__':
    main()
