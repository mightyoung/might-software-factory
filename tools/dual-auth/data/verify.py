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
        return record["id"] if field == "record_id" else record[field]
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
    return value(plan_filter["condition"], record)


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
    cases = []
    for name, actor, action, expected in scenarios:
        check = request(args.url, "/api/check/resources", {
            "requestId": name, "principal": actor,
            "resources": [{"actions": [action], "resource": {
                "kind": "ledger", "id": row["id"],
                "attr": {**row, "record_id": row["id"]}}} for row in RECORDS]})
        allowed = [r["resource"]["id"] for r in check["results"]
                   if r["actions"][action] == "EFFECT_ALLOW"]
        assert allowed == expected, (name, allowed, expected)
        plan = request(args.url, "/api/plan/resources", {
            "requestId": name, "principal": actor, "action": action,
            "resource": {"kind": "ledger"}, "includeMeta": True})
        found = [r["id"] for r in RECORDS if matches(plan["filter"], r)]
        assert found == allowed, (name, found, allowed, plan)
        cases.append({"name": name, "action": action, "filter": plan["filter"],
                      "expectedIds": allowed, "check": check, "plan": plan})
    output = {"records": RECORDS, "cases": cases}
    if args.output:
        args.output.write_text(json.dumps(output, indent=2) + "\n")
    print(f"PASS: {len(cases)} real CheckResources/PlanResources cases; row, field, tenant and revocation decisions agree")


if __name__ == "__main__":
    main()
