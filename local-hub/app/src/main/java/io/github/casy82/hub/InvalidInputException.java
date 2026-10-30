package io.github.casy82.hub;

/**
 * 사용자가 보낸 값(설정 파일, 프로젝트 id, 고정할 버전 등)이 잘못됐을 때. 메시지는 그대로 화면에 보여준다.
 * API 는 이 예외만 400 으로 돌려주고, 그 밖의 IllegalArgumentException 은 내부 오류로 본다.
 */
public class InvalidInputException extends IllegalArgumentException {

    public InvalidInputException(String message) {
        super(message);
    }
}
