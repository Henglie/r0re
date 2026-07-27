---
name: android-ctf-reverse
description: >
  Solve authorized Android APK reverse-engineering CTF and CrackMe challenges through
  evidence-driven static analysis, runtime tracing, Java/JNI/native mapping, state-machine
  recovery, deterministic solver construction, and independent verification. Use for APKs
  involving Java/Kotlin, resources, WebView, native .so, dynamic RegisterNatives, crypto,
  anti-debugging, packing, token-dependent flags, or game-state conditions.
---

# Android APK CTF Reverse Engineering Skill

## 1. Scope

Use this skill only for:

- CTF or CrackMe APKs supplied by the user;
- archived or completed competitions;
- applications the user owns or is explicitly authorized to test;
- local educational benchmarks.

Do not use it to access private user data, defeat paid-service licensing, modify real
production applications, or publish answers for an active competition without authorization.

## 2. Success definition

A challenge is **not solved** merely because a flag-like string was found.

A valid solution must establish this chain:

```text
sample identity
  -> reachable input/event source
  -> Java/Kotlin/resource/WebView logic
  -> JNI/native/state/network path when present
  -> final compare or flag-generation path
  -> deterministic solver
  -> independent verifier
```

The final result must distinguish:

```text
decoy
intermediate value
runtime token/state
accepted app input
displayed flag
competition submit format
```

## 3. Non-negotiable rules

1. Hash the APK before analysis.
2. Never promote a printable string, Base64 value, Morse text, digest, resource, or log line
   to a final answer unless it reaches the final accepted branch.
3. When `System.loadLibrary`, `native`, `JNI_OnLoad`, or `RegisterNatives` exists, map the
   Java declaration to the exact native implementation.
4. Treat each app launch token, nonce, seed, timestamp, device identifier, and game state as
   potentially variable. Do not assume the answer is fixed.
5. Build a deterministic `solve.py` or equivalent program.
6. A solver author may only emit a **candidate**. An independent verifier or gate decides
   whether the case is complete.
7. Record failed routes as dead ends so later workers do not repeat them.
8. State exactly what was verified: algorithm only, emulator, or physical device.
9. Preserve evidence: hashes, commands, offsets, class/method names, logs, scripts, and
   screenshots when they materially support the conclusion.
10. Do not patch the success branch and call that proof. Patching can locate a path, but the
    original validation logic must still be restored or independently reproduced.

## 4. Required case layout

Create one directory per sample:

```text
cases/<case-id>/
├── input/
│   └── challenge.apk
├── work/
│   ├── apktool/
│   ├── jadx/
│   ├── native/
│   └── extracted/
├── evidence/
│   ├── facts.jsonl
│   ├── hypotheses.jsonl
│   ├── dead-ends.jsonl
│   └── traces/
├── scripts/
│   ├── solve.py
│   └── verify.py
├── logs/
├── case.json
├── result.json
└── report.md
```

Use `tools/new_case.py` to initialize this structure.

## 5. Phase A — intake and triage

Run:

```bash
python3 tools/triage_apk.py cases/<case-id>/input/challenge.apk \
  --out cases/<case-id>
```

At minimum collect:

- APK SHA-256 and size;
- package and launcher activity when recoverable;
- DEX files;
- native libraries and ABIs;
- assets, raw resources, secondary APK/DEX/ZIP files;
- signing certificate metadata;
- permissions and exported components;
- suspicious URLs, flag formats, crypto names, native declarations, and anti-debug clues.

Do not stop if JADX produces errors. JADX itself warns that decompilation may be incomplete.
Cross-check with smali, bytecode, resources, and runtime behavior.

## 6. Phase B — classify the challenge

Assign one or more routes.

| Route | Typical indicators | Primary approach |
|---|---|---|
| `resource` | flag fragments in XML/assets/raw files | resource graph and usage tracing |
| `java` | Java/Kotlin validation, digest, encoding | decompile + smali confirmation |
| `state-machine` | game, pet, movement, counters, event order | recover state variables/transitions |
| `runtime-token` | token/nonce changes each launch | capture seed and derive per-run answer |
| `jni-native` | `.so`, native methods | Java→JNI→native mapping |
| `native-obfuscated` | stripped ELF, flattening, self-modifying code | Ghidra/IDA + trace-assisted recovery |
| `anti-analysis` | root/debug/Frida/emulator/signature checks | identify and isolate checks |
| `webview` | JS bridge, custom URL schemes, local HTML | trace bridge and trust boundaries |
| `network` | server response affects flag | reproduce only the authorized CTF protocol |
| `packed` | missing real DEX, runtime loaders | identify loader and dump authorized runtime code |

Record classification in `case.json`. Multiple routes may apply.

## 7. Phase C — static analysis

### 7.1 Manifest and component graph

Establish:

```text
launcher -> activities/fragments/services/receivers
         -> input widgets/intents/deep links
         -> success/failure UI
```

Check:

- exported activities and intent filters;
- custom schemes;
- WebView entry pages;
- `Application.attachBaseContext`;
- secondary DEX or dynamic class loading;
- permissions that imply file, network, or device-state dependencies.

### 7.2 Java/Kotlin and smali

Search for:

```text
flag{
ctf{
success
wrong
check
verify
decrypt
decode
MessageDigest
Cipher
Base64
System.loadLibrary
native
DexClassLoader
PathClassLoader
WebView
addJavascriptInterface
setText
Toast
Log.
```

For every candidate, answer:

1. Is it reachable?
2. What controls the branch?
3. Is it input, intermediate data, or output?
4. Does smali agree with the decompiler?
5. Is there a later native or state-dependent step?

### 7.3 Resource and asset usage

Resources are evidence only when their consumers are known. Trace:

```text
resource ID/path -> loading method -> transformation -> compare/output
```

Look for split flag fragments, image metadata, custom binary blobs, databases, serialized
objects, hidden files, and embedded secondary archives.

## 8. Phase D — runtime analysis

Use an isolated emulator or dedicated test device. Record Android version, ABI, package,
Frida version, and launch command.

Useful observations:

```bash
adb install -r challenge.apk
adb shell pm path <package>
adb logcat --pid="$(adb shell pidof <package>)"
adb shell dumpsys package <package>
adb shell run-as <package> ls -la  # only when debuggable/allowed
```

Dynamic analysis should answer a precise hypothesis, such as:

- Which method receives user input?
- Which state transition unlocks the flag?
- What token or nonce is generated this run?
- Which JNI method is registered?
- What bytes reach `memcmp`?
- Which anti-debug check terminates the app?

Do not collect indiscriminate traces when a narrower hook is possible.

## 9. Phase E — state-machine and runtime-token challenges

For game-like or event-driven APKs, recover:

```text
state variables
initial values
event handlers
transition conditions
terminal success condition
flag construction/display function
```

Represent transitions explicitly:

```text
S0 --event/guard--> S1 --event/guard--> SUCCESS
```

For per-launch tokens:

1. Locate token generation and storage.
2. Determine whether it is random, time-based, device-based, or server-provided.
3. Trace every transformation from token to displayed/accepted flag.
4. Make the solver accept the token as an argument.
5. Verify against at least one fresh launch; two launches are preferred when feasible.

Never hardcode a flag observed for one token as the universal answer.

## 10. Phase F — Java/JNI/native mapping

Build the mapping:

```text
Java class.method(signature)
  -> static JNI export or RegisterNatives entry
  -> native library and runtime address
  -> validation/generation function
  -> final comparison or output
```

Search exports and registration:

```bash
readelf -Ws lib*.so
nm -D lib*.so
strings -a lib*.so
```

If no `Java_<package>_<class>_<method>` export exists:

1. inspect `JNI_OnLoad`;
2. find `FindClass`;
3. recover `JNINativeMethod[]`;
4. map method name, signature, and function pointer;
5. account for ARM/Thumb mode and ASLR.

Use `templates/frida/register_natives.js` to observe registration on a running sample.

## 11. Phase G — native recovery

### 11.1 Baseline

Record for each library:

```text
SHA-256
ABI
ELF class
load segments
imports/exports
JNI entry
suspected validation functions
```

### 11.2 Obfuscated or malformed ELF

When section headers or symbols are unreliable:

- load using program headers or raw segments;
- distinguish file offset, RVA, runtime address, and Thumb-bit addresses;
- find dispatchers and indirect branches;
- use dynamic traces to identify hot blocks;
- reconstruct only the relevant validation slice first.

### 11.3 Self-modifying code and shellcode

Capture:

```text
encrypted bytes
decrypt routine
runtime memory address
permissions change/mmap/mprotect
entry address
input/output buffers
final comparison
```

Do not treat a static constant as authoritative when runtime code mutates it.

### 11.4 Crypto recognition

Look for:

- XOR/add/subtract/rotate pipelines;
- Base64, hex, CRC, MD5/SHA, HMAC;
- AES/DES/TEA/XXTEA;
- RC4/PRGA-like 256-byte S-box loops;
- custom table permutations;
- initialized buffer suffixes and fixed-width padding;
- index-dependent transformations.

Always reconstruct the entire buffer lifecycle. A frequent failure is passing the visible
string directly while omitting initialized suffix bytes, padding, or earlier transforms.

## 12. Phase H — anti-analysis

Identify checks separately:

```text
debugger
root
emulator
Frida/Xposed
signature/certificate
APK checksum
timing
procfs/maps
ptrace
fork/watchdog
self-integrity
```

For each check record:

```text
location
trigger
effect
whether it protects the real path
temporary observation bypass
final restored logic
```

A bypass is a means of observing the program, not the final solution.

## 13. Phase I — solver construction

The solver must:

- accept the APK, extracted artifact, token, or explicit parameters;
- verify the sample hash when a known hash is required;
- reproduce the original transformations;
- print machine-readable JSON with the protocol below;
- exit non-zero on failure;
- avoid brute force unless the keyspace and rationale are documented;
- include intermediate checkpoints useful for auditing.

Required solver JSON:

```json
{
  "sample_sha256": "<64 hex>",
  "candidate": {
    "app_input": "<value or empty>",
    "displayed_flag": "<value or empty>",
    "submit": "<value>"
  },
  "checkpoints": [
    {"name": "native_target", "value": "..."}
  ]
}
```

## 14. Phase J — independent verification

The verifier must be separate from the solver and should test a different property.

Preferred levels:

```text
V1 path evidence      candidate reaches the real compare/generator
V2 algorithm replay   independent implementation reproduces result
V3 emulator accepted  original APK accepts/displays result
V4 fresh-run proof    variable token/state works on a new run
```

Required verifier JSON:

```json
{
  "passed": true,
  "sample_sha256": "<64 hex>",
  "candidate": {
    "app_input": "<value or empty>",
    "displayed_flag": "<value or empty>",
    "submit": "<value>"
  },
  "level": "V2",
  "checks": [
    {"name": "independent_algorithm", "passed": true}
  ]
}
```

Run the gate:

```bash
python3 tools/result_gate.py cases/<case-id>/result.json
```

The gate verifies that:

- the APK hash is valid;
- evidence artifacts exist;
- solver and verifier are separate commands;
- both return matching candidate data;
- the verifier reports `passed: true`;
- the result author has not marked their own output verified.

## 15. Evidence model

Write JSON Lines records.

Fact:

```json
{"id":"F1","kind":"fact","claim":"MainActivity.check calls native ch(String)",
 "artifact":"work/jadx/.../MainActivity.java","locator":"line 84","confidence":1.0}
```

Hypothesis:

```json
{"id":"H1","kind":"hypothesis","claim":"The 16-byte table is an AES target",
 "based_on":["F3","F5"],"next_test":"trace buffer before memcmp","confidence":0.6}
```

Dead end:

```json
{"id":"D1","kind":"dead_end","claim":"Morse-decoded text is the final input",
 "reason":"It never reaches the native comparison","evidence":["F7","F9"]}
```

Candidate answers must reference facts and a solver artifact.

## 16. Worker roles for a multi-agent harness

Use these roles when the harness supports workers:

```text
triage-worker       sample identity, manifest, files, routing
java-worker         Java/Kotlin/smali/resource path
dynamic-worker      runtime state, token, UI and Frida observations
native-worker       JNI mapping, ELF and crypto recovery
solver-worker       deterministic implementation; emits candidate only
verifier-worker     independent replay/device acceptance
review-worker       audits evidence, decoys, missing steps, and report
```

Hard rule:

```text
solver-worker != verifier-worker
```

The maker cannot mark its own work complete. The verifier may reject the candidate and create
new intents for missing evidence.

## 17. Completion gate

A case may be marked complete only when all are true:

- APK hash recorded;
- real input/event path identified;
- Java/JNI/native/state chain documented as applicable;
- decoys explicitly rejected;
- deterministic solver exists and runs;
- independent verifier passes;
- app input, displayed flag, and submit format are distinguished;
- report contains commands and reproducible steps;
- limitations state whether verification was algorithmic, emulator, or physical device.

## 18. Required final report

Use `templates/analysis-report.md`. Include:

1. Sample identity.
2. Challenge classification.
3. App behavior and entry points.
4. Static path.
5. Dynamic observations.
6. Java/JNI/native or state-machine chain.
7. Recovered algorithm.
8. Solver.
9. Independent verification.
10. Decoys and failed approaches.
11. Final app input/displayed flag/submit format.
12. Reproduction commands.
13. Remaining uncertainty.
