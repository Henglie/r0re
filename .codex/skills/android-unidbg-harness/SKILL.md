---
name: android-unidbg-harness
description: Build unidbg harnesses for Android native CTF algorithms. Use when a JNI/native .so function has clear inputs and outputs, needs offline execution, brute force, batch testing, signature reproduction, Android API stubbing, or when Frida/static analysis identified a native target that can be called outside the APK.
---

# Android unidbg Harness

## Objective

Create a reproducible offline native execution harness for Android CTF functions. Use unidbg when dynamic device execution is slow, noisy, or unsuitable for brute force.

## References

Read `references/unidbg-harness-patterns.md` when deciding whether unidbg is a good fit, planning the harness, listing JNI stubs, debugging load/call failures, or packaging harness evidence.

## Required Output

- Target library, ABI, function name/offset, JNI signature, and call path.
- Harness project structure and exact run command.
- Stubbed Android/JNI dependencies.
- Test vectors and observed output.
- Limitations: unsupported syscalls, missing dependencies, anti-emulation, unresolved callbacks.

## Workflow

1. Confirm unidbg is appropriate:
   - The target function has stable input/output.
   - Required app state can be stubbed or reconstructed.
   - Native dependencies are available or can be mocked.
   - The task benefits from repeated calls, brute force, or signature reproduction.

2. Prepare artifacts:
   - Extract the correct ABI `.so`.
   - Collect dependent libraries if needed.
   - Record Java class name, native method name, JNI signature, and package name.
   - Record any constants, keys, assets, or device values used by the function.

3. Build the harness:
   - Load the library with the matching emulator architecture.
   - Initialize JNI and call `JNI_OnLoad` when required.
   - Create Java strings, byte arrays, objects, or arrays matching the real method signature.
   - Stub Android APIs only as narrowly as needed.

4. Validate incrementally:
   - First confirm the library loads.
   - Then confirm `JNI_OnLoad` succeeds.
   - Then call a simple exported or registered function.
   - Then call the target with one known input.
   - Only then add brute force or batch loops.

5. Debug failures:
   - Missing class/method: add a targeted JNI stub.
   - Unsupported syscall: inspect whether it is essential or anti-analysis.
   - Crash before target call: inspect constructors and loader code.
   - Wrong output: compare Frida runtime arguments against harness inputs.

## Harness Evidence Checklist

- ABI and emulator architecture match.
- Library base, target offset, and method signature are documented.
- Input encoding matches Android runtime behavior.
- Output is verified against one real app trace whenever possible.
- The final solver can run from a clean checkout with stated dependencies.

## Stop Conditions

Stop when the target native function is callable and produces verified output, or when missing runtime state makes Frida/manual native debugging the better next step.
