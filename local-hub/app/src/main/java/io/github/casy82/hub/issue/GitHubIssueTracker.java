package io.github.casy82.hub.issue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import io.github.casy82.hub.config.HubProperties;

/** GitHub REST API 구현. 응답은 필요한 필드만 Map 으로 꺼내 쓴다. */
public class GitHubIssueTracker implements IssueTracker {

    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST =
            new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<Map<String, Object>> OBJECT =
            new ParameterizedTypeReference<>() { };

    private final RestClient http;

    public GitHubIssueTracker(HubProperties.Issues config) {
        // 요청 본문을 버퍼링해 Content-Length 를 붙인다 (chunked 전송을 받지 않는 프록시·서버 대비)
        this(config, RestClient.builder()
                .requestFactory(new BufferingClientHttpRequestFactory(new JdkClientHttpRequestFactory())));
    }

    GitHubIssueTracker(HubProperties.Issues config, RestClient.Builder builder) {
        this.http = builder
                .baseUrl(config.apiUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + config.token())
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
    }

    @Override
    public Optional<Issue> findOpen(String repo, String label, String title) {
        List<Map<String, Object>> issues = http.get()
                .uri("/repos/{owner}/{name}/issues?state=open&labels={label}&per_page=100", vars(repo, "label", label))
                .retrieve()
                .body(LIST);
        if (issues == null) {
            return Optional.empty();
        }
        return issues.stream()
                .filter(i -> !i.containsKey("pull_request") && title.equals(i.get("title")))
                .findFirst()
                .map(GitHubIssueTracker::toIssue);
    }

    @Override
    public Issue create(String repo, String title, String body, List<String> labels) {
        Map<String, Object> created = http.post()
                .uri("/repos/{owner}/{name}/issues", vars(repo))
                .body(Map.of("title", title, "body", body, "labels", labels))
                .retrieve()
                .body(OBJECT);
        return toIssue(created);
    }

    @Override
    public void comment(String repo, int number, String body) {
        http.post()
                .uri("/repos/{owner}/{name}/issues/{number}/comments", vars(repo, "number", number))
                .body(Map.of("body", body))
                .retrieve()
                .toBodilessEntity();
    }

    @Override
    public void close(String repo, int number) {
        http.patch()
                .uri("/repos/{owner}/{name}/issues/{number}", vars(repo, "number", number))
                .body(Map.of("state", "closed", "state_reason", "completed"))
                .retrieve()
                .toBodilessEntity();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<String> commitAuthorLogin(String repo, String sha) {
        try {
            Map<String, Object> commit = http.get()
                    .uri("/repos/{owner}/{name}/commits/{sha}", vars(repo, "sha", sha))
                    .retrieve()
                    .body(OBJECT);
            Object author = commit == null ? null : commit.get("author");
            return author instanceof Map<?, ?> a && a.get("login") instanceof String login
                    ? Optional.of(login)
                    : Optional.empty();
        } catch (RestClientResponseException e) {
            // 아직 푸시되지 않았거나 다른 저장소의 커밋이면 422/404
            return Optional.empty();
        }
    }

    /** "owner/repo" 를 통째로 넣으면 '/' 가 %2F 로 인코딩되므로 둘로 나눠 넣는다 */
    private static Map<String, Object> vars(String repo, Object... extra) {
        int slash = repo.indexOf('/');
        if (slash <= 0 || slash == repo.length() - 1 || repo.indexOf('/', slash + 1) >= 0) {
            throw new IllegalArgumentException("저장소는 owner/repo 형식이어야 합니다: " + repo);
        }
        Map<String, Object> vars = new HashMap<>();
        vars.put("owner", repo.substring(0, slash));
        vars.put("name", repo.substring(slash + 1));
        for (int i = 0; i < extra.length; i += 2) {
            vars.put((String) extra[i], extra[i + 1]);
        }
        return vars;
    }

    private static Issue toIssue(Map<String, Object> json) {
        return new Issue(((Number) json.get("number")).intValue(), (String) json.get("html_url"));
    }
}
