package com.messenger.edge_service.utils;

import com.messenger.edge_service.model.dto.MessageEnvelope;
import com.messenger.edge_service.service.kafka.KafkaProducerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketErrorHandler {

    private final JsonUtils jsonUtils;
    private final KafkaProducerService kafkaProducerService;

    public Mono<Void> sendError(WebSocketSession session, String message) {
        return session.send(Mono.just(session.textMessage("{\"error\":\"" + message + "\"}")))
                .then();
    }

    public Mono<Void> handleError(String userId, MessageEnvelope envelope, Throwable e) {
        return jsonUtils.toJson(envelope)
                .flatMap(json -> kafkaProducerService.sendToDlq(userId, json + " | error: " + e.getMessage()))
                .onErrorResume(t -> {
                    log.error("DLQ failed", t);
                    return Mono.empty();
                });
    }
}