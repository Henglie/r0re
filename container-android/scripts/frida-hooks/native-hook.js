// native-hook.js — Hook Native层关键函数 (open/read/write/connect/sendto/recvfrom)
// 用法: frida -U -f com.target.app -l native-hook.js --no-pause
// 适用: 分析文件读写、网络通信、native加密

var isLittleEndian = true;

// ========== libc: open — 追踪文件打开 ==========
Interceptor.attach(Module.findExportByName("libc.so", "open"), {
    onEnter: function (args) {
        this.path = args[0].readUtf8String();
        if (this.path && !this.path.startsWith("/proc/") && !this.path.startsWith("/sys/")) {
            console.log("[open] " + this.path);
        }
    }
});

// ========== libc: connect — 追踪网络连接 ==========
Interceptor.attach(Module.findExportByName("libc.so", "connect"), {
    onEnter: function (args) {
        var sockfd = args[0].toInt32();
        var addr = args[1];
        var family = Memory.readU16(addr);
        if (family === 2) { // AF_INET
            var port = (Memory.readU8(addr.add(2)) << 8) | Memory.readU8(addr.add(3));
            var ip = Memory.readU8(addr.add(4)) + "." +
                     Memory.readU8(addr.add(5)) + "." +
                     Memory.readU8(addr.add(6)) + "." +
                     Memory.readU8(addr.add(7));
            console.log("[connect] " + ip + ":" + port);
        }
    }
});

// ========== SSL_read / SSL_write — 追踪TLS明文 ==========
var sslRead = Module.findExportByName("libssl.so", "SSL_read");
var sslWrite = Module.findExportByName("libssl.so", "SSL_write");

if (sslRead) {
    Interceptor.attach(sslRead, {
        onLeave: function (retval) {
            var len = retval.toInt32();
            if (len > 0) {
                try {
                    var data = Memory.readByteArray(this.context.x1 || this.context.r1, Math.min(len, 512));
                    console.log("[SSL_read] len=" + len + " data=" + hexdump(data, { length: Math.min(len, 256) }));
                } catch (e) { }
            }
        }
    });
}

if (sslWrite) {
    Interceptor.attach(sslWrite, {
        onEnter: function (args) {
            var len = args[2].toInt32();
            if (len > 0) {
                try {
                    var data = Memory.readByteArray(args[1], Math.min(len, 512));
                    console.log("[SSL_write] len=" + len + " data=" + hexdump(data, { length: Math.min(len, 256) }));
                } catch (e) { }
            }
        }
    });
}

// ========== JNI_OnLoad — 追踪动态注册 ==========
Java.perform(function () {
    var modules = Process.enumerateModules();
    modules.forEach(function (mod) {
        if (mod.name.endsWith(".so") && mod.name !== "libfrida-gadget.so") {
            var onload = Module.findExportByName(mod.name, "JNI_OnLoad");
            if (onload) {
                console.log("[JNI_OnLoad] found in " + mod.name + " at " + onload);
            }
        }
    });
});

console.log("[*] Native hooks installed. Monitoring file/network/SSL activity...");
