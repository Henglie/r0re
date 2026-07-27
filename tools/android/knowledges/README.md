# Solved-case Knowledge Base

每个子目录是一个已解决题型的"知识沉淀"：识别特征 + 完整解法 + 可直接复用的 solver 脚本。
遇到新 APK 时先比对各条目的 identification markers；命中则复用 solver 并在当前工作区重新验证，
不要从头发推导。解决新题型后，按同样格式新增条目（README + solver + 验证向量）。

## 条目索引

| 目录 | 题型 | 识别特征 |
|---|---|---|
| `godot-sec2026/` | Godot 4.5 APK + UPX 壳 GDExtension + 魔改 ChaCha20 | assets/.godot、libgodot_android.so、加壳的小型自定义 .so、*.gdc 加密 |
