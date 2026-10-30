package io.github.casy82.hub.process;

public record CommandResult(int exitCode, String output) {

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
