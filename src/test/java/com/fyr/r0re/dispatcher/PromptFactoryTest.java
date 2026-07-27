package com.fyr.r0re.dispatcher;

import com.fyr.r0re.protocol.Models.Fact;
import com.fyr.r0re.protocol.Models.Hint;
import com.fyr.r0re.protocol.Models.Intent;
import com.fyr.r0re.protocol.Models.ProjectDetail;
import com.fyr.r0re.protocol.Models.ProjectMeta;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptFactoryTest {
    @Test
    void bootstrapPromptEmphasizesFastReconAndReuse() {
        PromptFactory prompts = new PromptFactory("android-reverse");

        String prompt = prompts.bootstrap(new ProjectDetail(
                new ProjectMeta("proj_001", "demo", "active", true, "2026-06-12T00:00:00Z", null),
                List.of(new Fact("origin", "origin"), new Fact("goal", "goal")),
                List.of(),
                List.of(new Hint("h001", "hint", "Human", "2026-06-12T00:00:00Z"))
        ));

        assertTrue(prompt.contains("Step 1: Target Identification"));
        assertTrue(prompt.contains("Step 2: Unpacking & Decompilation"));
        assertTrue(prompt.contains("Report Files (MANDATORY)"));
        assertTrue(prompt.contains("Only return when Goal is definitively achieved"));
    }

    @Test
    void exportGraphYamlIncludesDeadEndsSectionAndVerifiedMarker() {
        PromptFactory prompts = new PromptFactory("android-reverse");

        String yaml = prompts.exportGraphYaml(new ProjectDetail(
                new ProjectMeta("proj_001", "demo", "active", true, "2026-06-12T00:00:00Z", null),
                List.of(
                        new Fact("origin", "origin"),
                        new Fact("goal", "goal"),
                        new Fact("f001", "verified finding", "fact", true, "pi"),
                        new Fact("f002", "wrong branch", "dead_end", false, "pi")
                ),
                List.of(),
                List.of()
        ));

        assertTrue(yaml.contains("dead_ends:"));
        assertTrue(yaml.contains("wrong branch"));
        assertTrue(yaml.contains("verified: true"));
    }

    @Test
    void nativeExplorePromptUsesSmcSpecializationWhenIntentMentionsSmc() {
        PromptFactory prompts = new PromptFactory("android-ctf");

        String prompt = prompts.explore(new ProjectDetail(
                        new ProjectMeta("proj_015", "demo", "active", true, "2026-06-17T00:00:00Z", null),
                        List.of(new Fact("origin", "origin"), new Fact("goal", "goal")),
                        List.of(),
                        List.of()
                ),
                new Intent(
                        "i004",
                        List.of("origin"),
                        null,
                        "Reverse engineer SMC-decrypted validation code at 0x24c8 in libwbox.so",
                        "pi",
                        null,
                        null,
                        "2026-06-17T00:00:00Z",
                        null
                ));

        assertTrue(prompt.contains("specialized SMC/native-unpacking agent"));
        assertTrue(prompt.contains("Recover the decrypt orchestrator"));
    }

    @Test
    void racePromptReplacesOriginGoalHintsAndGraphReference() {
        PromptFactory prompts = new PromptFactory("android-reverse");

        String prompt = prompts.race(new ProjectDetail(
                new ProjectMeta("proj_040", "demo", "active", true, "2026-07-22T00:00:00Z", null),
                List.of(new Fact("origin", "analyze crackme.apk"), new Fact("goal", "recover the flag")),
                List.of(),
                List.of(new Hint("h001", "focus libwbox.so", "Human", "2026-07-22T00:00:00Z"))
        ), "graph snapshot at /tmp/cairn-prompts/race/graph.yaml");

        assertTrue(prompt.contains("RACE phase"));
        assertTrue(prompt.contains("analyze crackme.apk"));
        assertTrue(prompt.contains("recover the flag"));
        assertTrue(prompt.contains("focus libwbox.so"));
        assertTrue(prompt.contains("graph snapshot at /tmp/cairn-prompts/race/graph.yaml"));
        assertTrue(!prompt.contains("{origin}"));
        assertTrue(!prompt.contains("{goal}"));
        assertTrue(!prompt.contains("{hints}"));
        assertTrue(!prompt.contains("{graph_yaml}"));
    }

    @Test
    void swarmExplorePromptReplacesPlaceholdersWithoutIntentReference() {
        PromptFactory prompts = new PromptFactory("android-reverse");

        String prompt = prompts.swarmExplore(new ProjectDetail(
                new ProjectMeta("proj_040", "demo", "active", true, "2026-07-22T00:00:00Z", null),
                List.of(new Fact("origin", "analyze crackme.apk"), new Fact("goal", "recover the flag")),
                List.of(),
                List.of()
        ), "graph snapshot reference");

        assertTrue(prompt.contains("swarm worker"));
        assertTrue(prompt.contains("analyze crackme.apk"));
        assertTrue(prompt.contains("recover the flag"));
        assertTrue(prompt.contains("graph snapshot reference"));
        assertTrue(!prompt.contains("{origin}"));
        assertTrue(!prompt.contains("{goal}"));
        assertTrue(!prompt.contains("{graph_yaml}"));
        assertTrue(!prompt.contains("{intent_id}"));
        assertTrue(!prompt.contains("{intent_description}"));
    }

    @Test
    void swarmPromptsFallBackToDefaultGroup() {
        PromptFactory prompts = new PromptFactory("no-such-group");
        ProjectDetail project = new ProjectDetail(
                new ProjectMeta("proj_040", "demo", "active", true, "2026-07-22T00:00:00Z", null),
                List.of(new Fact("origin", "origin"), new Fact("goal", "goal")),
                List.of(),
                List.of()
        );

        assertTrue(prompts.race(project, null).contains("RACE phase"));
        assertTrue(prompts.swarmExplore(project, null).contains("swarm worker"));
    }
}
