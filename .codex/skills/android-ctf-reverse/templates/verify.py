#!/usr/bin/env python3
"""Independent verifier template.

Do not import solve.py. Reimplement a different property, replay the original compare,
or validate against the unmodified APK.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def verify_candidate(apk: Path, candidate: dict) -> tuple[bool, list[dict]]:
    # TODO: independently verify the candidate.
    raise NotImplementedError("Implement challenge-specific verifier")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", default="input/challenge.apk")
    parser.add_argument("--candidate", default="result.json")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    apk = Path(args.apk)
    result_doc = json.loads(Path(args.candidate).read_text(encoding="utf-8"))
    candidate = result_doc["candidate"]
    passed, checks = verify_candidate(apk, candidate)
    output = {
        "passed": bool(passed),
        "sample_sha256": sha256_file(apk),
        "candidate": candidate,
        "level": "V2",
        "checks": checks,
    }
    print(json.dumps(output, ensure_ascii=False))
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
