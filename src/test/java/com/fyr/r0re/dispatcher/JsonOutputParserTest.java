package com.fyr.r0re.dispatcher;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JsonOutputParserTest {
    private final JsonOutputParser parser = new JsonOutputParser();

    @Test
    void parseBootstrapCompleteReturnsFactAndComplete() {
        WorkerResult result = parser.parseBootstrap("""
                {
                  "accepted": true,
                  "data": {
                    "fact": {"description": "key fact"},
                    "complete": {"description": "goal reached"}
                  }
                }
                """);

        assertInstanceOf(WorkerResult.BootstrapComplete.class, result);
        WorkerResult.BootstrapComplete complete = (WorkerResult.BootstrapComplete) result;
        assertEquals("key fact", complete.factDescription());
        assertEquals("goal reached", complete.completeDescription());
    }

    @Test
    void parseRejectedPayloadReturnsRejected() {
        WorkerResult result = parser.parseReason("""
                {
                  "accepted": false
                }
                """);

        assertInstanceOf(WorkerResult.Rejected.class, result);
    }

    @Test
    void parseReasonAcceptedFalseWithIntentsReturnsRejected() {
        WorkerResult result = parser.parseReason("""
                {
                  "accepted": false,
                  "data": {
                    "intents": [
                      {"description": "disassemble 0x24c8"},
                      {"description": "recover xor key"}
                    ]
                  }
                }
                """);

        assertInstanceOf(WorkerResult.Rejected.class, result);
    }

    @Test
    void parseBootstrapFactOnlyReturnsFact() {
        WorkerResult result = parser.parseBootstrap("""
                {
                  "accepted": true,
                  "data": {
                    "fact": {"description": "Java entry and native JNI bridge confirmed."}
                  }
                }
                """);

        assertInstanceOf(WorkerResult.Fact.class, result);
        assertEquals("Java entry and native JNI bridge confirmed.", ((WorkerResult.Fact) result).description());
    }

    @Test
    void parseBootstrapConcludeReturnsFact() {
        WorkerResult result = parser.parseBootstrapConclude("""
                {
                  "accepted": true,
                  "data": {
                    "fact": {"description": "Java entry and native JNI bridge confirmed."}
                  }
                }
                """);

        assertInstanceOf(WorkerResult.Fact.class, result);
        assertEquals("Java entry and native JNI bridge confirmed.", ((WorkerResult.Fact) result).description());
    }

    @Test
    void parseBootstrapAcceptedFalsePolicyStillRejected() {
        WorkerResult result = parser.parseBootstrap("""
                {
                  "accepted": false,
                  "data": {
                    "fact": {"description": "partial findings are ignored on rejected bootstrap like Cairn"}
                  },
                  "reason": "not complete"
                }
                """);

        assertInstanceOf(WorkerResult.Rejected.class, result);
    }

    @Test
    void parseReasonRequiresIntentSourcesLikeCairn() {
        assertThrows(IllegalArgumentException.class, () -> parser.parseReason("""
                {
                  "accepted": true,
                  "data": {
                    "intents": [
                      {"description": "recover xor key"}
                    ]
                  }
                }
                """));
    }

    @Test
    void parseReasonPreservesIntentSources() {
        WorkerResult result = parser.parseReason("""
                {
                  "accepted": true,
                  "data": {
                    "intents": [
                      {"from": ["origin", "f001"], "description": "verify native compare"}
                    ]
                  }
                }
                """);

        assertInstanceOf(WorkerResult.Intents.class, result);
        WorkerResult.Intents intents = (WorkerResult.Intents) result;
        assertEquals(java.util.List.of("origin", "f001"), intents.items().get(0).from());
        assertEquals("verify native compare", intents.items().get(0).description());
    }

    @Test
    void parseReasonAcceptsSingularIntentLikeCairn() {
        WorkerResult result = parser.parseReason("""
                {
                  "accepted": true,
                  "data": {
                    "intent": {"from": ["f002"], "description": "recover xor key"}
                  }
                }
                """);

        assertInstanceOf(WorkerResult.Intents.class, result);
        WorkerResult.Intents intents = (WorkerResult.Intents) result;
        assertEquals(java.util.List.of("f002"), intents.items().get(0).from());
        assertEquals(java.util.List.of("recover xor key"), intents.descriptions());
    }

    @Test
    void parseSwarmCompleteWithoutFromDefaultsToOrigin() {
        WorkerResult result = parser.parseSwarm("""
                {"accepted": true, "data": {"complete": {"description": "flag recovered"}}}
                """);

        assertInstanceOf(WorkerResult.Complete.class, result);
        WorkerResult.Complete complete = (WorkerResult.Complete) result;
        assertEquals("flag recovered", complete.description());
        assertEquals(java.util.List.of("origin"), complete.from());
    }

    @Test
    void parseSwarmCompleteWithFromKeepsSources() {
        WorkerResult result = parser.parseSwarm("""
                {"accepted": true, "data": {"complete": {"description": "flag recovered", "from": ["f001", "f003"]}}}
                """);

        assertInstanceOf(WorkerResult.Complete.class, result);
        WorkerResult.Complete complete = (WorkerResult.Complete) result;
        assertEquals(java.util.List.of("f001", "f003"), complete.from());
    }

    @Test
    void parseSwarmDescriptionReturnsFact() {
        WorkerResult result = parser.parseSwarm("""
                {"accepted": true, "data": {"description": "validator at 0x24c8"}}
                """);

        assertInstanceOf(WorkerResult.Fact.class, result);
        assertEquals("validator at 0x24c8", ((WorkerResult.Fact) result).description());
    }

    @Test
    void parseSwarmOrNullToleratesMissingTerminalJson() {
        assertNull(parser.parseSwarmOrNull("worker wrote everything to the blackboard and exited"));
        assertNull(parser.parseSwarmOrNull(""));
        assertThrows(IllegalArgumentException.class, () -> parser.parseSwarm("no json here"));
    }
}
