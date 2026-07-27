# Android CTF Challenge Taxonomy

## Resource / configuration

Indicators:

- `res/values/strings.xml`, assets, raw resources, databases, image metadata;
- flag fragments combined at runtime;
- hidden component or debug menu.

Minimum proof: identify the consumer and final assembly path.

## Java/Kotlin validation

Indicators:

- button handler calls `check`, `verify`, digest, encoding, or comparison;
- no Native library required for the final branch.

Minimum proof: smali or bytecode confirms the decompiled path; solver reproduces it.

## State machine / game

Indicators:

- counters, position, inventory, pet state, collision, event ordering;
- flag appears only after an exact sequence.

Minimum proof: transition table plus a script or repeatable input sequence.

## Runtime token / nonce

Indicators:

- Token changes on restart;
- flag contains a derived code;
- random/time/device seed.

Minimum proof: solver parameterized by token and verified on a fresh run.

## JNI/native

Indicators:

- `System.loadLibrary`, native declarations, `.so` libraries.

Minimum proof: Java signature mapped to static export or `RegisterNatives` function pointer.

## Native obfuscation

Indicators:

- malformed sections, stripped symbols, flattened control flow, self-modifying code,
  table dispatch, ARM/Thumb transitions.

Minimum proof: relevant validation slice restored and independently reimplemented.

## Anti-analysis

Indicators:

- `ptrace`, `/proc/self/status`, maps scan, root/emulator/Frida checks,
  certificate or APK hash verification.

Minimum proof: checks are separated from the actual secret-generation/validation logic.

## WebView / JavaScript bridge

Indicators:

- local HTML/JS, `addJavascriptInterface`, URL handlers, bridge methods.

Minimum proof: data flow across JavaScript↔Java↔Native boundaries is documented.

## Network-assisted CTF

Indicators:

- challenge server provides nonce, state, or encrypted data.

Minimum proof: authorized protocol captured and reproduced without attacking unrelated systems.

## Packed / runtime-loaded

Indicators:

- tiny loader DEX, encrypted payload, `DexClassLoader`, native unpacking.

Minimum proof: dumped payload hash, loader path, and analysis of the real code.
