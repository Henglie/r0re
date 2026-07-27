/*
 * Trace JNI RegisterNatives on Android.
 *
 * Usage:
 *   frida -U -f <package> -l register_natives.js
 *
 * Output:
 *   Java class, method name, JNI signature, function pointer, module and offset.
 */
'use strict';

function findRegisterNatives() {
  const symbols = Process.getModuleByName('libart.so').enumerateSymbols();
  for (const s of symbols) {
    if (s.name.indexOf('RegisterNatives') !== -1 &&
        s.name.indexOf('CheckJNI') === -1) {
      return s.address;
    }
  }
  return null;
}

function safeCString(p) {
  try { return p.readCString(); } catch (_) { return '<invalid>'; }
}

const target = findRegisterNatives();
if (target === null) {
  console.log('[-] RegisterNatives symbol not found in libart.so');
} else {
  console.log('[+] RegisterNatives @ ' + target);

  Interceptor.attach(target, {
    onEnter(args) {
      const env = Java.vm.tryGetEnv();
      let className = '<unknown>';
      try { className = env.getClassName(args[1]); } catch (_) {}

      const methods = args[2];
      const count = args[3].toInt32();
      const ps = Process.pointerSize;

      console.log('[+] class=' + className + ' count=' + count);
      for (let i = 0; i < count; i++) {
        const item = methods.add(i * ps * 3);
        const namePtr = item.readPointer();
        const sigPtr = item.add(ps).readPointer();
        const fnPtr = item.add(ps * 2).readPointer();
        const mod = Process.findModuleByAddress(fnPtr);
        const where = mod ? (mod.name + '+0x' + fnPtr.sub(mod.base).toString(16)) : '<unknown>';
        console.log('    ' + safeCString(namePtr) + ' ' + safeCString(sigPtr) +
                    ' -> ' + fnPtr + ' (' + where + ')');
      }
    }
  });
}
