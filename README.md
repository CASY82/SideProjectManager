# SideProjectManager

어떤 토이·사이드 프로젝트에서도 쓸 수 있는 무료 업무 도구 모음입니다. 유료 서비스 없이 동작하는 것을 원칙으로 합니다.

| 도구 | 하는 일 | 문서 |
| --- | --- | --- |
| Local Hub | 클라우드 없이 공용 PC 한 대에 공통 서비스(DB 등)와 여러 프로젝트의 서비스를 띄우고, git 에 새 커밋이 올라오면 자동으로 다시 빌드·재기동. 프로젝트 설정 버전 기록·되돌리기, 배포 이력, 버전 고정 | [local-hub/README.md](local-hub/README.md) |
| 일정·할 일 자동 관리 | GitHub Issues + Actions 로 기획서 양식과 기획별 진행률, 상태·우선순위 라벨, 매일 마감 알림, 주간 요약, Discord/Slack 알림 | [docs/task-automation.md](docs/task-automation.md) |
| 프로젝트 템플릿 | 새 프로젝트의 뼈대: README·기여 규칙·PR 양식·기술 결정 기록, 언어 자동 감지 CI, Dependabot, 공통 `.gitignore`·`.editorconfig` | [template/](template/) |

## 새 프로젝트에 적용하기

이 저장소를 받아서 초기화 스크립트로 새 프로젝트를 만들거나, 기존 프로젝트에 원하는 도구만 더합니다.

```bash
git clone https://github.com/CASY82/SideProjectManager.git
SideProjectManager/scripts/init-project.sh ../my-project              # 템플릿 + 일정 자동화 + Local Hub
SideProjectManager/scripts/init-project.sh ../my-project --template   # 프로젝트 뼈대만
SideProjectManager/scripts/init-project.sh ../my-project --tasks      # 일정 자동화만
SideProjectManager/scripts/init-project.sh ../my-project --hub        # Local Hub 만
```

새 프로젝트 기준으로 만들어지는 구조는 이렇습니다.

```
my-project/
├── README.md               소개·팀·기술 스택·실행 방법·일정 (프로젝트 이름은 자동으로 채워짐)
├── CONTRIBUTING.md         브랜치·커밋·PR·리뷰 규칙
├── docs/decisions/         기술 결정 기록 (양식 포함)
├── .github/
│   ├── workflows/ci.yml    pom.xml / Gradle / package.json / Python 을 감지해 빌드·테스트
│   ├── workflows/...       일정 자동화 (이슈 정리, 마감 알림, 주간 요약, 라벨 동기화)
│   ├── ISSUE_TEMPLATE/     할 일·버그 양식
│   ├── pull_request_template.md
│   └── dependabot.yml
├── local-hub/              공용 PC 통합 서버
└── .gitignore .gitattributes .editorconfig .env.example
```

- 이미 있는 파일은 건너뛰고, `--force` 를 주면 덮어씁니다.
- 실행 중에 생기는 파일(`hub.yml`, `.env`, `data/`, 빌드 결과)은 복사하지 않고 `.gitignore` 에 추가합니다.
- 대상 폴더가 git 저장소가 아니면 `git init` 까지 해 줍니다.
- Windows 에서는 Git Bash 로 실행하면 됩니다.

## 개발

```bash
node --test ".github/scripts/*.test.js"     # 일정 자동화 스크립트
(cd local-hub/app && ./mvnw test)            # Local Hub
```

PR 을 올리면 CI 가 두 테스트를 모두 돌립니다.
