package com.messenger.edge_service.service.session;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.messenger.edge_service.error.ServiceDegradedException;
import com.messenger.edge_service.grpc.UserChatsGrpcClient;
import com.messenger.edge_service.model.utils.EnvelopeFactory;
import com.messenger.edge_service.model.utils.SessionInfo;
import com.messenger.edge_service.service.kafka.KafkaProducerService;
import com.messenger.edge_service.utils.JsonUtils;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.lettuce.core.RedisConnectionException;
import io.micrometer.core.instrument.*;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.*;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Service
@Slf4j
public class SessionManager {

    //Clients
    private final ReactiveRedisTemplate<String, String> cacheRedisTemplate;
    private final UserChatsGrpcClient userChatsGrpcClient;

    //Utils
    private final KafkaProducerService kafkaProducerService;
    private final JsonUtils jsonUtils;

    //EdgeId
    private final String edgeId;

    //Constraints
    private final int maxSessionsPerUser;

    // Topics
    @Value("${spring.kafka.topic.message.out}")
    private String messageOutTopic;

    //Metrics
    private final MeterRegistry meterRegistry;
    private final AtomicLong activeSessions = new AtomicLong(0);
    private final Counter sessionAddedCounter;
    private final Counter sessionRemovedCounter;

    //Cache
    private final Cache<String, Set<String>> userSessions = Caffeine.newBuilder().build();
    private final Cache<String, ConcurrentHashMap<String, WebSocketSession>> chatSessions = Caffeine.newBuilder().build();
    private final Cache<String, SessionInfo> sessions = Caffeine.newBuilder()
            .expireAfterWrite(SESSION_TTL, TimeUnit.SECONDS)
            .removalListener((String sessionId, SessionInfo info, RemovalCause cause) -> {
                if (cause == RemovalCause.EXPIRED && info != null) {
                    log.info("Session expired: userId={}, sessionId={}", info.userId(), sessionId);
                    info.wsSession().close().subscribe();
                }
            })
            .recordStats()
            .build();

    //Fallback
    private final Set<String> pendingChatRemovals = ConcurrentHashMap.newKeySet();
    private final Map<String, Map<String, Long>> localSessionFallback = new ConcurrentHashMap<>();
    private final Counter redisFallbackCounter;
    private final Counter grpcFallbackCounter;

    //TTL
    private static final int SESSION_TTL = 100;
    private static final int LOCAL_FALLBACK_TTL = 300_000;

    private static final String LUA_ADD_EDGE_TO_CHAT = """
            local updated = 0
            local edgeId = ARGV[1]
            for i, key in ipairs(KEYS) do
                local added = redis.call("SADD", key, edgeId)
                if added == 1 then
                    updated = updated + 1
                end
            end
            return updated
        """;

    public SessionManager(
            @Qualifier("cacheRedisTemplate") ReactiveRedisTemplate<String, String> cacheRedisTemplate,
            UserChatsGrpcClient userChatsGrpcClient,
            KafkaProducerService kafkaProducerService,
            JsonUtils jsonUtils,
            MeterRegistry meterRegistry,
            @Value("${spring.application.name}") String edgeId,
            @Value("${edge.max-sessions-per-user:5}") int maxSessionsPerUser
    ) {
        this.cacheRedisTemplate = cacheRedisTemplate;
        this.userChatsGrpcClient = userChatsGrpcClient;
        this.kafkaProducerService = kafkaProducerService;
        this.jsonUtils = jsonUtils;
        this.meterRegistry = meterRegistry;
        this.edgeId = edgeId;
        this.maxSessionsPerUser = maxSessionsPerUser;

        this.meterRegistry.gauge("edge.active_sessions.total", activeSessions, AtomicLong::get);
        this.sessionAddedCounter = Counter.builder("edge.sessions.added").register(meterRegistry);
        this.sessionRemovedCounter = Counter.builder("edge.sessions.removed").register(meterRegistry);
        this.redisFallbackCounter = Counter.builder("edge.session.redis.fallback.used").register(meterRegistry);
        this.grpcFallbackCounter = Counter.builder("edge.session.grpc.fallback.used").register(meterRegistry);
    }

    public Mono<Void> addSession(String userId, String sessionId, WebSocketSession session) {
        Set<String> currentUserSessions = userSessions.asMap()
                .computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet());

        if (currentUserSessions.size() >= maxSessionsPerUser) {
            throw new IllegalStateException("Max sessions per user exceeded");
        }

        currentUserSessions.add(sessionId);
        SessionInfo info = new SessionInfo(userId, session);
        sessions.put(sessionId, info);

        return addUserEdgeInRedis(userId)
                .then(userChatsGrpcClient.getUserChats(userId))
                    .flatMap(chatIds -> {
                        List<String> firstTimeChats = new ArrayList<>();

                        for (String chatId : chatIds) {
                            ConcurrentHashMap<String, WebSocketSession> usersMap = chatSessions.asMap()
                                    .computeIfAbsent(chatId, k -> new ConcurrentHashMap<>());

                            boolean first = usersMap.isEmpty();
                            usersMap.put(sessionId, session);
                            if (first) {
                                firstTimeChats.add(chatId);
                            }
                        }

                        if (firstTimeChats.isEmpty()) {
                            return Mono.empty();
                        }

                        return updateChatEdgesInRedisBulk(firstTimeChats);
                    })
                    .flatMap(v -> jsonUtils.toJson(EnvelopeFactory.createOnline(userId, true))
                                        .flatMap(json -> kafkaProducerService.send(messageOutTopic, null, json, userId)))
                    .doOnSuccess(v -> {
                        activeSessions.incrementAndGet();
                        sessionAddedCounter.increment();
                    });
    }

    @CircuitBreaker(name = "redisOps", fallbackMethod = "addUserEdgeFallback")
    private Mono<Void> addUserEdgeInRedis(String userId) {
        String key = "user:" + userId + ":edge";

        return cacheRedisTemplate.opsForValue()
                .set(key, edgeId, Duration.ofSeconds(SESSION_TTL))
                .then()
                .retryWhen(Retry.backoff(20, Duration.ofSeconds(2))
                        .maxBackoff(Duration.ofSeconds(10))
                        .filter(t -> t instanceof RedisConnectionException))
                .onErrorMap(e -> new ServiceDegradedException("Redis unavailable", e, false));
    }

    private Mono<Void> addUserEdgeFallback(String userId, Throwable t) {
        log.warn("Redis unavailable for addUserEdge(userId={})", userId, t);
        redisFallbackCounter.increment();
        return Mono.error(new ServiceDegradedException("Redis unavailable", t, false));
    }

    public void removeUserSessionFromMemory(String userId, String sessionId) {
        Set<String> currentUserSessions = userSessions.getIfPresent(userId);
        if (currentUserSessions != null) {
            currentUserSessions.remove(sessionId);
            if (currentUserSessions.isEmpty()) {
                userSessions.asMap().remove(userId);
            }
        }
        sessions.asMap().remove(sessionId);
    }

    public void removeSessionFromMemory(String userId, String sessionId) {
        removeUserSessionFromMemory(userId, sessionId);

        chatSessions.asMap().forEach((chatId, usersMap) -> {
            usersMap.remove(sessionId);
            if (usersMap.isEmpty()) {
                chatSessions.invalidate(chatId);
            }
        });
    }

    public void degradedServiceHandler(String userId, String sessionId,ServiceDegradedException e){
        if (e.isRedisBulkError()) {
            removeSessionFromMemory(userId, sessionId);
        } else {
            removeUserSessionFromMemory(userId, sessionId);
        }
    }

    @CircuitBreaker(name = "redisOps", fallbackMethod = "updateRedisFallback")
    private Mono<Long> updateChatEdgesInRedisBulk(List<String> chatIds) {
        List<String> keys = chatIds.stream()
                .map(id -> "chat:" + id + ":edges")
                .toList();

        return cacheRedisTemplate.execute(
                RedisScript.of(LUA_ADD_EDGE_TO_CHAT, Long.class),
                keys,
                edgeId
        )
        .single()
        .retryWhen(Retry.backoff(3, Duration.ofMillis(100))
                .filter(throwable -> throwable instanceof RedisConnectionException)
        );
    }

    private Mono<Long> updateRedisFallback(List<String> chatIds, Throwable t) {
        log.warn("Redis down → forcing session cleanup", t);
        redisFallbackCounter.increment();

        return Mono.error(new ServiceDegradedException("Redis unavailable", t, true));
    }

    @CircuitBreaker(name = "grpcOps", fallbackMethod = "getUserChatsFallback")
    private Mono<List<String>> getUserChats(String userId){
        return userChatsGrpcClient.getUserChats(userId);
    }

    private Mono<List<String>> getUserChatsFallback(String userId, Throwable t) {
        log.warn("gRPC down → forcing session cleanup", t);
        grpcFallbackCounter.increment();

        return Mono.error(new ServiceDegradedException("gRPC unavailable", t, false));
    }

    public Mono<Void> updateHeartbeat(String userId, String sessionId) {
        return Mono.justOrEmpty(sessions.getIfPresent(sessionId))
                .filter(info -> info.userId().equals(userId))
                .flatMap(info -> {
                    String redisKey = "user:" + userId + ":edge";

                    return cacheRedisTemplate.expire(redisKey, Duration.ofSeconds(SESSION_TTL))
                            .doOnSuccess(s -> sessions.put(sessionId, info))
                            .onErrorResume(e -> {
                                log.warn("Failed to refresh TTL for {}", redisKey + "|" + e.getMessage());
                                return Mono.empty();
                            });
                })
                .doOnNext(info -> log.debug("Heartbeat: userId={}, sessionId={}", userId, sessionId))
                .then();
    }

    public Mono<Void> cleanupSession(String userId, String sessionId) {
        return Mono.justOrEmpty(userSessions.getIfPresent(userId))
                .doOnNext(currentUserSessions -> {
                    currentUserSessions.remove(sessionId);
                    sessions.asMap().remove(sessionId);
                    if (currentUserSessions.isEmpty()) {
                        userSessions.asMap().remove(userId);
                    }
                })
                .thenMany(Flux.fromIterable(new ArrayList<>(chatSessions.asMap().entrySet())))
                .filter(entry -> entry.getValue().containsKey(sessionId))
                .flatMap(entry -> {
                    String chatId = entry.getKey();
                    ConcurrentHashMap<String, WebSocketSession> map = entry.getValue();
                    map.remove(sessionId);

                    if (map.isEmpty()) {
                        chatSessions.invalidate(chatId);
                        return removeEdgeFromChat(chatId)
                                    .onErrorResume(e -> {
                                        pendingChatRemovals.add(chatId);
                                        return Mono.empty();
                                    });
                    }

                    return Mono.empty();
                })
                .flatMap(v -> jsonUtils.toJson(EnvelopeFactory.createOnline(userId, false))
                                    .flatMap(json -> kafkaProducerService.send(messageOutTopic, null, json, userId)))
                .then(removeUserEdgeInRedis(userId))
                .doOnSuccess(v -> {
                    activeSessions.decrementAndGet();
                    sessionRemovedCounter.increment();
                });
    }

    @CircuitBreaker(name = "redisOps", fallbackMethod = "removeUserEdgeFallback")
    private Mono<Void> removeUserEdgeInRedis(String userId) {
        String key = "user:" + userId + ":edge";

        return cacheRedisTemplate.opsForValue()
                .delete(key)
                .then()
                .retryWhen(Retry.backoff(3, Duration.ofMillis(100))
                        .filter(t -> t instanceof RedisConnectionException));
    }

    private Mono<Void> removeUserEdgeFallback(String userId, Throwable t) {
        log.warn("Redis unavailable for removeUserEdge(userId={})", userId, t);
        return Mono.empty();
    }


    @CircuitBreaker(name = "redisOps", fallbackMethod = "removeEdgeFromChatFallback")
    private Mono<Void> removeEdgeFromChat(String chatId) {
        String key = "chat:" + chatId + ":edges";

        return cacheRedisTemplate.opsForSet()
                .remove(key, edgeId)
                .then()
                .retryWhen(Retry.backoff(3, Duration.ofMillis(100))
                        .filter(t -> t instanceof RedisConnectionException));
    }

    private Mono<Void> removeEdgeFromChatFallback(String chatId, Throwable t) {
        log.warn("Redis unavailable for removeEdgeFromChat(chatId={}), will retry later", chatId, t);
        pendingChatRemovals.add(chatId);
        return Mono.empty();
    }

    @Scheduled(fixedRate = LOCAL_FALLBACK_TTL)
    public void syncPendingRemovals() {
        if (pendingChatRemovals.isEmpty()) return;

        log.info("Syncing {} pending chat removals", pendingChatRemovals.size());

        Iterator<String> iterator = pendingChatRemovals.iterator();
        while (iterator.hasNext()) {
            String chatId = iterator.next();

            if (chatSessions.getIfPresent(chatId) != null) {
                iterator.remove();
                continue;
            }

            removeEdgeFromChat(chatId)
                    .doOnSuccess(v -> {
                        log.info("Synced: removed edgeId={} from chat:{}", edgeId, chatId);
                        iterator.remove();
                    })
                    .doOnError(err -> {
                        log.debug("Redis still down for chat:{}, will retry later", chatId);
                    })
                    .subscribe();
        }
    }

    public Set<String> getUserSessions(String userId) {
        Set<String> sessions = userSessions.getIfPresent(userId);
        return sessions != null ? Set.copyOf(sessions) : Set.of();
    }

    public Map<String, WebSocketSession> getChatSessions(String chatId) {
        ConcurrentHashMap<String, WebSocketSession> usersMap = chatSessions.getIfPresent(chatId);
        return usersMap != null ? Map.copyOf(usersMap) : Map.of();
    }

    @PreDestroy
    public void gracefulShutdown() {
        log.info("Graceful shutdown: removing edge {} from all chat subscriptions", edgeId);

        List<String> chatIds = new ArrayList<>(chatSessions.asMap().keySet());
        if (chatIds.isEmpty()) {
            log.info("No chats to clean up for edge {}", edgeId);
            return;
        }

        Flux.fromIterable(chatIds)
                .flatMap(chatId ->
                                removeEdgeFromChat(chatId)
                                        .timeout(Duration.ofMillis(300))
                                        .doOnSuccess(v -> log.debug("Removed edge {} from chat {}", edgeId, chatId))
                                        .onErrorResume(e -> {
                                            log.warn("Failed to remove edge {} from chat {}: {}", edgeId, chatId, e.getMessage());
                                            return Mono.empty();
                                        }),
                        32
                )
                .then()
                .block(Duration.ofSeconds(10));

        log.info("Edge {} graceful shutdown complete", edgeId);
    }

}
