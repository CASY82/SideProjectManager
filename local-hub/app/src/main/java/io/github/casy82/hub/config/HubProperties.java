package io.github.casy82.hub.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

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
        @DefaultValue List<Service> services) {

    /**
     * @param name           대시보드와 API 에서 쓰는 이름. workspace/{name} 에 clone 된다
     * @param repo           git 저장소 주소 (https, ssh, file 모두 가능)
     * @param branch         감시할 브랜치
     * @param composeService compose 파일 안의 서비스 이름. 비우면 name 과 같다
     */
    public record Service(
            String name,
            String repo,
            @DefaultValue("main") String branch,
            String composeService) {

        public String composeServiceOrName() {
            return composeService == null || composeService.isBlank() ? name : composeService;
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
