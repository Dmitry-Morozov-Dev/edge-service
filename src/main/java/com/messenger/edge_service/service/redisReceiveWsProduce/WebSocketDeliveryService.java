package com.messenger.edge_service.service.redisReceiveWsProduce;

import com.messenger.edge_service.model.enums.EventType;
import com.messenger.edge_service.model.utils.ProcessedEnvelope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class WebSocketDeliveryService {

    @Value("${edge.broadcast.concurrency:16}")
    private int broadcastConcurrency;

    public Mono<Void> deliveryMessage(ProcessedEnvelope envelope) {

        // READ / DELIVERED — only to receiver
        if (envelope.eventType() == EventType.DELIVERED ||
                envelope.eventType() == EventType.READ) {
            return sendFiltered(envelope, entry -> true);
        }

        // Other event — to everyone except sender
        return sendFiltered(envelope, entry ->
                !envelope.senderSessions().contains(entry.getKey()));
    }

    private Mono<Void> sendFiltered(
            ProcessedEnvelope processed,
            java.util.function.Predicate<Map.Entry<String, WebSocketSession>> filter
    ) {
        String payload = processed.envelope();

        log.debug(processed.sessions().toString());
        log.debug(processed.senderSessions().toString());
        return Flux.fromIterable(processed.sessions().entrySet())
                .filter(filter)
                .flatMap(entry -> {
                    WebSocketSession ws = entry.getValue();
                    if (ws == null || !ws.isOpen()) return Mono.empty();

                    return ws.send(Mono.just(ws.textMessage(payload)))
                            .timeout(Duration.ofMillis(500))
                            .onErrorResume(e -> {
                                log.debug("WS send failed for session {}: {}",
                                        entry.getKey(), e.getMessage());
                                return Mono.empty();
                            })
                            .doOnSuccess(e -> log.debug("READ:" + entry))
                            .then();
                }, broadcastConcurrency)
                .then()
                .doOnSuccess(e -> log.debug("READAll:" + processed));
    }
}

