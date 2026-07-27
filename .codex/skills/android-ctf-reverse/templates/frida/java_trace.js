/*
 * Generic targeted Java method trace.
 *
 * Usage:
 *   1. Fill TARGET_CLASS and TARGET_METHOD.
 *   2. frida -U -f <package> -l java_trace.js
 */
'use strict';

const TARGET_CLASS = 'com.example.MainActivity';
const TARGET_METHOD = 'check';

Java.perform(function () {
  const Cls = Java.use(TARGET_CLASS);
  const overloads = Cls[TARGET_METHOD].overloads;

  console.log('[+] Hooking ' + TARGET_CLASS + '.' + TARGET_METHOD +
              ' overloads=' + overloads.length);

  overloads.forEach(function (ov, index) {
    ov.implementation = function () {
      const args = [];
      for (let i = 0; i < arguments.length; i++) {
        try { args.push(String(arguments[i])); }
        catch (_) { args.push('<unprintable>'); }
      }

      console.log('[>] #' + index + ' args=' + JSON.stringify(args));
      const result = ov.apply(this, arguments);
      console.log('[<] #' + index + ' result=' + String(result));
      return result;
    };
  });
});
