package com.fyr.r0re.server;

import com.fyr.r0re.protocol.Models.Settings;
import org.springframework.web.bind.annotation.*;

@RestController
public class SettingsController {
    private final R0reService service;

    public SettingsController(R0reService service) {
        this.service = service;
    }

    @GetMapping("/settings")
    public Settings settings() {
        return service.settings();
    }

    @PutMapping("/settings")
    public Settings updateSettings(@RequestBody Settings settings) {
        return service.updateSettings(settings);
    }
}
