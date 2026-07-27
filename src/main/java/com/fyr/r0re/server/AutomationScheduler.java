package com.fyr.r0re.server;

import com.fyr.r0re.config.R0reProperties;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AutomationScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(AutomationScheduler.class);

    private final R0reService service;
    private final R0reProperties properties;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public AutomationScheduler(R0reService service, R0reProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @Scheduled(
            initialDelayString = "#{${r0re.automation.scheduler-interval-seconds:60} * 1000}",
            fixedDelayString = "#{${r0re.automation.scheduler-interval-seconds:60} * 1000}"
    )
    public void tick() {
        if (!properties.getAutomation().isEnabled()) {
            return;
        }
        if (properties.getDispatcher().isEnabled() && properties.getDispatcher().isStandaloneInvocation()) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            var dueJobs = service.dueAutomationJobs();
            if (dueJobs.isEmpty()) {
                return;
            }
            var result = service.runDueAutomationJobs();
            LOG.info("automation scheduler ran jobs={} findings={} status={}",
                    dueJobs.stream().map(job -> job.id()).toList(),
                    result.findings(),
                    result.status());
        } catch (Exception exc) {
            LOG.warn("automation scheduler tick failed: {}", exc.toString());
        } finally {
            running.set(false);
        }
    }
}
