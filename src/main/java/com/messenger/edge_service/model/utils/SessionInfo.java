package com.messenger.edge_service.model.utils;

import org.springframework.web.reactive.socket.WebSocketSession;

public record SessionInfo(String userId, WebSocketSession wsSession) {}