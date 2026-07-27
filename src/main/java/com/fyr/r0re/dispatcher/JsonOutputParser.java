package com.fyr.r0re.dispatcher;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class JsonOutputParser {
    private static final Pattern FENCE = Pattern.compile("```(?:json)?\\s*\\n?(.*?)```", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private final ObjectMapper mapper = new ObjectMapper();

    public WorkerResult parseExplore(String text) {
        Map<String, Object> payload = extractObject(text);
        Map<String, Object> data = unwrapAccepted(payload);
        if (Boolean.TRUE.equals(data.get("rejected"))) {
            return new WorkerResult.Rejected("rejected");
        }
        Object description = data.get("description");
        if (!(description instanceof String value) || value.isBlank()) {
            throw new IllegalArgumentException("description is required");
        }
        return new WorkerResult.Fact(value.trim());
    }

    public WorkerResult parseReason(String text) {
        Map<String, Object> payload = extractObject(text);
        Map<String, Object> data = unwrapReasonPayload(payload);
        if (Boolean.TRUE.equals(data.get("rejected"))) {
            return new WorkerResult.Rejected("rejected");
        }
        Object complete = data.get("complete");
        if (complete instanceof Map<?, ?> map) {
            Object description = map.get("description");
            Object from = map.get("from");
            if (!(description instanceof String value) || value.isBlank()) {
                throw new IllegalArgumentException("complete.description is required");
            }
            List<String> sources = requiredStringList(from, "complete.from");
            return new WorkerResult.Complete(value.trim(), sources);
        }
        Object intents = data.get("intents");
        if (intents == null && data.get("intent") instanceof Map<?, ?>) {
            intents = List.of(data.get("intent"));
        }
        if (intents instanceof List<?> list) {
            List<WorkerResult.ProposedIntent> items = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                Object item = list.get(i);
                if (!(item instanceof Map<?, ?> map)) {
                    throw new IllegalArgumentException("invalid intent at index " + i);
                }
                Object description = map.get("description");
                if (!(description instanceof String value) || value.isBlank()) {
                    throw new IllegalArgumentException("intent.description is required at index " + i);
                }
                items.add(new WorkerResult.ProposedIntent(requiredStringList(map.get("from"), "intent.from"), value.trim()));
            }
            return new WorkerResult.Intents(items);
        }
        return new WorkerResult.Intents(List.of());
    }

    public WorkerResult parseBootstrap(String text) {
        Map<String, Object> payload = extractObject(text);
        Map<String, Object> data = unwrapAccepted(payload);
        if (Boolean.TRUE.equals(data.get("rejected"))) {
            return new WorkerResult.Rejected("rejected");
        }
        Object fact = data.get("fact");
        if (!(fact instanceof Map<?, ?> factMap)) {
            throw new IllegalArgumentException("fact is required");
        }
        Object factDescription = factMap.get("description");
        if (!(factDescription instanceof String factValue) || factValue.isBlank()) {
            throw new IllegalArgumentException("fact.description is required");
        }
        Object complete = data.get("complete");
        if (!(complete instanceof Map<?, ?> completeMap)) {
            return new WorkerResult.Fact(factValue.trim());
        }
        Object completeDescription = completeMap.get("description");
        if (!(completeDescription instanceof String completeValue) || completeValue.isBlank()) {
            throw new IllegalArgumentException("complete.description is required");
        }
        return new WorkerResult.BootstrapComplete(factValue.trim(), completeValue.trim());
    }

    public WorkerResult parseBootstrapConclude(String text) {
        Map<String, Object> payload = extractObject(text);
        Map<String, Object> data = unwrapAccepted(payload);
        if (Boolean.TRUE.equals(data.get("rejected"))) {
            return new WorkerResult.Rejected("rejected");
        }
        Object fact = data.get("fact");
        if (!(fact instanceof Map<?, ?> factMap)) {
            throw new IllegalArgumentException("fact is required");
        }
        Object description = factMap.get("description");
        if (!(description instanceof String value) || value.isBlank()) {
            throw new IllegalArgumentException("fact.description is required");
        }
        return new WorkerResult.Fact(value.trim());
    }

    public WorkerResult parseExploreConclude(String text) {
        return parseExplore(text);
    }

    /** Swarm race/explore terminal contract: {"data":{"complete":{...}}} or {"data":{"description":...}}. */
    public WorkerResult parseSwarm(String text) {
        Map<String, Object> payload = extractObject(text);
        Map<String, Object> data = unwrapAccepted(payload);
        if (Boolean.TRUE.equals(data.get("rejected"))) {
            return new WorkerResult.Rejected("rejected");
        }
        Object complete = data.get("complete");
        if (complete instanceof Map<?, ?> map) {
            Object description = map.get("description");
            if (!(description instanceof String value) || value.isBlank()) {
                throw new IllegalArgumentException("complete.description is required");
            }
            return new WorkerResult.Complete(value.trim(), stringList(map.get("from")));
        }
        Object description = data.get("description");
        if (!(description instanceof String value) || value.isBlank()) {
            throw new IllegalArgumentException("description is required");
        }
        return new WorkerResult.Fact(value.trim());
    }

    /** Board-only swarm workers may exit without a valid terminal JSON; treat that as no result. */
    public WorkerResult parseSwarmOrNull(String text) {
        try {
            return parseSwarm(text);
        } catch (RuntimeException exc) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> unwrapAccepted(Map<String, Object> payload) {
        Object accepted = payload.get("accepted");
        if (Boolean.FALSE.equals(accepted)) {
            return Map.of("rejected", true);
        }
        if (Boolean.TRUE.equals(accepted)) {
            Object data = payload.get("data");
            if (!(data instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("data must be an object");
            }
            return (Map<String, Object>) map;
        }
        return payload;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> unwrapReasonPayload(Map<String, Object> payload) {
        Object accepted = payload.get("accepted");
        if (Boolean.FALSE.equals(accepted)) {
            return Map.of("rejected", true);
        }
        return unwrapAccepted(payload);
    }

    private Map<String, Object> extractObject(String text) {
        for (String candidate : candidates(text)) {
            Map<String, Object> direct = tryParse(candidate.trim());
            if (direct != null) {
                return direct;
            }
            for (int index = 0; index < candidate.length(); index++) {
                if (candidate.charAt(index) != '{') {
                    continue;
                }
                for (int end = candidate.length(); end > index; end--) {
                    String slice = candidate.substring(index, end).trim();
                    Map<String, Object> parsed = tryParse(slice);
                    if (parsed != null) {
                        return parsed;
                    }
                }
            }
        }
        throw new IllegalArgumentException("no JSON object found in output");
    }

    private List<String> candidates(String text) {
        List<String> items = new ArrayList<>();
        items.add(text == null ? "" : text.trim());
        var matcher = FENCE.matcher(text == null ? "" : text);
        while (matcher.find()) {
            items.add(matcher.group(1).trim());
        }
        return items;
    }

    private Map<String, Object> tryParse(String value) {
        if (value.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(value, new TypeReference<>() {
            });
        } catch (JsonProcessingException ignored) {
            return null;
        }
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of("origin");
        }
        return list.stream().filter(String.class::isInstance).map(String.class::cast).filter(s -> !s.isBlank()).toList();
    }

    private List<String> requiredStringList(Object value, String field) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException(field + " is required");
        }
        List<String> strings = list.stream().filter(String.class::isInstance).map(String.class::cast).filter(s -> !s.isBlank()).toList();
        if (strings.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be empty");
        }
        return strings;
    }
}
