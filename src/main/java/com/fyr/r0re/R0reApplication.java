package com.fyr.r0re;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.WebApplicationType;
import org.springframework.scheduling.annotation.EnableScheduling;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class R0reApplication {
    public static void main(String[] args) {
        if (args.length > 0 && "dispatch".equals(args[0])) {
            SpringApplication app = new SpringApplication(R0reApplication.class);
            app.setWebApplicationType(WebApplicationType.NONE);
            app.run(dispatchArgs(args));
            return;
        }
        SpringApplication.run(R0reApplication.class, serveArgs(args));
    }

    private static String[] serveArgs(String[] args) {
        if (args.length > 0 && "serve".equals(args[0])) {
            return Arrays.copyOfRange(args, 1, args.length);
        }
        return args;
    }

    private static String[] dispatchArgs(String[] args) {
        List<String> forwarded = new ArrayList<>();
        forwarded.add("--r0re.dispatcher.enabled=true");
        forwarded.add("--r0re.dispatcher.standalone-invocation=true");
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if ("--config".equals(arg) && i + 1 < args.length) {
                forwarded.add("--r0re.dispatcher.config=" + args[++i]);
            } else if ("--once".equals(arg)) {
                forwarded.add("--r0re.dispatcher.once=true");
            } else if ("--startup-healthcheck-only".equals(arg)) {
                forwarded.add("--r0re.dispatcher.startup-healthcheck-only=true");
            } else if ("--allow-embedded".equals(arg)) {
                forwarded.add("--r0re.dispatcher.standalone-only=false");
            } else {
                forwarded.add(arg);
            }
        }
        return forwarded.toArray(String[]::new);
    }
}
