package io.github.casy82.hub.deploy;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import io.github.casy82.hub.project.ProjectChangeListener;
import io.github.casy82.hub.project.ProjectConfig;
import io.github.casy82.hub.project.ServiceConfig;

/**
 * 서비스마다 메모리에 두는 배포 상태와, 같은 서비스 동기화가 겹치지 않게 하는 잠금.
 * 설정을 다시 읽으면 저장소·브랜치가 그대로인 서비스는 상태를 이어받고, 빠진 서비스는 지운다.
 */
@Component
public class ServiceStates implements ProjectChangeListener {

    /** 프로젝트 id → 서비스 이름 → 상태 */
    private final Map<String, Map<String, ServiceState>> states = new ConcurrentHashMap<>();
    /** "프로젝트 id/서비스 이름" → 잠금 */
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public Optional<ServiceState> get(String projectId, String service) {
        return Optional.ofNullable(states.getOrDefault(projectId, Map.of()).get(service));
    }

    ReentrantLock lock(String projectId, String service) {
        return locks.computeIfAbsent(key(projectId, service), k -> new ReentrantLock());
    }

    /** 상태는 불변 객체라 바꿀 때마다 새로 만들어 넣는다. 그사이 설정에서 서비스가 빠졌으면 되살리지 않는다 */
    void update(String projectId, String service, UnaryOperator<ServiceState> change) {
        Map<String, ServiceState> project = states.get(projectId);
        if (project != null) {
            project.computeIfPresent(service, (k, state) -> change.apply(state));
        }
    }

    @Override
    public void onLoaded(ProjectConfig config) {
        // 배포 중에 설정이 바뀌어도 결과가 사라지지 않도록 프로젝트의 상태 맵은 같은 것을 계속 쓴다
        Map<String, ServiceState> project = states.computeIfAbsent(config.id(), id -> new ConcurrentHashMap<>());
        Set<String> names = config.services().stream().map(ServiceConfig::name).collect(Collectors.toSet());
        project.keySet().retainAll(names);
        locks.keySet().removeIf(k -> k.startsWith(config.id() + "/") && !names.contains(serviceOf(k)));
        for (ServiceConfig s : config.services()) {
            ServiceState prev = project.get(s.name());
            boolean same = prev != null && prev.repo().equals(s.repo()) && prev.branch().equals(s.branch());
            if (!same) {
                project.put(s.name(), ServiceState.initial(s.name(), s.repo(), s.branch()));
            }
        }
    }

    @Override
    public void onRemoved(String projectId) {
        states.remove(projectId);
        locks.keySet().removeIf(k -> k.startsWith(projectId + "/"));
    }

    private static String key(String projectId, String service) {
        return projectId + "/" + service;
    }

    private static String serviceOf(String key) {
        return key.substring(key.indexOf('/') + 1);
    }
}
