package com.fyr.r0re.server;

import com.fyr.r0re.protocol.Models.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
public class BlackboardController {
    private final R0reService service;

    public BlackboardController(R0reService service) {
        this.service = service;
    }

    @GetMapping("/projects/{projectId}/blackboard")
    public BlackboardView view(@PathVariable("projectId") String projectId) {
        return service.blackboardView(projectId);
    }

    @PostMapping("/projects/{projectId}/blackboard/facts")
    @ResponseStatus(HttpStatus.CREATED)
    public Fact addFact(@PathVariable("projectId") String projectId, @Valid @RequestBody BlackboardFactRequest body) {
        return service.blackboardAddFact(projectId, body);
    }

    @PostMapping("/projects/{projectId}/blackboard/deadends")
    @ResponseStatus(HttpStatus.CREATED)
    public Fact addDeadEnd(@PathVariable("projectId") String projectId, @Valid @RequestBody BlackboardFactRequest body) {
        return service.blackboardAddDeadEnd(projectId, body);
    }
}
