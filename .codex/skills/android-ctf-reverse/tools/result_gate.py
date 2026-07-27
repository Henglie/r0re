#!/usr/bin/env python3
"""Independent completion gate for Android APK CTF cases.

The result document stays `status: candidate`. This gate runs a solver and a separate
verifier, compares their machine-readable JSON, validates evidence artifacts, and writes
gate-report.json.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path
from typing import Any


LEVELS = {"V1": 1, "V2": 2, "V3": 3, "V4": 4, "V5": 5}


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def is_inside(child: Path, parent: Path) -> bool:
    try:
        child.resolve().relative_to(parent.resolve())
        return True
    except ValueError:
        return False


def run_json_command(spec: dict[str, Any], case_dir: Path, timeout: int) -> dict[str, Any]:
    command = spec.get("command")
    if not isinstance(command, list) or not command or not all(isinstance(x, str) for x in command):
        raise ValueError("command must be a non-empty string array")

    cwd = (case_dir / spec.get("cwd", ".")).resolve()
    if not is_inside(cwd, case_dir):
        raise ValueError(f"cwd escapes case directory: {cwd}")

    cp = subprocess.run(
        command,
        cwd=str(cwd),
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        timeout=timeout,
        check=False,
        shell=False,
    )
    if cp.returncode != 0:
        raise RuntimeError(
            f"command failed ({cp.returncode}): {command}\n"
            f"stdout:\n{cp.stdout}\nstderr:\n{cp.stderr}"
        )
    try:
        payload = json.loads(cp.stdout.strip())
    except json.JSONDecodeError as exc:
        raise ValueError(
            f"command did not emit one JSON document: {command}\nstdout:\n{cp.stdout}"
        ) from exc
    return {
        "command": command,
        "returncode": cp.returncode,
        "stdout_json": payload,
        "stderr": cp.stderr,
    }


def normalized_candidate(value: Any) -> dict[str, str]:
    if not isinstance(value, dict):
        raise ValueError("candidate must be an object")
    keys = ["app_input", "displayed_flag", "submit"]
    return {k: str(value.get(k, "")) for k in keys}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("result", type=Path)
    parser.add_argument("--timeout", type=int, default=180)
    args = parser.parse_args()

    result_path = args.result.resolve()
    case_dir = result_path.parent
    errors: list[str] = []
    checks: list[dict[str, Any]] = []

    try:
        doc = json.loads(result_path.read_text(encoding="utf-8"))
    except Exception as exc:
        print(f"error: cannot read result JSON: {exc}", file=sys.stderr)
        return 2

    if doc.get("status") != "candidate":
        errors.append("result.status must remain 'candidate'; only the gate may verify it")

    sample = doc.get("sample") or {}
    apk_rel = sample.get("apk", "input/challenge.apk")
    apk = (case_dir / apk_rel).resolve()
    if not is_inside(apk, case_dir) or not apk.is_file():
        errors.append(f"sample APK missing or outside case directory: {apk}")
        actual_hash = None
    else:
        actual_hash = sha256_file(apk)
        expected_hash = str(sample.get("sha256", "")).lower()
        if not re.fullmatch(r"[0-9a-f]{64}", expected_hash):
            errors.append("sample.sha256 is not a valid 64-character lowercase hex digest")
        elif expected_hash != actual_hash:
            errors.append(f"sample hash mismatch: expected {expected_hash}, actual {actual_hash}")
        checks.append({"name": "sample_hash", "passed": expected_hash == actual_hash,
                       "actual": actual_hash})

    result_candidate = normalized_candidate(doc.get("candidate") or {})
    if not any(result_candidate.values()):
        errors.append("candidate is empty")

    evidence = doc.get("evidence")
    if not isinstance(evidence, list) or not evidence:
        errors.append("at least one evidence record is required")
    else:
        for item in evidence:
            artifact = item.get("artifact") if isinstance(item, dict) else None
            if not artifact:
                errors.append("evidence record missing artifact")
                continue
            artifact_path = (case_dir / artifact).resolve()
            if not is_inside(artifact_path, case_dir) or not artifact_path.exists():
                errors.append(f"evidence artifact missing/outside case: {artifact}")
        checks.append({"name": "evidence_artifacts", "passed": not any(
            "evidence" in e for e in errors)})

    solver_spec = doc.get("solver") or {}
    verifier_spec = doc.get("verifier") or {}
    if solver_spec.get("command") == verifier_spec.get("command"):
        errors.append("solver and verifier commands must be different")

    solver_run = None
    verifier_run = None
    try:
        solver_run = run_json_command(solver_spec, case_dir, args.timeout)
        solver_output = solver_run["stdout_json"]
        solver_candidate = normalized_candidate(solver_output.get("candidate"))
        if solver_candidate != result_candidate:
            errors.append("solver candidate does not match result.json candidate")
        if actual_hash and solver_output.get("sample_sha256") != actual_hash:
            errors.append("solver sample_sha256 does not match APK")
        checks.append({"name": "solver", "passed": solver_candidate == result_candidate})
    except Exception as exc:
        errors.append(f"solver failed: {exc}")

    try:
        verifier_run = run_json_command(verifier_spec, case_dir, args.timeout)
        verifier_output = verifier_run["stdout_json"]
        verifier_candidate = normalized_candidate(verifier_output.get("candidate"))
        if verifier_candidate != result_candidate:
            errors.append("verifier candidate does not match result.json candidate")
        if verifier_output.get("passed") is not True:
            errors.append("verifier did not report passed=true")
        if actual_hash and verifier_output.get("sample_sha256") != actual_hash:
            errors.append("verifier sample_sha256 does not match APK")

        level = str(verifier_output.get("level", ""))
        minimum = str(verifier_spec.get("minimum_level", "V2"))
        if level not in LEVELS:
            errors.append(f"unknown verifier level: {level}")
        elif minimum not in LEVELS:
            errors.append(f"unknown minimum verifier level: {minimum}")
        elif LEVELS[level] < LEVELS[minimum]:
            errors.append(f"verification level {level} is below required {minimum}")

        checks.append({
            "name": "verifier",
            "passed": verifier_output.get("passed") is True and
                      verifier_candidate == result_candidate,
            "level": level,
        })
    except Exception as exc:
        errors.append(f"verifier failed: {exc}")

    report = {
        "schema_version": 1,
        "passed": not errors,
        "case_dir": str(case_dir),
        "candidate": result_candidate,
        "checks": checks,
        "errors": errors,
        "solver": solver_run,
        "verifier": verifier_run,
    }
    report_path = case_dir / "gate-report.json"
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n",
                           encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if not errors else 1


if __name__ == "__main__":
    raise SystemExit(main())
