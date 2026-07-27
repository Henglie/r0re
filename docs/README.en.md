# r0re User Guide

`r0re` is a local orchestration tool for Android reverse engineering and CTF analysis. It provides a Web UI for project state and a dispatcher that starts Docker workers powered by Kimi Code.

## Project Lineage

`r0re` started as a secondary development effort based on [oritera/Cairn](https://github.com/oritera/Cairn). Cairn is an AI general-purpose state-space search engine first validated on autonomous penetration testing. Cairn is maintained by [起零衍迹 / oritera](https://github.com/oritera), an open-source organization focused on AI applications and Agent engineering, with security offense and defense as one of its long-term directions.

During `r0re`'s own development, this project also learned from [FishCodeTech/muteki](https://github.com/FishCodeTech/muteki), especially its shared blackboard approach for multi-agent CTF collaboration.

The public repository ships the Kimi swarm template: `dispatch.kimi.swarm.example.yaml`. Real API keys, APK samples, and analysis output stay on your machine.

## 1. Requirements

Install these on the host:

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

## 2. Install

```bash
git clone https://github.com/fyrlove/r0re.git
cd r0re
./scripts/setup-dev.sh
```

`setup-dev.sh` will:

- Create local `.env` from `.env.example`
- Create local `dispatch.kimi.swarm.yaml` from `dispatch.kimi.swarm.example.yaml`
- Create `output/` and `container-android/test_apk/`
- Build the Docker worker image `cairn-android-reverse:latest`
- Build the r0re Spring Boot jar

## 3. Configure Kimi

Edit `.env`:

```env
KIMI_MODEL_API_KEY=your_kimi_key
```

Load the environment:

```bash
source .env
```

`.env` and `dispatch.kimi.swarm.yaml` are local files and should not be committed.

## 4. Model Configuration

During development and testing, `r0re` was used with GPT-5.4, GPT-5.5, GLM-5.1, and Kimi K3. The public repository only keeps the Kimi K3 configuration template.

If you want to use another AI provider or model, clone the project first and ask your local AI coding assistant to add the corresponding dispatch configuration based on `dispatch.kimi.swarm.example.yaml`.

## 5. Worker Count

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

## 6. Add APKs

Place APK files under:

```text
container-android/test_apk/
```

Example:

```text
container-android/test_apk/challenge.apk
```

## 7. Run

```bash
source .env
./start-r0re.sh --no-bridge --config=dispatch.kimi.swarm.yaml
```

Open the Web UI:

```text
http://127.0.0.1:8001
```

After you create or resume a project in the Web UI, the dispatcher starts Kimi workers according to `dispatch.kimi.swarm.yaml`.

## 8. Example: Kanxue Android CTF Challenge

The screenshot below shows a completed run for an Android CTF challenge sample from the Kanxue forum. The project reached `COMPLETED`, and the log panel shows the finalizer verifying and closing the result.

![r0re Android CTF result](img.png)

Example project input:

```text
Origin:
Analyze the Kanxue Android CTF APK placed under container-android/test_apk/. Recover how the app validates the accepted input across Java, JNI, and native code.

Goal:
Recover the final accepted flag/input and provide a reproducible verification path showing that the Java/native checks accept it.

Hint:
This is an Android reverse-engineering CTF task. Prioritize the Java entry point, JNI bridge, native libraries, runtime-decrypted logic, and anti-debugging checks. Use static analysis first, then confirm candidates with local execution or instrumentation when needed.
```

The final result from this example run:

```text
FLAG='kboloy0'
```

Example status shown in the screenshot:

- Project: `swarm-e2e-AliCrackme2`
- Status: `COMPLETED`
- Result graph: 27 facts, 4 intents
- Key outcome: a Kimi worker produced the candidate, and the finalizer verified and closed the project

## 9. Stop

```bash
./start-r0re.sh --stop --no-bridge --port=8001 --config=dispatch.kimi.swarm.yaml
```

## 10. macOS Background Service

Install a launchd service:

```bash
source .env
R0RE_PORT=8001 R0RE_CONFIG="$PWD/dispatch.kimi.swarm.yaml" ./scripts/r0re-launchd-install.sh
```

Check status:

```bash
./scripts/r0re-launchd-status.sh
```

Uninstall:

```bash
./scripts/r0re-launchd-uninstall.sh
```

## 11. Troubleshooting

### Docker daemon is not running

Start Docker Desktop or your Docker service, then run:

```bash
./scripts/check-env.sh
```

### Java is too old

Java 17 or newer is required. Check:

```bash
java -version
```

### Gradle is missing

Install Gradle, or add a project-local `./gradlew`, then rerun:

```bash
./scripts/setup-dev.sh
```

### Web UI does not open

Check service health:

```bash
./scripts/r0re-health.sh 8001
```

Logs are written to:

```text
output/r0re-serve.log
output/r0re-dispatch.log
```

## 12. Acknowledgements

`r0re` builds on ideas and engineering experience from these open-source projects:

- [oritera/Cairn](https://github.com/oritera/Cairn): the original foundation for the project and its state-space search / fact-intent orchestration model.
- [FishCodeTech/muteki](https://github.com/FishCodeTech/muteki): inspiration for the shared blackboard mechanism used in multi-agent CTF workflows.
