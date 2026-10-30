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
import io.github.casy82.hub.project.ProjectRegistry;
import io.github.casy82.hub.project.ServiceConfig;

/** 앱이 뜨면 공통 네트워크와 공통 서비스를 먼저 올리고, 이후 hub.poll-interval 마다 모든 프로젝트의 서비스를 확인한다. */
@Component
public class PollScheduler {

    private static final Logger log = LoggerFactory.getLogger(PollScheduler.class);

    private final HubProperties props;
    private final ComposeClient compose;
    private final DeployService deploys;
    private final ProjectRegistry registry;
    private final TaskScheduler scheduler;
    // 빌드가 오래 걸리는 서비스 하나 때문에 다른 서비스 확인이 밀리지 않도록 서비스마다 따로 돌린다
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    public PollScheduler(HubProperties props, ComposeClient compose, DeployService deploys, ProjectRegistry registry,
            TaskScheduler scheduler) {
        this.props = props;
        this.compose = compose;
        this.deploys = deploys;
        this.registry = registry;
        this.scheduler = scheduler;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        CommandResult network = compose.ensureNetwork();
        if (!network.ok()) {
            log.error("공통 네트워크 {} 를 만들지 못했습니다:\n{}", props.network(), network.output());
        }
        if (!props.infraProfiles().isEmpty()) {
            log.info("공통 서비스 기동: {}", props.infraProfiles());
            CommandResult result = compose.upProfiles(props.infraProfiles());
            if (!result.ok()) {
                log.error("공통 서비스 기동 실패:\n{}", result.output());
            }
        }
        log.info("프로젝트 {} 개를 {} 마다 확인합니다: {}", registry.all().size(), props.pollInterval(),
                registry.all().stream().map(ProjectRegistry.Project::id).toList());
        scheduler.scheduleWithFixedDelay(this::pollAll, props.pollInterval());
    }

    void pollAll() {
        for (ProjectRegistry.Project project : registry.all()) {
            for (ServiceConfig svc : project.config().services()) {
                triggerAsync(project.id(), svc.name(), false);
            }
        }
    }

    /** 대시보드나 웹훅에서 즉시 배포를 요청할 때도 쓴다. 요청은 바로 반환하고 배포는 뒤에서 돈다. */
    public void triggerAsync(String projectId, String service, boolean force) {
        workers.submit(() -> {
            try {
                deploys.sync(projectId, service, force);
            } catch (RuntimeException e) {
                log.error("[{}/{}] 확인 중 오류", projectId, service, e);
            }
        });
    }
}
