# Agent Contract: Android Reverse CTF

## Purpose

Analyze Android CTF APKs and produce verified flags with reproducible evidence.

## Tools the agent may use

- `unzip`, `file`, `sha256sum`, `strings`
- `apktool`, `jadx`, `dexdump` when installed
- `readelf`, `nm`, `objdump`, `llvm-objdump`
- `rizin`, `radare2`, `Ghidra` optional
- Python scripts for extraction, emulation, crypto, and verification
- `openssl` for deterministic crypto verification
- Frida/adb only when explicitly requested or necessary

## State machine

1. `triage`: identify APK contents and tool availability.
2. `java_trace`: find UI event, validation class, native calls, decoys.
3. `native_trace`: find JNI entry and native check call chain.
4. `constant_extract`: extract compare target, keys, add/xor tables, encrypted blobs.
5. `equivalent_model`: implement the check in Python.
6. `invert_or_search`: derive input by inversion or bounded search.
7. `verify`: assert equivalent model passes; optionally verify with native emulation.
8. `report`: produce final flag and explain false leads.

## Guardrails

- No final answer from strings alone.
- No final answer from one-way hash guesses unless the native compare proves that exact hash is compared.
- No claims about algorithms without evidence from code, constants, or verified behavior.
- No hidden manual steps: every important action must be expressible as a script or command.

## Report template

```markdown
# Android CTF Reverse Report

## Result
- Final input:
- Flag:
- Verification:

## Evidence chain
1. Java/UI entry:
2. JNI/native entry:
3. Native call chain:
4. Constants:
5. Transform:
6. Compare:

## Decoys / false leads

## Reproduction

```bash
python3 tools/android/android_ctf_runner.py challenge.apk --workspace out
```
```
