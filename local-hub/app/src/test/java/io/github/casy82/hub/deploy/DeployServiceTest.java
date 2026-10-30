package io.github.casy82.hub.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.casy82.hub.compose.ComposeClient;
import io.github.casy82.hub.config.HubProperties;
import io.github.casy82.hub.deploy.DeployService.SyncResult;
import io.github.casy82.hub.git.GitClient;
import io.github.casy82.hub.process.CommandResult;
import io.github.casy82.hub.process.CommandRunner;

class DeployServiceTest {

    @TempDir
    Path base;

    FakeRunner runner;
    DeployService service;
    List<String> events = new ArrayList<>();

    @BeforeEach
    void setUp() {
        HubProperties props = new HubProperties(base.toString(), "workspace", List.of("docker-compose.yml"), "test",
                List.of(), Duration.ofSeconds(60), Duration.ofMinutes(1), "",
                new HubProperties.Issues(false, "", "", List.of("local-hub"), true),
                List.of(new HubProperties.Service("api", "https://example.com/api.git", "main", null, null)));
        runner = new FakeRunner(base.resolve("workspace/api"));
        service = new DeployService(props, new GitClient(runner), new ComposeClient(runner, props),
                List.of((svc, before, after) -> events.add(before.status() + "->" + after.status())));
    }

    @Test
    void 처음에는_clone_후_배포한다() {
        runner.remote = "aaaaaaa1";

        assertThat(service.sync("api", false)).isEqualTo(SyncResult.DEPLOYED);

        ServiceState state = service.state("api").orElseThrow();
        assertThat(state.status()).isEqualTo(DeployStatus.RUNNING);
        assertThat(state.deployedCommit().sha()).isEqualTo("aaaaaaa1");
        assertThat(runner.commands).anyMatch(c -> c.startsWith("git clone"));
        assertThat(runner.commands).anyMatch(c -> c.contains("up -d --build --no-deps --wait api"));
    }

    @Test
    void 커밋이_그대로면_다시_빌드하지_않는다() {
        runner.remote = "aaaaaaa1";
        service.sync("api", false);
        runner.commands.clear();

        assertThat(service.sync("api", false)).isEqualTo(SyncResult.UNCHANGED);
        assertThat(runner.commands).noneMatch(c -> c.contains("compose"));
    }

    @Test
    void force_면_커밋이_그대로여도_다시_빌드한다() {
        runner.remote = "aaaaaaa1";
        service.sync("api", false);

        assertThat(service.sync("api", true)).isEqualTo(SyncResult.DEPLOYED);
    }

    @Test
    void 새_커밋_빌드가_실패하면_이전_커밋으로_되돌리고_같은_커밋은_재시도하지_않는다() {
        runner.remote = "aaaaaaa1";
        service.sync("api", false);

        runner.remote = "bbbbbbb2";
        runner.composeResults.add(new CommandResult(1, "build failed"));
        assertThat(service.sync("api", false)).isEqualTo(SyncResult.ROLLED_BACK);

        ServiceState state = service.state("api").orElseThrow();
        assertThat(state.status()).isEqualTo(DeployStatus.ROLLED_BACK);
        assertThat(state.deployedCommit().sha()).isEqualTo("aaaaaaa1");
        assertThat(state.failedCommit().sha()).isEqualTo("bbbbbbb2");
        assertThat(state.lastOutput()).contains("build failed");
        assertThat(runner.head).isEqualTo("aaaaaaa1");

        runner.commands.clear();
        assertThat(service.sync("api", false)).isEqualTo(SyncResult.UNCHANGED);
        assertThat(runner.commands).noneMatch(c -> c.contains("compose"));
        // 건너뛴 확인은 리스너에 알리지 않는다
        assertThat(events).containsExactly("PENDING->RUNNING", "RUNNING->ROLLED_BACK");

        // 고친 커밋이 올라오면 다시 배포한다
        runner.remote = "ccccccc3";
        assertThat(service.sync("api", false)).isEqualTo(SyncResult.DEPLOYED);
        assertThat(service.state("api").orElseThrow().deployedCommit().sha()).isEqualTo("ccccccc3");
    }

    @Test
    void 처음_배포가_실패하면_FAILED() {
        runner.remote = "aaaaaaa1";
        runner.composeResults.add(new CommandResult(1, "no Dockerfile"));

        assertThat(service.sync("api", false)).isEqualTo(SyncResult.FAILED);
        assertThat(service.state("api").orElseThrow().status()).isEqualTo(DeployStatus.FAILED);
    }

    @Test
    void git_명령이_실패하면_FAILED_로_남긴다() {
        runner.cloneFails = true;

        assertThat(service.sync("api", false)).isEqualTo(SyncResult.FAILED);
        ServiceState state = service.state("api").orElseThrow();
        assertThat(state.status()).isEqualTo(DeployStatus.FAILED);
        assertThat(state.lastOutput()).contains("Authentication failed");
    }

    @Test
    void 등록되지_않은_서비스는_UNKNOWN() {
        assertThat(service.sync("nope", false)).isEqualTo(SyncResult.UNKNOWN);
    }

    /** git 저장소 하나와 docker compose 를 흉내 낸다. */
    static class FakeRunner implements CommandRunner {

        final Path repoDir;
        final List<String> commands = new ArrayList<>();
        final Deque<CommandResult> composeResults = new ArrayDeque<>();
        String remote;
        String head;
        boolean cloneFails;

        FakeRunner(Path repoDir) {
            this.repoDir = repoDir;
        }

        @Override
        public CommandResult run(Path workDir, List<String> command) {
            String cmd = String.join(" ", command);
            commands.add(cmd);
            if (cmd.startsWith("git clone")) {
                if (cloneFails) {
                    return new CommandResult(128, "fatal: Authentication failed");
                }
                try {
                    Files.createDirectories(repoDir.resolve(".git"));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                head = remote;
                return ok("");
            }
            if (cmd.startsWith("git fetch")) {
                return ok("");
            }
            if (cmd.equals("git rev-parse HEAD")) {
                return ok(head);
            }
            if (cmd.startsWith("git rev-parse origin/")) {
                return ok(remote);
            }
            if (cmd.startsWith("git reset --hard ")) {
                head = command.get(3);
                return ok("");
            }
            if (cmd.startsWith("git log")) {
                return ok(head + "\ntester\n2026-10-30T00:00:00Z\ncommit " + head);
            }
            if (cmd.startsWith("docker compose")) {
                return composeResults.isEmpty() ? ok("Container api Started") : composeResults.poll();
            }
            throw new IllegalArgumentException("예상하지 못한 명령: " + cmd);
        }

        private static CommandResult ok(String out) {
            return new CommandResult(0, out);
        }
    }
}
