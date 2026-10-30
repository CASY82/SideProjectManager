package io.github.casy82.hub.deploy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.UnaryOperator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import io.github.casy82.hub.compose.ComposeClient;
import io.github.casy82.hub.git.GitClient;
import io.github.casy82.hub.git.GitClient.CommitInfo;
import io.github.casy82.hub.process.CommandFailedException;
import io.github.casy82.hub.process.CommandResult;
import io.github.casy82.hub.process.Outputs;
import io.github.casy82.hub.project.ProjectConfig;
import io.github.casy82.hub.project.ProjectRegistry;
import io.github.casy82.hub.project.ProjectStore;
import io.github.casy82.hub.project.ServiceConfig;

/**
 * 서비스 하나를 "목표 커밋 확인 → 바뀌었으면 빌드·재기동 → 실패하면 이전 커밋으로 되돌림" 순서로 맞춘다.
 * 목표 커밋은 고정(pin)된 커밋·태그가 있으면 그것, 없으면 브랜치 최신이다.
 * 같은 서비스에 대한 동기화는 동시에 하나만 돈다.
 */
@Service
public class DeployService {

    private static final Logger log = LoggerFactory.getLogger(DeployService.class);
    private static final int MAX_OUTPUT_LINES = 200;

    private final ProjectRegistry registry;
    private final ServiceStates states;
    private final ProjectStore store;
    private final GitClient git;
    private final ComposeClient compose;
    private final List<DeployListener> listeners;

    public DeployService(ProjectRegistry registry, ServiceStates states, ProjectStore store, GitClient git,
            ComposeClient compose, List<DeployListener> listeners) {
        this.registry = registry;
        this.states = states;
        this.store = store;
        this.git = git;
        this.compose = compose;
        this.listeners = listeners;
    }

    /** WAITING: compose.yml 에 서비스 정의가 없어 배포하지 않음 */
    public enum SyncResult { DEPLOYED, UNCHANGED, WAITING, ROLLED_BACK, FAILED, BUSY, UNKNOWN }

    /**
     * @param force true 면 커밋이 그대로여도 다시 빌드·기동한다 (대시보드의 "다시 배포" 버튼)
     */
    public SyncResult sync(String projectId, String serviceName, boolean force) {
        if (registry.get(projectId).flatMap(p -> p.service(serviceName)).isEmpty()) {
            return SyncResult.UNKNOWN;
        }
        ReentrantLock lock = states.lock(projectId, serviceName);
        // 주기 확인은 겹치면 건너뛰지만, force(설정 저장·다시 배포)는 버리면 바뀐 설정이 반영되지 않으므로 앞 작업을 기다린다
        if (force) {
            lock.lock();
        } else if (!lock.tryLock()) {
            return SyncResult.BUSY;
        }
        try {
            // 기다리는 동안 설정이 바뀌었을 수 있으니 잠금을 잡은 뒤 다시 읽는다
            ProjectConfig project = registry.get(projectId).orElse(null);
            ServiceConfig svc = project == null ? null : project.service(serviceName).orElse(null);
            ServiceState before = states.get(projectId, serviceName).orElse(null);
            if (svc == null || before == null) {
                return SyncResult.UNKNOWN;
            }
            SyncResult result = doSync(project, svc, before, force);
            if (result != SyncResult.UNCHANGED && result != SyncResult.WAITING) {
                ServiceState after = states.get(projectId, serviceName).orElse(before);
                for (DeployListener listener : listeners) {
                    try {
                        listener.onSynced(project, svc, before, after);
                    } catch (RuntimeException e) {
                        log.warn("[{}/{}] {} 처리 중 오류", projectId, serviceName, listener.getClass().getSimpleName(), e);
                    }
                }
            }
            return result;
        } finally {
            lock.unlock();
        }
    }

    private SyncResult doSync(ProjectConfig project, ServiceConfig svc, ServiceState current, boolean force) {
        String name = svc.name();
        // project.yml 을 compose.yml 보다 먼저 저장한 경우 등. 실패로 남기거나 이슈를 올리지 않고 기다린다
        String composeName = svc.composeServiceOrName();
        if (!store.composeServices(project.id()).contains(composeName)) {
            update(project, name, s -> s.waiting("compose.yml 에 '" + composeName + "' 서비스 정의가 필요해요"));
            return SyncResult.WAITING;
        }
        Path dir = store.workspace(project.id()).resolve(name);
        Optional<Checkout> checkout;
        try {
            checkout = checkout(project, svc, current, dir, force);
        } catch (CommandFailedException e) {
            log.warn("[{}] {}", tag(project, svc), e.getMessage());
            update(project, name, s -> s.failed(null, e.getMessage(), tail(e.output())));
            return SyncResult.FAILED;
        }
        if (checkout.isEmpty()) {
            update(project, name, ServiceState::checked);
            return SyncResult.UNCHANGED;
        }
        return deploy(project, svc, dir, checkout.get());
    }

    /**
     * @param previous 바꾸기 전 작업 공간의 커밋. 처음 clone 했으면 null
     * @param target   이번에 배포할 커밋
     */
    private record Checkout(String previous, CommitInfo target) {

        boolean canRollBack() {
            return previous != null && !previous.equals(target.sha());
        }
    }

    /** 작업 공간을 목표 커밋으로 맞춘다. 이미 맞거나 다시 시도할 필요가 없으면 비어 있다 */
    private Optional<Checkout> checkout(ProjectConfig config, ServiceConfig svc, ServiceState current, Path dir,
            boolean force) {
        String targetRef = config.targetRef(svc);
        String previous = null;
        if (git.isCloned(dir)) {
            if (git.ensureRemote(dir, svc.repo())) {
                log.info("[{}/{}] 저장소 주소 변경: {}", config.id(), svc.name(), svc.repo());
            }
            git.fetch(dir, svc.branch());
            previous = git.head(dir);
            String wanted = git.resolve(dir, targetRef);
            if (!force && shouldSkip(current, previous, wanted)) {
                return Optional.empty();
            }
            git.resetHard(dir, wanted);
        } else {
            createDirectories(dir.getParent());
            log.info("[{}/{}] clone {} ({})", config.id(), svc.name(), svc.repo(), svc.branch());
            git.cloneRepo(svc.repo(), svc.branch(), dir);
            if (!targetRef.equals("origin/" + svc.branch())) {
                git.fetch(dir, svc.branch());
                git.resetHard(dir, git.resolve(dir, targetRef));
            }
        }
        return Optional.of(new Checkout(previous, git.describe(dir, "HEAD")));
    }

    private SyncResult deploy(ProjectConfig project, ServiceConfig svc, Path dir, Checkout checkout) {
        String name = svc.name();
        CommitInfo target = checkout.target();
        update(project, name, s -> s.deploying(target));
        log.info("[{}] {} 배포 시작: {}", tag(project, svc), target.shortSha(), target.subject());

        CommandResult result = compose.buildAndUp(project.id(), svc.composeServiceOrName());
        if (result.ok()) {
            log.info("[{}] {} 배포 완료", tag(project, svc), target.shortSha());
            update(project, name, s -> s.deployed(target, tail(result.output())));
            return SyncResult.DEPLOYED;
        }

        log.warn("[{}] {} 배포 실패 (exit {})", tag(project, svc), target.shortSha(), result.exitCode());
        if (!checkout.canRollBack()) {
            update(project, name, s -> s.failed(target, target.shortSha() + " 배포 실패", tail(result.output())));
            return SyncResult.FAILED;
        }
        return rollback(project, svc, dir, target, checkout.previous(), result);
    }

    /** 이미 목표 커밋으로 떠 있거나, 같은 커밋이 이미 실패했으면 매 주기마다 다시 시도하지 않는다. */
    private static boolean shouldSkip(ServiceState current, String localHead, String wanted) {
        boolean sameCommit = localHead.equals(wanted);
        return switch (current.status()) {
            case RUNNING -> sameCommit;
            case ROLLED_BACK, FAILED -> current.failedCommit() != null && current.failedCommit().sha().equals(wanted);
            default -> false;
        };
    }

    private SyncResult rollback(ProjectConfig project, ServiceConfig svc, Path dir, CommitInfo failed,
            String previous, CommandResult failure) {
        String name = svc.name();
        CommitInfo restored;
        try {
            git.resetHard(dir, previous);
            restored = git.describe(dir, "HEAD");
        } catch (CommandFailedException e) {
            update(project, name, s -> s.failed(failed, "되돌리기 실패: " + e.getMessage(),
                    tail(failure.output() + "\n" + e.output())));
            return SyncResult.FAILED;
        }
        CommandResult retry = compose.buildAndUp(project.id(), svc.composeServiceOrName());
        String output = tail(failure.output() + "\n----- " + restored.shortSha() + " 로 되돌린 뒤 -----\n" + retry.output());
        if (retry.ok()) {
            log.info("[{}] {} 로 되돌림", tag(project, svc), restored.shortSha());
            update(project, name, s -> s.rolledBack(failed, restored, output));
            return SyncResult.ROLLED_BACK;
        }
        update(project, name, s -> s.failed(failed, "배포 실패, 되돌린 버전도 기동 실패", output));
        return SyncResult.FAILED;
    }

    private void update(ProjectConfig project, String service, UnaryOperator<ServiceState> change) {
        states.update(project.id(), service, change);
    }

    private static String tag(ProjectConfig project, ServiceConfig svc) {
        return project.id() + "/" + svc.name();
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
        String last = Outputs.lastLines(output, MAX_OUTPUT_LINES);
        return last.length() < output.strip().length() ? "... (앞부분 생략)\n" + last : last;
    }
}
