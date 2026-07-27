# Android CTF Triage Matrix

Use this file when an APK needs first-pass classification or a Cairn task needs routing.

## Source Anchors

- Frida Android workflow: https://frida.re/docs/android/
- OWASP MASTG Android testing guide: https://mas.owasp.org/MASTG/
- CTF Wiki reverse basics: https://ctf-wiki.org/
- unidbg project: https://github.com/zhkl0228/unidbg

## Fast Inventory Commands

```sh
unzip -l target.apk | sed -n '1,120p'
unzip -l target.apk | grep -E '(^|/)(classes[0-9]*\.dex|lib/.+\.so|assets/|res/raw/)'
aapt dump badging target.apk
aapt dump xmltree target.apk AndroidManifest.xml
jadx -d jadx-out target.apk
apktool d -f target.apk -o apktool-out
find jadx-out apktool-out -type f | wc -l
```

If tools differ in the environment, use equivalent commands and record what was used.

## Classification Signals

| Signal | Likely Category | Route |
| --- | --- | --- |
| Readable Activity logic, string checks | Java/Kotlin algorithm | android-java-smali-reverse |
| `native` methods or `System.loadLibrary` | JNI/native algorithm | android-native-jni-reverse |
| `JNI_OnLoad`, no exported JNI names | Dynamic JNI registration | android-native-jni-reverse, android-frida-dynamic |
| Sparse app code, stub activity | Packer/dynamic dex | android-anti-analysis-bypass |
| `DexClassLoader`, `InMemoryDexClassLoader` | Dynamic code loading | android-anti-analysis-bypass |
| URL/sign/token/timestamp/nonce | Protocol signing | Java/Frida workflow |
| `libil2cpp.so`, `global-metadata.dat` | Unity | android-game-ctf |
| `libgodot_android.so`, `.pck`, `.gd`, `.tscn` | Godot | android-game-ctf |
| `libcocos2dcpp.so`, Lua files | Cocos2d-x | android-game-ctf |
| `libUE4.so`, `.pak` | Unreal | android-game-ctf |
| `libflutter.so`, `libapp.so` | Flutter | triage, then framework-specific reversing |
| JS bundle, WebView assets | React Native / Hybrid | Java/asset analysis |
| `ptrace`, `TracerPid`, `frida`, `xposed` | Anti-analysis | android-anti-analysis-bypass |

## Search Sets

Run targeted searches before deep reverse:

```sh
rg -n "flag|ctf|check|verify|token|secret|sign|encrypt|decrypt|native|loadLibrary" jadx-out apktool-out
rg -n "DexClassLoader|InMemoryDexClassLoader|PathClassLoader|loadClass|defineClass" jadx-out apktool-out
rg -n "frida|xposed|magisk|root|su|TracerPid|ptrace|emulator|genymotion" jadx-out apktool-out
strings -a lib/*/*.so | rg "flag|check|verify|JNI_OnLoad|RegisterNatives|frida|ptrace|TracerPid|%02x|md5|sha|aes"
```

## Routing Output Template

````markdown
## Triage Result

- Primary route:
- Secondary route:
- Confidence:
- Reason:

## Evidence

| Finding | Evidence | Next action |
| --- | --- | --- |
| ... | ... | ... |

## Immediate Commands

```sh
...
```
````

## Common Mistakes

- Do not spend hours in `libgodot_android.so`, `libUE4.so`, or `libil2cpp.so` before checking game-specific resources and plugins.
- Do not assume a stripped native library means dynamic registration; prove it through `JNI_OnLoad`, `RegisterNatives`, or Frida logs.
- Do not bypass root/debug checks before documenting what they block.
