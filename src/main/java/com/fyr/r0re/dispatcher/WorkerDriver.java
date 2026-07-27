package com.fyr.r0re.dispatcher;

public interface WorkerDriver {
    default boolean shouldStopEarly(String stdout, String stderr) {
        return false;
    }

    default boolean isRetryableOutput(String stdout, String stderr) {
        return false;
    }

    default boolean supportsConclude() {
        return true;
    }

    default String prepareSession() {
        return null;
    }

    default java.util.List<String> buildStartupHealthcheck(DispatchConfig.WorkerConfig worker) {
        return buildHealthcheck(worker);
    }

    java.util.List<String> buildHealthcheck(DispatchConfig.WorkerConfig worker);

    DriverCommand buildExecute(DispatchConfig.WorkerConfig worker, String prompt, String session);

    java.util.List<String> buildConclude(DispatchConfig.WorkerConfig worker, String prompt, String session);

    default String extractSession(String session, String stdout, String stderr) {
        return session;
    }

    default String extractResponseText(String stdout, String stderr) {
        return stdout;
    }
}
