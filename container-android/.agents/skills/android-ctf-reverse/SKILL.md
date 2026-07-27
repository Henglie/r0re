---
name: android-ctf-reverse
description: Analyze Android APK reverse-engineering CTF challenges with Java/JNI/native/crypto evidence-chain verification.
---

# Android CTF Reverse Skill

Use this skill when the task is to reverse an Android APK, especially when the APK includes native `.so` libraries, JNI, crypto, packing, self-modifying code, or suspicious strings.

## Workflow

1. Inventory the APK archive.
2. Inspect `AndroidManifest.xml`, `classes.dex`, resources, assets, and native libraries.
3. Find Java/Kotlin entry points, UI handlers, `System.loadLibrary`, and native method declarations.
4. Resolve JNI symbols in native libraries.
5. Trace from native entry to final check/compare.
6. Extract constants and offsets.
7. Reimplement the validation in Python.
8. Prove the candidate input by running the solver.
9. Write the report and explicitly mark decoys.

## Mandatory checks

- A string in DEX or native strings is only a candidate until it is proven to reach the final compare.
- Morse/Base64/MD5-like values are common decoys; do not stop there.
- A final answer must include a solver path and successful verification output.
- If native code initializes a fixed-size buffer before copying input, preserve the untouched suffix bytes.

## Preferred command sequence

```bash
python3 tools/android/android_ctf_runner.py challenge.apk --workspace out
jadx -d out/jadx challenge.apk || true
apktool d -f challenge.apk -o out/apktool || true
readelf -Ws out/extract/lib/*/*.so || true
strings -a out/extract/classes.dex | head || true
```

## Output format

```text
final_input = ...
flag = flag{...}
verification = passed
report = out/report.md
```
