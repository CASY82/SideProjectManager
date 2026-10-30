package io.github.casy82.hub.issue;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.casy82.hub.config.HubProperties;
import io.github.casy82.hub.deploy.DeployListener;
import io.github.casy82.hub.deploy.DeployStatus;
import io.github.casy82.hub.deploy.ServiceState;
import io.github.casy82.hub.git.GitClient.CommitInfo;
import io.github.casy82.hub.project.ProjectConfig;
import io.github.casy82.hub.project.ServiceConfig;

/**
 * 배포가 실패하거나 롤백되면 서비스마다 이슈 하나를 열고, 또 실패하면 같은 이슈에 댓글을 단다.
 * 다음 배포가 성공하면 댓글을 남기고 닫는다. GitHub 호출은 배포 흐름을 막지 않도록 따로 돈다.
 */
public class FailureIssueReporter implements DeployListener {

    private static final Logger log = LoggerFactory.getLogger(FailureIssueReporter.class);
    private static final int OUTPUT_LINES = 80;
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final HubProperties.Issues config;
    private final IssueTracker tracker;
    private final Executor executor;
    /** 같은 실패를 매 주기마다 다시 올리지 않도록 서비스별로 마지막에 올린 실패를 기억한다 */
    private final Map<String, String> lastReported = new ConcurrentHashMap<>();

    public FailureIssueReporter(HubProperties.Issues config, IssueTracker tracker, Executor executor) {
        this.config = config;
        this.tracker = tracker;
        this.executor = executor;
    }

    static String titleFor(ProjectConfig project, ServiceConfig service) {
        return "[Local Hub] " + project.id() + "/" + service.name() + " 배포 실패";
    }

    @Override
    public void onSynced(ProjectConfig project, ServiceConfig service, ServiceState before, ServiceState after) {
        Optional<String> repo = service.issueRepoOrDerived();
        String key0 = project.id() + "/" + service.name();
        if (repo.isEmpty()) {
            log.debug("[{}] 이슈를 올릴 저장소를 알 수 없어 건너뜀 (issue-repo 설정 필요)", key0);
            return;
        }
        boolean failed = after.status() == DeployStatus.FAILED || after.status() == DeployStatus.ROLLED_BACK;
        if (failed) {
            String key = after.status() + "|" + (after.failedCommit() == null ? "" : after.failedCommit().sha())
                    + "|" + after.message();
            if (key.equals(lastReported.put(key0, key))) {
                return;
            }
            executor.execute(() -> safely(key0, () -> reportFailure(repo.get(), project, service, after)));
        } else if (after.status() == DeployStatus.RUNNING && before.status() != DeployStatus.RUNNING) {
            // 앱을 다시 켠 뒤 첫 성공(PENDING → RUNNING)도 확인해서, 꺼져 있는 동안 남은 이슈를 닫는다
            lastReported.remove(key0);
            executor.execute(() -> safely(key0, () -> reportRecovery(repo.get(), project, service, after)));
        }
    }

    private void reportFailure(String repo, ProjectConfig project, ServiceConfig service, ServiceState state) {
        String label = config.labels().getFirst();
        String body = failureBody(repo, project, service, state);
        Optional<IssueTracker.Issue> open = tracker.findOpen(repo, label, titleFor(project, service));
        if (open.isPresent()) {
            tracker.comment(repo, open.get().number(), body);
            log.info("[{}] 기존 이슈 #{} 에 실패 내용 추가", service.name(), open.get().number());
        } else {
            IssueTracker.Issue created = tracker.create(repo, titleFor(project, service), body, config.labels());
            log.info("[{}] 실패 이슈 생성 {}", service.name(), created.url());
        }
    }

    private void reportRecovery(String repo, ProjectConfig project, ServiceConfig service, ServiceState state) {
        Optional<IssueTracker.Issue> open =
                tracker.findOpen(repo, config.labels().getFirst(), titleFor(project, service));
        if (open.isEmpty()) {
            return;
        }
        CommitInfo c = state.deployedCommit();
        tracker.comment(repo, open.get().number(),
                "`" + c.shortSha() + "` " + c.subject() + " 배포가 성공해서 정상으로 돌아왔어요. 이슈를 닫습니다.");
        tracker.close(repo, open.get().number());
        log.info("[{}] 이슈 #{} 닫음", service.name(), open.get().number());
    }

    String failureBody(String repo, ProjectConfig project, ServiceConfig service, ServiceState state) {
        StringBuilder b = new StringBuilder();
        boolean rolledBack = state.status() == DeployStatus.ROLLED_BACK;
        b.append("Local Hub 의 ").append(escape(project.name())).append(" 프로젝트에서 `").append(service.name()).append("` 배포가 ")
                .append(rolledBack ? "실패해서 이전 버전으로 되돌렸어요." : "실패했어요.").append("\n\n");

        b.append("| 항목 | 내용 |\n| --- | --- |\n");
        b.append("| 상태 | ").append(rolledBack
                ? "`ROLLED_BACK` 이전 커밋 `" + state.deployedCommit().shortSha() + "` 로 서비스는 계속 동작 중"
                : "`FAILED` 서비스가 떠 있지 않을 수 있음").append(" |\n");
        b.append("| 브랜치 | `").append(service.branch()).append("` |\n");
        CommitInfo failed = state.failedCommit();
        if (failed != null) {
            b.append("| 실패한 커밋 | `").append(failed.shortSha()).append("` ")
                    .append(escape(failed.subject())).append(" (").append(escape(failed.author())).append(") |\n");
        }
        b.append("| 내용 | ").append(escape(state.message())).append(" |\n");
        b.append("| 시각 | ").append(state.lastCheckedAt() == null ? "-" : TIME.format(state.lastCheckedAt())).append(" |\n\n");

        if (failed != null) {
            tracker.commitAuthorLogin(repo, failed.sha())
                    .ifPresent(login -> b.append("@").append(login).append(" 확인 부탁드려요.\n\n"));
        }

        if (config.includeOutput() && state.lastOutput() != null && !state.lastOutput().isBlank()) {
            b.append("<details><summary>빌드·기동 출력 (마지막 ").append(OUTPUT_LINES).append("줄)</summary>\n\n```\n")
                    .append(lastLines(failureOutput(state.lastOutput())).replace("```", "'''"))
                    .append("\n```\n</details>\n\n");
        }
        b.append("다음 배포가 성공하면 이 이슈는 자동으로 닫힙니다.");
        return b.toString();
    }

    private void safely(String key, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            // GitHub 가 안 되면 다음 실패 때 다시 올리도록 기억을 지운다
            lastReported.remove(key);
            log.warn("[{}] GitHub 이슈 처리 실패: {}", key, e.getMessage());
        }
    }

    /** 롤백한 경우 출력 뒤쪽은 이전 버전을 다시 빌드한 내용이라, 실패 원인이 있는 앞부분만 쓴다 */
    private static String failureOutput(String output) {
        int marker = output.indexOf("\n----- ");
        return marker > 0 ? output.substring(0, marker) : output;
    }

    private static String lastLines(String output) {
        String[] lines = output.strip().split("\n");
        return lines.length <= OUTPUT_LINES ? output.strip()
                : String.join("\n", Arrays.copyOfRange(lines, lines.length - OUTPUT_LINES, lines.length));
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("|", "\\|").replace("\n", " ");
    }
}
