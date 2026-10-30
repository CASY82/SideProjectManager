package io.github.casy82.hub.web;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.casy82.hub.compose.ComposeClient;
import io.github.casy82.hub.config.HubProperties;
import io.github.casy82.hub.deploy.DeployService;
import io.github.casy82.hub.deploy.PollScheduler;
import io.github.casy82.hub.deploy.ServiceState;
import io.github.casy82.hub.process.CommandResult;

@RestController
@RequestMapping("/api")
public class HubController {

    private final HubProperties props;
    private final DeployService deploys;
    private final PollScheduler poller;
    private final ComposeClient compose;

    public HubController(HubProperties props, DeployService deploys, PollScheduler poller, ComposeClient compose) {
        this.props = props;
        this.deploys = deploys;
        this.poller = poller;
        this.compose = compose;
    }

    @GetMapping("/services")
    public List<ServiceState> services() {
        return deploys.states();
    }

    @GetMapping("/services/{name}")
    public ResponseEntity<ServiceState> service(@PathVariable String name) {
        return ResponseEntity.of(deploys.state(name));
    }

    /**
     * 즉시 확인·배포. force=true 면 커밋이 그대로여도 다시 빌드한다.
     * GitHub 웹훅이나 CI 에서 호출해도 된다 (hub.api-token 을 설정했다면 X-Hub-Token 헤더 필요).
     */
    @PostMapping("/services/{name}/deploy")
    public ResponseEntity<Map<String, String>> deploy(@PathVariable String name,
            @RequestParam(defaultValue = "false") boolean force,
            @RequestHeader(name = "X-Hub-Token", required = false) String token) {
        if (!authorized(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "X-Hub-Token 이 올바르지 않습니다"));
        }
        if (deploys.state(name).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        poller.triggerAsync(name, force);
        return ResponseEntity.accepted().body(Map.of("service", name, "result", "queued"));
    }

    @GetMapping("/services/{name}/logs")
    public ResponseEntity<Map<String, String>> logs(@PathVariable String name,
            @RequestParam(defaultValue = "200") int tail) {
        return props.services().stream()
                .filter(s -> s.name().equals(name))
                .findFirst()
                .map(s -> ResponseEntity.ok(output(compose.logs(s.composeServiceOrName(), Math.min(tail, 2000)))))
                .orElse(ResponseEntity.notFound().build());
    }

    /** compose 프로젝트 전체 컨테이너 상태 (공통 서비스 포함) */
    @GetMapping("/compose/ps")
    public Map<String, String> ps() {
        return output(compose.ps());
    }

    private boolean authorized(String token) {
        return props.apiToken().isBlank() || props.apiToken().equals(token);
    }

    private static Map<String, String> output(CommandResult result) {
        return Map.of("exitCode", String.valueOf(result.exitCode()), "output", result.output());
    }
}
