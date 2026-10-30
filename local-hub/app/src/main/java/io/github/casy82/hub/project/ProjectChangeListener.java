package io.github.casy82.hub.project;

/** 프로젝트 설정을 다시 읽거나 지울 때 불린다. 설정에 딸린 실행 상태(배포 상태 등)를 맞추는 쪽이 구현한다. */
public interface ProjectChangeListener {

    void onLoaded(ProjectConfig config);

    void onRemoved(String projectId);
}
