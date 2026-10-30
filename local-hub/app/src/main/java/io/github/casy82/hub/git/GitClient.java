package io.github.casy82.hub.git;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.springframework.stereotype.Component;

import io.github.casy82.hub.process.CommandRunner;

/** 호스트에 설치된 git CLI 를 그대로 쓴다. 자격 증명(SSH 키, credential helper)도 호스트 설정을 따른다. */
@Component
public class GitClient {

    private final CommandRunner runner;

    public GitClient(CommandRunner runner) {
        this.runner = runner;
    }

    public boolean isCloned(Path dir) {
        return Files.isDirectory(dir.resolve(".git"));
    }

    public void cloneRepo(String repo, String branch, Path dir) {
        runner.run(dir.getParent(), List.of("git", "clone", "--branch", branch, "--single-branch", repo, dir.toString()))
                .orThrow("git clone " + repo);
    }

    public void fetch(Path dir, String branch) {
        runner.run(dir, List.of("git", "fetch", "--prune", "origin", branch)).orThrow("git fetch");
    }

    public String head(Path dir) {
        return runner.run(dir, List.of("git", "rev-parse", "HEAD")).orThrow("git rev-parse HEAD");
    }

    public String remoteHead(Path dir, String branch) {
        return runner.run(dir, List.of("git", "rev-parse", "origin/" + branch)).orThrow("git rev-parse origin/" + branch);
    }

    /** 로컬 변경은 버리고 지정한 커밋으로 맞춘다. workspace 는 배포 전용이라 직접 수정하지 않는다는 전제. */
    public void resetHard(Path dir, String ref) {
        runner.run(dir, List.of("git", "reset", "--hard", ref)).orThrow("git reset --hard " + ref);
    }

    public CommitInfo describe(Path dir, String ref) {
        String out = runner.run(dir, List.of("git", "log", "-1", "--format=%H%n%an%n%cI%n%s", ref)).orThrow("git log");
        String[] lines = out.split("\n", 4);
        return new CommitInfo(lines[0], lines.length > 1 ? lines[1] : "", lines.length > 2 ? lines[2] : "",
                lines.length > 3 ? lines[3] : "");
    }

    public record CommitInfo(String sha, String author, String date, String subject) {

        public String shortSha() {
            return sha.length() > 7 ? sha.substring(0, 7) : sha;
        }
    }
}
