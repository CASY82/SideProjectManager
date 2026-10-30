# SideProjectManager

어떤 토이·사이드 프로젝트에서도 쓸 수 있는 무료 업무 도구 모음입니다. 유료 서비스 없이 동작하는 것을 원칙으로 합니다.

| 도구 | 하는 일 | 문서 |
| --- | --- | --- |
| Local Hub | 클라우드 없이 공용 PC 한 대에 공통 서비스(DB 등)와 팀원 서비스를 띄우고, git 에 새 커밋이 올라오면 자동으로 다시 빌드·재기동 | [local-hub/README.md](local-hub/README.md) |
| 일정·할 일 자동 관리 | GitHub Issues + Actions 로 상태·우선순위 라벨, 매일 마감 알림, 주간 요약, Discord/Slack 알림 | [docs/task-automation.md](docs/task-automation.md) |

## 새 프로젝트에 적용하기

이 저장소를 받아서 초기화 스크립트로 원하는 도구만 복사합니다.

```bash
git clone https://github.com/CASY82/SideProjectManager.git
SideProjectManager/scripts/init-project.sh ../my-project            # 둘 다
SideProjectManager/scripts/init-project.sh ../my-project --tasks    # 일정 자동화만
SideProjectManager/scripts/init-project.sh ../my-project --hub      # Local Hub 만
```

- 이미 있는 파일은 건너뛰고, `--force` 를 주면 덮어씁니다.
- 실행 중에 생기는 파일(`hub.yml`, `.env`, `workspace/`, 빌드 결과)은 복사하지 않고 `.gitignore` 에 추가합니다.
- Windows 에서는 Git Bash 로 실행하면 됩니다.

새 저장소를 처음부터 만든다면 GitHub 에서 이 저장소를 **템플릿 저장소** 로 지정해 두고
(**Settings → General → Template repository** 체크) **Use this template** 으로 만드는 방법도 있습니다.

## 개발

```bash
node --test ".github/scripts/*.test.js"     # 일정 자동화 스크립트
(cd local-hub/app && ./mvnw test)            # Local Hub
```

PR 을 올리면 CI 가 두 테스트를 모두 돌립니다.
