package io.github.casy82.hub.compose;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import io.github.casy82.hub.config.HubProperties;
import io.github.casy82.hub.process.CommandResult;
import io.github.casy82.hub.process.CommandRunner;

/**
 * docker compose CLI 래퍼.
 * 공통 서비스는 hub.base-dir 의 compose 파일로 "{project-name}" 프로젝트에,
 * 각 프로젝트는 data/projects/{id}/compose.yml 로 "{project-name}-{id}" 프로젝트에 띄운다.
 * 모두 같은 외부 네트워크(hub.network)에 붙어서 서비스 이름으로 서로 접속한다.
 */
@Component
public class ComposeClient {

    private final CommandRunner runner;
    private final HubProperties props;

    public ComposeClient(CommandRunner runner, HubProperties props) {
        this.runner = runner;
        this.props = props;
    }

    // ---------- 공통 서비스 ----------

    /** 공통 네트워크가 없으면 만든다. compose 파일들은 이 네트워크를 external 로 쓴다 */
    public CommandResult ensureNetwork() {
        if (runner.run(props.resolvedBaseDir(), List.of("docker", "network", "inspect", props.network())).ok()) {
            return new CommandResult(0, "");
        }
        return runner.run(props.resolvedBaseDir(), List.of("docker", "network", "create", props.network()));
    }

    /**
     * 공통 서비스만 띄운다. docker-compose.yml 에서 프로필 이름과 서비스 이름을 같게 두었으므로 서비스 이름으로 지정한다.
     */
    public CommandResult upProfiles(List<String> profiles) {
        if (profiles.isEmpty()) {
            return new CommandResult(0, "");
        }
        List<String> args = new ArrayList<>(List.of("up", "-d"));
        args.addAll(profiles);
        return infra(profiles, args);
    }

    public CommandResult infraPs() {
        return infra(List.of("*"), psArgs());
    }

    // ---------- 프로젝트 ----------

    /** 이미지를 다시 빌드하고 해당 서비스만 다시 띄운다. 의존 서비스(depends_on)는 건드리지 않는다. */
    public CommandResult buildAndUp(String projectId, String service) {
        return project(projectId, List.of("up", "-d", "--build", "--no-deps", "--wait", service));
    }

    public CommandResult ps(String projectId) {
        return project(projectId, psArgs());
    }

    public CommandResult logs(String projectId, String service, int tail) {
        return project(projectId, List.of("logs", "--no-color", "--tail", String.valueOf(tail), service));
    }

    /** 프로젝트의 컨테이너를 모두 내린다 (프로젝트 삭제 시). 볼륨은 남긴다 */
    public CommandResult down(String projectId) {
        return project(projectId, List.of("down", "--remove-orphans"));
    }

    /**
     * compose 파일 문법 검사. docker 가 없는 환경이면 검사를 건너뛴다.
     * @param dir  파일 안의 상대 경로 기준 디렉터리
     */
    public CommandResult validate(Path dir, String file) {
        CommandResult r = runner.run(dir, List.of("docker", "compose", "-f", file, "config", "-q"));
        if (r.exitCode() == -1 && r.output().startsWith("명령을 실행할 수 없음")) {
            return new CommandResult(0, "docker 가 없어 검사를 건너뜀");
        }
        return r;
    }

    public String projectName(String projectId) {
        return props.projectName() + "-" + projectId;
    }

    private static List<String> psArgs() {
        return List.of("ps", "--all", "--format", "table {{.Service}}\t{{.State}}\t{{.Status}}\t{{.Ports}}");
    }

    private CommandResult infra(List<String> profiles, List<String> args) {
        List<String> cmd = new ArrayList<>(List.of("docker", "compose", "-p", props.projectName()));
        for (String file : props.composeFiles()) {
            cmd.add("-f");
            cmd.add(file);
        }
        for (String profile : profiles) {
            cmd.add("--profile");
            cmd.add(profile);
        }
        cmd.addAll(args);
        return runner.run(props.resolvedBaseDir(), cmd);
    }

    private CommandResult project(String projectId, List<String> args) {
        Path dir = props.resolvedDataDir().resolve("projects").resolve(projectId);
        List<String> cmd = new ArrayList<>(List.of("docker", "compose", "-p", projectName(projectId),
                "-f", "compose.yml", "--profile", "*"));
        cmd.addAll(args);
        return runner.run(dir, cmd);
    }
}
