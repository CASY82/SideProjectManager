package io.github.casy82.hub.issue;

import java.util.List;
import java.util.Optional;

/** 이슈 저장소(GitHub)와 주고받는 부분만 모았다. 테스트에서는 메모리 구현으로 바꾼다. */
public interface IssueTracker {

    /** label 이 붙은 열린 이슈 중 제목이 title 인 것 */
    Optional<Issue> findOpen(String repo, String label, String title);

    Issue create(String repo, String title, String body, List<String> labels);

    void comment(String repo, int number, String body);

    void close(String repo, int number);

    /** 커밋 작성자의 GitHub 아이디. 저장소에 없는 커밋이거나 GitHub 계정과 연결되지 않은 이메일이면 비어 있다 */
    Optional<String> commitAuthorLogin(String repo, String sha);

    record Issue(int number, String url) {
    }
}
