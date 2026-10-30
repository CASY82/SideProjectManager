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
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import io.github.casy82.hub.InvalidInputException;
import io.github.casy82.hub.config.HubProperties;
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
    public ProjectStore(HubProperties props, CommandRunner runner) {
        this.props = props;
        this.runner = runner;
    }

    public Path root() {
        return props.projectsDir();
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
            throw new InvalidInputException("이미 있는 프로젝트 id 입니다: " + id);
        }
        try {
            Files.createDirectories(d);
            write(d.resolve(".gitignore"), ProjectTemplates.gitignore());
            write(d.resolve(PROJECT_FILE), ProjectTemplates.projectYaml(id, name));
            write(d.resolve(COMPOSE_FILE), ProjectTemplates.composeYaml(props.network()));
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

    /**
     * compose.yml 에 정의된 서비스 이름들. 파일이 없거나 읽을 수 없으면 빈 집합.
     * 형식 검사는 저장할 때 하므로 여기서는 services 의 키만 본다.
     */
    public Set<String> composeServices(String id) {
        Path file = dir(id).resolve(COMPOSE_FILE);
        if (!Files.isRegularFile(file)) {
            return Set.of();
        }
        try {
            Object root = new Yaml(new SafeConstructor(new LoaderOptions())).load(read(file));
            if (root instanceof Map<?, ?> m && m.get("services") instanceof Map<?, ?> services) {
                return services.keySet().stream().map(String::valueOf).collect(Collectors.toUnmodifiableSet());
            }
        } catch (YAMLException e) {
            // 저장 전 검사를 거치지 않고 직접 고친 파일. 정의가 없는 것으로 본다
        }
        return Set.of();
    }

    public String readFile(String id, String file) {
        requireEditable(file);
        return read(dir(id).resolve(file));
    }

    /**
     * 파일을 저장하고 커밋한다. project.yml 은 형식을 검사하고, compose.yml 검사는 docker 가 필요해서
     * {@link ProjectService} 가 저장 전에 한다.
     * @return 바뀐 것이 있어 새 버전이 생겼으면 true
     */
    public boolean saveFile(String id, String file, String content, String message) {
        requireEditable(file);
        Path d = requireExisting(id);
        if (PROJECT_FILE.equals(file)) {
            ProjectYaml.parse(id, content, readIfExists(d.resolve(PINS_FILE)));
        }
        write(d.resolve(file), content.endsWith("\n") ? content : content + "\n");
        return history(id).commit(message == null || message.isBlank() ? file + " 수정" : message);
    }

    /** ref 가 null 이면 고정을 푼다 */
    public boolean setPin(String id, String service, String ref, String message) {
        Path d = requireExisting(id);
        ProjectConfig config = load(id).orElseThrow();
        if (config.service(service).isEmpty()) {
            throw new InvalidInputException("없는 서비스입니다: " + service);
        }
        if (ref != null) {
            ProjectYaml.requireRef(ref);
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
        deleteTree(requireExisting(id));
    }

    public ConfigHistory history(String id) {
        return new ConfigHistory(runner, requireExisting(id));
    }

    public Path workspace(String id) {
        return dir(id).resolve("workspace");
    }

    /** 있는 프로젝트의 디렉터리. 없으면 InvalidInputException */
    public Path requireExisting(String id) {
        Path d = dir(id);
        if (!Files.isRegularFile(d.resolve(PROJECT_FILE))) {
            throw new InvalidInputException("없는 프로젝트입니다: " + id);
        }
        return d;
    }

    public static void requireId(String id) {
        if (id == null || !ProjectYaml.NAME.matcher(id).matches()) {
            throw new InvalidInputException("프로젝트 id 는 영문 소문자, 숫자, - 로 40자 이내여야 합니다: " + id);
        }
    }

    private static void requireEditable(String file) {
        if (!EDITABLE.contains(file)) {
            throw new InvalidInputException("편집할 수 없는 파일입니다: " + file);
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
