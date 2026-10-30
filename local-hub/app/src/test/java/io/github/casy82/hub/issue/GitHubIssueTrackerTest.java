package io.github.casy82.hub.issue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import io.github.casy82.hub.config.HubProperties;

class GitHubIssueTrackerTest {

    MockRestServiceServer server;
    GitHubIssueTracker tracker;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        tracker = new GitHubIssueTracker(
                new HubProperties.Issues(true, "secret", "https://api.github.com", List.of("local-hub"), true), builder);
    }

    @Test
    void 열린_이슈를_제목으로_찾는다_PR_은_뺀다() {
        server.expect(requestTo("https://api.github.com/repos/team/api/issues?state=open&labels=local-hub&per_page=100"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer secret"))
                .andRespond(withSuccess("""
                        [{"number": 3, "title": "[Local Hub] api 배포 실패", "html_url": "u3", "pull_request": {}},
                         {"number": 4, "title": "[Local Hub] api 배포 실패", "html_url": "u4"},
                         {"number": 5, "title": "다른 이슈", "html_url": "u5"}]
                        """, MediaType.APPLICATION_JSON));

        assertThat(tracker.findOpen("team/api", "local-hub", "[Local Hub] api 배포 실패"))
                .contains(new IssueTracker.Issue(4, "u4"));
        server.verify();
    }

    @Test
    void 이슈_생성_댓글_닫기() {
        server.expect(requestTo("https://api.github.com/repos/team/api/issues"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"title": "t", "body": "b", "labels": ["local-hub"]}"""))
                .andRespond(withSuccess("""
                        {"number": 7, "html_url": "https://github.com/team/api/issues/7"}""", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.com/repos/team/api/issues/7/comments"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"body": "c"}"""))
                .andRespond(withStatus(HttpStatus.CREATED));
        server.expect(requestTo("https://api.github.com/repos/team/api/issues/7"))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(content().json("""
                        {"state": "closed", "state_reason": "completed"}"""))
                .andRespond(withSuccess());

        IssueTracker.Issue issue = tracker.create("team/api", "t", "b", List.of("local-hub"));
        tracker.comment("team/api", issue.number(), "c");
        tracker.close("team/api", issue.number());

        assertThat(issue).isEqualTo(new IssueTracker.Issue(7, "https://github.com/team/api/issues/7"));
        server.verify();
    }

    @Test
    void 커밋_작성자_아이디_없거나_404_면_비어_있다() {
        server.expect(requestTo("https://api.github.com/repos/team/api/commits/abc"))
                .andRespond(withSuccess("""
                        {"author": {"login": "kim"}}""", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.com/repos/team/api/commits/def"))
                .andRespond(withSuccess("""
                        {"author": null}""", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.com/repos/team/api/commits/ghi"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY));

        assertThat(tracker.commitAuthorLogin("team/api", "abc")).contains("kim");
        assertThat(tracker.commitAuthorLogin("team/api", "def")).isEmpty();
        assertThat(tracker.commitAuthorLogin("team/api", "ghi")).isEmpty();
    }
}
