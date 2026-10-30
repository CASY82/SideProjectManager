package io.github.casy82.hub.project;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/** project.yml / pins.yml 읽기·쓰기와 검증 */
public final class ProjectYaml {

    static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,39}");
    private static final Pattern BRANCH = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9._/-]{0,199}");
    private static final Pattern COMPOSE_NAME = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}");
    private static final Pattern ISSUE_REPO = Pattern.compile("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+");

    private ProjectYaml() {
    }

    public static String template(String id, String name) {
        return """
                # 프로젝트 설정. 저장할 때마다 버전으로 남고, 버전 탭에서 비교하거나 되돌릴 수 있어요.
                name: %s
                description: ""

                # 감시할 저장소. name 은 compose.yml 의 서비스 이름과 같게 맞춘다.
                services: []
                # services:
                #   - name: api
                #     repo: https://github.com/your-team/api.git
                #     branch: main
                #   - name: web
                #     repo: git@github.com:your-team/web.git
                #     branch: develop
                #     compose-service: web        # compose 서비스 이름이 name 과 다를 때만
                #     issue-repo: your-team/web   # 실패 이슈를 올릴 저장소. 비우면 repo 주소에서 알아낸다
                """.formatted(quote(name == null || name.isBlank() ? id : name));
    }

    /** 형식이 틀리면 사람이 읽을 수 있는 메시지로 IllegalArgumentException 을 던진다 */
    public static ProjectConfig parse(String id, String projectYaml, String pinsYaml) {
        Map<String, Object> root = load(projectYaml, "project.yml");
        String name = string(root.get("name"));
        List<ServiceConfig> services = new ArrayList<>();
        Object list = root.get("services");
        if (list != null && !(list instanceof List<?>)) {
            throw new IllegalArgumentException("services 는 목록이어야 합니다");
        }
        Set<String> seen = new HashSet<>();
        if (list instanceof List<?> items) {
            for (Object item : items) {
                if (!(item instanceof Map<?, ?> m)) {
                    throw new IllegalArgumentException("services 의 각 항목은 name, repo 를 가진 객체여야 합니다");
                }
                ServiceConfig s = new ServiceConfig(string(m.get("name")), string(m.get("repo")),
                        string(m.get("branch")), string(m.get("compose-service")), string(m.get("issue-repo")));
                if (s.name() == null || !NAME.matcher(s.name()).matches()) {
                    throw new IllegalArgumentException("서비스 이름 '" + s.name() + "' 은 영문 소문자, 숫자, - 만 쓸 수 있습니다");
                }
                if (!seen.add(s.name())) {
                    throw new IllegalArgumentException("서비스 이름 '" + s.name() + "' 이 두 번 나옵니다");
                }
                if (s.repo() == null || s.repo().isBlank()) {
                    throw new IllegalArgumentException("서비스 '" + s.name() + "' 에 repo 가 없습니다");
                }
                // 아래 값들은 git·docker 명령 인자로 들어가므로 옵션(-로 시작)으로 해석될 수 있는 값은 막는다
                if (s.repo().strip().startsWith("-") || s.repo().chars().anyMatch(Character::isWhitespace)) {
                    throw new IllegalArgumentException("서비스 '" + s.name() + "' 의 repo 주소 형식이 아닙니다: " + s.repo());
                }
                if (!BRANCH.matcher(s.branch()).matches()) {
                    throw new IllegalArgumentException("서비스 '" + s.name() + "' 의 branch 형식이 아닙니다: " + s.branch());
                }
                if (s.composeService() != null && !COMPOSE_NAME.matcher(s.composeService()).matches()) {
                    throw new IllegalArgumentException(
                            "서비스 '" + s.name() + "' 의 compose-service 형식이 아닙니다: " + s.composeService());
                }
                if (s.issueRepo() != null && !s.issueRepo().isBlank() && !ISSUE_REPO.matcher(s.issueRepo().strip()).matches()) {
                    throw new IllegalArgumentException("서비스 '" + s.name() + "' 의 issue-repo 는 owner/repo 형식이어야 합니다");
                }
                services.add(s);
            }
        }
        Map<String, String> pins = new LinkedHashMap<>();
        if (pinsYaml != null && !pinsYaml.isBlank()) {
            load(pinsYaml, "pins.yml").forEach((k, v) -> {
                if (v != null && seen.contains(k)) {
                    pins.put(k, String.valueOf(v));
                }
            });
        }
        return new ProjectConfig(id, name == null ? id : name, string(root.get("description")), List.copyOf(services),
                Map.copyOf(pins));
    }

    public static String pinsYaml(Map<String, String> pins) {
        if (pins.isEmpty()) {
            return "";
        }
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        return "# 서비스를 특정 커밋·태그로 고정한 목록. 대시보드의 \"버전 고정\" 으로 바뀐다\n"
                + new Yaml(options).dump(new LinkedHashMap<>(pins));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> load(String text, String file) {
        try {
            Object loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(text);
            if (loaded == null) {
                return Map.of();
            }
            if (!(loaded instanceof Map<?, ?>)) {
                throw new IllegalArgumentException(file + " 의 최상위는 key: value 형식이어야 합니다");
            }
            return (Map<String, Object>) loaded;
        } catch (YAMLException e) {
            throw new IllegalArgumentException(file + " YAML 형식 오류: " + e.getMessage());
        }
    }

    private static String string(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
