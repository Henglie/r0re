package com.fyr.r0re.server;

import com.fyr.r0re.protocol.Models.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
public class IntentsController {
    private final R0reService service;

    public IntentsController(R0reService service) {
        this.service = service;
    }

    @PostMapping("/projects/{projectId}/intents")
    @ResponseStatus(HttpStatus.CREATED)
    public Intent createIntent(@PathVariable("projectId") String projectId, @Valid @RequestBody CreateIntentRequest body) {
        return service.createIntent(projectId, body);
    }

    @PostMapping("/projects/{projectId}/intents/{intentId}/heartbeat")
    public Intent heartbeat(@PathVariable("projectId") String projectId, @PathVariable("intentId") String intentId, @Valid @RequestBody HeartbeatRequest body) {
        return service.heartbeat(projectId, intentId, body);
    }

    @PostMapping("/projects/{projectId}/intents/{intentId}/release")
    public Intent release(@PathVariable("projectId") String projectId, @PathVariable("intentId") String intentId, @Valid @RequestBody HeartbeatRequest body) {
        return service.release(projectId, intentId, body);
    }

    @PostMapping("/projects/{projectId}/intents/{intentId}/conclude")
    public ConcludeResponse conclude(@PathVariable("projectId") String projectId, @PathVariable("intentId") String intentId, @Valid @RequestBody ConcludeRequest body) {
        return service.conclude(projectId, intentId, body);
    }
}
