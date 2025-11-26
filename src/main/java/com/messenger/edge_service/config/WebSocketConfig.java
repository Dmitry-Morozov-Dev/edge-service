package com.messenger.edge_service.config;

import com.messenger.edge_service.service.wsReceivePubsubProduce.EdgeWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.server.WebSocketService;
import org.springframework.web.reactive.socket.server.support.HandshakeWebSocketService;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;
import org.springframework.web.reactive.socket.server.upgrade.ReactorNettyRequestUpgradeStrategy;
import reactor.netty.http.server.WebsocketServerSpec;

import java.util.Map;

@Configuration
@RequiredArgsConstructor
public class WebSocketConfig {

    @Value("${edge.websocket.path:/ws/connect}")
    private String wsPath;

    @Value("${edge.websocket.compression:true}")
    private boolean compression;

    @Value("${edge.websocket.max-frame-size:65536}")
    private int maxFrameSize;

    @Bean
    public HandlerMapping webSocketHandlerMapping(EdgeWebSocketHandler handler) {
        return new SimpleUrlHandlerMapping(Map.of(wsPath, handler), 0);
    }

    @Bean
    public WebSocketService webSocketService() {
        ReactorNettyRequestUpgradeStrategy strategy =
                new ReactorNettyRequestUpgradeStrategy(
                        () -> WebsocketServerSpec.builder()
                                .compress(compression)
                                .maxFramePayloadLength(maxFrameSize)
                );

        HandshakeWebSocketService service = new HandshakeWebSocketService(strategy);
        service.setSessionAttributePredicate(attributeName -> true);
        return service;
    }

    @Bean
    public WebSocketHandlerAdapter webSocketHandlerAdapter() {
        return new WebSocketHandlerAdapter();
    }
}
