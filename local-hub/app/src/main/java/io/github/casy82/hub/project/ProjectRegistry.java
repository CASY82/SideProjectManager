package io.github.casy82.hub.project;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.github.casy82.hub.deploy.ServiceState;

/** 디스크의 프로젝트 설정과 메모리의 배포 상태를 함께 들고 있는다. 설정이 바뀌면 reload 로 다시 읽는다. */
@Component
public class ProjectRegistry {

    private static final Logger log = LoggerFactory.getLogger(ProjectRegistry.class);

    private final ProjectStore store;
    private final Map<String, Project> projects = new ConcurrentHashMap<>();

    /**
     * @param states 서비스 이름 → 상태. 서비스 추가·삭제에 맞춰 reload 가 항목을 고친다
     * @param locks  서비스 이름 → 같은 서비스 동기화가 겹치지 않게 하는 잠금
     */
    public record Project(ProjectConfig config, Map<String, ServiceState> states, Map<String, ReentrantLock> locks) {

        public String id() {
            return config.id();
        }
    }

    public ProjectRegistry(ProjectStore store) {
        this.store = store;
        for (String id : store.ids()) {
            try {
                reload(id);
            } catch (RuntimeException e) {
                log.error("[{}] 프로젝트 설정을 읽지 못했습니다: {}", id, e.getMessage());
            }
        }
    }

    public List<Project> all() {
        return projects.values().stream().sorted(Comparator.comparing(Project::id)).toList();
    }

    public Optional<Project> get(String id) {
        return Optional.ofNullable(projects.get(id));
    }

    /** 설정을 다시 읽는다. 저장소·브랜치가 그대로인 서비스는 상태를 이어받는다 */
    public synchronized Project reload(String id) {
        ProjectConfig config = store.load(id).orElseThrow(() -> new IllegalArgumentException("없는 프로젝트입니다: " + id));
        Project old = projects.get(id);
        // 배포 중에 설정이 바뀌어도 결과가 사라지지 않도록 상태 맵은 같은 것을 계속 쓴다
        Map<String, ServiceState> states = old == null ? new ConcurrentHashMap<>() : old.states();
        Map<String, ReentrantLock> locks = new LinkedHashMap<>();
        states.keySet().retainAll(config.services().stream().map(ServiceConfig::name).toList());
        for (ServiceConfig s : config.services()) {
            ServiceState prev = states.get(s.name());
            boolean same = prev != null && prev.repo().equals(s.repo()) && prev.branch().equals(s.branch());
            if (!same) {
                states.put(s.name(), ServiceState.initial(s.name(), s.repo(), s.branch()));
            }
            locks.put(s.name(), old != null && old.locks().containsKey(s.name())
                    ? old.locks().get(s.name()) : new ReentrantLock());
        }
        Project project = new Project(config, states, Map.copyOf(locks));
        projects.put(id, project);
        return project;
    }

    public void remove(String id) {
        projects.remove(id);
    }
}
