package io.github.casy82.hub.project;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 프로젝트 안의 서비스 하나 (project.yml 의 services 항목).
 *
 * @param name           대시보드와 API 에서 쓰는 이름. 프로젝트의 workspace/{name} 에 clone 된다
 * @param repo           git 저장소 주소 (https, ssh, file 모두 가능)
 * @param branch         따라갈 브랜치
 * @param composeService compose.yml 안의 서비스 이름. 비우면 name 과 같다
 * @param issueRepo      실패 이슈를 올릴 저장소 (owner/repo). 비우면 repo 가 GitHub 주소일 때 거기서 알아낸다
 */
public record ServiceConfig(String name, String repo, String branch, String composeService, String issueRepo) {

    private static final Pattern GITHUB_REPO =
            Pattern.compile("github\\.com[:/]([^/\\s]+)/([^/\\s]+?)(?:\\.git)?/?$");

    public ServiceConfig {
        branch = branch == null || branch.isBlank() ? "main" : branch.strip();
    }

    public String composeServiceOrName() {
        return composeService == null || composeService.isBlank() ? name : composeService;
    }

    /** 이슈를 올릴 owner/repo. 알 수 없으면 비어 있다 */
    public Optional<String> issueRepoOrDerived() {
        if (issueRepo != null && !issueRepo.isBlank()) {
            return Optional.of(issueRepo.strip());
        }
        if (repo == null) {
            return Optional.empty();
        }
        Matcher m = GITHUB_REPO.matcher(repo.strip());
        return m.find() ? Optional.of(m.group(1) + "/" + m.group(2)) : Optional.empty();
    }
}
