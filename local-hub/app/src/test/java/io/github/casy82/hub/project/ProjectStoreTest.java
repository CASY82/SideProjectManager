package io.github.casy82.hub.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.casy82.hub.compose.ComposeClient;
import io.github.casy82.hub.config.HubProperties;
import io.github.casy82.hub.process.CommandResult;
import io.github.casy82.hub.process.CommandRunner;
import io.github.casy82.hub.process.ProcessCommandRunner;

/** 실제 git 으로 설정 버전 기록·비교·되돌리기를 확인한다. docker 검사는 통과로 흉내 낸다 */
class ProjectStoreTest {

    @TempDir
    Path base;

    ProjectStore store;

    static final String WITH_API = """
            name: 쇼핑몰
            services:
              - name: api
                repo: https://github.com/team/api.git
            """;

    @BeforeEach
    void setUp() {
        HubProperties props = new HubProperties(base.toString(), "data", List.of("docker-compose.yml"), "test", "test",
                List.of(), Duration.ofSeconds(60), Duration.ofMinutes(1), "",
                new HubProperties.Issues(false, "", "", List.of("local-hub"), true), List.of());
        CommandRunner real = new ProcessCommandRunner(props);
        CommandRunner runner = (dir, cmd) -> cmd.getFirst().equals("docker") ? new CommandResult(0, "") : real.run(dir, cmd);
        store = new ProjectStore(props, runner, new ComposeClient(runner, props));
    }

    @Test
    void 프로젝트를_만들면_기본_파일과_첫_버전이_생긴다() {
        ProjectConfig config = store.create("shop", "쇼핑몰");

        assertThat(config.name()).isEqualTo("쇼핑몰");
        assertThat(config.services()).isEmpty();
        assertThat(store.ids()).containsExactly("shop");
        assertThat(store.readFile("shop", "compose.yml")).contains("external: true");
        assertThat(store.history("shop").versions(10)).singleElement()
                .satisfies(v -> assertThat(v.message()).isEqualTo("프로젝트 생성: 쇼핑몰"));
    }

    @Test
    void 파일을_저장할_때마다_버전이_남고_바뀐_게_없으면_남지_않는다() {
        store.create("shop", "쇼핑몰");

        assertThat(store.saveFile("shop", "project.yml", WITH_API, "api 추가")).isTrue();
        assertThat(store.saveFile("shop", "project.yml", WITH_API, "그대로")).isFalse();

        ConfigHistory history = store.history("shop");
        assertThat(history.versions(10)).extracting(ConfigHistory.Version::message)
                .containsExactly("api 추가", "프로젝트 생성: 쇼핑몰");
        String sha = history.versions(10).getFirst().sha();
        assertThat(history.diff(sha)).contains("+  - name: api");
        assertThat(store.load("shop").orElseThrow().services()).extracting(ServiceConfig::name).containsExactly("api");
    }

    @Test
    void 잘못된_설정은_저장하지_않는다() {
        store.create("shop", null);

        assertThatThrownBy(() -> store.saveFile("shop", "project.yml", "services:\n  - name: API\n", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("영문 소문자");
        assertThatThrownBy(() -> store.saveFile("shop", "pins.yml", "", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.create("../etc", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.create("shop", null)).hasMessageContaining("이미 있는");
        assertThat(store.history("shop").versions(10)).hasSize(1);
    }

    @Test
    void 예전_버전으로_되돌리면_새_버전으로_남고_고정도_함께_되돌아간다() {
        store.create("shop", "쇼핑몰");
        store.saveFile("shop", "project.yml", WITH_API, "api 추가");
        String beforePin = store.history("shop").versions(1).getFirst().sha();

        store.setPin("shop", "api", "v1.0", "api 를 v1.0 로 고정");
        assertThat(store.load("shop").orElseThrow().pins()).containsEntry("api", "v1.0");
        assertThat(store.history("shop").diffWithCurrent(beforePin)).contains("pins.yml").contains("v1.0");

        assertThat(store.history("shop").restore(beforePin, "되돌림")).isTrue();

        assertThat(store.load("shop").orElseThrow().pins()).isEmpty();
        assertThat(store.dir("shop").resolve("pins.yml")).doesNotExist();
        assertThat(store.history("shop").versions(10)).extracting(ConfigHistory.Version::message)
                .containsExactly("되돌림", "api 를 v1.0 로 고정", "api 추가", "프로젝트 생성: 쇼핑몰");
    }

    @Test
    void 작업_공간과_배포_이력은_버전에_넣지_않는다() throws Exception {
        store.create("shop", null);
        Files.createDirectories(store.workspace("shop").resolve("api"));
        Files.writeString(store.workspace("shop").resolve("api/README"), "x");

        assertThat(store.history("shop").commit("무시")).isFalse();
    }

    @Test
    void 옵션처럼_보이는_고정_ref_는_거절한다() {
        store.create("shop", null);
        store.saveFile("shop", "project.yml", WITH_API, "api 추가");
        assertThatThrownBy(() -> store.setPin("shop", "api", "--output=x", "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 명령_옵션으로_해석될_수_있는_서비스_설정은_거절한다() {
        store.create("shop", null);
        for (String svc : List.of("repo: --upload-pack=touch /tmp/x", "repo: https://x/a.git\n    branch: --foo",
                "repo: https://x/a.git\n    compose-service: --privileged", "repo: https://x/a.git\n    issue-repo: a/b/c")) {
            String yaml = "services:\n  - name: api\n    " + svc + "\n";
            assertThatThrownBy(() -> store.saveFile("shop", "project.yml", yaml, "x"))
                    .as(svc).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void 없는_버전_id_는_거절한다() {
        store.create("shop", null);
        assertThatThrownBy(() -> store.history("shop").diff("--output=/tmp/x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.history("shop").restore("deadbeef", "x"))
                .hasMessageContaining("없는 버전");
    }

    @Test
    void 삭제하면_목록에서_사라진다() {
        store.create("shop", null);
        store.delete("shop");
        assertThat(store.ids()).isEmpty();
    }
}
