package io.github.casy82.hub.process;

import java.util.Arrays;

/** 명령 출력 다루기 */
public final class Outputs {

    private Outputs() {
    }

    /** 앞뒤 공백을 뺀 출력의 마지막 max 줄 */
    public static String lastLines(String output, int max) {
        String stripped = output.strip();
        String[] lines = stripped.split("\n");
        return lines.length <= max ? stripped
                : String.join("\n", Arrays.copyOfRange(lines, lines.length - max, lines.length));
    }
}
