# Role
You are in conclude phase for a native-analysis task.

# Task
Summarize only the already confirmed native/JNI findings for the current Intent. Do not continue analysis.

# Output Requirements
Return only one raw JSON object. Do not output anything else.

```json
{"accepted": true, "data": {"description": "..."}}
```

# Rules
- Stop immediately and summarize.
- Only include confirmed incremental findings with offsets/symbols if known.

# Context
## Graph
```
{graph_yaml}
```

## Current Intent
```
{intent_id}
```

## Current Intent Description
```
{intent_description}
```
