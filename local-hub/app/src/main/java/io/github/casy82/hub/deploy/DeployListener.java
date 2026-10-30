package io.github.casy82.hub.deploy;

import io.github.casy82.hub.project.ProjectConfig;
import io.github.casy82.hub.project.ServiceConfig;

/** 서비스 동기화가 무언가를 바꿨을 때(배포, 실패, 롤백) 불린다. 커밋이 그대로라 건너뛴 경우는 불리지 않는다. */
public interface DeployListener {

    void onSynced(ProjectConfig project, ServiceConfig service, ServiceState before, ServiceState after);
}
