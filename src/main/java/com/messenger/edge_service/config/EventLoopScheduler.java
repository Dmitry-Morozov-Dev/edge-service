package com.messenger.edge_service.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.netty.resources.LoopResources;

@Configuration
public class EventLoopScheduler {

    @Bean
    public LoopResources edgeLoopResources() {
        return LoopResources.create(
                "edge-event-loop",
                1,
                Runtime.getRuntime().availableProcessors() * 2,
                false
        );
    }

    @Bean
    public Scheduler serverEventLoopScheduler(LoopResources edgeLoopResources) {
        return Schedulers.fromExecutor(edgeLoopResources.onServer(true));
    }
}
