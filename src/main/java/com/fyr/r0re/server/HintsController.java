package com.fyr.r0re.server;

import com.fyr.r0re.protocol.Models.CreateHintRequest;
import com.fyr.r0re.protocol.Models.Hint;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
public class HintsController {
    private final R0reService service;

    public HintsController(R0reService service) {
        this.service = service;
    }

    @PostMapping("/projects/{projectId}/hints")
    @ResponseStatus(HttpStatus.CREATED)
    public Hint createHint(@PathVariable("projectId") String projectId, @Valid @RequestBody CreateHintRequest body) {
        return service.createHint(projectId, body);
    }

    @DeleteMapping("/projects/{projectId}/hints/{hintId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteHint(@PathVariable("projectId") String projectId, @PathVariable("hintId") String hintId) {
        service.deleteHint(projectId, hintId);
    }
}
