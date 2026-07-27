# Evidence and Verification Rules

## Confidence

- `1.0`: directly observed in bytecode, disassembly, or runtime trace.
- `0.8`: deterministic inference supported by multiple facts.
- `0.5`: plausible hypothesis awaiting a targeted test.
- `<0.5`: weak lead; do not build final conclusions on it.

## Provenance levels

```text
P0 string-only
P1 reachable code path
P2 deterministic solver
P3 independent algorithm verifier
P4 original APK accepted in emulator/device
P5 fresh-run token/state proof
```

A normal static CrackMe should reach at least `P3`.
A state/token-dependent challenge should reach `P4` or preferably `P5`.

## Independent verifier patterns

Good:

- solver decrypts target; verifier re-encrypts and compares;
- solver derives path; verifier feeds candidate to original APK;
- solver implements algorithm in Python; verifier implements comparison independently;
- solver derives state sequence; verifier replays it and observes success.

Weak/not independent:

- verifier imports solver and checks `candidate == candidate`;
- verifier only checks flag syntax;
- verifier uses the same constants and same function without another property;
- verifier checks a patched APK.

## Evidence artifact requirements

Each decisive claim should point to one of:

```text
source/smali file and line
native function RVA/file offset
runtime trace with timestamp
memory dump and hash
solver output
verifier output
emulator/device screenshot or log
```
