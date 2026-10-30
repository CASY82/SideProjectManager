package io.github.casy82.hub.process;

import java.nio.file.Path;
import java.util.List;

/** 외부 명령(git, docker) 실행을 감싼다. 테스트에서는 가짜 구현으로 바꿔 끼운다. */
public interface CommandRunner {

    CommandResult run(Path workDir, List<String> command);
}
