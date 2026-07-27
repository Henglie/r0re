package com.fyr.r0re.dispatcher;

import java.util.List;

public sealed interface WorkerResult permits WorkerResult.Fact, WorkerResult.Intents, WorkerResult.Complete, WorkerResult.BootstrapComplete, WorkerResult.Rejected {
    record Fact(String description) implements WorkerResult {
    }

    record ProposedIntent(List<String> from, String description) {
    }

    record Intents(List<ProposedIntent> items) implements WorkerResult {
        public Intents {
            items = items == null ? List.of() : List.copyOf(items);
        }

        public static Intents fromDescriptions(List<String> descriptions) {
            if (descriptions == null || descriptions.isEmpty()) {
                return new Intents(List.of());
            }
            return new Intents(descriptions.stream()
                    .filter(description -> description != null && !description.isBlank())
                    .map(description -> new ProposedIntent(List.of("origin"), description.trim()))
                    .toList());
        }

        public List<String> descriptions() {
            return items.stream().map(ProposedIntent::description).toList();
        }
    }

    record Complete(String description, List<String> from) implements WorkerResult {
    }

    record BootstrapComplete(String factDescription, String completeDescription) implements WorkerResult {
    }

    record Rejected(String reason) implements WorkerResult {
    }
}
