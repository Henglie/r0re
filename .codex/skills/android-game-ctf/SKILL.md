---
name: android-game-ctf
description: Analyze Android game CTFs built with Unity, Godot, Cocos2d-x, Unreal, or custom engines. Use when an APK contains libil2cpp.so, global-metadata.dat, libgodot_android.so, Godot pck/scripts/GDExtension, libcocos2dcpp.so, Lua assets, libUE4.so, pak files, encrypted game resources, script-bytecode checks, or engine-to-native flag paths.
---

# Android Game CTF

## Objective

Recover the flag path across game resources, scripts, engine bindings, and native extensions. Treat the engine as part of the attack surface, not as a black box.

## References

Read `references/game-engine-patterns.md` when you need Unity, Godot, Cocos2d-x, Unreal, or custom engine signals, route checklists, or a game-to-native evidence template.

## Required Output

- Engine classification and version when possible.
- Resource inventory: scripts, scenes, metadata, packs, native plugins, suspicious assets.
- Flag path: UI/input -> script/component -> engine binding -> native or resource logic.
- Decryption/extraction steps for scripts/resources.
- Follow-up route: Java/Smali, native JNI, Frida, unidbg, or anti-analysis.

## Workflow

1. Classify the engine:
   - Unity: `libil2cpp.so`, `global-metadata.dat`, `assets/bin/Data`, UnityFS.
   - Godot: `libgodot_android.so`, `.pck`, `.gd`, `.gdc`, `.tscn`, `.res`, GDExtension libs.
   - Cocos2d-x: `libcocos2dcpp.so`, Lua scripts, `.luac`, resource packs.
   - Unreal: `libUE4.so`, `.pak`, UE config/layout.
   - Custom: engine-like native library plus custom resources.

2. Inventory resources before deep native reversing:
   - List large archives and script-like files.
   - Extract readable strings from resources and native plugins.
   - Search for `flag`, `token`, `check`, `secret`, `encrypt`, scene names, button labels, and UI text.
   - Identify whether resources are plaintext, compiled, compressed, or encrypted.

3. Engine-specific routes:
   - Unity: dump Il2Cpp metadata, recover class/method names, inspect input and manager classes.
   - Godot: extract pck, recover/decrypt GDScript, inspect scenes, then map GDExtension calls.
   - Cocos2d-x: recover Lua scripts or hook script decryption/loading, then inspect C++ bindings.
   - Unreal: inspect pak/config/blueprint clues, then native `libUE4.so` only where needed.

4. Connect script to native:
   - Identify script calls into native plugins or engine extension methods.
   - Recover method names, signatures, and binding registration.
   - Route native callbacks to `android-native-jni-reverse` or `android-unidbg-harness`.

5. Use dynamic analysis when static resources are insufficient:
   - Hook script loading/decryption.
   - Hook engine method binding or native extension calls.
   - Log UI input, token generation, resource keys, and final formatter output.

## Game CTF Pitfalls

- Do not assume Java Activity code contains the game logic.
- Do not start with the largest engine library unless evidence points there.
- Do not treat encrypted scripts as absent; find the loader or key path.
- For Godot/Unity, distinguish engine code from game-specific extension/plugin code.

## Stop Conditions

Stop when the game-level flag path is mapped and the remaining work belongs to script decryption, native reversing, Frida tracing, or deliverable packaging.
