#!/usr/bin/env python3
"""Baseline triage for an authorized Android APK CTF sample.

Uses Python stdlib for deterministic ZIP/hash inventory and optionally runs installed
Android reverse-engineering tools. Individual tool failures are recorded, not fatal.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path
from typing import Any


TEXT_SCAN_EXTENSIONS = {
    ".xml", ".json", ".txt", ".html", ".htm", ".js", ".properties",
    ".yml", ".yaml", ".ini", ".cfg", ".csv"
}
INTERESTING_TERMS = [
    b"flag{", b"ctf{", b"success", b"wrong", b"native", b"loadLibrary",
    b"MessageDigest", b"Cipher", b"Base64", b"RegisterNatives",
    b"JNI_OnLoad", b"frida", b"ptrace", b"WebView", b"javascriptinterface"
]


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def safe_case_id(name: str) -> str:
    stem = Path(name).stem.lower()
    stem = re.sub(r"[^a-z0-9._-]+", "-", stem).strip("-")
    return stem or "android-ctf-case"


def run_command(command: list[str], cwd: Path, timeout: int = 240) -> dict[str, Any]:
    exe = shutil.which(command[0])
    if exe is None:
        return {"command": command, "status": "missing"}
    command = [exe, *command[1:]]
    try:
        cp = subprocess.run(
            command,
            cwd=str(cwd),
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=timeout,
            check=False,
        )
        return {
            "command": command,
            "status": "ok" if cp.returncode == 0 else "failed",
            "returncode": cp.returncode,
            "stdout": cp.stdout,
            "stderr": cp.stderr,
        }
    except subprocess.TimeoutExpired as exc:
        return {
            "command": command,
            "status": "timeout",
            "stdout": exc.stdout or "",
            "stderr": exc.stderr or "",
        }


def inventory_zip(apk: Path) -> dict[str, Any]:
    data: dict[str, Any] = {
        "valid_zip": False,
        "entries": [],
        "dex": [],
        "native_libraries": [],
        "abis": [],
        "assets": [],
        "certificates": [],
        "embedded_archives": [],
        "interesting_hits": [],
    }
    with zipfile.ZipFile(apk) as zf:
        data["valid_zip"] = True
        infos = zf.infolist()
        file_infos = [i for i in infos if not i.is_dir()]
        data["entries"] = [{"name": i.filename, "size": i.file_size} for i in file_infos]
        names = [i.filename for i in file_infos]

        data["dex"] = sorted(n for n in names if re.fullmatch(r"classes\d*\.dex", Path(n).name))
        data["native_libraries"] = sorted(
            n for n in names if n.startswith("lib/") and n.endswith(".so")
        )
        data["abis"] = sorted({n.split("/")[1] for n in data["native_libraries"]})
        data["assets"] = sorted(n for n in names if n.startswith("assets/"))
        data["certificates"] = sorted(
            n for n in names if n.upper().startswith("META-INF/") and
            Path(n).suffix.upper() in {".RSA", ".DSA", ".EC", ".SF", ".MF"}
        )
        data["embedded_archives"] = sorted(
            n for n in names if Path(n).suffix.lower() in {".apk", ".dex", ".jar", ".zip"}
            and not re.fullmatch(r"classes\d*\.dex", Path(n).name)
        )

        for info in file_infos:
            suffix = Path(info.filename).suffix.lower()
            if info.file_size > 2 * 1024 * 1024:
                continue
            should_scan = (
                suffix in TEXT_SCAN_EXTENSIONS or
                suffix in {".dex", ".so"} or
                info.filename.startswith("assets/")
            )
            if not should_scan:
                continue
            try:
                blob = zf.read(info.filename)
            except Exception:
                continue
            lower = blob.lower()
            hits = [t.decode("ascii", errors="ignore") for t in INTERESTING_TERMS
                    if t.lower() in lower]
            if hits:
                data["interesting_hits"].append({
                    "entry": info.filename,
                    "terms": sorted(set(hits)),
                })
    return data


def extract_selected(apk: Path, out: Path, inventory: dict[str, Any]) -> None:
    dest = out / "work" / "extracted"
    dest.mkdir(parents=True, exist_ok=True)
    selected = set(inventory["native_libraries"])
    selected.update(inventory["dex"])
    selected.update(inventory["assets"])
    selected.update(inventory["certificates"])
    selected.add("AndroidManifest.xml")

    with zipfile.ZipFile(apk) as zf:
        names = set(zf.namelist())
        for name in sorted(selected & names):
            target = dest / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(zf.read(name))


def parse_package_from_outputs(tool_runs: list[dict[str, Any]]) -> tuple[str | None, str | None]:
    package = None
    launcher = None
    for run in tool_runs:
        text = (run.get("stdout") or "") + "\n" + (run.get("stderr") or "")
        if package is None:
            m = re.search(r"package:\s+name='([^']+)'", text)
            if not m:
                m = re.search(r"^([A-Za-z0-9_.]+)\s*$", text, re.MULTILINE)
            if m:
                package = m.group(1)
        if launcher is None:
            m = re.search(r"launchable-activity:\s+name='([^']+)'", text)
            if m:
                launcher = m.group(1)
    return package, launcher


def write_markdown(out: Path, result: dict[str, Any]) -> None:
    inv = result["inventory"]
    lines = [
        "# APK Triage",
        "",
        "## Sample",
        "",
        f"- Path: `{result['apk_path']}`",
        f"- SHA-256: `{result['sha256']}`",
        f"- Size: `{result['size_bytes']}` bytes",
        f"- Package: `{result.get('package') or 'unknown'}`",
        f"- Launcher: `{result.get('launcher') or 'unknown'}`",
        "",
        "## Inventory",
        "",
        f"- DEX: {', '.join(inv['dex']) or 'none'}",
        f"- ABIs: {', '.join(inv['abis']) or 'none'}",
        f"- Native libraries: {len(inv['native_libraries'])}",
        f"- Assets: {len(inv['assets'])}",
        f"- Embedded archives: {len(inv['embedded_archives'])}",
        "",
        "## Native libraries",
        "",
    ]
    lines.extend(f"- `{x}`" for x in inv["native_libraries"])
    lines.extend(["", "## Interesting term hits", ""])
    if inv["interesting_hits"]:
        for hit in inv["interesting_hits"]:
            lines.append(f"- `{hit['entry']}`: {', '.join(hit['terms'])}")
    else:
        lines.append("- none from baseline scan")
    lines.extend(["", "## Optional tools", ""])
    for run in result["tool_runs"]:
        lines.append(f"- `{Path(run['command'][0]).name}`: {run['status']}")
    lines.extend([
        "",
        "## Next actions",
        "",
        "1. Confirm package/launcher and component graph.",
        "2. Inspect JADX and smali outputs; do not trust either alone.",
        "3. Classify resource/java/state/runtime-token/JNI/native/packed routes.",
        "4. Create facts, hypotheses, and dead-end records.",
    ])
    (out / "triage.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path)
    parser.add_argument("--out", type=Path)
    parser.add_argument("--timeout", type=int, default=240)
    parser.add_argument("--skip-heavy", action="store_true",
                        help="skip JADX and Apktool")
    args = parser.parse_args()

    apk = args.apk.resolve()
    if not apk.is_file():
        parser.error(f"APK not found: {apk}")

    out = (args.out or Path("cases") / safe_case_id(apk.name)).resolve()
    for rel in ["work", "evidence/traces", "logs", "scripts"]:
        (out / rel).mkdir(parents=True, exist_ok=True)

    try:
        inventory = inventory_zip(apk)
    except zipfile.BadZipFile:
        print("error: input is not a valid APK/ZIP", file=sys.stderr)
        return 2

    extract_selected(apk, out, inventory)

    runs: list[dict[str, Any]] = []
    if not args.skip_heavy:
        runs.append(run_command(
            ["apktool", "d", "-f", str(apk), "-o", str(out / "work" / "apktool")],
            out, args.timeout
        ))
        runs.append(run_command(
            ["jadx", "-d", str(out / "work" / "jadx"), str(apk)],
            out, args.timeout
        ))

    # Lightweight metadata commands, attempted in order.
    runs.append(run_command(["apkanalyzer", "manifest", "application-id", str(apk)],
                            out, args.timeout))
    runs.append(run_command(["aapt2", "dump", "badging", str(apk)], out, args.timeout))
    runs.append(run_command(["aapt", "dump", "badging", str(apk)], out, args.timeout))
    runs.append(run_command(["keytool", "-printcert", "-jarfile", str(apk)],
                            out, args.timeout))

    logs_dir = out / "logs"
    for idx, run in enumerate(runs):
        name = Path(run["command"][0]).name if run.get("command") else f"tool-{idx}"
        (logs_dir / f"{idx:02d}-{name}.json").write_text(
            json.dumps(run, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8"
        )

    package, launcher = parse_package_from_outputs(runs)
    result = {
        "schema_version": 1,
        "apk_path": str(apk),
        "sha256": sha256_file(apk),
        "size_bytes": apk.stat().st_size,
        "package": package,
        "launcher": launcher,
        "inventory": inventory,
        "tool_runs": [
            {k: v for k, v in run.items() if k not in {"stdout", "stderr"}}
            for run in runs
        ],
    }

    (out / "case.json").write_text(
        json.dumps(result, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8"
    )
    write_markdown(out, result)

    print(json.dumps({
        "case_dir": str(out),
        "sha256": result["sha256"],
        "package": package,
        "launcher": launcher,
        "native_libraries": inventory["native_libraries"],
        "triage": str(out / "triage.md"),
    }, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
