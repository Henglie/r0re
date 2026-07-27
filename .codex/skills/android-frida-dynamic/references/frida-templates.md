# Frida Templates for Android CTFs

Use this file when runtime values, dynamic registration, or bypass hooks are needed.

Source anchors:

- Android quick start: https://frida.re/docs/android/
- JavaScript API: https://frida.re/docs/javascript-api/

## Commands

```sh
frida-ps -Uai
frida -U -f com.example.app -l hook.js --no-pause
frida -U com.example.app -l hook.js
```

Use spawn mode for early hooks. Use attach mode when the app must reach a screen first.

## Hook Java Method

```javascript
Java.perform(function () {
  const C = Java.use("pkg.ClassName");
  C.check.overload("java.lang.String").implementation = function (s) {
    console.log("[check] in=" + s);
    const ret = this.check(s);
    console.log("[check] out=" + ret);
    return ret;
  };
});
```

## Hook Crypto

```javascript
Java.perform(function () {
  const MD = Java.use("java.security.MessageDigest");
  MD.digest.overload("[B").implementation = function (b) {
    console.log("[MessageDigest.digest] len=" + b.length);
    const ret = this.digest(b);
    console.log("[MessageDigest.digest] ret=" + Java.array("byte", ret));
    return ret;
  };
});
```

## Hook RegisterNatives

```javascript
function hookRegisterNatives() {
  const addr = Module.findExportByName("libart.so", "_ZN3art3JNI15RegisterNativesEP7_JNIEnvP7_jclassPK15JNINativeMethodi");
  if (!addr) {
    console.log("RegisterNatives export not found; enumerate libart symbols");
    return;
  }
  Interceptor.attach(addr, {
    onEnter(args) {
      const methods = args[2];
      const count = args[3].toInt32();
      for (let i = 0; i < count; i++) {
        const p = methods.add(i * Process.pointerSize * 3);
        const name = p.readPointer().readCString();
        const sig = p.add(Process.pointerSize).readPointer().readCString();
        const fn = p.add(Process.pointerSize * 2).readPointer();
        const mod = Process.findModuleByAddress(fn);
        console.log("[RegisterNatives] " + name + " " + sig + " -> " + fn + " " + (mod ? mod.name : ""));
      }
    }
  });
}
setImmediate(hookRegisterNatives);
```

If the symbol name differs by Android version, enumerate `libart.so` symbols and filter `RegisterNatives`.

## Hook dlopen then Native Offset

```javascript
function onModule(name, cb) {
  const now = Process.findModuleByName(name);
  if (now) return cb(now);
  const f = Module.findExportByName(null, "android_dlopen_ext") || Module.findExportByName(null, "dlopen");
  Interceptor.attach(f, {
    onEnter(args) { this.path = args[0].readCString(); },
    onLeave() {
      if (this.path && this.path.indexOf(name) >= 0) cb(Process.getModuleByName(name));
    }
  });
}

onModule("libtarget.so", function (m) {
  const target = m.base.add(0x1234);
  Interceptor.attach(target, {
    onEnter(args) {
      console.log("[native] arg0=" + args[0]);
    },
    onLeave(retval) {
      console.log("[native] ret=" + retval);
    }
  });
});
```

## Bypass Examples

Return false from Java debugger checks only when they are confirmed blockers:

```javascript
Java.perform(function () {
  const Debug = Java.use("android.os.Debug");
  Debug.isDebuggerConnected.implementation = function () { return false; };
});
```

Hook native `ptrace`:

```javascript
const ptrace = Module.findExportByName(null, "ptrace");
if (ptrace) {
  Interceptor.replace(ptrace, new NativeCallback(function () { return 0; }, "int", ["int", "int", "pointer", "pointer"]));
}
```
