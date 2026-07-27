#!/usr/bin/env python3
"""Create an Android CTF case directory from an APK."""
from __future__ import annotations

import argparse
import re
import shutil
from pathlib import Path


def safe_id(value: str) -> str:
    value = value.lower()
    value = re.sub(r"[^a-z0-9._-]+", "-", value).strip("-")
    return value or "android-ctf-case"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path)
    parser.add_argument("--cases-dir", type=Path, default=Path("cases"))
    parser.add_argument("--case-id")
    parser.add_argument("--force", action="store_true")
    args = parser.parse_args()

    apk = args.apk.resolve()
    if not apk.is_file():
        parser.error(f"APK not found: {apk}")

    skill_root = Path(__file__).resolve().parents[1]
    case_id = safe_id(args.case_id or apk.stem)
    case_dir = (args.cases_dir / case_id).resolve()
    if case_dir.exists() and any(case_dir.iterdir()) and not args.force:
        parser.error(f"Case directory is not empty: {case_dir}; use --force to overwrite templates")

    for rel in [
        "input", "work/apktool", "work/jadx", "work/native", "work/extracted",
        "evidence/traces", "scripts", "logs"
    ]:
        (case_dir / rel).mkdir(parents=True, exist_ok=True)

    shutil.copy2(apk, case_dir / "input" / "challenge.apk")
    shutil.copy2(skill_root / "templates" / "analysis-report.md", case_dir / "report.md")
    shutil.copy2(skill_root / "templates" / "case-intake.md", case_dir / "intake.md")
    shutil.copy2(skill_root / "templates" / "result.json", case_dir / "result.json")
    shutil.copy2(skill_root / "templates" / "solve.py", case_dir / "scripts" / "solve.py")
    shutil.copy2(skill_root / "templates" / "verify.py", case_dir / "scripts" / "verify.py")

    for name in ["facts.jsonl", "hypotheses.jsonl", "dead-ends.jsonl"]:
        (case_dir / "evidence" / name).touch()

    print(case_dir)
    print("Next:")
    print(f"  python3 {skill_root / 'tools' / 'triage_apk.py'} "
          f"{case_dir / 'input' / 'challenge.apk'} --out {case_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
