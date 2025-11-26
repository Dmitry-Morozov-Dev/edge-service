package com.messenger.edge_service.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.messenger.edge_service.model.dto.MessageEnvelope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class JsonUtils {

    private final ObjectMapper objectMapper;

    public Mono<MessageEnvelope> parseEnvelope(String payload) {
        return Mono.fromCallable(() -> objectMapper.readValue(payload, MessageEnvelope.class))
                .onErrorMap(e -> new IllegalArgumentException("Invalid JSON", e));
    }

    public Mono<String> toJson(Object obj) {
        return Mono.fromCallable(() -> objectMapper.writeValueAsString(obj))
                .onErrorMap(JsonProcessingException.class::isInstance, e -> (JsonProcessingException) e);
    }
}