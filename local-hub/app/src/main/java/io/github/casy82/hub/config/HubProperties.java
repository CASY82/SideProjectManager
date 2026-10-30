package io.github.casy82.hub.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * hub.yml 의 hub.* 설정. 앱 전체에 걸리는 값만 둔다. 프로젝트와 서비스는 대시보드에서 관리하고
 * data-dir 아래에 프로젝트마다 저장된다.
 *
 * @param baseDir        기준 디렉터리 (공통 서비스 compose 파일과 data 가 있는 곳)
 * @param dataDir        프로젝트 설정·작업 공간·이력을 두는 디렉터리 (baseDir 기준 상대 경로 가능)
 * @param composeFiles   공통 서비스 compose 파일 목록 (baseDir 기준)
 * @param projectName    공통 서비스의 docker compose 프로젝트 이름. 각 프로젝트는 "{이 값}-{프로젝트 id}" 를 쓴다
 * @param network        공통 서비스와 모든 프로젝트가 함께 쓰는 docker 네트워크 이름
 * @param infraProfiles  앱 시작 시 함께 띄울 공통 서비스 프로필 (postgres, redis 등)
 * @param pollInterval   git 변경 확인 주기
 * @param commandTimeout git/docker 명령 하나의 최대 실행 시간
 * @param apiToken       비어 있지 않으면 변경 API(POST/PUT/DELETE) 호출 시 X-Hub-Token 헤더로 요구
 * @param issues         배포 실패를 GitHub 이슈로 올리는 설정
 * @param allowedHosts   localhost·IP 주소 말고 대시보드를 열 때 쓰는 호스트 이름 (예: hub-pc.local)
 */
@ConfigurationProperties(prefix = "hub")
public record HubProperties(
        @DefaultValue(".") String baseDir,
        @DefaultValue("data") String dataDir,
        @DefaultValue("docker-compose.yml") List<String> composeFiles,
        @DefaultValue("sidehub") String projectName,
        @DefaultValue("sidehub") String network,
        @DefaultValue List<String> infraProfiles,
        @DefaultValue("60s") Duration pollInterval,
        @DefaultValue("15m") Duration commandTimeout,
        @DefaultValue("") String apiToken,
        @DefaultValue Issues issues,
        @DefaultValue List<String> allowedHosts) {

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

    // Path 로 바인딩하면 Spring 이 "." 을 웹 리소스 경로(톰캣 임시 디렉터리)로 해석하므로 문자열로 받는다
    public Path resolvedBaseDir() {
        return Path.of(baseDir).toAbsolutePath().normalize();
    }

    public Path resolvedDataDir() {
        return resolvedBaseDir().resolve(dataDir).normalize();
    }

    /** 프로젝트마다 하나씩 있는 디렉터리들의 부모 (data/projects) */
    public Path projectsDir() {
        return resolvedDataDir().resolve("projects");
    }
}
