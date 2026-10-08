#!/usr/bin/env python3
# Copyright (c) 2026 autumn
# SPDX-License-Identifier: MPL-2.0
"""Seeded weighted-choice graphs with an independently replayed planted witness."""
import argparse
import json
from pathlib import Path
import random


def make_case(seed, count, choices, dimensions, slack, amount):
    rng = random.Random(seed)
    stock = {f'U{i}': amount for i in range(count)}
    recipes, selected, goal = [], [], {}
    for i in range(count):
        weights = [rng.randint(1, 1000) for _ in range(dimensions)]
        chosen = rng.randrange(choices)
        selected.append(f'r{i}c{chosen}')
        for option in range(choices):
            outputs = {f'C{option}_{d}': weight for d, weight in enumerate(weights)}
            recipes.append(dict(id=f'r{i}c{option}', inputs={f'U{i}': 1}, outputs=outputs))
            if option == chosen:
                for key, value in outputs.items():
                    goal[key] = goal.get(key, 0) + value
    goal = {key: max(0, value - slack) for key, value in goal.items()}
    goal = {key: value for key, value in goal.items() if value}
    recipes.append(dict(id='finish', inputs=goal, outputs={'GOAL': 1}))
    witness = {key: amount for key in selected + ['finish']}
    name = f'choice_n{count}_c{choices}_d{dimensions}_slack{slack}_s{seed}_q{amount}'
    case = dict(name=name, target='GOAL', amount=amount, stock=stock, recipes=recipes,
                truth='SAT', seed=seed, dag=True, witness=witness)
    # Plain integer inventory replay, independent of the CGSE compiler/solver.
    inventory = dict(stock)
    for recipe in recipes:
        runs = witness.get(recipe['id'], 0)
        if not runs:
            continue
        for key, value in recipe['inputs'].items():
            inventory[key] = inventory.get(key, 0) - value * runs
            assert inventory[key] >= 0, (name, key)
        for key, value in recipe['outputs'].items():
            inventory[key] = inventory.get(key, 0) + value * runs
    assert inventory['GOAL'] == amount
    return case


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=Path(__file__).resolve().parents[2] / 'build/choice-fixtures')
    parser.add_argument('--seeds', type=int, default=8)
    parser.add_argument('--start-seed', type=int, default=100)
    parser.add_argument('--amounts', default='1')
    args = parser.parse_args()
    amounts = [int(value) for value in args.amounts.split(',')]
    if args.seeds <= 0 or any(not 0 < value < 2**63 for value in amounts):
        parser.error('Require positive seeds and signed-long amounts')
    args.output.mkdir(parents=True, exist_ok=True)
    count = 0
    for seed in range(args.start_seed, args.start_seed + args.seeds):
        for n, choices, dimensions, slack in [(32, 2, 8, 0), (32, 2, 12, 0), (36, 2, 2, 1),
                                               (20, 3, 2, 0), (20, 4, 2, 0)]:
            for amount in amounts:
                case = make_case(seed, n, choices, dimensions, slack, amount)
                (args.output / (case['name'] + '.json')).write_text(json.dumps(case), encoding='utf-8')
                count += 1
    print(f'{count} SAT cases with independently replayed witnesses: {args.output}')


if __name__ == '__main__':
    main()
