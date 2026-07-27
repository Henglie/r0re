---
name: android-ctf-triage
description: Classify Android CTF APKs and choose a concrete analysis route. Use when Codex receives an APK, XAPK, decompiled Android project, mobile CTF writeup corpus, or Cairn task and must decide whether the challenge is Java/Kotlin, Smali patching, JNI/native, packed/dynamic dex, protocol signing, Flutter/React Native, Android component/IPC, local storage, anti-analysis, or game-engine reversing.
---

# Android CTF Triage

## Objective

Perform the first-pass classification of an Android CTF target and route it to the right specialized workflow. Do not attempt deep reversing before producing a triage table and next-action plan.

## References

Read `references/triage-matrix.md` when you need concrete inventory commands, classification signals, search sets, or a routing output template.

## Required Output

Always produce:

- APK identity: package name, app label, min/target SDK, main/exported components.
- File map: dex count, native libraries, assets/raw/res anomalies, large archives, network strings.
- Classification: primary category, secondary category, confidence, evidence.
- Routing: which follow-up skill(s) to use and why.
- Immediate commands: concrete next commands or scripts to run.
- Risk notes: packer, anti-debug, missing assets, emulator/device requirement, server dependency.

## Workflow

1. Inventory the target before decompiling deeply:
   - List APK contents with `unzip -l` or equivalent.
   - Read `AndroidManifest.xml` through apktool/aapt/jadx output.
   - List `classes*.dex`, `lib/*/*.so`, `assets/`, `res/raw/`, large unknown files.

2. Identify technology signals:
   - Java/Kotlin: readable app classes, button handlers, string checks.
   - JNI/native: `System.loadLibrary`, `native` methods, `JNI_OnLoad`, `RegisterNatives`, `lib*.so`.
   - Unity: `libil2cpp.so`, `global-metadata.dat`, `assets/bin/Data`.
   - Godot: `libgodot_android.so`, `.pck`, `.gd`, `.gdc`, `.tscn`, GDExtension libraries.
   - Cocos2d-x: `libcocos2dcpp.so`, Lua scripts, encrypted `.luac`, resource packs.
   - Unreal: `libUE4.so`, `.pak`.
   - Flutter: `libflutter.so`, `libapp.so`, Dart snapshot artifacts.
   - React Native / Hybrid: JS bundle, WebView, HTML/JS assets.
   - Packer/dynamic loading: sparse jadx output, `DexClassLoader`, encrypted dex/assets, heavy stub classes.

3. Search high-value strings:
   - `flag`, `ctf`, `check`, `verify`, `encrypt`, `decrypt`, `sign`, `token`, `secret`, `native`.
   - `frida`, `xposed`, `root`, `magisk`, `ptrace`, `TracerPid`, `emulator`.
   - URLs, hostnames, API paths, request signing keywords.

4. Route conservatively:
   - Use `android-java-smali-reverse` when the check is visible in Java/Kotlin/Smali.
   - Use `android-native-jni-reverse` when JNI/native functions likely hold the algorithm.
   - Use `android-frida-dynamic` when runtime values, hooks, or bypasses are needed.
   - Use `android-unidbg-harness` when a native function has clear inputs/outputs and can be called offline.
   - Use `android-game-ctf` when Unity/Godot/Cocos/Unreal signals are present.
   - Use `android-anti-analysis-bypass` when packers, self-decryption, anti-debug, anti-hook, anti-root, or dynamic dex block analysis.
   - Use `android-deliverable-packaging` when the flag path is understood and needs a reproducible report.

## Triage Table Template

```markdown
| Area | Finding | Evidence | Route |
| --- | --- | --- | --- |
| Package | ... | AndroidManifest.xml | ... |
| Java layer | ... | jadx search | android-java-smali-reverse |
| Native layer | ... | lib/arm64-v8a/*.so | android-native-jni-reverse |
| Assets | ... | assets listing | android-game-ctf / anti-analysis |
| Network | ... | URL strings | protocol analysis |
| Protection | ... | strings/API use | android-anti-analysis-bypass |
```

## Stop Conditions

Stop triage and switch skills when a strong route is found. Do not spend time fully solving during triage unless the flag is immediately visible.
