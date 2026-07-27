# Java and Smali CTF Patterns

Use this file when the target check is visible in jadx/apktool output or when a small Smali patch can expose the next stage.

## Java/Kotlin Search Queries

```sh
rg -n "onClick|setOnClickListener|TextWatcher|afterTextChanged|onCreate|Intent|getStringExtra" jadx-out
rg -n "flag|ctf|check|verify|validate|login|password|token|secret" jadx-out
rg -n "Base64|MessageDigest|Cipher|Mac|SecretKeySpec|IvParameterSpec|CRC32|Random|SecureRandom" jadx-out
rg -n "native |System\.loadLibrary|Runtime\.getRuntime|ProcessBuilder" jadx-out
```

## Common Algorithm Shapes

| Pattern | Static Clue | Action |
| --- | --- | --- |
| XOR loop | `^`, byte arrays, repeated key | Recover key and inverse transform |
| Base64 wrapper | `Base64.encode/decode` | Decode constants, then inspect inner data |
| Hash compare | `MessageDigest`, hex loop | Find preimage constraints; do not brute force blindly |
| AES/DES | `Cipher.getInstance`, key/iv constants | Extract mode, padding, key, iv; test vectors |
| Array permutation | hard-coded index table | Build inverse permutation |
| Kotlin obfuscation | `Intrinsics`, synthetic classes | Rename data-flow variables manually |
| Reflection | `Class.forName`, `getMethod`, `invoke` | Resolve target strings and continue analysis |
| Component trigger | exported Activity/Receiver/Provider | Build `adb shell am` or content query trigger |

## Smali Patch Recipes

Patch only after confirming the target method is reached.

### Force Boolean Return

Look for:

```smali
const/4 v0, 0x0
return v0
```

Change to:

```smali
const/4 v0, 0x1
return v0
```

### Invert a Branch

Common swaps:

- `if-eqz` <-> `if-nez`
- `if-eq` <-> `if-ne`
- `if-lt` <-> `if-ge`
- `if-gt` <-> `if-le`

### Replace String Result

If a method returns a displayed string:

```smali
const-string v0, "flag{...}"
return-object v0
```

Keep register count valid. Prefer patching a leaf method over a broad UI method.

## Rebuild Commands

```sh
apktool b apktool-out -o patched-unsigned.apk
zipalign -p 4 patched-unsigned.apk patched-aligned.apk
apksigner sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android patched-aligned.apk
adb install -r patched-aligned.apk
```

Use the local project signing convention if it already exists.

## Evidence Output

```markdown
| Item | Value |
| --- | --- |
| Entry Activity | ... |
| Handler | ... |
| Check method | ... |
| Input transform | ... |
| Comparison target | ... |
| Solver | ... |
| Patch | ... |
```
