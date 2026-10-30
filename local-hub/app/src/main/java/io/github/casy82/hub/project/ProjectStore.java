package io.github.casy82.hub.project;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import io.github.casy82.hub.compose.ComposeClient;
import io.github.casy82.hub.config.HubProperties;
import io.github.casy82.hub.process.CommandResult;
import io.github.casy82.hub.process.CommandRunner;

/**
 * data/projects/{id}/ 아래 프로젝트 파일을 읽고 쓴다. 모든 변경은 ConfigHistory 로 커밋된다.
 *
 * <pre>
 * data/projects/{id}/
 *   project.yml   이름, 설명, 서비스 목록
 *   compose.yml   이 프로젝트 서비스들의 docker compose 정의
 *   pins.yml      서비스별 고정 버전 (있을 때만)
 *   workspace/    서비스 저장소 clone (기록 안 함)
 *   history/      배포 이력 (기록 안 함)
 * </pre>
 */
@Component
public class ProjectStore {

    public static final String PROJECT_FILE = "project.yml";
    public static final String COMPOSE_FILE = "compose.yml";
    public static final String PINS_FILE = "pins.yml";
    public static final List<String> EDITABLE = List.of(PROJECT_FILE, COMPOSE_FILE);

    private final HubProperties props;
    private final CommandRunner runner;
    private final ComposeClient compose;

    public ProjectStore(HubProperties props, CommandRunner runner, ComposeClient compose) {
        this.props = props;
        this.runner = runner;
        this.compose = compose;
    }

    public Path root() {
        return props.resolvedDataDir().resolve("projects");
    }

    public Path dir(String id) {
        requireId(id);
        return root().resolve(id);
    }

    public List<String> ids() {
        if (!Files.isDirectory(root())) {
            return List.of();
        }
        try (Stream<Path> dirs = Files.list(root())) {
            return dirs.filter(d -> Files.isRegularFile(d.resolve(PROJECT_FILE)))
                    .map(d -> d.getFileName().toString())
                    .filter(n -> ProjectYaml.NAME.matcher(n).matches())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Optional<ProjectConfig> load(String id) {
        Path d = dir(id);
        if (!Files.isRegularFile(d.resolve(PROJECT_FILE))) {
            return Optional.empty();
        }
        return Optional.of(ProjectYaml.parse(id, read(d.resolve(PROJECT_FILE)), readIfExists(d.resolve(PINS_FILE))));
    }

    public ProjectConfig create(String id, String name) {
        requireId(id);
        Path d = dir(id);
        if (Files.exists(d)) {
            throw new IllegalArgumentException("이미 있는 프로젝트 id 입니다: " + id);
        }
        try {
            Files.createDirectories(d);
            write(d.resolve(".gitignore"), "workspace/\nhistory/\n.compose-check.yml\n.commit-msg\n");
            write(d.resolve(PROJECT_FILE), ProjectYaml.template(id, name));
            write(d.resolve(COMPOSE_FILE), composeTemplate());
        } catch (IOException e) {
            deleteTree(d);
            throw new UncheckedIOException(e);
        } catch (RuntimeException e) {
            deleteTree(d);
            throw e;
        }
        ConfigHistory history = history(id);
        history.init();
        history.commit("프로젝트 생성: " + (name == null || name.isBlank() ? id : name));
        return load(id).orElseThrow();
    }

    public String readFile(String id, String file) {
        requireEditable(file);
        return read(dir(id).resolve(file));
    }

    /**
     * 파일을 검증한 뒤 저장하고 커밋한다.
     * @return 바뀐 것이 있어 새 버전이 생겼으면 true
     */
    public boolean saveFile(String id, String file, String content, String message) {
        requireEditable(file);
        Path d = existing(id);
        if (PROJECT_FILE.equals(file)) {
            ProjectYaml.parse(id, content, readIfExists(d.resolve(PINS_FILE)));
        } else {
            validateCompose(d, content);
        }
        write(d.resolve(file), content.endsWith("\n") ? content : content + "\n");
        return history(id).commit(message == null || message.isBlank() ? file + " 수정" : message);
    }

    /** ref 가 null 이면 고정을 푼다 */
    public boolean setPin(String id, String service, String ref, String message) {
        Path d = existing(id);
        ProjectConfig config = load(id).orElseThrow();
        if (config.service(service).isEmpty()) {
            throw new IllegalArgumentException("없는 서비스입니다: " + service);
        }
        if (ref != null && !ref.matches("[A-Za-z0-9._/-]{1,100}")) {
            throw new IllegalArgumentException("커밋·태그 형식이 아닙니다: " + ref);
        }
        Map<String, String> pins = new LinkedHashMap<>(config.pins());
        if (ref == null) {
            pins.remove(service);
        } else {
            pins.put(service, ref);
        }
        try {
            if (pins.isEmpty()) {
                Files.deleteIfExists(d.resolve(PINS_FILE));
            } else {
                write(d.resolve(PINS_FILE), ProjectYaml.pinsYaml(pins));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return history(id).commit(message);
    }

    public void delete(String id) {
        deleteTree(existing(id));
    }

    public ConfigHistory history(String id) {
        return new ConfigHistory(runner, existing(id));
    }

    public Path workspace(String id) {
        return dir(id).resolve("workspace");
    }

    private Path existing(String id) {
        Path d = dir(id);
        if (!Files.isRegularFile(d.resolve(PROJECT_FILE))) {
            throw new IllegalArgumentException("없는 프로젝트입니다: " + id);
        }
        return d;
    }

    /** docker compose config 로 문법을 확인한다. 파일 안의 상대 경로가 맞도록 프로젝트 디렉터리에 임시로 쓴다 */
    private void validateCompose(Path d, String content) {
        Path check = d.resolve(".compose-check.yml");
        write(check, content);
        try {
            CommandResult r = compose.validate(d, check.getFileName().toString());
            if (!r.ok()) {
                throw new IllegalArgumentException("compose.yml 검사 실패:\n" + r.output().strip());
            }
        } finally {
            try {
                Files.deleteIfExists(check);
            } catch (IOException ignored) {
                // 다음 저장 때 덮어쓴다
            }
        }
    }

    private String composeTemplate() {
        return """
                # 이 프로젝트 서비스들의 docker compose 정의. 저장할 때마다 버전으로 남아요.
                # build 경로는 ./workspace/<서비스 이름> (Local Hub 가 저장소를 여기에 clone 해요)
                # 공통 서비스(postgres, redis 등)는 같은 네트워크에 있어서 서비스 이름으로 접속해요. 예) postgres:5432
                # 여러 프로젝트가 한 PC 에서 돌기 때문에 ports 는 프로젝트끼리 겹치지 않게 정하세요.

                services:
                  # api:
                  #   build: ./workspace/api
                  #   restart: unless-stopped
                  #   ports: ["8080:8080"]
                  #   environment:
                  #     SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/app

                  # 서비스가 하나도 없을 때 compose 가 오류를 내지 않도록 두는 자리. 실제 서비스를 추가하면 지워도 된다
                  placeholder:
                    image: busybox:1.37
                    profiles: [never]

                networks:
                  default:
                    name: %s
                    external: true
                """.formatted(props.network());
    }

    public static void requireId(String id) {
        if (id == null || !ProjectYaml.NAME.matcher(id).matches()) {
            throw new IllegalArgumentException("프로젝트 id 는 영문 소문자, 숫자, - 로 40자 이내여야 합니다: " + id);
        }
    }

    private static void requireEditable(String file) {
        if (!EDITABLE.contains(file)) {
            throw new IllegalArgumentException("편집할 수 없는 파일입니다: " + file);
        }
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String readIfExists(Path p) {
        return Files.isRegularFile(p) ? read(p) : null;
    }

    private static void write(Path p, String content) {
        try {
            Files.writeString(p, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void deleteTree(Path d) {
        if (!Files.exists(d)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(d)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                p.toFile().setWritable(true);
                Files.delete(p);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
