package io.github.casy82.hub.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.casy82.hub.config.HubProperties;
import io.github.casy82.hub.git.GitClient.CommitInfo;
import io.github.casy82.hub.project.ProjectConfig;
import io.github.casy82.hub.project.ProjectStore;
import io.github.casy82.hub.project.ServiceConfig;

class DeployHistoryTest {

    @TempDir
    Path base;

    @Test
    void 배포_결과를_최신순으로_돌려준다() {
        HubProperties props = new HubProperties(base.toString(), "data", List.of("docker-compose.yml"), "test", "test",
                List.of(), Duration.ofSeconds(60), Duration.ofMinutes(1), "",
                new HubProperties.Issues(false, "", "", List.of("local-hub"), true));
        DeployHistory history = new DeployHistory(new ProjectStore(props, null, null));
        ServiceConfig api = new ServiceConfig("api", "r", "main", null, null);
        ServiceConfig web = new ServiceConfig("web", "r", "main", null, null);
        ProjectConfig shop = new ProjectConfig("shop", "쇼핑몰", "", List.of(api, web), Map.of());
        CommitInfo a = new CommitInfo("aaaaaaa1", "kim", "2026-10-30", "탭\t포함\n메시지");
        CommitInfo b = new CommitInfo("bbbbbbb2", "lee", "2026-10-30", "깨진 커밋");
        ServiceState pending = ServiceState.initial("api", "r", "main");

        history.onSynced(shop, api, pending, state(DeployStatus.RUNNING, a, null));
        history.onSynced(shop, web, pending, state(DeployStatus.RUNNING, a, null));
        history.onSynced(shop, api, pending, state(DeployStatus.ROLLED_BACK, a, b));
        history.onSynced(shop, api, pending, state(DeployStatus.DEPLOYING, a, null));

        List<DeployHistory.Entry> all = history.read("shop", null, 10);
        assertThat(all).extracting(DeployHistory.Entry::status).containsExactly("ROLLED_BACK", "RUNNING", "RUNNING");
        assertThat(all.getFirst().sha()).isEqualTo("bbbbbbb2");
        assertThat(history.read("shop", "api", 10)).hasSize(2);
        assertThat(history.read("shop", "api", 1)).hasSize(1);
        assertThat(history.read("shop", "web", 10).getFirst().subject()).isEqualTo("탭 포함 메시지");
    }

    static ServiceState state(DeployStatus status, CommitInfo deployed, CommitInfo failed) {
        return new ServiceState("api", "r", "main", status, deployed, failed, Instant.now(), Instant.now(), "msg", "");
    }
}
