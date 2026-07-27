// force_part1.js
// 用途：强制 Godot Area3D::get_overlapping_bodies() 返回非空，触发绿色方块 Trigger2，
// 让游戏自己显示 flag{sec2026_PART1_xxxxxxxx}
//
// 适用 APK：com.tencent.ACE.gamesec2026.preliminary / libgodot_android.so arm64-v8a
// 运行：frida -U -f com.tencent.ACE.gamesec2026.preliminary -l force_part1.js --no-pause

'use strict';

const GODOT_SO = 'libgodot_android.so';

// 来自该 APK / 看雪文章分析的 arm64 偏移。
// 注意：这是 module base + offset，不是 IDA 里的绝对地址。
const OFF_AREA3D_GET_OVERLAPPING_BODIES = 0x25fa6f4;
const OFF_ARRAY_RESIZE                = 0x395126c;

// 打印频率，避免日志刷屏。
const LOG_EVERY_N_FORCES = 120;

// 是否强制所有 Area3D 返回非空。
// 对这个题目来说通常直接 force all 就能让 Trigger2 执行。
const FORCE_ALL_AREA3D = true;

let forceCount = 0;
let callCount = 0;

function now() {
    return new Date().toISOString().substr(11, 8);
}

function log(msg) {
    console.log(`[${now()}] ${msg}`);
}

function waitForModule(name, cb) {
    const m = Process.findModuleByName(name);
    if (m) {
        cb(m);
        return;
    }

    log(`waiting for ${name} ...`);

    const timer = setInterval(function () {
        const mod = Process.findModuleByName(name);
        if (mod) {
            clearInterval(timer);
            cb(mod);
        }
    }, 100);
}

function main() {
    if (Process.arch !== 'arm64') {
        log(`[-] 当前架构是 ${Process.arch}，但这个脚本里的偏移是 arm64-v8a 的。`);
        log(`[-] 请确认手机/模拟器运行的是 arm64 版本 APK。`);
        return;
    }

    waitForModule(GODOT_SO, function (godot) {
        const base = godot.base;
        const getBodiesPtr = base.add(OFF_AREA3D_GET_OVERLAPPING_BODIES);
        const arrayResizePtr = base.add(OFF_ARRAY_RESIZE);

        log(`[+] ${GODOT_SO} base = ${base}`);
        log(`[+] Area3D::get_overlapping_bodies = ${getBodiesPtr}`);
        log(`[+] Array::resize                  = ${arrayResizePtr}`);

        // Godot Array::resize(int)
        // 返回值不重要，这里用 int 接住即可。
        const arrayResize = new NativeFunction(arrayResizePtr, 'int', ['pointer', 'int']);

        Interceptor.attach(getBodiesPtr, {
            onEnter(args) {
                callCount++;

                // C++ this 指针：当前 Area3D 对象
                this.area3d = args[0];

                // AArch64 C++ ABI：非平凡对象按值返回时，隐藏返回对象地址一般在 x8。
                // Godot Array 返回值会构造到这个地址里。
                this.outArray = this.context.x8;
            },

            onLeave(retval) {
                if (!FORCE_ALL_AREA3D) {
                    return;
                }

                if (this.outArray.isNull()) {
                    return;
                }

                try {
                    // 核心：把返回的 Array 强制改成 size = 1。
                    // 这样 GDScript 里的 body.size() > 0 判断就成立。
                    arrayResize(this.outArray, 1);
                    forceCount++;

                    if (forceCount === 1 || forceCount % LOG_EVERY_N_FORCES === 0) {
                        log(`[+] forced get_overlapping_bodies result: area=${this.area3d}, outArray=${this.outArray}, total=${forceCount}`);
                    }
                } catch (e) {
                    log(`[-] Array::resize failed: ${e}`);
                }
            }
        });

        log(`[+] hook installed.`);
        log(`[+] 现在进入游戏场景，观察右上角/Label2 是否出现 flag{sec2026_PART1_...}`);
        log(`[+] 如果只看到 flag{sec2026_PART0_example}，说明你看到的是黄色方块；继续观察右上角 Part1 Label。`);
    });
}

setImmediate(main);