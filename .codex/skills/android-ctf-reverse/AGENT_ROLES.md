# Multi-Agent Roles and Blackboard Contract

## 1. Shared objects

Recommended blackboard entities:

```text
intent       待执行的明确任务
fact         已验证事实
hypothesis   待验证推断
dead_end     已排除路线
artifact     文件、日志、截图、脚本
candidate    候选输入/flag
verification 独立验证结果
```

## 2. Worker permissions

| Worker | May create | Must not do |
|---|---|---|
| triage | facts, artifacts, routing intents | declare final flag |
| java | facts, hypotheses, dead ends | skip native path when JNI exists |
| dynamic | facts, traces, token/state records | infer universal flag from one run |
| native | facts, pseudocode, constants | promote random strings |
| solver | candidate, solver artifact | mark candidate verified |
| verifier | verification, rejection intents | silently modify solver |
| reviewer | audit findings, missing-step intents | manufacture evidence |

## 3. Intent examples

```json
{
  "id": "I-NATIVE-01",
  "type": "native-map",
  "goal": "Map com.example.MainActivity.check(Ljava/lang/String;)Z to its native function",
  "inputs": ["F-JAVA-03", "artifact:lib/arm64-v8a/libcheck.so"],
  "acceptance": [
    "registration mechanism identified",
    "function pointer/RVA recorded",
    "runtime address formula documented"
  ]
}
```

```json
{
  "id": "I-VERIFY-01",
  "type": "verify",
  "goal": "Independently verify candidate against the recovered compare path",
  "inputs": ["candidate:C1", "artifact:scripts/solve.py"],
  "acceptance": [
    "separate verifier command",
    "candidate values match",
    "verifier passed",
    "verification level recorded"
  ]
}
```

## 4. Candidate gate

Candidate lifecycle:

```text
proposed -> solver-reproduced -> verifier-passed -> reviewed -> complete
```

Forbidden transition:

```text
solver-worker: proposed -> complete
```

## 5. Conflict handling

When facts conflict:

1. Preserve both records.
2. Mark the conflict.
3. Prefer original bytecode/runtime evidence over decompiler presentation.
4. Create a resolving intent.
5. Do not overwrite an earlier fact without a superseding record and reason.

## 6. Recovery and rollback

Quarantine a completion when:

- sample hash does not match;
- solver and verifier disagree;
- candidate came from a patched success branch only;
- runtime token changed and solver cannot handle it;
- evidence artifact is missing;
- report claims device acceptance without a device log/screenshot.

A quarantined case returns to the earliest unsupported stage.
