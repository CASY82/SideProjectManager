package io.github.casy82.hub.project;

import java.nio.file.Path;
import java.util.List;

import org.springframework.stereotype.Service;

import io.github.casy82.hub.compose.ComposeClient;
import io.github.casy82.hub.deploy.PollScheduler;
import io.github.casy82.hub.git.GitClient;
import io.github.casy82.hub.process.CommandFailedException;

/** 대시보드에서 하는 프로젝트 변경을 한곳에 모았다. 파일 저장 → 버전 기록 → 다시 읽기 → 필요한 배포 순서로 처리한다. */
@Service
public class ProjectService {

    private final ProjectStore store;
    private final ProjectRegistry registry;
    private final PollScheduler poller;
    private final ComposeClient compose;
    private final GitClient git;

    public ProjectService(ProjectStore store, ProjectRegistry registry, PollScheduler poller, ComposeClient compose,
            GitClient git) {
        this.store = store;
        this.registry = registry;
        this.poller = poller;
        this.compose = compose;
        this.git = git;
    }

    public ProjectRegistry.Project create(String id, String name) {
        store.create(id, name);
        return registry.reload(id);
    }

    /**
     * project.yml 을 바꾸면 새로 생긴 서비스를 바로 확인하고, compose.yml 을 바꾸면 모든 서비스를 다시 띄워 반영한다.
     * @return 새 버전이 생겼으면 true
     */
    public boolean saveFile(String id, String file, String content, String message) {
        boolean changed = store.saveFile(id, file, content, message);
        if (changed) {
            ProjectRegistry.Project project = registry.reload(id);
            deployAll(project, ProjectStore.COMPOSE_FILE.equals(file));
        }
        return changed;
    }

    /** 서비스를 특정 커밋·태그로 고정하고 그 버전으로 배포한다. 이미 받아 둔 저장소에서 찾을 수 있는 ref 만 받는다 */
    public void pin(String id, String service, String ref) {
        Path dir = store.workspace(id).resolve(service);
        String label = ref;
        if (git.isCloned(dir)) {
            try {
                String sha = git.resolve(dir, ref);
                label = ref.equals(sha) || sha.startsWith(ref) ? sha.substring(0, 7) : ref + " (" + sha.substring(0, 7) + ")";
            } catch (CommandFailedException e) {
                throw new IllegalArgumentException(e.getMessage());
            }
        }
        if (store.setPin(id, service, ref, service + " 를 " + label + " 로 고정")) {
            registry.reload(id);
            poller.triggerAsync(id, service, false);
        }
    }

    public void unpin(String id, String service) {
        if (store.setPin(id, service, null, service + " 고정 해제 (브랜치 최신을 따라감)")) {
            registry.reload(id);
            poller.triggerAsync(id, service, false);
        }
    }

    /** 설정을 예전 버전으로 되돌리고 모든 서비스를 다시 띄운다 */
    public boolean restore(String id, String sha) {
        ConfigHistory history = store.history(id);
        boolean changed = history.restore(sha, "버전 " + sha.substring(0, Math.min(7, sha.length())) + " 으로 되돌림");
        if (changed) {
            deployAll(registry.reload(id), true);
        }
        return changed;
    }

    /** 프로젝트를 지운다. removeContainers 면 컨테이너도 내린다 (볼륨은 남는다) */
    public void delete(String id, boolean removeContainers) {
        if (registry.get(id).isEmpty()) {
            throw new IllegalArgumentException("없는 프로젝트입니다: " + id);
        }
        if (removeContainers) {
            compose.down(id);
        }
        registry.remove(id);
        store.delete(id);
    }

    private void deployAll(ProjectRegistry.Project project, boolean force) {
        List<ServiceConfig> services = project.config().services();
        for (ServiceConfig s : services) {
            poller.triggerAsync(project.id(), s.name(), force);
        }
    }
}
