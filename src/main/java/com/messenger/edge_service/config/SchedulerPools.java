package com.messenger.edge_service.config;

import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

public class SchedulerPools {
    public static final Scheduler CPU_SCHEDULER =
            Schedulers.newParallel("event-cpu", Runtime.getRuntime().availableProcessors());
}
