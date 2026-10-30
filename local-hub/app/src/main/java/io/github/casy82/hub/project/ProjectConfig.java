package io.github.casy82.hub.project;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 프로젝트 하나의 설정. project.yml 과 pins.yml 에서 읽는다.
 *
 * @param id          디렉터리 이름이자 API 경로에 쓰는 id (영문 소문자, 숫자, -)
 * @param name        화면에 보이는 이름
 * @param description 설명
 * @param services    서비스 목록
 * @param pins        서비스 이름 → 고정한 커밋·태그. 없으면 브랜치 최신을 따라간다
 */
public record ProjectConfig(String id, String name, String description, List<ServiceConfig> services,
        Map<String, String> pins) {

    public Optional<ServiceConfig> service(String name) {
        return services.stream().filter(s -> s.name().equals(name)).findFirst();
    }

    /** 배포할 git ref. 고정돼 있으면 그 커밋·태그, 아니면 origin/{branch} */
    public String targetRef(ServiceConfig service) {
        String pin = pins.get(service.name());
        return pin != null ? pin : "origin/" + service.branch();
    }
}
