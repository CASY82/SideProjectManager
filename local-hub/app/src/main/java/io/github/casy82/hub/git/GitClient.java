package io.github.casy82.hub.git;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import io.github.casy82.hub.process.CommandFailedException;
import io.github.casy82.hub.process.CommandResult;
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

    /** 브랜치와 태그를 가져온다. 태그는 "이 버전으로 고정" 에 쓴다 */
    public void fetch(Path dir, String branch) {
        runner.run(dir, List.of("git", "fetch", "--prune", "--tags", "--force", "origin",
                "+refs/heads/" + branch + ":refs/remotes/origin/" + branch)).orThrow("git fetch");
    }

    public String head(Path dir) {
        return runner.run(dir, List.of("git", "rev-parse", "HEAD")).orThrow("git rev-parse HEAD");
    }

    /** 브랜치(origin/main), 태그, 커밋 해시를 커밋 해시로 바꾼다 */
    public String resolve(Path dir, String ref) {
        CommandResult r = runner.run(dir, List.of("git", "rev-parse", "--verify", "--quiet", ref + "^{commit}"));
        if (!r.ok()) {
            throw new CommandFailedException("커밋·태그를 찾을 수 없음: " + ref, r.output());
        }
        return r.output().strip();
    }

    /** ref 에서 거슬러 올라간 최근 커밋들 (고정할 버전 고르기용) */
    public List<CommitInfo> recentCommits(Path dir, String ref, int limit) {
        CommandResult r = runner.run(dir, List.of("git", "log", "-n", String.valueOf(limit),
                "--format=%H%x1f%an%x1f%cI%x1f%s", ref));
        if (!r.ok()) {
            return List.of();
        }
        List<CommitInfo> out = new ArrayList<>();
        for (String line : r.output().strip().split("\n")) {
            String[] f = line.split("\u001f", 4);
            if (f.length == 4) {
                out.add(new CommitInfo(f[0], f[1], f[2], f[3]));
            }
        }
        return out;
    }

    /** 태그 이름과 가리키는 커밋, 최신순 */
    public List<Tag> tags(Path dir) {
        CommandResult r = runner.run(dir, List.of("git", "for-each-ref", "--sort=-creatordate",
                "--format=%(refname:short)%1f%(objectname)%1f%(*objectname)", "refs/tags"));
        if (!r.ok() || r.output().isBlank()) {
            return List.of();
        }
        List<Tag> out = new ArrayList<>();
        // strip() 은 구분자(\u001f)도 공백으로 보고 지우므로 줄 단위로만 나눈다
        for (String line : r.output().split("\n")) {
            String[] f = line.replace("\r", "").split("\u001f", -1);
            if (f.length == 3) {
                // 주석 달린 태그는 *objectname 이 커밋, 가벼운 태그는 objectname 이 커밋
                out.add(new Tag(f[0], f[2].isBlank() ? f[1] : f[2]));
            }
        }
        return out;
    }

    public record Tag(String name, String sha) {
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
