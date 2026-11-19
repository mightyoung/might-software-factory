"""AU-07 HTTPS commands and database evidence across the V1 -> V2 publication."""

import argparse
import json
import os
import re
from pathlib import Path
from uuid import uuid4

import psycopg2
import requests

LOANS = "/api/v1/tooling/loan-applications"
ORIGIN = os.environ["D2_VERSION_ORIGIN"]
DATABASE = "msf_d2_version_test"
if (
    os.environ.get("PGDATABASE") != DATABASE
    or os.environ.get("PGHOST") != "127.0.0.1"
    or os.environ.get("D2_VERSION_ACK") != "isolated:" + DATABASE
    or not re.fullmatch(r"https://127\.0\.0\.1:\d+", ORIGIN)
):
    raise RuntimeError("Use the dedicated loopback loan-version database")
STATE = Path(os.environ["D2_VERSION_EVIDENCE"]) / "scenario.json"


def post(session, path, body, key=None):
    return session.post(
        ORIGIN + path,
        json=body,
        headers={
            "X-XSRF-TOKEN": session.cookies["XSRF-TOKEN"],
            "Idempotency-Key": key or str(uuid4()),
        },
        timeout=30,
    )


def login(actor):
    session = requests.Session()
    session.trust_env = False
    session.verify = os.environ["D2_VERSION_CA"]
    session.get(ORIGIN + "/api/v1/auth/csrf", timeout=10).raise_for_status()
    post(
        session,
        "/api/v1/auth/login",
        {
            "tenantCode": "d1seed",
            "loginKey": "d1seed-" + actor,
            "password": os.environ["D2_PASSWORD_" + actor.upper()],
        },
    ).raise_for_status()
    session.get(ORIGIN + "/api/v1/auth/csrf", timeout=10).raise_for_status()
    return session


def check(response, status=200):
    assert response.status_code == status, (response.status_code, response.text)
    return response.json()


def prepare():
    with login("reader") as reader, login("keeper") as keeper:
        candidates = check(reader.get(ORIGIN + LOANS + "/candidates", timeout=10))[
            "data"
        ]
        org = next(
            item["id"]
            for item in candidates["departments"]
            if "D1-ORG-A" in item["label"]
        )
        scope = next(
            item["id"] for item in candidates["scopes"] if "D1-SCOPE-A" in item["label"]
        )
        marker = "AU07-" + uuid4().hex[:10]
        tools = []
        for index in range(2):
            data = {
                "code": marker + "-" + str(index),
                "name": "版本验收工装",
                "custodyScopeId": scope,
            }
            tools.append(
                check(
                    post(
                        keeper,
                        "/api/v1/apps/tooling/entities/tool/records",
                        {"definitionVersion": "1", "values": data},
                    )
                )["data"]["id"]
            )
        command = {
            "definitionVersion": "1",
            "organizationId": org,
            "custodyScopeId": scope,
            "purpose": marker,
            "toolIds": [tools[0]],
        }
        key = str(uuid4())
        loan = check(post(reader, LOANS, command, key))["data"]
        assert loan["version"] == 1 and loan["definitionVersion"] == "1"
        STATE.parent.mkdir(parents=True, exist_ok=True)
        STATE.write_text(
            json.dumps({"key": key, "body": command, "id": loan["id"], "tools": tools})
        )
        print("V1 CREATE accepted", loan["id"])


def verify():
    s = json.loads(STATE.read_text())
    old = s["id"]
    new = s["v2Id"]
    with login("reader") as reader, login("keeper") as keeper:
        replay = check(post(reader, LOANS, s["body"], s["key"]))["data"]
        assert (
            replay["id"] == old
            and replay["version"] == 1
            and replay["toolIds"] == s["tools"][:1]
        )
        assert (
            check(post(reader, LOANS, s["body"]), 409)["code"] == "DEFINITION_CONFLICT"
        )
        assert (
            check(post(reader, LOANS, dict(s["body"], definitionVersion="2")), 400)[
                "code"
            ]
            == "INVALID_REQUEST"
        )
        # An old definition still checks the current tool references before writing a revision.
        with psycopg2.connect("") as db, db.cursor() as q:
            q.execute(
                """select b.id::text from tool a join tool b on b.tenant_id=a.tenant_id
                and b.custody_scope_id<>a.custody_scope_id where a.id=%s limit 1""",
                (s["tools"][0],),
            )
            other_tool = q.fetchone()[0]
        refused = post(
            reader,
            LOANS + "/" + old + "/revise",
            dict(s["body"], expectedVersion=2, toolIds=[other_tool]),
        )
        assert refused.status_code == 400, (refused.status_code, refused.text)
        new_location = s["location"] + "二区"
        revised = check(
            post(
                reader,
                LOANS + "/" + new + "/revise",
                dict(
                    s["body"],
                    definitionVersion="2",
                    expectedVersion=1,
                    usageLocation=new_location,
                ),
            )
        )["data"]
        assert revised["version"] == 2 and revised["usageLocation"] == new_location

        for loan, bound, rev, tools in [
            (old, "1", 2, s["tools"][1:]),
            (new, "2", 2, s["tools"][:1]),
        ]:
            base = LOANS + "/" + loan
            stale = "2" if bound == "1" else "1"
            assert (
                check(
                    post(
                        keeper,
                        base + "/issue",
                        {"definitionVersion": stale, "expectedVersion": rev},
                    ),
                    409,
                )["code"]
                == "DEFINITION_CONFLICT"
            )
            issued = check(
                post(
                    keeper,
                    base + "/issue",
                    {"definitionVersion": bound, "expectedVersion": rev},
                )
            )["data"]
            assert issued["state"] == "ISSUED" and issued["definitionVersion"] == bound
            check(
                post(
                    reader,
                    base + "/return-requests",
                    {
                        "definitionVersion": bound,
                        "expectedVersion": rev + 1,
                        "toolIds": tools,
                    },
                )
            )
            settled = check(
                post(
                    keeper,
                    base + "/receive",
                    {
                        "definitionVersion": bound,
                        "expectedVersion": rev + 2,
                        "items": [{"toolId": t, "condition": "NORMAL"} for t in tools],
                    },
                )
            )["data"]
            assert (
                settled["state"] == "SETTLED" and settled["definitionVersion"] == bound
            )
        with psycopg2.connect("") as db, db.cursor() as q:
            for loan, bound, actions, location in [
                (old, "1", ["CREATE", "UPDATE", "ISSUE", "RETURN", "RECEIVE"], None),
                (
                    new,
                    "2",
                    ["CREATE", "UPDATE", "ISSUE", "RETURN", "RECEIVE"],
                    new_location,
                ),
            ]:
                q.execute(
                    "select definition_version,usage_location,version from tool_loan where id=%s",
                    (loan,),
                )
                assert q.fetchone() == (bound, location, len(actions))
                q.execute(
                    """select r.action,r.usage_location from tool_loan_revision r
                    join tool_loan_command_receipt c on c.loan_id=r.loan_id and c.command_id=r.command_id and c.tenant_id=r.tenant_id
                    where r.loan_id=%s order by r.version""",
                    (loan,),
                )
                assert q.fetchall() == [
                    (a, s["location"] if loan == new and a == "CREATE" else location)
                    for a in actions
                ]
                q.execute(
                    "select count(*) from tool_loan_item where loan_id=%s and issued_at is not null and settled_at is null",
                    (loan,),
                )
                assert q.fetchone() == (0,)
            q.execute(
                "select version,tool_id::text from tool_loan_item where loan_id=%s and version in (1,2) order by version",
                (old,),
            )
            assert q.fetchall() == [(1, s["tools"][0]), (2, s["tools"][1])]
        print(
            "V1 and V2 settled; idempotency, version conflicts, item revisions and usage-location history verified"
        )


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("phase", choices=["prepare", "verify"])
    if parser.parse_args().phase == "prepare":
        prepare()
    else:
        verify()
