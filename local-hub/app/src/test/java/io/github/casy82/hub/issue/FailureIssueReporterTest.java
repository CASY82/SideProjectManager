package io.github.casy82.hub.issue;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.github.casy82.hub.config.HubProperties;
import io.github.casy82.hub.deploy.DeployStatus;
import io.github.casy82.hub.deploy.ServiceState;
import io.github.casy82.hub.git.GitClient.CommitInfo;
import io.github.casy82.hub.project.ProjectConfig;
import io.github.casy82.hub.project.ServiceConfig;

class FailureIssueReporterTest {

    static final HubProperties.Issues CONFIG =
            new HubProperties.Issues(true, "t", "https://api.github.com", List.of("local-hub", "type: bug"), true);
    static final ServiceConfig API =
            new ServiceConfig("api", "git@github.com:team/api.git", "main", null, null);
    static final ProjectConfig SHOP = new ProjectConfig("shop", "쇼핑몰", "", List.of(API), java.util.Map.of());
    static final CommitInfo GOOD = new CommitInfo("aaaaaaa1111", "kim", "2026-10-30", "로그인 추가");
    static final CommitInfo BAD = new CommitInfo("bbbbbbb2222", "lee", "2026-10-30", "설정 | 변경");

    FakeTracker tracker = new FakeTracker();
    FailureIssueReporter reporter = new FailureIssueReporter(CONFIG, tracker, Runnable::run);

    @Test
    void 롤백되면_이슈를_만들고_커밋_작성자를_부른다() {
        tracker.logins.put(BAD.sha(), "lee-gh");

        reporter.onSynced(SHOP, API, running(GOOD), rolledBack());

        assertThat(tracker.issues).hasSize(1);
        FakeTracker.Stored issue = tracker.issues.getFirst();
        assertThat(issue.repo).isEqualTo("team/api");
        assertThat(issue.title).isEqualTo("[Local Hub] shop/api 배포 실패");
        assertThat(issue.labels).containsExactly("local-hub", "type: bug");
        assertThat(issue.body)
                .contains("이전 버전으로 되돌렸어요")
                .contains("`bbbbbbb` 설정 \\| 변경 (lee)")
                .contains("@lee-gh 확인 부탁드려요")
                .contains("<details>")
                .contains("RUN exit 1")
                .doesNotContain("되돌린 뒤");
    }

    @Test
    void 같은_실패는_다시_올리지_않고_새_실패는_같은_이슈에_댓글로_단다() {
        reporter.onSynced(SHOP, API, running(GOOD), rolledBack());
        reporter.onSynced(SHOP, API, rolledBack(), rolledBack());
        assertThat(tracker.issues).hasSize(1);
        assertThat(tracker.comments).isEmpty();

        CommitInfo worse = new CommitInfo("ccccccc3333", "lee", "2026-10-30", "또 실패");
        reporter.onSynced(SHOP, API, rolledBack(), state(DeployStatus.ROLLED_BACK, GOOD, worse));
        assertThat(tracker.issues).hasSize(1);
        assertThat(tracker.comments).singleElement().satisfies(c -> assertThat(c).contains("ccccccc"));
    }

    @Test
    void 다시_성공하면_댓글을_남기고_닫는다() {
        reporter.onSynced(SHOP, API, running(GOOD), rolledBack());
        CommitInfo fixed = new CommitInfo("ddddddd4444", "lee", "2026-10-30", "설정 되돌림");

        reporter.onSynced(SHOP, API, rolledBack(), running(fixed));

        assertThat(tracker.issues.getFirst().closed).isTrue();
        assertThat(tracker.comments).singleElement().satisfies(c -> assertThat(c).contains("ddddddd").contains("정상"));

        // 닫은 뒤 같은 커밋이 또 실패하면 새로 연다
        reporter.onSynced(SHOP, API, running(fixed), rolledBack());
        assertThat(tracker.issues).hasSize(2);
    }

    @Test
    void 정상_배포가_이어질_때는_GitHub_을_부르지_않는다() {
        reporter.onSynced(SHOP, API, running(GOOD), running(GOOD));
        assertThat(tracker.calls).isZero();
    }

    @Test
    void GitHub_저장소를_알_수_없으면_건너뛴다() {
        ServiceConfig local = new ServiceConfig("api", "file:///repos/api.git", "main", null, null);
        reporter.onSynced(SHOP, local, running(GOOD), rolledBack());
        assertThat(tracker.calls).isZero();
    }

    @Test
    void GitHub_호출이_실패하면_다음에_다시_시도한다() {
        tracker.failNext = true;
        reporter.onSynced(SHOP, API, running(GOOD), rolledBack());
        assertThat(tracker.issues).isEmpty();

        reporter.onSynced(SHOP, API, rolledBack(), rolledBack());
        assertThat(tracker.issues).hasSize(1);
    }

    @Test
    void 출력을_넣지_않도록_설정할_수_있다() {
        var noOutput = new HubProperties.Issues(true, "t", "", List.of("local-hub"), false);
        new FailureIssueReporter(noOutput, tracker, Runnable::run).onSynced(SHOP, API, running(GOOD), rolledBack());
        assertThat(tracker.issues.getFirst().body).doesNotContain("<details>");
    }

    @Test
    void 저장소_주소에서_owner_repo_를_알아낸다() {
        assertThat(svc("https://github.com/team/api.git").issueRepoOrDerived()).contains("team/api");
        assertThat(svc("https://github.com/team/web").issueRepoOrDerived()).contains("team/web");
        assertThat(svc("git@github.com:team/my.app.git").issueRepoOrDerived()).contains("team/my.app");
        assertThat(svc("https://gitlab.com/team/api.git").issueRepoOrDerived()).isEmpty();
        assertThat(new ServiceConfig("api", "file:///x", "main", null, "team/other").issueRepoOrDerived())
                .contains("team/other");
    }

    static ServiceConfig svc(String repo) {
        return new ServiceConfig("api", repo, "main", null, null);
    }

    static ServiceState running(CommitInfo commit) {
        return state(DeployStatus.RUNNING, commit, null);
    }

    static ServiceState rolledBack() {
        return state(DeployStatus.ROLLED_BACK, GOOD, BAD);
    }

    static ServiceState state(DeployStatus status, CommitInfo deployed, CommitInfo failed) {
        return new ServiceState("api", "repo", "main", status, deployed, failed, Instant.now(), Instant.now(),
                failed == null ? "ok" : failed.sha().substring(0, 7) + " 배포 실패", "#5 RUN exit 1\nfailed to solve\n----- aaaaaaa 로 되돌린 뒤 -----\nStarted");
    }

    static class FakeTracker implements IssueTracker {

        static class Stored {
            String repo, title, body;
            List<String> labels;
            int number;
            boolean closed;
        }

        final List<Stored> issues = new ArrayList<>();
        final List<String> comments = new ArrayList<>();
        final java.util.Map<String, String> logins = new java.util.HashMap<>();
        int calls;
        boolean failNext;

        private void call() {
            calls++;
            if (failNext) {
                failNext = false;
                throw new IllegalStateException("GitHub 503");
            }
        }

        @Override
        public Optional<Issue> findOpen(String repo, String label, String title) {
            call();
            return issues.stream()
                    .filter(i -> !i.closed && i.repo.equals(repo) && i.title.equals(title) && i.labels.contains(label))
                    .findFirst()
                    .map(i -> new Issue(i.number, "url/" + i.number));
        }

        @Override
        public Issue create(String repo, String title, String body, List<String> labels) {
            call();
            Stored s = new Stored();
            s.repo = repo;
            s.title = title;
            s.body = body;
            s.labels = labels;
            s.number = issues.size() + 1;
            issues.add(s);
            return new Issue(s.number, "url/" + s.number);
        }

        @Override
        public void comment(String repo, int number, String body) {
            call();
            comments.add(body);
        }

        @Override
        public void close(String repo, int number) {
            call();
            issues.get(number - 1).closed = true;
        }

        @Override
        public Optional<String> commitAuthorLogin(String repo, String sha) {
            call();
            return Optional.ofNullable(logins.get(sha));
        }
    }
}
