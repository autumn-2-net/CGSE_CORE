"""Validate exported AEDUMP archives, including the extracted request coordinator."""
from pathlib import Path
import argparse
import json
import re
import sys
import zipfile


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def validate_archive(path):
    path = Path(path)
    with zipfile.ZipFile(path) as archive:
        require(archive.testzip() is None, "ZIP integrity failure")
        read = lambda name: json.loads(archive.read(name))
        status = read("export-status.json")
        if not status["complete"]:
            return {"file": path.name, "complete": False, "failed_entry": status.get("failed_entry")}
        request = read("request.json")
        result = read("result.json")
        network = read("network.json")
        resources = {row["id"]: row for row in map(json.loads, archive.read("resources.jsonl").splitlines())}
        patterns = {row["id"]: row for row in map(json.loads, archive.read("patterns.jsonl").splitlines())}
        require(len(patterns) == network["pattern_count"], "Pattern count mismatch")
        require(len(archive.read("nodes.jsonl").splitlines()) == network["grid_size"], "Node count mismatch")
        require(not network["capture_errors"], "Network capture errors in a complete archive")

        def key(value):
            require(value is None or value in resources, "Unknown resource key: " + str(value))

        def amount(value):
            require(isinstance(value, str) and re.fullmatch(r"-?[0-9]+", value), "Lossy quantity: " + repr(value))

        def stock(values):
            require(isinstance(values, dict), "Expected an amount map")
            for resource, value in values.items():
                key(resource)
                amount(value)

        def recipe(row):
            stock(row["outputs"])
            for slot in row["slots"]:
                key(slot["key"])
                amount(slot["amount"])

        amount(request["amount"])
        key(request.get("target"))
        for field in ["cached_inventory", "advertised_inventory", "extractable_inventory"]:
            stock(network[field])
        for resource, ids in network["producer_order"].items():
            key(resource)
            require(all(pattern in patterns for pattern in ids), "Unknown producer pattern")
        for pattern in patterns.values():
            key(pattern["definition"])
            for output in pattern["outputs"]:
                key(output["key"])
                amount(output["amount"])
            for slot in pattern["inputs"]:
                amount(slot["multiplier"])
                for candidate in slot["possible"]:
                    key(candidate["key"])
                    amount(candidate["amount"])
                for candidate in slot["candidate_checks"]:
                    key(candidate["key"])
                    key(candidate["remaining"])
        require(all("snbt" in row and "error" not in row for row in resources.values()), "Incomplete resource encoding")
        cgse = read("cgse-input.json")
        for field in ["available", "stock", "forecast", "required_seeds"]:
            if field in cgse:
                stock(cgse[field])
        recipe_ids = {row["id"] for row in cgse.get("recipes", [])}
        for row in cgse.get("recipes", []):
            recipe(row)
        for resource, ids in cgse.get("producer_order", {}).items():
            key(resource)
            require(set(ids) <= recipe_ids, "Unknown compiled producer")
        plan = read("cgse-plan.json")
        if request["engine"] == "CGSE" and (plan is not None or result["status"].startswith("FEASIBLE")):
            require(cgse.get("complete") is True, "Portable request delegate was not captured")
            require(plan is not None and "available" in cgse, "Missing actual input or selected plan")
            coordinator = result.get("coordinator", {})
            for flag in ["directEmission", "fallbackAttempted", "fallbackMode"]:
                require(isinstance(coordinator.get(flag), bool), "Missing delegated coordinator flag: " + flag)
            require(not coordinator["directEmission"] or cgse.get("force_craft") is False,
                    "Direct emission incorrectly captured as force-craft")
            require(not coordinator["fallbackAttempted"] or result.get("fallback") is True,
                    "Fallback attempt omitted from archive result")
        if plan:
            amount(plan["amount"])
            key(plan["target"])
            for field in ["initial", "missing", "seeds"]:
                stock(plan[field])
            for row in plan["recipes"]:
                recipe(row)
            size = len(plan["program"])
            require(plan["program_root"] == -1 if size == 0 else 0 <= plan["program_root"] < size,
                    "Invalid program root")
            for index, node in enumerate(plan["program"]):
                require(node["id"] == index, "Program index changed")
                if node["kind"] == "batch":
                    amount(node["runs"])
                elif node["kind"] == "repeat":
                    amount(node["times"])
                    require(0 <= node["body"] < size, "Invalid repeat body")
                elif node["kind"] == "sequence":
                    require(all(0 <= child < size for child in node["children"]), "Invalid sequence child")
                else:
                    raise AssertionError("Unknown program node kind")
        return {"file": path.name, "complete": True, "engine": request["engine"],
                "result": result["status"], "amount": request["amount"], "patterns": len(patterns),
                "nodes": network["grid_size"], "cgse_recipes": len(recipe_ids), "resources": len(resources),
                "capture_ms": int(network["capture_ns"]) / 1e6,
                "timing": request.get("full_network_capture_timing"),
                "stock_above_2pow53": sum(int(value) > 2**53 for value in network["advertised_inventory"].values())}


def main():
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf8")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("path", type=Path, help="Archive file or directory containing ZIP archives")
    parser.add_argument("--require-complete", action="store_true")
    args = parser.parse_args()
    paths = [args.path] if args.path.is_file() else sorted(args.path.glob("*.zip"))
    require(bool(paths), "No archives found")
    reports = [validate_archive(path) for path in paths]
    print(json.dumps(reports, ensure_ascii=False, indent=2))
    if args.require_complete:
        require(all(report["complete"] for report in reports), "Incomplete archive encountered")


if __name__ == "__main__":
    main()
