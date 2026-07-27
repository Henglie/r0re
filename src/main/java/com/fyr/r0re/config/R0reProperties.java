package com.fyr.r0re.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "r0re")
public class R0reProperties {
    private final Dispatcher dispatcher = new Dispatcher();
    private final Automation automation = new Automation();
    private final Completion completion = new Completion();

    public Dispatcher getDispatcher() {
        return dispatcher;
    }

    public Automation getAutomation() {
        return automation;
    }

    public Completion getCompletion() {
        return completion;
    }

    public static class Dispatcher {
        private boolean enabled;
        private String config = "dispatch.yaml";
        private boolean once;
        private boolean startupHealthcheckOnly;
        private boolean standaloneOnly = true;
        private boolean standaloneInvocation;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getConfig() {
            return config;
        }

        public void setConfig(String config) {
            this.config = config;
        }

        public boolean isOnce() {
            return once;
        }

        public void setOnce(boolean once) {
            this.once = once;
        }

        public boolean isStartupHealthcheckOnly() {
            return startupHealthcheckOnly;
        }

        public void setStartupHealthcheckOnly(boolean startupHealthcheckOnly) {
            this.startupHealthcheckOnly = startupHealthcheckOnly;
        }

        public boolean isStandaloneOnly() {
            return standaloneOnly;
        }

        public void setStandaloneOnly(boolean standaloneOnly) {
            this.standaloneOnly = standaloneOnly;
        }

        public boolean isStandaloneInvocation() {
            return standaloneInvocation;
        }

        public void setStandaloneInvocation(boolean standaloneInvocation) {
            this.standaloneInvocation = standaloneInvocation;
        }
    }

    public static class Automation {
        private boolean enabled = true;
        private int schedulerIntervalSeconds = 60;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getSchedulerIntervalSeconds() {
            return schedulerIntervalSeconds;
        }

        public void setSchedulerIntervalSeconds(int schedulerIntervalSeconds) {
            this.schedulerIntervalSeconds = schedulerIntervalSeconds;
        }
    }

    public static class Completion {
        private boolean requireVerifierForCompletion = true;
        private String reverseCompletionPolicy = "solver_or_verifier_required";

        public boolean isRequireVerifierForCompletion() {
            return requireVerifierForCompletion;
        }

        public void setRequireVerifierForCompletion(boolean requireVerifierForCompletion) {
            this.requireVerifierForCompletion = requireVerifierForCompletion;
        }

        public String getReverseCompletionPolicy() {
            return reverseCompletionPolicy;
        }

        public void setReverseCompletionPolicy(String reverseCompletionPolicy) {
            this.reverseCompletionPolicy = reverseCompletionPolicy;
        }
    }
}
