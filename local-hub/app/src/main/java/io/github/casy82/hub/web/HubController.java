package io.github.casy82.hub.web;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.casy82.hub.compose.ComposeClient;
import io.github.casy82.hub.deploy.DeployHistory;
import io.github.casy82.hub.deploy.PollScheduler;
import io.github.casy82.hub.git.GitClient;
import io.github.casy82.hub.process.CommandResult;
import io.github.casy82.hub.project.ConfigHistory;
import io.github.casy82.hub.project.ProjectRegistry;
import io.github.casy82.hub.project.ProjectService;
import io.github.casy82.hub.project.ProjectStore;
import io.github.casy82.hub.project.ServiceConfig;

/**
 * 대시보드가 쓰는 API. 변경 요청(POST/PUT/DELETE)은 hub.api-token 이 설정돼 있으면 X-Hub-Token 헤더가 필요하다
 * ({@link TokenInterceptor}).
 */
@RestController
@RequestMapping("/api")
public class HubController {

    private final ProjectRegistry registry;
    private final ProjectStore store;
    private final ProjectService projects;
    private final PollScheduler poller;
    private final ComposeClient compose;
    private final DeployHistory history;
    private final GitClient git;

    public HubController(ProjectRegistry registry, ProjectStore store, ProjectService projects, PollScheduler poller,
            ComposeClient compose, DeployHistory history, GitClient git) {
        this.registry = registry;
        this.store = store;
        this.projects = projects;
        this.poller = poller;
        this.compose = compose;
        this.history = history;
        this.git = git;
    }

    // ---------- 프로젝트 ----------

    @GetMapping("/projects")
    public List<Map<String, Object>> list() {
        return registry.all().stream().map(HubController::view).toList();
    }

    public record CreateRequest(String id, String name) {
    }

    @PostMapping("/projects")
    public ResponseEntity<Map<String, Object>> create(@RequestBody CreateRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(view(projects.create(req.id(), req.name())));
    }

    @GetMapping("/projects/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        return view(project(id));
    }

    @DeleteMapping("/projects/{id}")
    public Map<String, Object> delete(@PathVariable String id,
            @RequestParam(defaultValue = "true") boolean removeContainers) {
        projects.delete(id, removeContainers);
        return Map.of("deleted", id);
    }

    @GetMapping("/projects/{id}/files/{file}")
    public Map<String, Object> readFile(@PathVariable String id, @PathVariable String file) {
        project(id);
        return Map.of("file", file, "content", store.readFile(id, file));
    }

    public record SaveRequest(String content, String message) {
    }

    @PutMapping("/projects/{id}/files/{file}")
    public Map<String, Object> saveFile(@PathVariable String id, @PathVariable String file,
            @RequestBody SaveRequest req) {
        project(id);
        boolean changed = projects.saveFile(id, file, req.content() == null ? "" : req.content(), req.message());
        return Map.of("changed", changed);
    }

    @GetMapping("/projects/{id}/ps")
    public Map<String, String> ps(@PathVariable String id) {
        project(id);
        return output(compose.ps(id));
    }

    // ---------- 서비스 ----------

    /** 즉시 확인·배포. force=true 면 커밋이 그대로여도 다시 빌드한다. CI 나 스크립트에서 호출해도 된다 */
    @PostMapping("/projects/{id}/services/{service}/deploy")
    public ResponseEntity<Map<String, String>> deploy(@PathVariable String id, @PathVariable String service,
            @RequestParam(defaultValue = "false") boolean force) {
        service(id, service);
        poller.triggerAsync(id, service, force);
        return ResponseEntity.accepted().body(Map.of("project", id, "service", service, "result", "queued"));
    }

    @GetMapping("/projects/{id}/services/{service}/logs")
    public Map<String, String> logs(@PathVariable String id, @PathVariable String service,
            @RequestParam(defaultValue = "200") int tail) {
        ServiceConfig s = service(id, service);
        return output(compose.logs(id, s.composeServiceOrName(), Math.min(tail, 2000)));
    }

    /** 고정할 버전 후보: 브랜치의 최근 커밋, 태그, 예전에 성공한 배포 */
    @GetMapping("/projects/{id}/services/{service}/versions")
    public Map<String, Object> versions(@PathVariable String id, @PathVariable String service) {
        ProjectRegistry.Project p = project(id);
        ServiceConfig s = service(id, service);
        Path dir = store.workspace(id).resolve(service);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pin", p.config().pins().get(service));
        out.put("branch", s.branch());
        out.put("commits", git.isCloned(dir) ? git.recentCommits(dir, "origin/" + s.branch(), 30) : List.of());
        out.put("tags", git.isCloned(dir) ? git.tags(dir) : List.of());
        // 같은 커밋을 여러 번 배포했으면 가장 최근 것만 보여준다
        Set<String> seen = new HashSet<>();
        out.put("deployed", history.read(id, service, 500).stream()
                .filter(e -> e.status().equals("RUNNING") && seen.add(e.sha())).limit(20).toList());
        return out;
    }

    public record PinRequest(String ref) {
    }

    @PutMapping("/projects/{id}/services/{service}/pin")
    public Map<String, Object> pin(@PathVariable String id, @PathVariable String service, @RequestBody PinRequest req) {
        service(id, service);
        if (req.ref() == null || req.ref().isBlank()) {
            throw new IllegalArgumentException("고정할 커밋이나 태그를 적어 주세요");
        }
        projects.pin(id, service, req.ref().strip());
        return Map.of("pinned", req.ref().strip());
    }

    @DeleteMapping("/projects/{id}/services/{service}/pin")
    public Map<String, Object> unpin(@PathVariable String id, @PathVariable String service) {
        service(id, service);
        projects.unpin(id, service);
        return Map.of("pinned", false);
    }

    // ---------- 형상관리 ----------

    @GetMapping("/projects/{id}/history")
    public List<DeployHistory.Entry> deployHistory(@PathVariable String id,
            @RequestParam(required = false) String service, @RequestParam(defaultValue = "100") int limit) {
        project(id);
        return history.read(id, service, Math.min(limit, 1000));
    }

    @GetMapping("/projects/{id}/config-versions")
    public List<ConfigHistory.Version> configVersions(@PathVariable String id,
            @RequestParam(defaultValue = "50") int limit) {
        project(id);
        return store.history(id).versions(Math.min(limit, 500));
    }

    @GetMapping("/projects/{id}/config-versions/{sha}")
    public Map<String, String> configVersion(@PathVariable String id, @PathVariable String sha) {
        project(id);
        ConfigHistory h = store.history(id);
        return Map.of("changes", h.diff(sha), "diffWithCurrent", h.diffWithCurrent(sha));
    }

    @PostMapping("/projects/{id}/config-versions/{sha}/restore")
    public Map<String, Object> restore(@PathVariable String id, @PathVariable String sha) {
        project(id);
        return Map.of("changed", projects.restore(id, sha));
    }

    // ---------- 공통 서비스 ----------

    @GetMapping("/infra/ps")
    public Map<String, String> infraPs() {
        return output(compose.infraPs());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(NotFound.class)
    public ResponseEntity<Map<String, String>> notFound(NotFound e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    static class NotFound extends RuntimeException {
        NotFound(String message) {
            super(message);
        }
    }

    private ProjectRegistry.Project project(String id) {
        return registry.get(id).orElseThrow(() -> new NotFound("없는 프로젝트입니다: " + id));
    }

    private ServiceConfig service(String id, String service) {
        return project(id).config().service(service)
                .orElseThrow(() -> new NotFound("없는 서비스입니다: " + id + "/" + service));
    }

    private static Map<String, Object> view(ProjectRegistry.Project p) {
        List<Map<String, Object>> services = new ArrayList<>();
        for (ServiceConfig s : p.config().services()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", s.name());
            m.put("repo", s.repo());
            m.put("branch", s.branch());
            m.put("pin", p.config().pins().get(s.name()));
            m.put("state", p.states().get(s.name()));
            services.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", p.id());
        out.put("name", p.config().name());
        out.put("description", p.config().description());
        out.put("services", services);
        return out;
    }

    private static Map<String, String> output(CommandResult result) {
        return Map.of("exitCode", String.valueOf(result.exitCode()), "output", result.output());
    }
}
