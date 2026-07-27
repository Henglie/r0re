# Android CTF Deliverable Templates

Use this file when packaging a final or partial Android CTF solution.

## Full Solve Template

````markdown
# <Challenge> Android CTF Solution

## Result

- Flag:
- Verification:

## Target

- APK:
- SHA256:
- Package:
- Framework/engine:
- ABI/native libs:

## Flag Path

1. Entry:
2. Input source:
3. Transform:
4. Native/script/network step:
5. Final formatter:

## Evidence

| Claim | Evidence |
| --- | --- |
| ... | ... |

## Reproduction

```sh
...
```

## Solver

```python
...
```

## Notes

- ...
```
````

## Partial Solve Template

````markdown
# <Challenge> Android CTF Partial Analysis

## Current Status

- Confirmed:
- Not confirmed:
- Blocker:

## Evidence

| Fact | Evidence | Confidence |
| --- | --- | --- |
| ... | ... | confirmed/inferred |

## Next Action

1. ...
2. ...
```
````

## Cairn Fact Style

Good fact:

```text
GameExtension.Process is registered through RegisterNatives at libsec2026.so offset 0x517e8; Java/Godot callers pass an 8-byte PackedByteArray. Evidence: analysis/i017_process_methodbind_findings.txt.
```

Bad fact:

```text
The algorithm is probably MD5 because strings mention md5.
```

## Script Packaging Checklist

- Use deterministic inputs.
- Print the final flag or computed part plainly.
- Include test vector comments.
- Keep external dependencies explicit.
- Do not require the reader to manually copy intermediate values from a GUI if a command can produce them.

## Patch Packaging Checklist

- Original method/function.
- Original instruction(s).
- Patched instruction(s).
- Build/sign/install commands.
- Whether patch is a solve, bypass, or observation aid.
