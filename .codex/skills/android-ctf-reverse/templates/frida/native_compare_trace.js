/*
 * Focused native compare tracer.
 *
 * Set TARGET_MODULE to the challenge library. Calls not originating from that module
 * are ignored to reduce noise.
 *
 * Usage:
 *   frida -U -f <package> -l native_compare_trace.js
 */
'use strict';

const TARGET_MODULE = 'libchallenge.so';
const MAX_BYTES = 128;

function globalExport(name) {
  try { return Module.getGlobalExportByName(name); } catch (_) {}
  try { return Module.findGlobalExportByName(name); } catch (_) {}
  return null;
}

function fromTarget(returnAddress) {
  const m = Process.findModuleByAddress(returnAddress);
  return m !== null && m.name === TARGET_MODULE;
}

function safeBytes(p, n) {
  try {
    const len = Math.min(n, MAX_BYTES);
    return hexdump(p, { offset: 0, length: len, header: false, ansi: false });
  } catch (_) {
    return '<unreadable>';
  }
}

function hookMemcmp() {
  const p = globalExport('memcmp');
  if (p === null) return;
  Interceptor.attach(p, {
    onEnter(args) {
      this.active = fromTarget(this.returnAddress);
      if (!this.active) return;
      this.a = args[0];
      this.b = args[1];
      this.n = args[2].toInt32();
      console.log('[memcmp] n=' + this.n);
      console.log('A:\n' + safeBytes(this.a, this.n));
      console.log('B:\n' + safeBytes(this.b, this.n));
    },
    onLeave(retval) {
      if (this.active) console.log('[memcmp] result=' + retval.toInt32());
    }
  });
}

function hookStrcmp(name) {
  const p = globalExport(name);
  if (p === null) return;
  Interceptor.attach(p, {
    onEnter(args) {
      this.active = fromTarget(this.returnAddress);
      if (!this.active) return;
      try { console.log('[' + name + '] A=' + args[0].readCString()); } catch (_) {}
      try { console.log('[' + name + '] B=' + args[1].readCString()); } catch (_) {}
    },
    onLeave(retval) {
      if (this.active) console.log('[' + name + '] result=' + retval.toInt32());
    }
  });
}

hookMemcmp();
hookStrcmp('strcmp');
hookStrcmp('strncmp');
