package io.github.casy82.hub.project;

import io.github.casy82.hub.compose.ComposeClient;

/** 새 프로젝트를 만들 때 쓰는 기본 파일 내용 */
final class ProjectTemplates {

    private ProjectTemplates() {
    }

    /** 설정 파일만 버전에 남도록 작업 공간·이력·임시 파일은 뺀다 */
    static String gitignore() {
        return "workspace/\nhistory/\n" + ComposeClient.CHECK_FILE + "\n" + ConfigHistory.MESSAGE_FILE + "\n";
    }

    static String projectYaml(String id, String name) {
        return """
                # 프로젝트 설정. 저장할 때마다 버전으로 남고, 버전 탭에서 비교하거나 되돌릴 수 있어요.
                name: %s
                description: ""

                # 감시할 저장소. name 은 compose.yml 의 서비스 이름과 같게 맞춘다.
                services: []
                # services:
                #   - name: api
                #     repo: https://github.com/your-team/api.git
                #     branch: main
                #   - name: web
                #     repo: git@github.com:your-team/web.git
                #     branch: develop
                #     compose-service: web        # compose 서비스 이름이 name 과 다를 때만
                #     issue-repo: your-team/web   # 실패 이슈를 올릴 저장소. 비우면 repo 주소에서 알아낸다
                """.formatted(quote(name == null || name.isBlank() ? id : name));
    }

    static String composeYaml(String network) {
        return """
                # 이 프로젝트 서비스들의 docker compose 정의. 저장할 때마다 버전으로 남아요.
                # build 경로는 ./workspace/<서비스 이름> (Local Hub 가 저장소를 여기에 clone 해요)
                # 공통 서비스(postgres, redis 등)는 같은 네트워크에 있어서 서비스 이름으로 접속해요. 예) postgres:5432
                # 여러 프로젝트가 한 PC 에서 돌기 때문에 ports 는 프로젝트끼리 겹치지 않게 정하세요.

                services:
                  # api:
                  #   build: ./workspace/api
                  #   restart: unless-stopped
                  #   ports: ["8080:8080"]
                  #   environment:
                  #     SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/app

                  # 서비스가 하나도 없을 때 compose 가 오류를 내지 않도록 두는 자리. 실제 서비스를 추가하면 지워도 된다
                  placeholder:
                    image: busybox:1.37
                    profiles: [never]

                networks:
                  default:
                    name: %s
                    external: true
                """.formatted(network);
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
