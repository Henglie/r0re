// trace-class.js — 追踪指定Java类的所有方法调用
// 用法: frida -U -f com.target.app -l trace-class.js --no-pause
// 修改下方的 TARGET_CLASS 为目标类名

var TARGET_CLASS = "com.example.TargetClass";  // ← 修改为实际类名
var TARGET_METHOD = null;  // null=追踪所有方法, 或指定方法名如 "encrypt"

Java.perform(function () {
    try {
        var clazz = Java.use(TARGET_CLASS);
        var methods = clazz.class.getDeclaredMethods();

        methods.forEach(function (method) {
            var methodName = method.getName();
            if (TARGET_METHOD && methodName !== TARGET_METHOD) return;

            // 获取所有重载
            var overloads = clazz[methodName].overloads;
            overloads.forEach(function (overload) {
                overload.implementation = function () {
                    var argsStr = "";
                    for (var i = 0; i < arguments.length; i++) {
                        var arg = arguments[i];
                        if (arg === null || arg === undefined) {
                            argsStr += "null";
                        } else if (typeof arg === "object" && arg.$className) {
                            argsStr += arg.$className + "@" + arg.hashCode();
                        } else if (typeof arg === "object" && arg.length !== undefined) {
                            argsStr += "byte[" + arg.length + "]";
                        } else {
                            argsStr += String(arg).substring(0, 100);
                        }
                        if (i < arguments.length - 1) argsStr += ", ";
                    }

                    console.log("[TRACE] " + TARGET_CLASS + "." + methodName + "(" + argsStr + ")");

                    var result;
                    try {
                        result = this[methodName].apply(this, arguments);
                    } catch (e) {
                        console.log("[TRACE] " + TARGET_CLASS + "." + methodName + " threw: " + e);
                        throw e;
                    }

                    var resultStr = "";
                    if (result === null || result === undefined) {
                        resultStr = "null";
                    } else if (typeof result === "object" && result.length !== undefined) {
                        resultStr = "byte[" + result.length + "]";
                    } else {
                        resultStr = String(result).substring(0, 200);
                    }
                    console.log("[TRACE] " + TARGET_CLASS + "." + methodName + " => " + resultStr);

                    return result;
                };
            });
        });
        console.log("[*] Tracing " + methods.length + " methods in " + TARGET_CLASS);
    } catch (e) {
        console.log("[!] Failed to trace " + TARGET_CLASS + ": " + e);
        console.log("[!] Make sure the class exists in the loaded APK");
    }
});
