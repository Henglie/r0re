# Android Game CTF Engine Patterns

Use this file after triage identifies Unity, Godot, Cocos2d-x, Unreal, or a custom game engine.

## Engine Signals

| Engine | Files | First Analysis Target |
| --- | --- | --- |
| Unity Il2Cpp | `libil2cpp.so`, `global-metadata.dat` | metadata dump, game classes |
| Godot | `libgodot_android.so`, `.pck`, `.gd`, `.gdc`, `.tscn` | pck/scripts/scenes, GDExtension |
| Cocos2d-x | `libcocos2dcpp.so`, Lua assets | Lua scripts/loaders, C++ bindings |
| Unreal | `libUE4.so`, `.pak` | pak/config/resources before native |
| Custom | game-specific `.so` + assets | resource loader and native plugin |

## Unity Route

1. Extract `libil2cpp.so` and `global-metadata.dat`.
2. Recover class/method names with local Il2Cpp tooling when available.
3. Search class/method names: `Flag`, `Check`, `Input`, `GameManager`, `Login`, `Secret`.
4. Inspect UI input flow and manager/update methods.
5. Route native algorithm fragments to `android-native-jni-reverse`.

## Godot Route

1. Identify Godot version from strings or library metadata.
2. Extract `.pck` and list scenes/scripts.
3. If scripts are encrypted, search native library and resources for key-loading paths.
4. Inspect `.tscn` node tree for labels, buttons, scripts, and autoloads.
5. Map GDScript calls to GDExtension methods.
6. For GDExtension, recover method names, signatures, MethodBind callbacks, and native offsets.

## Cocos2d-x Route

1. Locate Lua files, `.luac`, or packed resources.
2. Hook script load/decrypt if plaintext scripts are unavailable.
3. Search Lua for UI text, `flag`, `check`, `token`, `encrypt`, network calls.
4. Map Lua binding functions to `libcocos2dcpp.so`.

## Unreal Route

1. Extract `.pak` when possible.
2. Search config/resource strings before reversing `libUE4.so`.
3. Identify game-specific modules or blueprint clues.
4. Use native reversing only after narrowing the target.

## Game-to-Native Evidence Template

```markdown
| Layer | Element | Evidence | Next |
| --- | --- | --- | --- |
| UI | input/label/button | scene/script | ... |
| Script | function | script file | ... |
| Binding | method name/signature | metadata/registration | ... |
| Native | offset/function | Ghidra/Frida | ... |
| Output | flag formatter | script/native/log | ... |
```

## Pitfall Avoidance

- Engine libraries are huge; prefer game-specific scripts, resources, metadata, and plugins first.
- Encrypted game scripts usually mean the loader/key path is the real early target.
- UI text often gives the fastest route to the script or scene.
