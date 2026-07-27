package com.fyr.r0re.dispatcher;

import java.util.concurrent.atomic.AtomicReference;

public class TaskCancellation {
    private final AtomicReference<String> reason = new AtomicReference<>();

    public boolean cancel(String value) {
        return reason.compareAndSet(null, value);
    }

    public boolean isCancelled() {
        return reason.get() != null;
    }

    public String reason() {
        return reason.get();
    }
}
