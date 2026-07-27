# JNI Native Reverse Patterns

Use this file when Java calls native code or the APK contains challenge-specific `.so` libraries.

## Static Triage Commands

```sh
find . -path "*/lib/*/*.so" -print
file lib/arm64-v8a/*.so
readelf -Ws lib/arm64-v8a/libtarget.so | c++filt | rg "Java_|JNI_OnLoad|RegisterNatives|check|flag"
readelf -d lib/arm64-v8a/libtarget.so
strings -a lib/arm64-v8a/libtarget.so | rg "flag|check|verify|md5|sha|aes|base64|%02x|JNI|RegisterNatives"
```

## JNI Mapping Decision Tree

1. If `Java_pkg_Class_method` exists, map it directly to the Java native declaration.
2. If only `JNI_OnLoad` exists, inspect it for `FindClass` and `RegisterNatives`.
3. If the library is stripped and registration is indirect, hook `RegisterNatives` with Frida.
4. If registration target is a callback table, recover method name, signature, and function pointer as a unit.

## JNI Type Reminders

| Java Type | JNI Type | Common Native Handling |
| --- | --- | --- |
| `String` | `jstring` | `GetStringUTFChars`, `NewStringUTF` |
| `byte[]` | `jbyteArray` | `GetByteArrayElements`, `GetArrayLength` |
| `int[]` | `jintArray` | array elements and length |
| `boolean` | `jboolean` | byte-sized return |
| `Object` | `jobject` | `GetObjectClass`, field/method lookups |

## Algorithm Extraction Checklist

- Strip JNI boilerplate mentally before naming the algorithm.
- Track input encoding: UTF-8 string, UTF-16 Java chars, signed bytes, hex string, Base64.
- Record all constants and table lengths.
- Identify final comparison or formatter.
- Build a Python reproduction with at least one known input/output.
- If output is missing, route to Frida before guessing.

## Dynamic Registration Evidence Template

```markdown
| Java class | Method | Signature | Native pointer | Module offset | Evidence |
| --- | --- | --- | --- | --- | --- |
| ... | ... | ... | ... | ... | RegisterNatives log / static table |
```

## Common Native CTF Patterns

- `memcmp` against transformed input.
- Hex formatter `%02x` after digest or custom hash.
- Rolling XOR with previous byte/state.
- TEA/XTEA/XXTEA, RC4-like KSA/PRGA, AES wrapper.
- Lookup table substitution and permutation.
- License/signature check mixed with flag check.
- Loader decrypts second-stage code before actual check.
