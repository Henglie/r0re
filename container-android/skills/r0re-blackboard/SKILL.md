---
name: r0re-blackboard
description: Shared blackboard for the analysis swarm. Use it BEFORE starting any new analysis direction (read known facts and dead ends), WHILE working (write confirmed facts immediately, mark disproven routes), and to claim/conclude intents. This is the only channel through which your findings reach teammates.
---

# r0re Blackboard

You are one of several workers analyzing the same target. The blackboard is the
single source of truth shared by all workers: facts everyone can use, dead ends
nobody should retry, intents waiting to be claimed.

The client is `blackboard.py` (pure stdlib, already on PATH or at
`/opt/r0re/skills/r0re-blackboard/blackboard.py`). Environment variables
`R0RE_BLACKBOARD_URL`, `R0RE_PROJECT_ID`, `R0RE_WORKER_NAME` are pre-injected —
never invent them yourself.

## Discipline — when to read

Run `python3 /opt/r0re/skills/r0re-blackboard/blackboard.py read` (and
`read-deadends`) at these moments:

1. **Before starting any new analysis direction** — do not repeat what teammates
   already confirmed, and never retry a recorded dead end.
2. **When stuck** — scan `read` for facts that unblock you.
3. **Before claiming work** — `list-intents` shows what is available.

## Discipline — when to write

1. **The moment you confirm a finding in real tool output**, write it:
   `write-fact "<concise finding with exact location>" --verified`
   Only pass `--verified` for things you literally saw in command output.
2. **The moment a route is disproven**, record it:
   `mark-deadend "<what was tried and why it failed>"`
   A dead end is as valuable as a fact — it stops everyone from repeating it.
3. Keep facts atomic and objective: one finding per write, with file path /
   class / offset / symbol. Do not write speculation as verified.

## Discipline — intents

1. `list-intents` → pick one → `claim <id>`. If it prints `LOST`, claim a
   different one. Never work on an intent you did not win.
2. When done with a claimed intent, always `conclude <id> "<result summary>"`,
   even if the outcome is "nothing found" — that closes the loop for everyone.
3. Only call `complete --from <fact-ids> "<conclusion>"` when the project goal
   is genuinely achieved and backed by facts you wrote. The server gate will
   reject unsupported completions.

## Rules

- The blackboard is append-only: never try to edit or delete another worker's
  records. If a fact looks wrong, write a new fact with the corrected evidence.
- Write in the same concise, location-precise style as the facts you read.
- Large outputs belong in files under the analysis workspace; the fact should
  reference the path.
