---
name: android-native-jni-reverse
description: Reverse JNI and native .so algorithms in Android CTFs. Use when the APK calls System.loadLibrary, declares native methods, uses JNI_OnLoad/RegisterNatives, contains stripped ARM/ARM64 libraries, hides flag checks in native code, or requires mapping Java calls to native functions and reproducing the native algorithm.
---

# Android Native JNI Reverse

## Objective

Recover the native flag algorithm or enough callable structure to move into dynamic tracing or offline invocation. Keep Java/JNI/native evidence tied together.

## References

Read `references/jni-native-patterns.md` when you need JNI mapping commands, type reminders, dynamic registration templates, native CTF pattern checks, or evidence table formats.

## Required Output

- Java native declaration and call site.
- Library name, ABI, architecture, exported symbols, and protection notes.
- JNI binding map: static export or `RegisterNatives` mapping.
- Candidate native functions with offsets and evidence.
- Algorithm reconstruction, solver script, or reason to route to Frida/unidbg.

## Workflow

1. Map Java to native:
   - Search Java for `System.loadLibrary`, `native`, and wrapper methods.
   - Identify parameter and return types.
   - Check for static JNI exports like `Java_pkg_Class_method`.
   - If exports are missing, analyze `JNI_OnLoad` and `RegisterNatives`.

2. Inventory the library:
   - Record ABI, bitness, compiler hints, stripped status, sections, imports, exports, and suspicious strings.
   - Search strings for `flag`, `check`, `verify`, `md5`, `sha`, `aes`, `base64`, `%02x`, `RegisterNatives`, anti-analysis markers.
   - Inspect `.init_array`, constructors, and loader code before assuming the file is plain.

3. Reverse candidate functions:
   - Normalize JNI boilerplate: `GetStringUTFChars`, `NewStringUTF`, `GetByteArrayElements`, `SetByteArrayRegion`, object/class lookups.
   - Track input transformations, constants, tables, loops, branches, and comparisons.
   - Separate algorithm code from Android/JNI plumbing.
   - Rebuild the algorithm in Python with test vectors when possible.

4. Handle dynamic registration:
   - Find the `JNINativeMethod` array.
   - Recover method name, signature, and function pointer.
   - Convert pointers to file offsets or image-relative offsets.
   - Cross-reference Java call sites to native callbacks.

5. Escalate when needed:
   - Use `android-frida-dynamic` when runtime keys, decrypted strings, or call arguments are needed.
   - Use `android-unidbg-harness` when the target function is callable with stable inputs.
   - Use `android-anti-analysis-bypass` when loader, packer, self-decryption, or anti-debug blocks static analysis.

## Evidence Discipline

Always distinguish:

- Confirmed facts: backed by code, offsets, traces, or reproduced output.
- Inferences: likely but not yet proven.
- Guesses: do not present as the algorithm.

## Stop Conditions

Stop when the native algorithm is reproduced, the JNI target is ready for unidbg/Frida, or protection must be bypassed before further progress.
