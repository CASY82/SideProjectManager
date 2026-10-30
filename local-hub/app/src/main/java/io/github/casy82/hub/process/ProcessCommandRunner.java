package io.github.casy82.hub.process;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import io.github.casy82.hub.config.HubProperties;

@Component
public class ProcessCommandRunner implements CommandRunner {

    private final HubProperties props;

    public ProcessCommandRunner(HubProperties props) {
        this.props = props;
    }

    @Override
    public CommandResult run(Path workDir, List<String> command) {
        ProcessBuilder pb = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(true);
        // git 이 사용자 입력(비밀번호 등)을 기다리며 멈추지 않도록 한다
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        try {
            Process process = pb.start();
            CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> readAll(process.getInputStream()));
            if (!process.waitFor(props.commandTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return new CommandResult(-1, output.getNow("") + "\n[timeout] " + props.commandTimeout() + " 초과");
            }
            return new CommandResult(process.exitValue(), output.join());
        } catch (IOException e) {
            return CommandResult.cannotRun(command, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CommandResult(-1, "중단됨");
        }
    }

    private static String readAll(InputStream in) {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
