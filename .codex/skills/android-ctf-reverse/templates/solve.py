#!/usr/bin/env python3
"""Deterministic solver template.

Replace derive_candidate() with the restored challenge algorithm.
The script must not call the verifier.
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


def derive_candidate(apk: Path) -> dict:
    # TODO: implement the restored algorithm/state derivation.
    raise NotImplementedError("Implement challenge-specific solver")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", default="input/challenge.apk")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    apk = Path(args.apk)
    result = {
        "sample_sha256": sha256_file(apk),
        "candidate": derive_candidate(apk),
        "checkpoints": []
    }
    if args.json:
        print(json.dumps(result, ensure_ascii=False))
    else:
        print(result)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
