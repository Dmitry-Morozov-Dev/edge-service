package com.messenger.edge_service.service.wsReceivePubsubProduce;

import com.datastax.oss.driver.api.core.uuid.Uuids;
import com.messenger.edge_service.config.SchedulerPools;
import com.messenger.edge_service.error.ServiceDegradedException;
import com.messenger.edge_service.model.dto.MessageEnvelope;
import com.messenger.edge_service.model.utils.EnvelopeFactory;
import com.messenger.edge_service.service.kafka.KafkaProducerService;
import com.messenger.edge_service.service.session.SessionManager;
import com.messenger.edge_service.utils.JsonUtils;
import com.messenger.edge_service.utils.WebSocketErrorHandler;
import io.micrometer.core.instrument.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.*;
import reactor.core.publisher.*;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Component
public class EdgeWebSocketHandler implements WebSocketHandler {

    // Services
    private final KafkaProducerService kafkaProducerService;
    private final SessionManager sessionManager;
    private final MessageValidationService validator;

    //Utils
    private final JsonUtils jsonUtils;
    private final WebSocketErrorHandler errorHandler;

    // Configurable params
    @Value("${edge.websocket.buffer.size:10}")
    private int bufferSize;

    @Value("${edge.websocket.heartbeat.interval-seconds:30}")
    private long heartbeatIntervalSeconds;

    // Topics
    @Value("${spring.kafka.topic.message.out}")
    private String messageOutTopic;

    // Metrics — создаём один раз при старте
    private final Counter messagesSentCounter;
    private final Counter messagesFailedCounter;
    private final Counter bufferOverflowCounter;

    public EdgeWebSocketHandler(
            KafkaProducerService kafkaProducerService,
            SessionManager sessionManager,
            MessageValidationService validator,
            MeterRegistry meterRegistry,
            JsonUtils jsonUtils,
            WebSocketErrorHandler errorHandler,
            @Value("${edge.websocket.heartbeat.interval-seconds:30}") long heartbeatIntervalSeconds
    ) {
        this.kafkaProducerService = kafkaProducerService;
        this.sessionManager = sessionManager;
        this.validator = validator;
        this.jsonUtils = jsonUtils;
        this.errorHandler = errorHandler;
        this.heartbeatIntervalSeconds = heartbeatIntervalSeconds;

        this.messagesSentCounter = Counter.builder("edge.messages.sent")
                .tag("status", "sent")
                .register(meterRegistry);

        this.messagesFailedCounter = Counter.builder("edge.messages.failed")
                .register(meterRegistry);

        this.bufferOverflowCounter = Counter.builder("edge.buffer.overflow")
                .register(meterRegistry);
    }

    @Override
    public @NonNull Mono<Void> handle(@NonNull WebSocketSession session) {
        return validateUser(session)
                .flatMap(userId -> {
                    String sessionId = UUID.randomUUID().toString();

                    Mono<Void> closeTrigger = session.close()
                            .doOnSuccess(v -> log.debug("Physical close sent: userId={}", userId))
                            .cache();

                    return sessionManager.addSession(userId, sessionId, session)
                            .thenMany(
                                    Flux.merge(
                                                    handleInbound(session, userId, sessionId),
                                                    sendHeartbeat(session, userId, sessionId)
                                            )
                                            .takeUntilOther(closeTrigger)
                                            .doOnCancel(() -> {
                                                log.info("Clean cancel → cleanup session: userId={}", userId);
                                                sessionManager.cleanupSession(userId, sessionId).subscribe();
                                            })
                            )
                            .onErrorResume(ServiceDegradedException.class, e -> {
                                log.info("Service degraded → reconnect");
                                sessionManager.degradedServiceHandler(userId, sessionId, e);
                                return closeTrigger.then(Mono.error(e));
                            })
                            .onErrorResume(t -> {
                                log.error("Unexpected error", t);
                                sessionManager.cleanupSession(userId, sessionId).subscribe();
                                return closeTrigger.then(Mono.error(t));
                            })
                            .then(closeTrigger);
                });
    }

    private Mono<String> validateUser(WebSocketSession session) {
        String query = session.getHandshakeInfo().getUri().getQuery();
        if (query == null) {
            return closeInvalid(session);
        }

        Map<String, String> params = Arrays.stream(query.split("&"))
                .map(p -> p.split("=", 2))
                .filter(p -> p.length == 2)
                .collect(Collectors.toMap(p -> p[0], p -> p[1]));

        String userId = params.get("x-user-id");

        if (userId == null || !validator.isValidUUID(userId)) {
            return closeInvalid(session);
        }

        log.info("WS connected: userId={}", userId);
        return Mono.just(userId);
    }

    private Mono<String> closeInvalid(WebSocketSession session) {
        return session.close(CloseStatus.POLICY_VIOLATION.withReason("Invalid x-user-id"))
                .then(Mono.empty());
    }

    private Flux<Void> sendHeartbeat(WebSocketSession session, String userId, String sessionId) {
        Duration heartbeatInterval = Duration.ofSeconds(Math.max(1, heartbeatIntervalSeconds)); // защитный минимум 1s
        return Flux.interval(heartbeatInterval)
                .flatMap(i -> session.send(Mono.just(session.textMessage("ping"))))
                .doOnError(e -> {
                    log.warn("Ping send failed: userId={}, sessionId={}", userId, sessionId, e);
                });
    }


    private Flux<Void> handleInbound(WebSocketSession session, String userId, String sessionId) {
        return session.receive()
                .onBackpressureBuffer(bufferSize, BufferOverflowStrategy.DROP_OLDEST)
                .flatMap(msg -> {
                    if (msg.getType() != WebSocketMessage.Type.TEXT) {
                        messagesFailedCounter.increment();
                        return errorHandler.sendError(session, "only text messages supported");
                    }

                    String payload = msg.getPayloadAsText();

                    if ("pong".equals(payload)) {
                        return sessionManager.updateHeartbeat(userId, sessionId)
                                .then(Mono.empty());
                    }

                    return jsonUtils.parseEnvelope(payload)
                            .subscribeOn(Schedulers.parallel())
                            .publishOn(SchedulerPools.CPU_SCHEDULER)
                            .flatMap(envelope -> handleEvent(envelope, userId, session))
                            .onErrorResume(e -> {
                                log.warn("Event processing failed: userId={}, error={}", userId, e.toString());
                                messagesFailedCounter.increment();
                                return errorHandler.sendError(session, "invalid event");
                            });
                })
                .onBackpressureDrop(dropped -> {
                    bufferOverflowCounter.increment();
                    log.warn("Dropped message due to backpressure: userId={}", userId);
                });
    }

    private Mono<Void> handleEvent(MessageEnvelope envelope, String userId, WebSocketSession session) {
        return Mono.just(envelope.getType())
                .flatMap(type -> switch (type) {
                    case MESSAGE ->     validate(() ->          validator.validateMessage(envelope, userId))
                                                                .then(handleMessage(envelope, userId, session));
                    case EDIT ->        validate(() ->          validator.validateEdit(envelope, userId))
                                                                .then(handleEdit(envelope, userId, session));
                    case DELETE ->      validate(() ->          validator.validateDelete(envelope, userId))
                                                                .then(handleDelete(envelope, userId, session));
                    case TYPING ->      validate(() ->          validator.validateTyping(envelope, userId))
                                                                .then(handleTyping(envelope, userId, session));
                    case READ ->        validate(() ->          validator.validateRead(envelope, userId))
                                                                .then(handleRead(envelope, userId, session));
                    case DELIVERED  ->  validate(() ->          validator.validateDelivered(envelope, userId))
                                                                .then(handleDelivered(envelope, userId, session));
                    case ACK ->         Mono.empty();
                    default ->          errorHandler.sendError(session, "unknown event type");
                });
    }

    private Mono<Void> validate(Runnable validation) {
        try {
            validation.run();
            return Mono.empty();
        } catch (Exception e) {
            return Mono.error(e);
        }
    }

    private Mono<Void> handleMessage(MessageEnvelope env, String userId, WebSocketSession session) {
        UUID realId = Uuids.timeBased();

        MessageEnvelope ack = EnvelopeFactory.createAck(env.getTempId(), env.getRealId());

        MessageEnvelope enrichedEnvelope = EnvelopeFactory.createEnrichedMessage(
                env.getChatId(),
                userId,
                env.getSenderName(),
                env.getSenderAvatar(),
                realId.toString(),
                env.getContent(),
                env.getMediaDTOS(),
                env.getReplyTo()
        );

        return jsonUtils.toJson(ack)
                .flatMap(ackJson -> session.send(Mono.just(session.textMessage(ackJson))))
                .then(publishToKafka(enrichedEnvelope, null, userId));
    }

    private Mono<Void> handleEdit(MessageEnvelope env, String userId, WebSocketSession session) {
        MessageEnvelope editEvent = EnvelopeFactory.createEditEvent(
                env.getRealId(),
                env.getChatId(),
                userId,
                env.getContent(),
                env.getMediaDTOS()
        );

        return publishToKafka(editEvent, null, userId);
    }

    private Mono<Void> handleDelete(MessageEnvelope env, String userId, WebSocketSession session) {
        MessageEnvelope deleteEvent = EnvelopeFactory.createDeleteEvent(
                env.getRealId(),
                env.getChatId(),
                userId
        );

        return publishToKafka(deleteEvent, null, userId);
    }

    private Mono<Void> handleTyping(MessageEnvelope env, String userId, WebSocketSession session) {
        MessageEnvelope typingEvent = EnvelopeFactory.createTypingEvent(
                env.getChatId(),
                userId
        );

        return publishToKafka(typingEvent, null, userId);
    }

    private Mono<Void> handleRead(MessageEnvelope env, String userId, WebSocketSession session) {
        MessageEnvelope readEvent =
                EnvelopeFactory.createRead(env.getChatId(), userId, env.getReceiverId(), env.getReadUpTo());

        return publishToKafka(readEvent, null, userId);
    }

    private Mono<Void> handleDelivered(MessageEnvelope env, String userId, WebSocketSession session) {
        MessageEnvelope deliveredEvent =
                EnvelopeFactory.createDelivered(env.getChatId(), env.getRealId(), userId, env.getReceiverId());

        return publishToKafka(deliveredEvent, null, userId);
    }

    private Mono<Void> publishToKafka(MessageEnvelope env, String routingKey, String userId) {
        return jsonUtils.toJson(env)
                .flatMap(json -> kafkaProducerService.send(
                        messageOutTopic,
                        routingKey, json, userId
                ))
                .doOnSuccess(v -> messagesSentCounter.increment())
                .onErrorResume(e -> {
                    messagesFailedCounter.increment();
                    return errorHandler.handleError(userId, env, e);
                })
                .then();
    }
}