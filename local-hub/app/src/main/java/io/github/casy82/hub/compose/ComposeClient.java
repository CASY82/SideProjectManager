package io.github.casy82.hub.compose;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import io.github.casy82.hub.config.HubProperties;
import io.github.casy82.hub.process.CommandResult;
import io.github.casy82.hub.process.CommandRunner;

/** docker compose CLI 래퍼. 모든 명령은 hub.base-dir 에서 같은 -p / -f 옵션으로 실행된다. */
@Component
public class ComposeClient {

    private final CommandRunner runner;
    private final HubProperties props;

    public ComposeClient(CommandRunner runner, HubProperties props) {
        this.runner = runner;
        this.props = props;
    }

    /** 이미지를 다시 빌드하고 해당 서비스만 다시 띄운다. 의존 서비스(depends_on)는 건드리지 않는다. */
    public CommandResult buildAndUp(String service) {
        return run(List.of(), "up", "-d", "--build", "--no-deps", "--wait", service);
    }

    public CommandResult upProfiles(List<String> profiles) {
        if (profiles.isEmpty()) {
            return new CommandResult(0, "");
        }
        return run(profiles, "up", "-d");
    }

    public CommandResult ps() {
        return run(List.of("*"), "ps", "--all", "--format", "table {{.Service}}\t{{.State}}\t{{.Status}}\t{{.Ports}}");
    }

    public CommandResult logs(String service, int tail) {
        return run(List.of("*"), "logs", "--no-color", "--tail", String.valueOf(tail), service);
    }

    private CommandResult run(List<String> profiles, String... args) {
        List<String> cmd = new ArrayList<>(List.of("docker", "compose", "-p", props.projectName()));
        for (String file : props.composeFiles()) {
            cmd.add("-f");
            cmd.add(file);
        }
        for (String profile : profiles) {
            cmd.add("--profile");
            cmd.add(profile);
        }
        cmd.addAll(List.of(args));
        return runner.run(props.resolvedBaseDir(), cmd);
    }
}
