# unidbg Harness Patterns

Use this file when a native algorithm should be called offline.

Source anchor: https://github.com/zhkl0228/unidbg

## Good Fit Checklist

- Target is in a challenge-specific `.so`, not only in a huge engine runtime.
- Java/JNI signature is known or recoverable.
- Inputs are strings, byte arrays, ints, or simple objects.
- Required Android APIs can be stubbed.
- One runtime trace or expected output is available for validation.

## Poor Fit Signals

- Function depends on large engine state, GL/audio/input loops, Binder services, or network callbacks.
- Heavy anti-emulation or self-modifying loader runs before target.
- Required object graph is unknown and huge.

## Harness Plan Template

```markdown
## unidbg Plan

- Library:
- ABI:
- Java class:
- Method/signature:
- Native offset:
- Required files/assets:
- Required stubs:
- Test vector:
- Expected output:
```

## JNI Stub Inventory

Stub narrowly. Common stubs:

- `android/content/Context->getPackageName`
- `android/content/pm/PackageManager`
- `android/os/Build`
- `java/lang/String`
- `getFilesDir`, `getAssets`, asset reads
- app-specific methods used by the native function

## Debug Order

1. Load library.
2. Call `JNI_OnLoad`.
3. Resolve target method.
4. Call with simplest input.
5. Add stubs only for the missing call shown in the error.
6. Compare output against Frida/app trace.
7. Add brute force/batch loop last.

## Evidence to Preserve

- Exact `.so` path and hash.
- unidbg dependency version.
- ABI and emulator type.
- Method signature and input encoding.
- All stubs and why they are safe.
- Known mismatch versus real device if any.
