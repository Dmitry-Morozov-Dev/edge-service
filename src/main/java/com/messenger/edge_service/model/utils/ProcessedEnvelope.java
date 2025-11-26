package com.messenger.edge_service.model.utils;

import com.messenger.edge_service.model.dto.MessageEnvelope;
import com.messenger.edge_service.model.enums.EventType;
import org.springframework.web.reactive.socket.WebSocketSession;

import java.util.Map;
import java.util.Set;

public record ProcessedEnvelope(
        EventType eventType,
        String envelope,
        Map<String, WebSocketSession> sessions,
        Set<String> senderSessions
) {}
