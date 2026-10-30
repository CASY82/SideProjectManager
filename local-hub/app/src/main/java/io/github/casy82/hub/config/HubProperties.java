package io.github.casy82.hub.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * hub.yml 의 hub.* 설정.
 *
 * @param baseDir        docker compose 를 실행할 기준 디렉터리 (compose 파일과 workspace 가 있는 곳)
 * @param workspace      팀원 저장소를 clone 해 둘 디렉터리 (baseDir 기준 상대 경로 가능)
 * @param composeFiles   docker compose -f 로 넘길 파일 목록 (baseDir 기준)
 * @param projectName    docker compose -p 프로젝트 이름
 * @param infraProfiles  앱 시작 시 함께 띄울 공통 서비스 프로필 (postgres, redis 등)
 * @param pollInterval   git 변경 확인 주기
 * @param commandTimeout git/docker 명령 하나의 최대 실행 시간
 * @param apiToken       비어 있지 않으면 POST API 호출 시 X-Hub-Token 헤더로 요구
 * @param issues         배포 실패를 GitHub 이슈로 올리는 설정
 * @param services       감시할 서비스 목록
 */
@ConfigurationProperties(prefix = "hub")
public record HubProperties(
        @DefaultValue(".") String baseDir,
        @DefaultValue("workspace") String workspace,
        @DefaultValue("docker-compose.yml") List<String> composeFiles,
        @DefaultValue("sidehub") String projectName,
        @DefaultValue List<String> infraProfiles,
        @DefaultValue("60s") Duration pollInterval,
        @DefaultValue("15m") Duration commandTimeout,
        @DefaultValue("") String apiToken,
        @DefaultValue Issues issues,
        @DefaultValue List<Service> services) {

    /**
     * @param enabled       true 면 배포 실패·롤백 때 이슈를 만들고, 다음 배포가 성공하면 닫는다
     * @param token         이슈 쓰기 권한이 있는 GitHub 토큰
     * @param apiUrl        GitHub API 주소 (GitHub Enterprise 면 바꾼다)
     * @param labels        만든 이슈에 붙일 라벨. 첫 번째 라벨로 Local Hub 가 만든 이슈를 찾는다
     * @param includeOutput 빌드 출력 끝부분을 이슈 본문에 넣을지. 공개 저장소라면 출력에 비밀 값이 없는지 확인할 것
     */
    public record Issues(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("") String token,
            @DefaultValue("https://api.github.com") String apiUrl,
            @DefaultValue({"local-hub", "type: bug"}) List<String> labels,
            @DefaultValue("true") boolean includeOutput) {
    }

    /**
     * @param name           대시보드와 API 에서 쓰는 이름. workspace/{name} 에 clone 된다
     * @param repo           git 저장소 주소 (https, ssh, file 모두 가능)
     * @param branch         감시할 브랜치
     * @param composeService compose 파일 안의 서비스 이름. 비우면 name 과 같다
     * @param issueRepo      실패 이슈를 올릴 저장소 (owner/repo). 비우면 repo 가 GitHub 주소일 때 거기서 알아낸다
     */
    public record Service(
            String name,
            String repo,
            @DefaultValue("main") String branch,
            String composeService,
            String issueRepo) {

        private static final Pattern GITHUB_REPO =
                Pattern.compile("github\\.com[:/]([^/\\s]+)/([^/\\s]+?)(?:\\.git)?/?$");

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

    // Path 로 바인딩하면 Spring 이 "." 을 웹 리소스 경로(톰캣 임시 디렉터리)로 해석하므로 문자열로 받는다
    public Path resolvedBaseDir() {
        return Path.of(baseDir).toAbsolutePath().normalize();
    }

    public Path resolvedWorkspace() {
        return resolvedBaseDir().resolve(workspace).normalize();
    }
}
