package io.github.casy82.hub.deploy;

public enum DeployStatus {
    /** 아직 한 번도 확인하지 않음 */
    PENDING,
    /** 최신 커밋으로 떠 있음 */
    RUNNING,
    /** 새 커밋을 빌드·기동하는 중 */
    DEPLOYING,
    /** 새 커밋 배포에 실패해서 이전 커밋으로 되돌림 */
    ROLLED_BACK,
    /** 배포에 실패했고 되돌릴 이전 커밋도 없음 (또는 git 명령 자체가 실패) */
    FAILED
}
