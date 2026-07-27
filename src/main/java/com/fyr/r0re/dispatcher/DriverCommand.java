package com.fyr.r0re.dispatcher;

import java.util.List;

public record DriverCommand(List<String> argv, String session) {
}
