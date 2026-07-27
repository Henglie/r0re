---
name: android-anti-analysis-bypass
description: Identify and bypass Android CTF anti-analysis protections. Use when an APK uses packers, dynamic dex loading, encrypted assets, self-decrypting native code, anti-debug, anti-Frida, anti-Xposed, anti-root, anti-emulator, TracerPid checks, ptrace, integrity checks, signature checks, or runtime-only code/resource recovery.
---

# Android Anti Analysis Bypass

## Objective

Remove or observe protections only as much as needed to continue reversing. Prefer evidence-driven bypasses over broad patches.

## References

Read `references/bypass-patterns.md` when you need protection signals, dump targets, narrow bypass rules, hook candidates, or bypass evidence templates.

## Required Output

- Protection inventory with evidence.
- Blocking effect: what analysis step fails because of it.
- Bypass plan: patch, Frida hook, environment change, dump, or manual debug.
- Verification: app runs, dex/so/resource dumped, check bypassed, or target logic reached.
- Residual risk: remaining checks or unverified patches.

## Workflow

1. Identify protection signals:
   - Java: `Debug.isDebuggerConnected`, `Build.*`, root paths, package manager checks, signature checks, `DexClassLoader`.
   - Native: `ptrace`, `/proc/self/status`, `TracerPid`, `prctl`, `syscall`, `dlopen`, anti-Frida strings, checksum loops.
   - Assets: encrypted dex/jar/so/scripts/resources, staged extraction, suspicious large blobs.
   - Behavior: crash only under debugger, blank jadx output, runtime-loaded code, emulator-only failure.

2. Decide the bypass type:
   - Static patch when the branch is obvious and stable.
   - Frida hook when the value is runtime/device dependent.
   - Dump when code/resources are decrypted or loaded only at runtime.
   - Environment adjustment when the app expects device properties, files, or permissions.

3. Handle dynamic dex:
   - Locate `DexClassLoader`, `PathClassLoader`, `InMemoryDexClassLoader`, or reflection-heavy loading.
   - Hook class loading and file writes.
   - Dump loaded dex and rerun Java/Smali analysis on the recovered code.

4. Handle native self-decryption or packers:
   - Inspect `.init_array`, constructors, and `JNI_OnLoad`.
   - Watch `mmap`, `mprotect`, `memcpy`, `dlopen`, file extraction, and executable page changes.
   - Dump memory after decryption and before the target function runs.
   - Rebase or annotate dumped images before claiming offsets.

5. Handle anti-Frida/root/emulator:
   - Hook string/file checks narrowly.
   - Patch comparison results only after confirming the check.
   - Avoid hiding failures by returning success everywhere; it can corrupt later logic.

## Bypass Decision Table

| Symptom | Likely Cause | First Action |
| --- | --- | --- |
| jadx shows only stub app | packer/dynamic dex | find loader and dump dex |
| native strings absent until runtime | self-decryption | hook decryptor or dump memory |
| app exits under Frida | anti-Frida | hook detection strings/files/process scans |
| app exits on emulator | environment check | hook Build/device APIs |
| native function pointer unknown | dynamic registration | hook RegisterNatives |

## Stop Conditions

Stop once the protected code/resource is recovered or the blocking check is bypassed. Route the recovered target back to the relevant reversing skill.
