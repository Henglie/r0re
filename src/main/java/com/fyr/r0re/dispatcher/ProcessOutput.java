package com.fyr.r0re.dispatcher;

public record ProcessOutput(int code, String stdout, String stderr, String stopReason) {
    public ProcessOutput(int code, String stdout, String stderr) {
        this(code, stdout, stderr, null);
    }

    public boolean timedOut() {
        return "timeout".equals(stopReason);
    }

    public boolean stalled() {
        return "stalled".equals(stopReason);
    }
}
