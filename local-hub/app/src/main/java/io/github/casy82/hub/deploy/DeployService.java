package io.github.casy82.hub.deploy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import io.github.casy82.hub.compose.ComposeClient;
import io.github.casy82.hub.config.HubProperties;
import io.github.casy82.hub.git.GitClient;
import io.github.casy82.hub.git.GitClient.CommitInfo;
import io.github.casy82.hub.process.CommandFailedException;
import io.github.casy82.hub.process.CommandResult;

/**
 * 서비스 하나를 "git 최신 커밋 확인 → 바뀌었으면 빌드·재기동 → 실패하면 이전 커밋으로 되돌림" 순서로 맞춘다.
 * 같은 서비스에 대한 동기화는 동시에 하나만 돈다.
 */
@Service
public class DeployService {

    private static final Logger log = LoggerFactory.getLogger(DeployService.class);
    private static final int MAX_OUTPUT_LINES = 200;

    private final HubProperties props;
    private final GitClient git;
    private final ComposeClient compose;
    private final Map<String, HubProperties.Service> services = new LinkedHashMap<>();
    private final Map<String, ServiceState> states = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final List<DeployListener> listeners;

    public DeployService(HubProperties props, GitClient git, ComposeClient compose, List<DeployListener> listeners) {
        this.props = props;
        this.git = git;
        this.compose = compose;
        this.listeners = listeners;
        for (HubProperties.Service svc : props.services()) {
            services.put(svc.name(), svc);
            states.put(svc.name(), ServiceState.initial(svc.name(), svc.repo(), svc.branch()));
            locks.put(svc.name(), new ReentrantLock());
        }
    }

    public List<ServiceState> states() {
        return services.keySet().stream().map(states::get).toList();
    }

    public Optional<ServiceState> state(String name) {
        return Optional.ofNullable(states.get(name));
    }

    public List<String> serviceNames() {
        return List.copyOf(services.keySet());
    }

    public enum SyncResult { DEPLOYED, UNCHANGED, ROLLED_BACK, FAILED, BUSY, UNKNOWN }

    /**
     * @param force true 면 커밋이 그대로여도 다시 빌드·기동한다 (대시보드의 "다시 배포" 버튼)
     */
    public SyncResult sync(String name, boolean force) {
        HubProperties.Service svc = services.get(name);
        if (svc == null) {
            return SyncResult.UNKNOWN;
        }
        ReentrantLock lock = locks.get(name);
        if (!lock.tryLock()) {
            return SyncResult.BUSY;
        }
        try {
            ServiceState before = states.get(name);
            SyncResult result = doSync(svc, force);
            if (result != SyncResult.UNCHANGED) {
                ServiceState after = states.get(name);
                for (DeployListener listener : listeners) {
                    try {
                        listener.onSynced(svc, before, after);
                    } catch (RuntimeException e) {
                        log.warn("[{}] {} 처리 중 오류", name, listener.getClass().getSimpleName(), e);
                    }
                }
            }
            return result;
        } finally {
            lock.unlock();
        }
    }

    private SyncResult doSync(HubProperties.Service svc, boolean force) {
        String name = svc.name();
        Path dir = props.resolvedWorkspace().resolve(name);
        ServiceState current = states.get(name);
        String previous = null;
        CommitInfo target;
        try {
            if (git.isCloned(dir)) {
                git.fetch(dir, svc.branch());
                previous = git.head(dir);
                String remote = git.remoteHead(dir, svc.branch());
                if (!force && shouldSkip(current, previous, remote)) {
                    states.put(name, current.checked());
                    return SyncResult.UNCHANGED;
                }
                git.resetHard(dir, remote);
            } else {
                createDirectories(dir.getParent());
                log.info("[{}] clone {} ({})", name, svc.repo(), svc.branch());
                git.cloneRepo(svc.repo(), svc.branch(), dir);
            }
            target = git.describe(dir, "HEAD");
        } catch (CommandFailedException e) {
            log.warn("[{}] {}", name, e.getMessage());
            states.put(name, current.failed(null, e.getMessage(), tail(e.output())));
            return SyncResult.FAILED;
        }

        states.put(name, current.deploying(target));
        log.info("[{}] {} 배포 시작: {}", name, target.shortSha(), target.subject());

        CommandResult result = compose.buildAndUp(svc.composeServiceOrName());
        if (result.ok()) {
            log.info("[{}] {} 배포 완료", name, target.shortSha());
            states.put(name, states.get(name).deployed(target, tail(result.output())));
            return SyncResult.DEPLOYED;
        }

        log.warn("[{}] {} 배포 실패 (exit {})", name, target.shortSha(), result.exitCode());
        if (previous == null || previous.equals(target.sha())) {
            states.put(name, states.get(name).failed(target, target.shortSha() + " 배포 실패", tail(result.output())));
            return SyncResult.FAILED;
        }
        return rollback(svc, dir, target, previous, result);
    }

    /** 이미 최신으로 떠 있거나, 같은 커밋이 이미 실패했으면 매 주기마다 다시 시도하지 않는다. */
    private static boolean shouldSkip(ServiceState current, String localHead, String remoteHead) {
        boolean sameCommit = localHead.equals(remoteHead);
        return switch (current.status()) {
            case RUNNING -> sameCommit;
            case ROLLED_BACK, FAILED -> current.failedCommit() != null && current.failedCommit().sha().equals(remoteHead);
            default -> false;
        };
    }

    private SyncResult rollback(HubProperties.Service svc, Path dir, CommitInfo failed, String previous,
            CommandResult failure) {
        String name = svc.name();
        CommitInfo restored;
        try {
            git.resetHard(dir, previous);
            restored = git.describe(dir, "HEAD");
        } catch (CommandFailedException e) {
            states.put(name, states.get(name).failed(failed, "되돌리기 실패: " + e.getMessage(),
                    tail(failure.output() + "\n" + e.output())));
            return SyncResult.FAILED;
        }
        CommandResult retry = compose.buildAndUp(svc.composeServiceOrName());
        String output = tail(failure.output() + "\n----- " + restored.shortSha() + " 로 되돌린 뒤 -----\n" + retry.output());
        if (retry.ok()) {
            log.info("[{}] {} 로 되돌림", name, restored.shortSha());
            states.put(name, states.get(name).rolledBack(failed, restored, output));
            return SyncResult.ROLLED_BACK;
        }
        states.put(name, states.get(name).failed(failed, "배포 실패, 되돌린 버전도 기동 실패", output));
        return SyncResult.FAILED;
    }

    private static void createDirectories(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String tail(String output) {
        if (output == null) {
            return "";
        }
        String[] lines = output.strip().split("\n");
        if (lines.length <= MAX_OUTPUT_LINES) {
            return String.join("\n", lines);
        }
        return "... (앞부분 생략)\n" + String.join("\n", Arrays.copyOfRange(lines, lines.length - MAX_OUTPUT_LINES, lines.length));
    }
}
