package io.github.casy82.hub.project;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import io.github.casy82.hub.process.CommandResult;
import io.github.casy82.hub.process.CommandRunner;

/**
 * 프로젝트 디렉터리를 git 저장소로 두고 설정 파일이 바뀔 때마다 커밋한다 (형상관리).
 * workspace/ 와 history/ 는 .gitignore 로 빼서 설정 파일만 기록된다.
 */
public class ConfigHistory {

    static final List<String> TRACKED = List.of("project.yml", "compose.yml", "pins.yml");
    static final String MESSAGE_FILE = ".commit-msg";

    private final CommandRunner runner;
    private final Path dir;

    public ConfigHistory(CommandRunner runner, Path dir) {
        this.runner = runner;
        this.dir = dir;
    }

    public record Version(String sha, String date, String message) {

        public String shortSha() {
            return sha.substring(0, Math.min(7, sha.length()));
        }
    }

    public void init() {
        git("init", "-q", "-b", "main").orThrow("git init");
    }

    /** 바뀐 것이 있으면 커밋하고 true. 없으면 false */
    public boolean commit(String message) {
        git("add", "-A").orThrow("git add");
        if (git("diff", "--cached", "--quiet").ok()) {
            return false;
        }
        // 한글 메시지를 명령 인자로 넘기면 OS 로캘이 UTF-8 이 아닐 때 깨지므로 UTF-8 파일로 넘긴다
        Path file = dir.resolve(MESSAGE_FILE);
        try {
            Files.writeString(file, message, StandardCharsets.UTF_8);
            git("commit", "-q", "-F", MESSAGE_FILE).orThrow("git commit");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // 다음 커밋 때 덮어쓴다
            }
        }
        return true;
    }

    public List<Version> versions(int limit) {
        CommandResult r = git("log", "-n", String.valueOf(limit), "--format=%H%x1f%cI%x1f%s");
        if (!r.ok()) {
            return List.of();
        }
        List<Version> out = new ArrayList<>();
        for (String line : r.output().strip().split("\n")) {
            String[] f = line.split("\u001f", 3);
            if (f.length == 3) {
                out.add(new Version(f[0], f[1], f[2]));
            }
        }
        return out;
    }

    /** 그 버전에서 무엇이 바뀌었는지 (unified diff) */
    public String diff(String sha) {
        requireSha(sha);
        return git("show", "--format=", "--no-color", sha).orThrow("git show");
    }

    /** 지금 설정과 그 버전의 차이 */
    public String diffWithCurrent(String sha) {
        requireSha(sha);
        return git("diff", "--no-color", sha, "--", "project.yml", "compose.yml", "pins.yml").orThrow("git diff");
    }

    /** 설정 파일을 그 버전으로 되돌리고 새 커밋으로 남긴다. 이력은 지워지지 않는다 */
    public boolean restore(String sha, String message) {
        requireSha(sha);
        String files = git("ls-tree", "--name-only", sha).orThrow("git ls-tree");
        List<String> args = new ArrayList<>(List.of("checkout", sha, "--"));
        List<String> present = new ArrayList<>();
        for (String f : TRACKED) {
            if (files.lines().anyMatch(f::equals)) {
                args.add(f);
                present.add(f);
            }
        }
        git(args.toArray(String[]::new)).orThrow("git checkout");
        // 그 버전에 없던 파일(예: 나중에 생긴 pins.yml)은 지운다
        for (String f : TRACKED) {
            if (!present.contains(f)) {
                git("rm", "-q", "--ignore-unmatch", f);
            }
        }
        return commit(message);
    }

    private void requireSha(String sha) {
        if (sha == null || !sha.matches("[0-9a-f]{7,40}")) {
            throw new IllegalArgumentException("버전 id 형식이 아닙니다: " + sha);
        }
        if (!git("cat-file", "-e", sha + "^{commit}").ok()) {
            throw new IllegalArgumentException("없는 버전입니다: " + sha);
        }
    }

    private CommandResult git(String... args) {
        List<String> cmd = new ArrayList<>(List.of("git", "-c", "user.name=Local Hub", "-c", "user.email=hub@localhost",
                "-c", "core.quotepath=false",
                // 사용자 전역 설정에 서명이 켜져 있으면 커밋이 실패하므로 끈다
                "-c", "commit.gpgsign=false"));
        cmd.addAll(List.of(args));
        return runner.run(dir, cmd);
    }
}
