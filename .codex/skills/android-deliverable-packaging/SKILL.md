---
name: android-deliverable-packaging
description: Package Android CTF analysis into reproducible deliverables. Use when the flag path, algorithm, patch, hook, unidbg harness, or dynamic evidence has been found and Codex must produce a clean report, solver script, Frida script, offsets table, patch notes, reproduction steps, or Cairn facts suitable for future skills/writeups.
---

# Android CTF Deliverable Packaging

## Objective

Turn Android CTF findings into a reproducible artifact that another analyst or future Codex run can verify without redoing the entire investigation.

## References

Read `references/report-templates.md` when producing a full solve, partial solve, Cairn fact, solver package, or patch package.

## Required Output

- Executive result: flag or current status.
- Target summary: APK name, package, hashes when available, engine/framework, ABI.
- Flag path: input -> Java/Smali/script/native/network/resource chain.
- Evidence table: files, classes, methods, offsets, strings, logs, screenshots if relevant.
- Reproduction steps: exact commands and environment assumptions.
- Artifacts: solver, patch, Frida script, unidbg harness, extracted/decrypted resources.
- Open questions: only unresolved items that affect correctness.

## Workflow

1. Separate result types:
   - Full solve: flag and verified generation algorithm.
   - Partial solve: confirmed route and remaining blocker.
   - Bypass solve: patch/hook produces accepted behavior but original algorithm may be unrecovered.
   - Tooling deliverable: harness or script prepared for further analysis.

2. Package evidence:
   - Link every claim to a file, method, offset, command output, or runtime log.
   - Mark inferences clearly.
   - Avoid presenting guessed constants or untested algorithms as confirmed.

3. Package scripts:
   - Keep solver scripts deterministic and dependency-light.
   - Include sample input/output.
   - For Frida scripts, include package name, spawn/attach command, and expected log markers.
   - For unidbg harnesses, include ABI, target function, setup command, and known limitations.

4. Package patch notes:
   - Record original and patched instructions.
   - Explain whether the patch bypasses, reveals, or solves.
   - Include rebuild/sign/install commands when applicable.

5. Package Cairn facts when used:
   - Convert important discoveries into concise facts.
   - Include paths to evidence files.
   - Distinguish concluded tasks from active or failed tasks.

## Report Template

```markdown
# Android CTF Solution: <name>

## Result

<flag/status>

## Target

- Package:
- APK hash:
- Framework/engine:
- Native ABI:

## Flag Path

1. ...
2. ...

## Evidence

| Claim | Evidence |
| --- | --- |
| ... | ... |

## Reproduction

```sh
...
```

## Artifacts

- Solver:
- Frida:
- Patch:
- Harness:

## Open Questions

- ...
```

## Stop Conditions

Stop when a clean reader can reproduce the result from the package. If that is not possible, label the deliverable as partial and state the missing evidence.
