package io.github.casy82.hub.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.casy82.hub.config.HubProperties;
import io.github.casy82.hub.process.CommandFailedException;
import io.github.casy82.hub.process.CommandRunner;
import io.github.casy82.hub.process.ProcessCommandRunner;

/** 실제 git 으로 고정할 버전 후보(커밋, 태그)를 읽는지 확인한다 */
class GitClientTest {

    @TempDir
    Path dir;

    @Test
    void 가벼운_태그와_주석_태그_모두_커밋으로_바꾼다() throws Exception {
        CommandRunner runner = new ProcessCommandRunner(new HubProperties(dir.toString(), "data", List.of(), "t", "t",
                List.of(), Duration.ofSeconds(1), Duration.ofSeconds(30), "",
                new HubProperties.Issues(false, "", "", List.of(), true)));
        GitClient git = new GitClient(runner);
        run(runner, "git", "init", "-q", "-b", "main");
        Files.writeString(dir.resolve("a"), "1");
        run(runner, "git", "add", "-A");
        run(runner, "git", "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qm", "first");
        String first = git.head(dir);
        run(runner, "git", "tag", "light");
        run(runner, "git", "-c", "user.name=t", "-c", "user.email=t@t", "tag", "-a", "v1", "-m", "release");

        assertThat(git.tags(dir)).extracting(GitClient.Tag::sha).containsOnly(first);
        assertThat(git.tags(dir)).extracting(GitClient.Tag::name).containsExactlyInAnyOrder("light", "v1");
        assertThat(git.resolve(dir, "v1")).isEqualTo(first);
        assertThat(git.recentCommits(dir, "HEAD", 5)).singleElement()
                .satisfies(c -> assertThat(c.subject()).isEqualTo("first"));
        assertThatThrownBy(() -> git.resolve(dir, "nope")).isInstanceOf(CommandFailedException.class);
    }

    private void run(CommandRunner runner, String... cmd) {
        runner.run(dir, List.of(cmd)).orThrow(String.join(" ", cmd));
    }
}
