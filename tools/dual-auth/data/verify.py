#!/usr/bin/env python3
"""Compare real Cerbos row and field decisions with query plans.

The emitted filter is the unmodified PlanResources response for SQL validation.
"""
import argparse
import json
from pathlib import Path
import urllib.request


RECORDS = [
    {"id": "own", "tenant": "factory", "owner": "alice", "department": "quality", "quantity": 2, "price": 10},
    {"id": "peer", "tenant": "factory", "owner": "bob", "department": "quality", "quantity": 3, "price": 20},
    {"id": "other-dept", "tenant": "factory", "owner": "carol", "department": "sales", "quantity": 4, "price": 30},
    {"id": "other-tenant", "tenant": "other", "owner": "alice", "department": "quality", "quantity": 5, "price": 40},
]


NULLABLE_RECORDS = [
    {"id": "department-missing", "tenant": "factory", "owner": "bob", "quantity": 2, "price": 10},
    {"id": "department-null", "tenant": "factory", "owner": "bob", "department": None, "quantity": 2, "price": 10},
    {"id": "department-match", "tenant": "factory", "owner": "bob", "department": "quality", "quantity": 2, "price": 10},
    {"id": "owner-missing", "tenant": "factory", "department": "sales", "quantity": 2, "price": 10},
    {"id": "owner-null", "tenant": "factory", "owner": None, "department": "sales", "quantity": 2, "price": 10},
    {"id": "owner-match", "tenant": "factory", "owner": "alice", "department": "sales", "quantity": 2, "price": 10},
]
NULLABLE_FIELDS = {"owner", "department"}
REQUIRED_TEXT_FIELDS = {"id", "tenant"}
REQUIRED_NUMBER_FIELDS = {"quantity", "price"}


def valid_records(records):
    required = REQUIRED_TEXT_FIELDS | REQUIRED_NUMBER_FIELDS
    ids = set()
    if not records:
        raise ValueError("Empty fixture records")
    for record in records:
        if (not isinstance(record, dict) or not required <= record.keys() <= required | NULLABLE_FIELDS
                or any(not isinstance(record[field], str) or not record[field] for field in REQUIRED_TEXT_FIELDS)
                or any(type(record[field]) is not int for field in REQUIRED_NUMBER_FIELDS)
                or any(record.get(field) is not None and not isinstance(record[field], str) for field in NULLABLE_FIELDS)
                or record["id"] in ids):
            raise ValueError("Invalid synthetic authorization fixture record")
        ids.add(record["id"])


def principal(roles, temporary_ids=()):
    return {"id": "alice", "roles": roles, "attr": {
        "tenant": "factory", "department": "quality", "temporary_ids": list(temporary_ids)}}


def request(base, path, body):
    payload = json.dumps(body).encode()
    call = urllib.request.Request(base + path, payload, {"Content-Type": "application/json"})
    with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(call, timeout=10) as response:
        return json.load(response)


def value(node, record):
    if "value" in node:
        return node["value"]
    if "variable" in node:
        prefix = "request.resource.attr."
        assert node["variable"].startswith(prefix), node
        field = node["variable"][len(prefix):]
        if field == "record_id":
            return record["id"]
        if field not in REQUIRED_TEXT_FIELDS | NULLABLE_FIELDS:
            raise ValueError("Unsupported fixture plan field")
        return record.get(field)
    expression = node["expression"]
    args = [value(item, record) for item in expression["operands"]]
    op = expression["operator"]
    if op == "eq":
        return args[0] == args[1]
    if op == "and":
        return all(args)
    if op == "or":
        return any(args)
    if op == "in":
        return args[0] in args[1]
    raise AssertionError(f"Unsupported plan operator: {op}")


def matches(plan_filter, record):
    kind = plan_filter["kind"]
    if kind == "KIND_ALWAYS_ALLOWED":
        return True
    if kind == "KIND_ALWAYS_DENIED":
        return False
    assert kind == "KIND_CONDITIONAL", plan_filter
    result = value(plan_filter["condition"], record)
    if type(result) is not bool:
        raise ValueError("Expected a boolean fixture plan")
    return result


def compare(url, records, scenarios):
    valid_records(records)
    cases = []
    for name, actor, action, expected in scenarios:
        check = request(url, "/api/check/resources", {
            "requestId": name, "principal": actor,
            "resources": [{"actions": [action], "resource": {
                "kind": "ledger", "id": row["id"],
                "attr": {**row, "record_id": row["id"]}}} for row in records]})
        effects = {}
        for result in check["results"]:
            record_id = result["resource"]["id"]
            effect = result["actions"].get(action)
            if record_id in effects or effect not in ("EFFECT_ALLOW", "EFFECT_DENY"):
                raise ValueError("Unexpected individual Cerbos decision")
            effects[record_id] = effect
        if effects.keys() != {row["id"] for row in records}:
            raise ValueError("Cerbos did not decide each fixture record")
        allowed = [row["id"] for row in records if effects[row["id"]] == "EFFECT_ALLOW"]
        assert allowed == expected, (name, allowed, expected)
        plan = request(url, "/api/plan/resources", {
            "requestId": name, "principal": actor, "action": action,
            "resource": {"kind": "ledger"}, "includeMeta": True})
        found = [r["id"] for r in records if matches(plan["filter"], r)]
        assert found == allowed, (name, found, allowed, plan)
        cases.append({"name": name, "action": action, "filter": plan["filter"],
                      "expectedIds": allowed, "check": check, "plan": plan})
    return cases


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default="http://127.0.0.1:43592")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    scenarios = [
        ("supervisor", principal(["supervisor"]), "view", ["own", "peer"]),
        ("supervisor-no-edit", principal(["supervisor"]), "edit", []),
        ("registrar", principal(["registrar"]), "edit", ["own"]),
        ("combined-read", principal(["supervisor", "registrar"]), "view", ["own", "peer"]),
        ("combined-edit", principal(["supervisor", "registrar"]), "edit", ["own"]),
        ("combined-quantity", principal(["supervisor", "registrar"]), "quantity:view", ["own", "peer"]),
        ("combined-price", principal(["supervisor", "registrar"]), "price:view", ["own"]),
        ("temporary-before", principal(["collaborator"], ["peer"]), "edit", ["peer"]),
        ("temporary-view", principal(["collaborator"], ["peer"]), "view", ["peer"]),
        ("temporary-quantity", principal(["collaborator"], ["peer"]), "quantity:view", ["peer"]),
        ("temporary-price", principal(["collaborator"], ["peer"]), "price:view", []),
        ("temporary-after-revoke", principal(["collaborator"]), "edit", []),
        ("temporary-after-revoke-view", principal(["collaborator"]), "view", []),
        ("temporary-cross-tenant", principal(["collaborator"], ["other-tenant"]), "edit", []),
    ]
    baseline = compare(args.url, RECORDS, scenarios)
    nullable = compare(args.url, NULLABLE_RECORDS, [
        ("nullable-department-view", principal(["supervisor"]), "view", ["department-match"]),
        ("nullable-owner-edit", principal(["registrar"]), "edit", ["owner-match"]),
    ])
    output = {"records": RECORDS, "cases": baseline,
              "nullableRecords": NULLABLE_RECORDS, "nullableCases": nullable}
    if args.output:
        args.output.write_text(json.dumps(output, indent=2) + "\n")
    for sample in nullable:
        print(f"{sample['name']} returned {sample['filter']['kind']}")
    print(f"PASS: {len(baseline)} baseline and {len(nullable)} nullable real CheckResources/PlanResources cases")


if __name__ == "__main__":
    main()
