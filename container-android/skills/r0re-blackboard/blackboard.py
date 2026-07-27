#!/usr/bin/env python3
"""r0re blackboard client — the ONLY channel between a worker and the shared board.

Env:
  R0RE_BLACKBOARD_URL  base URL of the r0re server (default http://127.0.0.1:8001)
  R0RE_PROJECT_ID      project id this worker belongs to (required)
  R0RE_WORKER_NAME     identity of this worker (default "worker")

Commands:
  read                                  full board view (facts / dead_ends / intents / hints)
  read-deadends                         dead ends only — read before choosing a direction
  list-intents                          open (claimable) intents
  claim <intent_id>                     atomically claim an intent; prints WON or LOST
  write-fact "<text>" [--verified]      append a fact; --verified only if seen in real tool output
  mark-deadend "<text>"                 record a disproven route so nobody retries it
  conclude <intent_id> "<text>"         conclude a claimed intent with its result fact
  complete --from f1,f2 "<text>"        report goal completion (goes through the server gate)
"""
import argparse
import json
import os
import sys
import urllib.error
import urllib.request

BASE = os.environ.get("R0RE_BLACKBOARD_URL", "http://127.0.0.1:8001").rstrip("/")
PROJECT = os.environ.get("R0RE_PROJECT_ID", "").strip()
WORKER = os.environ.get("R0RE_WORKER_NAME", "worker").strip() or "worker"
TIMEOUT = 20


def fail(msg, code=2):
    print(f"blackboard: {msg}", file=sys.stderr)
    sys.exit(code)


def request(method, path, body=None):
    if not PROJECT:
        fail("R0RE_PROJECT_ID is not set")
    url = f"{BASE}/projects/{PROJECT}{path}"
    data = None
    headers = {"content-type": "application/json"}
    if body is not None:
        data = json.dumps(body).encode("utf-8")
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
            raw = resp.read().decode("utf-8")
            return resp.status, json.loads(raw) if raw else {}
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode("utf-8", "replace")
        return exc.code, {"error": raw}
    except Exception as exc:
        fail(f"cannot reach blackboard at {url}: {exc}")


def cmd_read(_args):
    status, board = request("GET", "/blackboard")
    if status != 200:
        fail(f"read failed: {board}")
    print(json.dumps(board, ensure_ascii=False, indent=2))


def cmd_read_deadends(_args):
    status, board = request("GET", "/blackboard")
    if status != 200:
        fail(f"read failed: {board}")
    dead_ends = board.get("dead_ends", [])
    if not dead_ends:
        print("no dead ends recorded")
        return
    for item in dead_ends:
        print(f"- [{item.get('id')}] {item.get('description')} (by {item.get('creator') or 'unknown'})")


def cmd_list_intents(_args):
    status, board = request("GET", "/blackboard")
    if status != 200:
        fail(f"read failed: {board}")
    open_intents = board.get("open_intents", [])
    if not open_intents:
        print("no open intents")
        return
    for item in open_intents:
        print(f"{item.get('id')}: {item.get('description')}")


def cmd_claim(args):
    status, body = request("POST", f"/intents/{args.intent_id}/heartbeat", {"worker": WORKER})
    if status == 200:
        print("WON")
        return
    print(f"LOST ({status})")
    sys.exit(1)


def cmd_write_fact(args):
    status, body = request("POST", "/blackboard/facts", {
        "worker": WORKER,
        "description": args.text,
        "verified": bool(args.verified),
        "intent_id": args.intent,
    })
    if status not in (200, 201):
        fail(f"write-fact failed: {body}")
    print(f"fact={body.get('id')} verified={body.get('verified', False)}")


def cmd_mark_deadend(args):
    status, body = request("POST", "/blackboard/deadends", {
        "worker": WORKER,
        "description": args.text,
        "intent_id": args.intent,
    })
    if status not in (200, 201):
        fail(f"mark-deadend failed: {body}")
    print(f"dead_end={body.get('id')}")


def cmd_conclude(args):
    status, body = request("POST", f"/intents/{args.intent_id}/conclude", {
        "worker": WORKER,
        "description": args.text,
    })
    if status != 200:
        fail(f"conclude failed ({status}): {body}")
    fact = body.get("fact") or {}
    print(f"concluded {args.intent_id} -> fact={fact.get('id')}")


def cmd_complete(args):
    sources = [s.strip() for s in (args.sources or "").split(",") if s.strip()]
    if not sources:
        fail("complete requires --from with at least one fact id")
    status, body = request("POST", "/complete", {
        "worker": WORKER,
        "description": args.text,
        "from": sources,
    })
    if status not in (200, 201):
        fail(f"complete failed ({status}): {body}")
    print(f"candidate={body.get('id')} status={body.get('verification_status', 'pending')}")


def main():
    parser = argparse.ArgumentParser(prog="blackboard", description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)

    sub.add_parser("read", help="full board view").set_defaults(func=cmd_read)
    sub.add_parser("read-deadends", help="dead ends only").set_defaults(func=cmd_read_deadends)
    sub.add_parser("list-intents", help="open claimable intents").set_defaults(func=cmd_list_intents)

    p = sub.add_parser("claim", help="claim an intent (prints WON/LOST)")
    p.add_argument("intent_id")
    p.set_defaults(func=cmd_claim)

    p = sub.add_parser("write-fact", help="append a fact")
    p.add_argument("text")
    p.add_argument("--verified", action="store_true", help="only if seen verbatim in real tool output")
    p.add_argument("--intent", default=None, help="related intent id")
    p.set_defaults(func=cmd_write_fact)

    p = sub.add_parser("mark-deadend", help="record a disproven route")
    p.add_argument("text")
    p.add_argument("--intent", default=None, help="related intent id")
    p.set_defaults(func=cmd_mark_deadend)

    p = sub.add_parser("conclude", help="conclude a claimed intent")
    p.add_argument("intent_id")
    p.add_argument("text")
    p.set_defaults(func=cmd_conclude)

    p = sub.add_parser("complete", help="report goal completion")
    p.add_argument("text")
    p.add_argument("--from", dest="sources", required=True, help="comma-separated fact ids")
    p.set_defaults(func=cmd_complete)

    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
