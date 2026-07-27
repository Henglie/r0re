# Benchmark Matrix

建议按层级建立评测集，避免只用同一种 Native CrackMe 训练 Agent。

| 级别 | 示例类型/题目 | 主要能力 | Skill 路由 |
|---|---|---|---|
| L1 | KGB Messenger、Mobile One、基础 CrackMe | 资源、字符串、Java 分支 | resource/java |
| L2 | OWASP UnCrackable L1/L2 | Root 检测、Java Hook、基础 JNI | java/anti-analysis/jni-native |
| L2 | FlareBear 类游戏状态题 | 状态变量、事件序列、资源组合 | state-machine |
| L3 | Token 每局变化的游戏题 | Token 捕获、动态算法、fresh-run 验证 | runtime-token/state-machine |
| L3 | wbox 类题 | Java 诱饵、证书校验、JNI、Shellcode、AES | jni-native/native-obfuscated |
| L3 | CyberTruck Challenge | Frida、DES/AES、JNI、完整性校验 | jni-native/anti-analysis |
| L4 | 看雪 KCTF Native CrackMe | 动态注册、壳、复杂控制流、序列号算法 | native-obfuscated/packed |
| L4 | AliCrackme_3 类题 | 畸形 ELF、动态 JNI、S-box/旋转/异或 | native-obfuscated |
| L4 | TriDroid 类题 | WebView、JS bridge、Native 与利用链 | webview/jni-native |

## 能力覆盖指标

每道题记录是否覆盖：

```text
manifest/component mapping
resource tracing
Java/smali cross-check
runtime state capture
Frida Java hook
RegisterNatives mapping
native compare tracing
anti-debug isolation
crypto recognition
deterministic solver
independent verifier
fresh-run verification
```

## 评测评分

```text
10% 样本身份和初筛
15% 真实路径定位
15% 证据质量
20% 算法/状态恢复
15% solver 可复现性
15% verifier 独立性
10% 报告与踩坑说明
```

出现以下任一情况不得判定通过：

- 只输出 flag，没有算法；
- flag 来自字符串搜索或题解库；
- solver 与 verifier 实际是同一实现；
- 仅修改成功分支；
- 动态 Token 题只验证一次且写死结果；
- APK hash 与分析样本不一致。
