package io.github.casy82.hub.process;

public class CommandFailedException extends RuntimeException {

    private final String output;

    public CommandFailedException(String message, String output) {
        super(message);
        this.output = output;
    }

    public String output() {
        return output;
    }
}
