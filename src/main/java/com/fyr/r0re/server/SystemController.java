package com.fyr.r0re.server;

import com.fyr.r0re.protocol.Models.SystemStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SystemController {
    private final SystemService service;

    public SystemController(SystemService service) {
        this.service = service;
    }

    @GetMapping("/system/status")
    public SystemStatus status() {
        return service.systemStatus();
    }
}
