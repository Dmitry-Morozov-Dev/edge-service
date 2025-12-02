package com.messenger.edge_service.service.redisReceiveWsProduce;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.messenger.edge_service.model.dto.MessageEnvelope;
import com.messenger.edge_service.model.enums.EventType;
import com.messenger.edge_service.model.utils.ProcessedEnvelope;
import com.messenger.edge_service.service.kafka.KafkaProducerService;
import com.messenger.edge_service.service.session.SessionManager;
import io.lettuce.core.RedisConnectionException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.connection.ReactiveSubscription;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Slf4j
@Service
public class EdgeStreamService {

    private final String edgeId;
    private final String channelsPattern;
    private final ReactiveRedisTemplate<String, String> pubSubRedisTemplate;

    private final Scheduler serverEventLoopScheduler;

    private final WebSocketDeliveryService webSocketDeliveryService;
    private final KafkaProducerService kafkaProducerService;
    private final SessionManager sessionManager;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    private final int receiverBufferSize;

    private final Counter messagesSentCounter;
    private final Counter messagesFailedCounter;
    private final Counter bufferOverflowCounter;
    private final Timer processingTimer;

    public EdgeStreamService(@Value("${edge.id}") String edgeId,
                             @Value("${edge.channel.pattern}") String channelsPattern,
                             @Qualifier("pubSubRedisTemplate") ReactiveRedisTemplate<String, String> pubSubRedisTemplate,
                             WebSocketDeliveryService webSocketDeliveryService,
                             KafkaProducerService kafkaProducerService,
                             SessionManager sessionManager,
                             Scheduler serverEventLoopScheduler,
                             ObjectMapper objectMapper,
                             @Value("${edge.receiver.buffer.size:10000}") int receiverBufferSize,
                             MeterRegistry meterRegistry) {
        this.edgeId = edgeId;
        this.channelsPattern = channelsPattern;
        this.pubSubRedisTemplate = pubSubRedisTemplate;
        this.webSocketDeliveryService = webSocketDeliveryService;
        this.kafkaProducerService = kafkaProducerService;
        this.sessionManager = sessionManager;
        this.serverEventLoopScheduler = serverEventLoopScheduler;
        this.objectMapper = objectMapper;
        this.receiverBufferSize = receiverBufferSize;
        this.meterRegistry = meterRegistry;

        this.messagesSentCounter = Counter.builder("edge.pubsub.messages.sent").register(meterRegistry);
        this.messagesFailedCounter = Counter.builder("edge.pubsub.messages.failed").register(meterRegistry);
        this.bufferOverflowCounter = Counter.builder("edge.pubsub.buffer.overflow").register(meterRegistry);
        this.processingTimer = Timer.builder("edge.pubsub.processing.time").register(meterRegistry);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startListening() {
        pubSubRedisTemplate.listenToPattern(channelsPattern)
                .retryWhen(
                        Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(1))
                                .filter(t -> t instanceof RedisConnectionException)
                                .maxBackoff(Duration.ofSeconds(10))
                )
                .onBackpressureBuffer(
                        receiverBufferSize,
                        dropped -> {
                            bufferOverflowCounter.increment();
                            log.warn("Buffer overflow — dropped incoming envelope on {}: {}", edgeId, dropped);
                        }
                )
                .map(ReactiveSubscription.Message::getMessage)
                .flatMap(this::handlePubSubMessage)
                .subscribe(
                        null,
                        err -> log.error("Fatal Pub/Sub processing error on {}: {}", edgeId, err.getMessage(), err)
                );

        log.info("EdgeStreamService listening on channel {}", channelsPattern);
    }

    private Mono<ProcessedEnvelope> preprocessPayload(String rawJson) {
        return Mono.fromCallable(() -> {
                    MessageEnvelope env = objectMapper.readValue(rawJson, MessageEnvelope.class);

                    if(env.getType() == null)
                        throw new IllegalArgumentException("Message envelope doesn't consist of event type");

                    if(env.getType() == EventType.PARTICIPATE){
                        sessionManager.addExistingUserSessionsToChat(env.getChatId(), env.getReceiverId());
                        return null;
                    }

                    if(env.getChatId() == null || env.getSenderId() == null)
                        throw new IllegalArgumentException("Message envelope doesn't consist of chatId or senderId");

                    EventType eventType = env.getType();
                    String chatId = env.getChatId();
                    String senderId = env.getSenderId();

                    Map<String, WebSocketSession> sessions = sessionManager.getChatSessions(chatId);
                    Set<String> senderSessions;
                    if (env.getType() == EventType.DELIVERED ||
                            env.getType() == EventType.READ) {
                        log.debug(env.getReceiverId());
                        senderSessions = sessionManager.getUserSessions(env.getReceiverId());
                    } else {
                        senderSessions = sessionManager.getUserSessions(senderId);
                    }

                    if (sessions == null || sessions.isEmpty()) {
                        return null;
                    }
                    log.debug(rawJson);

                    return new ProcessedEnvelope(eventType, rawJson, sessions, senderSessions);

                })
                .subscribeOn(Schedulers.parallel())
                .filter(Objects::nonNull);
    }


    private Mono<Void> handlePubSubMessage(String envelopeJson) {
        if (envelopeJson == null) return Mono.empty();

        return Mono.defer(() -> {
            long start = System.nanoTime();
            return preprocessPayload(envelopeJson)
                    .publishOn(serverEventLoopScheduler)
                    .flatMap(webSocketDeliveryService::deliveryMessage)
                    .timeout(Duration.ofSeconds(1))
                    .doOnSuccess(v -> {
                        messagesSentCounter.increment();
                        processingTimer.record(Duration.ofNanos(System.nanoTime() - start));
                    })
                    .doOnError(e -> {
                        messagesFailedCounter.increment();
                        log.error("Broadcast failed for envelope {}: {}", envelopeJson, e.getMessage());
                    })
                    .onErrorResume(e ->
                            kafkaProducerService.sendToDlq("edge:" + edgeId, envelopeJson + "|" + e.getMessage())
                                    .onErrorResume(ex -> {
                                        log.warn("Failed to send envelope to DLQ (serialization or kafka): {}", ex.getMessage());
                                        return Mono.empty();
                                    })
                                    .then(Mono.empty())
                    );
        });
    }
}
