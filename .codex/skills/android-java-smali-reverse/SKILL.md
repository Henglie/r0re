---
name: android-java-smali-reverse
description: Reverse Android CTF checks implemented in Java, Kotlin, or Smali. Use when jadx/apktool output contains visible flag validation, button handlers, string transforms, Base64/hash/crypto/XOR logic, Android component triggers, or when the task requires patching Smali, bypassing Java-layer checks, rebuilding, signing, and producing a solver script.
---

# Android Java Smali Reverse

## Objective

Recover or bypass Java/Kotlin/Smali-layer flag logic with reproducible evidence. Prefer solving the algorithm; patch only when the challenge allows behavioral bypass or when patching is the fastest way to observe the next stage.

## References

Read `references/java-smali-patterns.md` when you need search queries, common Java/Kotlin algorithm shapes, Smali patch recipes, rebuild commands, or evidence templates.

## Required Output

- Entry chain: Activity/component -> handler -> check function.
- Key files/classes/methods with line or method references.
- Algorithm summary in plain language.
- Solver script or patched Smali diff when applicable.
- Verification method: expected input/output, rebuilt APK behavior, or script output.

## Workflow

1. Locate execution entry:
   - Inspect `AndroidManifest.xml` for launcher and exported components.
   - Search jadx output for UI handlers, `onClick`, `TextWatcher`, `Intent` extras, deep links, and provider calls.
   - Search strings: `flag`, `check`, `verify`, `login`, `token`, `secret`, `encrypt`, `decrypt`, `md5`, `sha`, `base64`.

2. Recover the check:
   - Follow data flow from user input to comparison.
   - Normalize Kotlin artifacts such as companion objects, intrinsics, lambdas, and synthetic methods.
   - Identify encodings, hashes, symmetric crypto, XOR loops, array permutations, custom alphabets, and table lookups.
   - Watch for staged checks where Java prepares input for native or network logic.

3. Reproduce before patching:
   - Translate the check into Python or JavaScript.
   - Add test vectors from constants or observed values.
   - If a comparison target is hashed, solve only when enough constraints exist; otherwise route to dynamic analysis.

4. Patch when needed:
   - Patch boolean return functions by changing conditional branches or return constants.
   - Patch string comparisons by forcing expected value paths.
   - Keep the diff minimal and record original instructions.
   - Rebuild, sign, and test when the environment permits.

5. Escalate appropriately:
   - Use `android-native-jni-reverse` if logic crosses a native method.
   - Use `android-frida-dynamic` if runtime values are hidden or device-only.
   - Use `android-anti-analysis-bypass` if root/debug/emulator checks block execution.

## Smali Patch Checklist

- Confirm target method is actually reached.
- Patch the smallest branch or return point.
- Preserve register counts unless editing method structure intentionally.
- Rebuild and install with a fresh signature.
- Document whether the patch solves the challenge or only reveals the next stage.

## Stop Conditions

Stop when the flag algorithm is reproduced, the required patch is verified, or the remaining unknown logic moves to native/runtime/protected code.
