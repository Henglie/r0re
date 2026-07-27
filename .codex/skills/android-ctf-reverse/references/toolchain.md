# Toolchain Guide

## Core

- `adb`: device/emulator communication, installation, shell, logs.
- `jadx`: APK/DEX to Java-like source and resource inspection.
- `apktool`: resource decoding and smali disassembly/rebuild.
- `apkanalyzer` or `aapt/aapt2`: manifest/package inspection.
- `keytool`: signing certificate inspection.
- `readelf`, `nm`, `objdump`, `strings`: ELF baseline.
- Ghidra or IDA: native disassembly/decompilation.
- Frida: Java and Native runtime instrumentation.

## Optional

- `rizin`/`radare2`: scripting and binary inspection.
- `angr`: focused symbolic execution for native validation slices.
- `Unicorn`: emulation of isolated native routines.
- `lief`: ELF repair and program-header inspection.
- `androguard`: scripted APK/DEX analysis.
- `apk-mitm`, custom dumpers: only for authorized packed samples.

## Selection rule

Use the smallest tool that answers the current hypothesis. Do not start symbolic execution
or full instruction tracing when a resource reference or one method hook is sufficient.

## Version handling

Do not hardcode tool versions in reports. Record the actual versions used:

```bash
adb version
jadx --version
apktool --version
frida --version
python3 --version
```
