package io.github.casy82.hub.process;

import java.util.List;

public record CommandResult(int exitCode, String output) {

    private static final String CANNOT_RUN = "명령을 실행할 수 없음";

    /** 프로그램이 없는 등 명령을 시작하지도 못한 경우 */
    public static CommandResult cannotRun(List<String> command, String reason) {
        return new CommandResult(-1, CANNOT_RUN + ": " + String.join(" ", command) + "\n" + reason);
    }

    public boolean couldNotRun() {
        return exitCode == -1 && output.startsWith(CANNOT_RUN);
    }

    public boolean ok() {
        return exitCode == 0;
    }

    /** 성공했으면 출력을 돌려주고, 실패했으면 출력을 담아 예외를 던진다. */
    public String orThrow(String what) {
        if (!ok()) {
            throw new CommandFailedException(what + " 실패 (exit " + exitCode + ")", output);
        }
        return output.strip();
    }
}
