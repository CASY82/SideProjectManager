package io.github.casy82.hub.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.casy82.hub.compose.ComposeClient;
import io.github.casy82.hub.config.HubProperties;
import io.github.casy82.hub.deploy.DeployService.SyncResult;
import io.github.casy82.hub.git.GitClient;
import io.github.casy82.hub.process.CommandResult;
import io.github.casy82.hub.process.CommandRunner;
import io.github.casy82.hub.project.ProjectRegistry;
import io.github.casy82.hub.project.ProjectStore;

class DeployServiceTest {

    @TempDir
    Path base;

    FakeRunner runner;
    ProjectRegistry registry;
    DeployService service;
    List<String> events = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        HubProperties props = new HubProperties(base.toString(), "data", List.of("docker-compose.yml"), "test", "test",
                List.of(), Duration.ofSeconds(60), Duration.ofMinutes(1), "",
                new HubProperties.Issues(false, "", "", List.of("local-hub"), true));
        Path project = base.resolve("data/projects/shop");
        Files.createDirectories(project);
        Files.writeString(project.resolve("project.yml"), """
                name: 쇼핑몰
                services:
                  - name: api
                    repo: https://example.com/api.git
                """);
        Files.writeString(project.resolve("compose.yml"), "services:\n  api:\n    build: ./workspace/api\n");
        runner = new FakeRunner(project.resolve("workspace/api"));
        ComposeClient compose = new ComposeClient(runner, props);
        ProjectStore store = new ProjectStore(props, runner, compose);
        registry = new ProjectRegistry(store);
        service = new DeployService(registry, store, new GitClient(runner), compose,
                List.of((p, svc, before, after) -> events.add(before.status() + "->" + after.status())));
    }

    ServiceState state() {
        return registry.get("shop").orElseThrow().states().get("api");
    }

    SyncResult sync(boolean force) {
        return service.sync("shop", "api", force);
    }

    @Test
    void 처음에는_clone_후_배포한다() {
        runner.remote = "aaaaaaa1";

        assertThat(sync(false)).isEqualTo(SyncResult.DEPLOYED);

        ServiceState state = state();
        assertThat(state.status()).isEqualTo(DeployStatus.RUNNING);
        assertThat(state.deployedCommit().sha()).isEqualTo("aaaaaaa1");
        assertThat(runner.commands).anyMatch(c -> c.startsWith("git clone"));
        assertThat(runner.commands).anyMatch(c -> c.contains("up -d --build --no-deps --wait api"));
    }

    @Test
    void 커밋이_그대로면_다시_빌드하지_않는다() {
        runner.remote = "aaaaaaa1";
        sync(false);
        runner.commands.clear();

        assertThat(sync(false)).isEqualTo(SyncResult.UNCHANGED);
        assertThat(runner.commands).noneMatch(c -> c.contains("compose"));
    }

    @Test
    void force_면_커밋이_그대로여도_다시_빌드한다() {
        runner.remote = "aaaaaaa1";
        sync(false);

        assertThat(sync(true)).isEqualTo(SyncResult.DEPLOYED);
    }

    @Test
    void 새_커밋_빌드가_실패하면_이전_커밋으로_되돌리고_같은_커밋은_재시도하지_않는다() {
        runner.remote = "aaaaaaa1";
        sync(false);

        runner.remote = "bbbbbbb2";
        runner.composeResults.add(new CommandResult(1, "build failed"));
        assertThat(sync(false)).isEqualTo(SyncResult.ROLLED_BACK);

        ServiceState state = state();
        assertThat(state.status()).isEqualTo(DeployStatus.ROLLED_BACK);
        assertThat(state.deployedCommit().sha()).isEqualTo("aaaaaaa1");
        assertThat(state.failedCommit().sha()).isEqualTo("bbbbbbb2");
        assertThat(state.lastOutput()).contains("build failed");
        assertThat(runner.head).isEqualTo("aaaaaaa1");

        runner.commands.clear();
        assertThat(sync(false)).isEqualTo(SyncResult.UNCHANGED);
        assertThat(runner.commands).noneMatch(c -> c.contains("compose"));
        // 건너뛴 확인은 리스너에 알리지 않는다
        assertThat(events).containsExactly("PENDING->RUNNING", "RUNNING->ROLLED_BACK");

        // 고친 커밋이 올라오면 다시 배포한다
        runner.remote = "ccccccc3";
        assertThat(sync(false)).isEqualTo(SyncResult.DEPLOYED);
        assertThat(state().deployedCommit().sha()).isEqualTo("ccccccc3");
    }

    @Test
    void 처음_배포가_실패하면_FAILED() {
        runner.remote = "aaaaaaa1";
        runner.composeResults.add(new CommandResult(1, "no Dockerfile"));

        assertThat(sync(false)).isEqualTo(SyncResult.FAILED);
        assertThat(state().status()).isEqualTo(DeployStatus.FAILED);
    }

    @Test
    void git_명령이_실패하면_FAILED_로_남긴다() {
        runner.cloneFails = true;

        assertThat(sync(false)).isEqualTo(SyncResult.FAILED);
        ServiceState state = state();
        assertThat(state.status()).isEqualTo(DeployStatus.FAILED);
        assertThat(state.lastOutput()).contains("Authentication failed");
    }

    @Test
    void 등록되지_않은_서비스는_UNKNOWN() {
        assertThat(service.sync("shop", "nope", false)).isEqualTo(SyncResult.UNKNOWN);
    }

    @Test
    void 고정된_태그가_있으면_브랜치_최신_대신_그_커밋을_배포한다() throws Exception {
        runner.remote = "bbbbbbb2";
        runner.tags.put("v1.0", "aaaaaaa1");
        Files.writeString(base.resolve("data/projects/shop/pins.yml"), "api: v1.0\n");
        registry.reload("shop");

        assertThat(sync(false)).isEqualTo(SyncResult.DEPLOYED);
        assertThat(state().deployedCommit().sha()).isEqualTo("aaaaaaa1");

        // 고정이 있는 동안에는 브랜치에 새 커밋이 올라와도 그대로 둔다
        runner.remote = "ccccccc3";
        assertThat(sync(false)).isEqualTo(SyncResult.UNCHANGED);

        // 고정을 풀면 브랜치 최신을 따라간다
        Files.delete(base.resolve("data/projects/shop/pins.yml"));
        registry.reload("shop");
        assertThat(sync(false)).isEqualTo(SyncResult.DEPLOYED);
        assertThat(state().deployedCommit().sha()).isEqualTo("ccccccc3");
    }

    @Test
    void 고정한_ref_를_찾을_수_없으면_FAILED() throws Exception {
        runner.remote = "aaaaaaa1";
        sync(false);
        Files.writeString(base.resolve("data/projects/shop/pins.yml"), "api: v9.9\n");
        registry.reload("shop");

        assertThat(sync(false)).isEqualTo(SyncResult.FAILED);
        assertThat(state().message()).contains("v9.9");
    }

    @Test
    void 설정을_다시_읽어도_저장소가_같으면_배포_상태를_이어받는다() {
        runner.remote = "aaaaaaa1";
        sync(false);

        registry.reload("shop");

        assertThat(state().status()).isEqualTo(DeployStatus.RUNNING);
    }

    @Test
    void compose_yml_에_서비스_정의가_없으면_실패로_남기지_않고_기다린다() throws Exception {
        runner.remote = "aaaaaaa1";
        Path compose = base.resolve("data/projects/shop/compose.yml");
        Files.writeString(compose, "services:\n  placeholder:\n    image: busybox\n");

        assertThat(sync(false)).isEqualTo(SyncResult.WAITING);
        assertThat(state().status()).isEqualTo(DeployStatus.PENDING);
        assertThat(state().message()).contains("compose.yml").contains("api");
        assertThat(runner.commands).isEmpty();
        assertThat(events).isEmpty();

        // 정의를 추가하면 배포한다
        Files.writeString(compose, "services:\n  api:\n    build: ./workspace/api\n");
        assertThat(sync(false)).isEqualTo(SyncResult.DEPLOYED);
        assertThat(events).containsExactly("PENDING->RUNNING");
    }

    /** git 저장소 하나와 docker compose 를 흉내 낸다. */
    static class FakeRunner implements CommandRunner {

        final Path repoDir;
        final List<String> commands = new ArrayList<>();
        final Deque<CommandResult> composeResults = new ArrayDeque<>();
        final Map<String, String> tags = new HashMap<>();
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
            if (cmd.startsWith("git rev-parse --verify --quiet ")) {
                String ref = command.get(4).replace("^{commit}", "");
                String sha = ref.startsWith("origin/") ? remote : tags.get(ref);
                return sha == null ? new CommandResult(1, "") : ok(sha);
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
