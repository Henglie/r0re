package com.fyr.r0re.dispatcher;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DispatchConfig(
        String server,
        Map<String, String> common_env,
        RuntimeConfig runtime,
        VerifierConfig verifier,
        TasksConfig tasks,
        ContainerConfig container,
        List<WorkerConfig> workers
) {
    public RuntimeConfig runtimeOrDefault() {
        return runtime == null ? new RuntimeConfig(3, 4, 2, 2, 30, "disabled", "default", null, null) : runtime;
    }

    public VerifierConfig verifierOrDefault() {
        return verifier == null ? new VerifierConfig("r0re.rule-verifier", "reverse-verifier", "android-ctf-verifier", "rule-verifier", "verify", "reverse-verifier", "android-ctf-verifier") : verifier;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RuntimeConfig(
            int interval,
            int max_workers,
            int max_running_projects,
            int max_project_workers,
            int healthcheck_timeout,
            String worker_healthcheck,
            String prompt_group,
            Boolean completion_auto_finalize,
            String mode
    ) {
        /** Backward-compatible constructor without mode (defaults to classic). */
        public RuntimeConfig(int interval, int max_workers, int max_running_projects, int max_project_workers,
                             int healthcheck_timeout, String worker_healthcheck, String prompt_group,
                             Boolean completion_auto_finalize) {
            this(interval, max_workers, max_running_projects, max_project_workers, healthcheck_timeout,
                    worker_healthcheck, prompt_group, completion_auto_finalize, null);
        }

        public boolean completionAutoFinalize() {
            return completion_auto_finalize == null || completion_auto_finalize;
        }

        /** "classic" (default, dispatcher pre-claims intents) or "swarm" (muteki-style: race + worker self-claim). */
        public String modeOrDefault() {
            return mode == null || mode.isBlank() ? "classic" : mode.trim();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record VerifierConfig(
            String default_worker,
            String reverse_worker,
            String android_ctf_worker,
            String tool,
            String prompt_group,
            String reverse_prompt_group,
            String android_ctf_prompt_group
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TasksConfig(TaskTimeout bootstrap, ReasonTask reason, TaskTimeout explore) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TaskTimeout(int timeout, int conclude_timeout) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReasonTask(int timeout, int max_intents) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ContainerConfig(String image, String network_mode, String completed_action, Map<String, Volume> volumes, List<String> cap_add, List<String> extra_hosts, Map<String, String> container_env) {
        public List<String> capAdd() {
            return cap_add == null ? List.of() : cap_add;
        }
        public List<String> extraHosts() {
            return extra_hosts == null ? List.of() : extra_hosts;
        }
        public Map<String, String> containerEnv() {
            return container_env == null ? Map.of() : container_env;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Volume(String bind, String mode) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WorkerConfig(
            String name,
            String type,
            List<String> task_types,
            int max_running,
            int priority,
            Map<String, String> env
    ) {
        public boolean supports(String taskType) {
            return task_types == null || task_types.contains(taskType);
        }
    }
}
