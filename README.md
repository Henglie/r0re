# r0re

English guide: [docs/README.en.md](docs/README.en.md)

中文文档：[docs/README.zh-CN.md](docs/README.zh-CN.md)

`r0re` is a local orchestration service for Android reverse engineering and CTF analysis. It provides a Web UI, keeps project state, and dispatches containerized Android analysis workers powered by Kimi Code.

## Project Lineage

`r0re` started as a secondary development effort based on [oritera/Cairn](https://github.com/oritera/Cairn). Cairn is an AI general-purpose state-space search engine first validated on autonomous penetration testing. Cairn is maintained by [起零衍迹 / oritera](https://github.com/oritera), an open-source organization focused on AI applications and Agent engineering, with security offense and defense as one of its long-term directions.

During `r0re`'s own development, this project also learned from [FishCodeTech/muteki](https://github.com/FishCodeTech/muteki), especially its shared blackboard approach for multi-agent CTF collaboration.

## What You Need

- Docker or Docker Desktop
- Java 17+
- Gradle, or a project-local `./gradlew`
- `curl`
- `python3`
- A Kimi API key

Check your environment:

```bash
./scripts/check-env.sh
```

## Quick Start

```bash
git clone https://github.com/fyrlove/r0re.git
cd r0re
./scripts/setup-dev.sh
```

Edit `.env` and fill your Kimi key:

```env
KIMI_MODEL_API_KEY=your_kimi_key
```

## Model Configuration

During development and testing, `r0re` was used with GPT-5.4, GPT-5.5, GLM-5.1, and Kimi K3. The public repository only keeps the Kimi K3 configuration template.

If you want to use another AI provider or model, clone the project first and ask your local AI coding assistant to add the corresponding dispatch configuration based on `dispatch.kimi.swarm.example.yaml`.

## Worker Count

The number of active workers is configurable in `dispatch.kimi.swarm.yaml`. The default template uses 3 Kimi workers:

```yaml
runtime:
  max_workers: 3
  max_project_workers: 3

workers:
  - name: "kimi_swarm"
    max_running: 3
```

You can set these values to 1, 2, or 3 based on your machine and API quota. The current project has only been tested up to 3 workers.

Start r0re:

```bash
source .env
./start-r0re.sh --no-bridge --config=dispatch.kimi.swarm.yaml
```

Open the Web UI:

```text
http://127.0.0.1:8001
```

## Analyze APKs

Put APK files under:

```text
container-android/test_apk/
```

Then create or resume a project in the Web UI. The dispatcher will start Docker workers according to `dispatch.kimi.swarm.yaml`.

## Example Result

The screenshot below shows a completed Android CTF run based on a Kanxue forum challenge sample:

![r0re Android CTF result](docs/img.png)

Example project input:

```text
Origin:
Analyze the Kanxue Android CTF APK placed under container-android/test_apk/. Recover how the app validates the accepted input across Java, JNI, and native code.

Goal:
Recover the final accepted flag/input and provide a reproducible verification path showing that the Java/native checks accept it.

Hint:
This is an Android reverse-engineering CTF task. Prioritize the Java entry point, JNI bridge, native libraries, runtime-decrypted logic, and anti-debugging checks. Use static analysis first, then confirm candidates with local execution or instrumentation when needed.
```

In this example run, `r0re` completed the project and recovered:

```text
FLAG='kboloy0'
```

## Stop

```bash
./start-r0re.sh --stop --no-bridge --port=8001 --config=dispatch.kimi.swarm.yaml
```

## Local Files

These files are generated locally and should not be committed:

- `.env`
- `dispatch.kimi.swarm.yaml`
- `output/`
- `container-android/test_apk/`

The repository only includes `dispatch.kimi.swarm.example.yaml`. Copying it to `dispatch.kimi.swarm.yaml` is handled by `./scripts/setup-dev.sh`.

## Acknowledgements

`r0re` builds on ideas and engineering experience from these open-source projects:

- [oritera/Cairn](https://github.com/oritera/Cairn): the original foundation for the project and its state-space search / fact-intent orchestration model.
- [FishCodeTech/muteki](https://github.com/FishCodeTech/muteki): inspiration for the shared blackboard mechanism used in multi-agent CTF workflows.
