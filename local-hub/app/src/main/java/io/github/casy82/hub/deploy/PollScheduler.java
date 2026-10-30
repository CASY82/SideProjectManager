package io.github.casy82.hub.deploy;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import io.github.casy82.hub.compose.ComposeClient;
import io.github.casy82.hub.config.HubProperties;
import io.github.casy82.hub.process.CommandResult;

/** 앱이 뜨면 공통 서비스를 먼저 올리고, 이후 hub.poll-interval 마다 모든 서비스를 확인한다. */
@Component
public class PollScheduler {

    private static final Logger log = LoggerFactory.getLogger(PollScheduler.class);

    private final HubProperties props;
    private final ComposeClient compose;
    private final DeployService deploys;
    private final TaskScheduler scheduler;
    // 빌드가 오래 걸리는 서비스 하나 때문에 다른 서비스 확인이 밀리지 않도록 서비스마다 따로 돌린다
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    public PollScheduler(HubProperties props, ComposeClient compose, DeployService deploys, TaskScheduler scheduler) {
        this.props = props;
        this.compose = compose;
        this.deploys = deploys;
        this.scheduler = scheduler;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!props.infraProfiles().isEmpty()) {
            log.info("공통 서비스 기동: {}", props.infraProfiles());
            CommandResult result = compose.upProfiles(props.infraProfiles());
            if (!result.ok()) {
                log.error("공통 서비스 기동 실패:\n{}", result.output());
            }
        }
        log.info("{} 개 서비스를 {} 마다 확인합니다: {}", deploys.serviceNames().size(), props.pollInterval(),
                deploys.serviceNames());
        scheduler.scheduleWithFixedDelay(this::pollAll, props.pollInterval());
    }

    void pollAll() {
        for (String name : deploys.serviceNames()) {
            workers.submit(() -> {
                try {
                    deploys.sync(name, false);
                } catch (RuntimeException e) {
                    log.error("[{}] 확인 중 오류", name, e);
                }
            });
        }
    }

    /** 대시보드나 웹훅에서 즉시 배포를 요청할 때 쓴다. 요청은 바로 반환하고 배포는 뒤에서 돈다. */
    public void triggerAsync(String name, boolean force) {
        workers.submit(() -> deploys.sync(name, force));
    }
}
