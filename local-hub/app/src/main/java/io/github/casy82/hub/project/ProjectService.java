package io.github.casy82.hub.project;

import java.nio.file.Path;

import org.springframework.stereotype.Service;

import io.github.casy82.hub.InvalidInputException;
import io.github.casy82.hub.compose.ComposeClient;
import io.github.casy82.hub.git.GitClient;
import io.github.casy82.hub.process.CommandFailedException;
import io.github.casy82.hub.process.CommandResult;

/** 대시보드에서 하는 프로젝트 변경을 한곳에 모았다. 검사 → 파일 저장·버전 기록 → 다시 읽기 → 필요한 배포 순서로 처리한다. */
@Service
public class ProjectService {

    private final ProjectStore store;
    private final ProjectRegistry registry;
    private final DeployTrigger deploys;
    private final ComposeClient compose;
    private final GitClient git;

    public ProjectService(ProjectStore store, ProjectRegistry registry, DeployTrigger deploys, ComposeClient compose,
            GitClient git) {
        this.store = store;
        this.registry = registry;
        this.deploys = deploys;
        this.compose = compose;
        this.git = git;
    }

    public ProjectConfig create(String id, String name) {
        store.create(id, name);
        return registry.reload(id);
    }

    /**
     * project.yml 을 바꾸면 새로 생긴 서비스를 바로 확인하고, compose.yml 을 바꾸면 모든 서비스를 다시 띄워 반영한다.
     * @return 새 버전이 생겼으면 true
     */
    public boolean saveFile(String id, String file, String content, String message) {
        boolean composeFile = ProjectStore.COMPOSE_FILE.equals(file);
        if (composeFile) {
            requireValidCompose(id, content);
        }
        boolean changed = store.saveFile(id, file, content, message);
        if (changed) {
            deployAll(registry.reload(id), composeFile);
        }
        return changed;
    }

    /** 서비스를 특정 커밋·태그로 고정하고 그 버전으로 배포한다. 이미 받아 둔 저장소에서 찾을 수 있는 ref 만 받는다 */
    public void pin(String id, String service, String ref) {
        // git 명령 인자로 넘기기 전에 형식부터 확인한다
        ProjectYaml.requireRef(ref);
        if (store.setPin(id, service, ref, service + " 를 " + pinLabel(id, service, ref) + " 로 고정")) {
            registry.reload(id);
            deploys.requestSync(id, service, false);
        }
    }

    /** 버전 기록에 남길 이름. 태그·브랜치면 "v1.0 (abc1234)", 커밋이면 "abc1234" */
    private String pinLabel(String id, String service, String ref) {
        Path dir = store.workspace(id).resolve(service);
        if (!git.isCloned(dir)) {
            return ref;
        }
        String sha;
        try {
            sha = git.resolve(dir, ref);
        } catch (CommandFailedException e) {
            throw new InvalidInputException(e.getMessage());
        }
        String shortSha = GitClient.shortSha(sha);
        return sha.startsWith(ref) ? shortSha : ref + " (" + shortSha + ")";
    }

    public void unpin(String id, String service) {
        if (store.setPin(id, service, null, service + " 고정 해제 (브랜치 최신을 따라감)")) {
            registry.reload(id);
            deploys.requestSync(id, service, false);
        }
    }

    /** 설정을 예전 버전으로 되돌리고 모든 서비스를 다시 띄운다 */
    public boolean restore(String id, String sha) {
        ConfigHistory history = store.history(id);
        boolean changed = history.restore(sha, "버전 " + GitClient.shortSha(sha) + " 으로 되돌림");
        if (changed) {
            deployAll(registry.reload(id), true);
        }
        return changed;
    }

    /** 프로젝트를 지운다. removeContainers 면 컨테이너도 내린다 (볼륨은 남는다) */
    public void delete(String id, boolean removeContainers) {
        if (registry.get(id).isEmpty()) {
            throw new InvalidInputException("없는 프로젝트입니다: " + id);
        }
        if (removeContainers) {
            compose.down(id);
        }
        registry.remove(id);
        store.delete(id);
    }

    /** docker compose config 로 문법을 확인한다. 파일 안의 상대 경로가 맞도록 프로젝트 디렉터리 기준으로 본다 */
    private void requireValidCompose(String id, String content) {
        CommandResult r = compose.validate(store.requireExisting(id), content);
        if (!r.ok()) {
            throw new InvalidInputException("compose.yml 검사 실패:\n" + r.output().strip());
        }
    }

    private void deployAll(ProjectConfig project, boolean force) {
        for (ServiceConfig s : project.services()) {
            deploys.requestSync(project.id(), s.name(), force);
        }
    }
}
