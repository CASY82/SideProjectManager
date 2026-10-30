package io.github.casy82.hub.project;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.github.casy82.hub.InvalidInputException;

/** 디스크에서 읽은 프로젝트 설정을 메모리에 들고 있는다. 설정이 바뀌면 reload 로 다시 읽는다. */
@Component
public class ProjectRegistry {

    private static final Logger log = LoggerFactory.getLogger(ProjectRegistry.class);

    private final ProjectStore store;
    private final List<ProjectChangeListener> listeners;
    private final Map<String, ProjectConfig> projects = new ConcurrentHashMap<>();

    public ProjectRegistry(ProjectStore store, List<ProjectChangeListener> listeners) {
        this.store = store;
        this.listeners = listeners;
        for (String id : store.ids()) {
            try {
                reload(id);
            } catch (RuntimeException e) {
                log.error("[{}] 프로젝트 설정을 읽지 못했습니다: {}", id, e.getMessage());
            }
        }
    }

    public List<ProjectConfig> all() {
        return projects.values().stream().sorted(Comparator.comparing(ProjectConfig::id)).toList();
    }

    public Optional<ProjectConfig> get(String id) {
        return Optional.ofNullable(projects.get(id));
    }

    public synchronized ProjectConfig reload(String id) {
        ProjectConfig config = store.load(id).orElseThrow(() -> new InvalidInputException("없는 프로젝트입니다: " + id));
        // 새 설정이 보이기 전에 딸린 상태부터 맞춘다 (새 서비스의 상태가 없는 순간이 생기지 않게)
        listeners.forEach(l -> l.onLoaded(config));
        projects.put(id, config);
        return config;
    }

    public synchronized void remove(String id) {
        projects.remove(id);
        listeners.forEach(l -> l.onRemoved(id));
    }
}
