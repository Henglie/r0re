---
name: android-frida-dynamic
description: Use Frida for Android CTF dynamic analysis. Use when static analysis needs runtime arguments, return values, decrypted strings, class loading, RegisterNatives mappings, dlopen timing, SSL pinning bypass, root/debug/emulator bypass, Java method hooks, native Interceptor hooks, or controlled modification of Android app behavior.
---

# Android Frida Dynamic

## Objective

Use Frida to observe or modify runtime behavior with minimal hooks. Prefer targeted hooks that answer a concrete reversing question.

## References

Read `references/frida-templates.md` when you need ready-to-adapt Frida commands, Java hooks, crypto hooks, `RegisterNatives` hooks, `dlopen` hooks, or narrowly scoped bypass snippets.

## Required Output

- Hook goal: what question the hook answers.
- Attach/spawn command and package name.
- Frida script with target classes/functions and logging.
- Observed arguments, return values, stack traces, or bypass result.
- Next step: solve, patch, unidbg, or deeper native reversing.

## Workflow

1. Define the question:
   - What input reaches the check?
   - What value is compared?
   - Which native function is registered?
   - Which key/string/resource is decrypted at runtime?
   - Which anti-analysis check blocks execution?

2. Choose hook layer:
   - Java hook for Activity handlers, crypto APIs, encoders, file IO, class loading, and app logic.
   - Native hook for JNI callbacks, libc calls, custom crypto, string decryptors, and comparison functions.
   - Loader hook for `dlopen`, `android_dlopen_ext`, and delayed native libraries.

3. Hook Java safely:
   - Use `Java.perform`.
   - Hook overloads explicitly.
   - Print arguments and return values in readable formats.
   - Add stack traces only when routing is unclear because they are noisy.

4. Hook native safely:
   - Wait until the module is loaded.
   - Resolve exports first; fall back to module base plus known offset.
   - Log pointer arguments carefully: string, byte array, hexdump, and length when known.
   - Avoid modifying return values until logging confirms the target.

5. Common CTF hooks:
   - `java.lang.String.equals`, only with filters to avoid noise.
   - `MessageDigest.digest`, `Cipher.doFinal`, `Base64.encode/decode`.
   - `DexClassLoader`, `ClassLoader.loadClass`.
   - `System.loadLibrary`, `Runtime.loadLibrary0`.
   - `dlopen`, `android_dlopen_ext`.
   - `RegisterNatives`.
   - `ptrace`, `open/read` of `/proc/self/status`, root binary checks.

6. Record results:
   - Save scripts and logs.
   - Convert dynamic observations into static facts: method name, function offset, input/output, decrypted value.
   - Route back to static reversing once the missing runtime data is known.

## Minimal Java Hook Shape

```javascript
Java.perform(function () {
  const Cls = Java.use("pkg.ClassName");
  Cls.method.overload("java.lang.String").implementation = function (s) {
    console.log("[method] arg=" + s);
    const ret = this.method(s);
    console.log("[method] ret=" + ret);
    return ret;
  };
});
```

## Minimal Native Hook Shape

```javascript
function hookWhenLoaded(name, cb) {
  const m = Process.findModuleByName(name);
  if (m) return cb(m);
  const dlopen = Module.findExportByName(null, "android_dlopen_ext") || Module.findExportByName(null, "dlopen");
  Interceptor.attach(dlopen, {
    onEnter(args) { this.path = args[0].readCString(); },
    onLeave() {
      if (this.path && this.path.indexOf(name) >= 0) cb(Process.getModuleByName(name));
    }
  });
}
```

## Stop Conditions

Stop when the hook has answered the stated question. Do not keep adding broad hooks unless a new question requires them.
