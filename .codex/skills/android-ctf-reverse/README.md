# Android APK CTF Reverse Engineering Skill

这是一个可直接放进 Codex、Claude Code、Cairn、r0re 或其他 Agent Harness 的通用
Android APK 逆向 CTF Skill。

它不是“看到 flag 字符串就结束”的提示词，而是一套带证据链、solver、verifier 和
完成门禁的工作流。

## 目录

```text
SKILL.md                          主技能
AGENT_ROLES.md                    多 Worker / 黑板协作规则
references/challenge-taxonomy.md  题型分类与路由
references/benchmark-matrix.md    推荐题库与覆盖能力
references/evidence-and-verification.md
references/toolchain.md
templates/analysis-report.md
templates/case-intake.md
templates/result.json
templates/solve.py
templates/verify.py
templates/frida/*.js
tools/new_case.py
tools/triage_apk.py
tools/result_gate.py
```

## 安装到项目

推荐放置：

```text
<repo>/
├── AGENTS.md
├── skills/
│   └── android-apk-ctf-re/
│       ├── SKILL.md
│       ├── tools/
│       ├── templates/
│       └── references/
└── cases/
```

## 创建题目工作区

```bash
python3 skills/android-apk-ctf-re/tools/new_case.py \
  ./challenge.apk \
  --cases-dir ./cases \
  --case-id example-01
```

## 初筛

```bash
python3 skills/android-apk-ctf-re/tools/triage_apk.py \
  ./cases/example-01/input/challenge.apk \
  --out ./cases/example-01
```

脚本会使用 Python 标准库完成基础检查，并在本机存在时调用 Apktool、JADX、
apkanalyzer/aapt 和 keytool。某一工具失败不会阻止其他步骤继续。

## 开始分析

把以下任务交给 Agent：

```text
读取 skills/android-apk-ctf-re/SKILL.md。
分析 cases/example-01/input/challenge.apk。
先运行 triage，不要猜 flag。建立 facts、hypotheses、dead-ends。
最终必须生成 scripts/solve.py、scripts/verify.py、result.json 和 report.md，
并通过 result_gate.py；solver 不得自行把结果标记为 verified。
```

## 验证门禁

```bash
python3 skills/android-apk-ctf-re/tools/result_gate.py \
  ./cases/example-01/result.json
```

成功时会生成：

```text
cases/example-01/gate-report.json
```

## 设计来源

工作流吸收了用户此前样本中的关键教训：

- Java 层出现的 Morse、MD5、Base64 或字符串可能只是线索或证书校验；
- 真正校验可能位于 JNI、动态注册的 Native 函数、自解密 shellcode 或 AES 比较；
- Native 缓冲区可能带初始化后缀，不能只把可见字符串直接送入内部函数；
- 表数据、CRC32 表和偶然可打印字节不能当作 flag；
- 游戏/Token 题的答案可能每次启动变化，必须恢复 Token→Flag 关系；
- 不完整的 Hook、错误 JNI stub 或强制成功分支不能代替原始算法证明；
- 最终答案必须由确定性 solver 和独立 verifier 共同支持。

## 官方工具依据

- OWASP MASTG 把 Android 逆向划分为 Smali 反汇编、Java 反编译和 Native
  反汇编等技术路线。
- JADX 用于从 APK/DEX 生成 Java 源码，但官方明确说明不能保证 100% 正确反编译。
- Apktool 用于解码资源与反汇编 DEX/Smali。
- ADB 用于安装、启动、Shell、日志和设备交互。
- Frida 用于 Java 与 Native 动态插桩、函数跟踪和参数观察。

实际使用时应以各工具的当前官方文档为准，不在 Skill 中锁死版本。
