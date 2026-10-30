package io.github.casy82.hub.deploy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import io.github.casy82.hub.git.GitClient.CommitInfo;
import io.github.casy82.hub.project.ProjectConfig;
import io.github.casy82.hub.project.ProjectStore;
import io.github.casy82.hub.project.ServiceConfig;

/**
 * 프로젝트별 배포 이력. data/projects/{id}/history/deploys.tsv 에 한 줄씩 덧붙인다.
 * 성공한 배포 기록은 "이 버전으로 고정" 의 후보가 된다.
 */
@Component
public class DeployHistory implements DeployListener {

    private final ProjectStore store;

    public DeployHistory(ProjectStore store) {
        this.store = store;
    }

    /**
     * @param status RUNNING(배포 성공), ROLLED_BACK, FAILED
     * @param sha    배포했거나 실패한 커밋 (git 명령 자체가 실패했으면 비어 있음)
     */
    public record Entry(Instant time, String service, DeployStatus status, String sha, String subject, String author,
            String message) {
    }

    @Override
    public void onSynced(ProjectConfig project, ServiceConfig service, ServiceState before, ServiceState after) {
        if (!isResult(after.status())) {
            return;
        }
        CommitInfo commit = after.status() == DeployStatus.RUNNING ? after.deployedCommit() : after.failedCommit();
        append(project.id(), new Entry(Instant.now(), service.name(), after.status(),
                commit == null ? "" : commit.sha(), commit == null ? "" : commit.subject(),
                commit == null ? "" : commit.author(), after.message()));
    }

    private static boolean isResult(DeployStatus status) {
        return status == DeployStatus.RUNNING || status == DeployStatus.ROLLED_BACK || status == DeployStatus.FAILED;
    }

    synchronized void append(String projectId, Entry e) {
        Path file = file(projectId);
        String line = String.join("\t", e.time().toString(), clean(e.service()), e.status().name(), clean(e.sha()),
                clean(e.subject()), clean(e.author()), clean(e.message())) + "\n";
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** 최신순. service 가 null 이면 프로젝트 전체 */
    public List<Entry> read(String projectId, String service, int limit) {
        Path file = file(projectId);
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        List<Entry> out = new ArrayList<>();
        for (int i = lines.size() - 1; i >= 0 && out.size() < limit; i--) {
            String[] f = lines.get(i).split("\t", -1);
            if (f.length < 7 || (service != null && !service.equals(f[1]))) {
                continue;
            }
            out.add(new Entry(Instant.parse(f[0]), f[1], DeployStatus.valueOf(f[2]), f[3], f[4], f[5], f[6]));
        }
        return out;
    }

    private Path file(String projectId) {
        return store.dir(projectId).resolve("history").resolve("deploys.tsv");
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }
}
