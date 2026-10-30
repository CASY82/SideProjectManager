package io.github.casy82.hub.project;

/** 서비스 배포 요청. 요청은 바로 반환하고 배포는 뒤에서 돈다. 설정 변경이 배포 방식을 몰라도 되도록 둔 경계 */
public interface DeployTrigger {

    /** @param force true 면 커밋이 그대로여도 다시 빌드·기동한다 */
    void requestSync(String projectId, String service, boolean force);
}
