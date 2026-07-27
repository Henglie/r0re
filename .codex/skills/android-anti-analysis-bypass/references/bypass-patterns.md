# Android Anti-Analysis Bypass Patterns

Use this file when analysis is blocked by packers, runtime loading, anti-debug, anti-hook, anti-root, anti-emulator, integrity checks, or encrypted code/resources.

OWASP MASTG source anchor: https://mas.owasp.org/MASTG/

## Protection Signals

| Protection | Static Signals | Dynamic Symptom | First Action |
| --- | --- | --- | --- |
| Dynamic dex | `DexClassLoader`, encrypted assets | jadx has only stubs | hook loader, dump dex |
| Native loader | `.init_array`, `mprotect`, `mmap` | code appears after run | dump after decrypt |
| Anti-debug | `Debug.isDebuggerConnected`, `ptrace`, `TracerPid` | exit under debugger | hook/patch specific check |
| Anti-Frida | `frida`, port scans, maps scans | exit when Frida attaches | hook scan APIs |
| Root check | `su`, `magisk`, root paths | refusal on rooted device | hook file/package checks |
| Emulator check | `Build.FINGERPRINT`, sensors, qemu props | refusal in emulator | hook Build/system props |
| Signature/integrity | `getPackageInfo`, cert digest | patched APK fails | hook package/signature APIs |

## Dump Targets

- Loaded dex after `DexClassLoader` or `InMemoryDexClassLoader`.
- Decrypted native image after `mmap/mprotect/memcpy`.
- Decrypted assets/scripts after file read/decrypt/write.
- Runtime strings after string decryptor returns.

## Narrow Bypass Rule

Patch or hook the smallest confirmed blocker:

1. Log the check.
2. Confirm the result blocks the path.
3. Modify only that result.
4. Verify the target code is reached.
5. Preserve logs and original code evidence.

## Common Java Hooks to Consider

- `android.os.Debug.isDebuggerConnected`
- `java.io.File.exists`
- `android.os.Build` fields
- `android.provider.Settings.Secure.getString`
- `android.content.pm.PackageManager.getPackageInfo`
- `dalvik.system.DexClassLoader`
- `java.lang.ClassLoader.loadClass`

## Common Native Hooks to Consider

- `ptrace`
- `open`, `openat`, `read`, `access`, `stat`
- `mmap`, `mprotect`, `memcpy`
- `dlopen`, `android_dlopen_ext`
- `strstr`, `strcmp`, `memcmp` when filtered carefully

## Evidence Template

```markdown
| Check | Location | Original result | Bypass | Verified effect |
| --- | --- | --- | --- | --- |
| ... | ... | ... | ... | ... |
```
