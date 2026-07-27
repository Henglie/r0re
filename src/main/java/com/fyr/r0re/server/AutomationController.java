package com.fyr.r0re.server;

import com.fyr.r0re.protocol.Models.AutomationJob;
import com.fyr.r0re.protocol.Models.AutomationRunResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AutomationController {
    private final R0reService service;

    public AutomationController(R0reService service) {
        this.service = service;
    }

    @GetMapping("/automation/jobs")
    public List<AutomationJob> jobs() {
        return service.automationJobs();
    }

    @PostMapping("/automation/jobs/{jobId}/run")
    public AutomationRunResponse runJob(@PathVariable("jobId") String jobId) {
        return service.runAutomation(jobId);
    }

    @PostMapping("/automation/run")
    public AutomationRunResponse runAll() {
        return service.runAutomation("all");
    }
}
