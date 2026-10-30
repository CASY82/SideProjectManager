# Local Hub

클라우드 없이 팀 공용 PC(또는 집 서버) 한 대에서 **공통 서비스(DB 등)와 팀원들의 서비스를 함께 띄우고**,
각 저장소에 새 커밋이 올라오면 **자동으로 다시 빌드·재기동**하는 도구입니다.

```
 팀원 A ─push─▶ GitHub (shop/api)  ┐
 팀원 B ─push─▶ GitHub (blog/web)  ┤   git fetch (폴링)
                                   ▼
 ┌──────────── 공용 PC ──────────────────────────────────────────┐
 │  Local Hub (Spring Boot, :8090 대시보드)                       │
 │    프로젝트 추가·설정 편집·버전 기록 / 새 커밋 → 다시 빌드·기동  │
 │    실패하면 이전 커밋으로 되돌려 다시 기동                       │
 │                                                               │
 │  docker 네트워크 "sidehub"                                     │
 │    sidehub        postgres redis mysql rabbitmq   (공통)       │
 │    sidehub-shop   api  admin                      (프로젝트 1) │
 │    sidehub-blog   web                             (프로젝트 2) │
 └───────────────────────────────────────────────────────────────┘
```

## 준비물

- Docker (Docker Desktop 또는 Docker Engine + Compose v2)
- Java 21 이상 (Maven 은 따로 설치하지 않아도 됩니다. `mvnw` 가 알아서 받습니다)
- git, 그리고 감시할 저장소를 읽을 수 있는 권한 (아래 "비공개 저장소" 참고)

## 빠르게 시작하기

```bash
cd local-hub
./hub.sh          # Windows 는 hub.cmd
```

1. 처음 실행하면 `hub.example.yml` 이 `hub.yml` 로 복사됩니다. 함께 띄울 공통 서비스(`infra-profiles`)만 확인하세요.
2. `http://<공용PC IP>:8090` 에 들어가 왼쪽 아래에서 **프로젝트를 추가**합니다 (id 예: `shop`).
3. **설정** 탭의 `project.yml` 에 감시할 저장소를 적고 저장합니다. 아직 compose 정의가 없어서 서비스는 `대기` 로 보입니다.
4. `compose.yml` 로 바꿔 같은 이름의 서비스를 적고 저장합니다. `build: ./workspace/<이름>` 으로 두면 됩니다.
5. 저장하면 바로 저장소를 받아 빌드·기동합니다. **서비스** 탭에서 상태를 확인하세요.

```yaml
# project.yml
name: 쇼핑몰
description: 주문·결제 서비스
services:
  - name: api
    repo: https://github.com/your-team/api.git
    branch: main
```

```yaml
# compose.yml
services:
  api:
    build: ./workspace/api
    ports: ["8080:8080"]
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/app

networks:
  default:
    name: sidehub
    external: true
```

각 서비스 저장소의 루트에는 `Dockerfile` 이 있어야 합니다.

## 여러 프로젝트

프로젝트마다 설정과 compose 파일이 따로 있고, `sidehub-<id>` 라는 별도의 compose 프로젝트로 뜹니다.
모든 프로젝트가 같은 docker 네트워크(`sidehub`)에 붙기 때문에 공통 서비스는 `postgres:5432` 처럼 이름으로 함께 씁니다.

- 한 PC 에서 함께 돌기 때문에 **`ports` 는 프로젝트끼리 겹치지 않게** 정하세요 (예: shop 8080, blog 8081).
- 같은 DB 서버를 쓰더라도 프로젝트마다 DB 이름이나 스키마를 나누는 것을 추천합니다.
- 프로젝트를 삭제하면 그 프로젝트의 컨테이너를 내리고 설정·버전 기록·작업 공간을 지웁니다. 공통 서비스와 볼륨은 남습니다.

모든 데이터는 `local-hub/data/` 아래에 파일로 저장됩니다 (DB 를 쓰지 않습니다). 백업은 이 폴더를 복사하면 됩니다.

```
data/projects/shop/
  project.yml    이름, 설명, 서비스 저장소 목록
  compose.yml    이 프로젝트의 docker compose 정의
  pins.yml       버전 고정 목록 (있을 때만)
  .git/          설정 버전 기록
  workspace/     서비스 저장소 clone (배포 전용)
  history/       배포 이력 (deploys.tsv)
```

## 형상관리

### 설정 버전

`project.yml`, `compose.yml`, 버전 고정이 바뀔 때마다 프로젝트 폴더의 git 저장소에 커밋됩니다.
저장할 때 적은 메시지가 커밋 메시지가 됩니다.

- **설정 버전** 탭에서 버전을 누르면 "그 버전에서 바뀐 내용" 과 "그 버전 → 현재 설정" 차이를 나란히 봅니다.
- **이 버전으로 되돌리기** 를 누르면 그때의 설정 파일로 돌아가고 서비스를 모두 다시 배포합니다. 되돌린 것도 새 버전으로 남기 때문에 이력은 지워지지 않습니다.
- 잘못된 YAML 이나 `docker compose config` 검사를 통과하지 못하는 compose 파일은 저장되지 않습니다.

### 배포 이력

서비스가 배포에 성공·실패·롤백할 때마다 **배포 이력** 탭에 시각, 서비스, 결과, 커밋, 메시지가 쌓입니다.

### 버전 고정

서비스 카드의 **버전 고정** 에서 브랜치 최신 대신 특정 커밋이나 태그로 고정할 수 있습니다.
예전에 배포에 성공한 버전, 태그, 브랜치 최근 커밋 중에서 고르거나 직접 입력합니다.

- 고정하는 동안에는 브랜치에 새 커밋이 올라와도 그 버전을 유지합니다. 시연이나 QA 기간에 쓰기 좋습니다.
- **고정 풀기** 를 누르면 다시 브랜치 최신을 따라갑니다.
- 고정과 해제도 설정 버전으로 기록되므로 언제 누가 무엇으로 고정했는지 남습니다.

## 동작 방식

| 상황 | Local Hub 가 하는 일 |
| --- | --- |
| 처음 시작 | `sidehub` 네트워크를 만들고 `infra-profiles` 의 공통 서비스를 올린 뒤 모든 프로젝트의 서비스를 확인 |
| 새 커밋 발견 | 해당 서비스만 `docker compose up -d --build --no-deps --wait` 로 다시 기동 |
| 빌드나 기동 실패 | 이전 커밋으로 되돌려 다시 기동하고 상태를 `되돌림` 으로 표시 |
| 실패한 커밋 그대로 | 매 주기마다 다시 시도하지 않음. 새 커밋이 올라오거나 "다시 배포" 를 누르면 재시도 |
| `project.yml` 저장 | 새로 추가된 서비스를 바로 받아서 기동 |
| `compose.yml` 에 서비스 정의가 아직 없음 | 배포하지 않고 `대기` 로 두며 "compose.yml 에 서비스 정의가 필요해요" 를 표시. 실패 이력·이슈는 남기지 않음 |
| `compose.yml` 저장 · 설정 되돌리기 | 모든 서비스를 다시 기동해 바뀐 설정을 반영 |
| 커밋 변화 없음 | 아무것도 하지 않음 |

`--wait` 를 쓰기 때문에 Compose 에 `healthcheck` 를 적어 두면 헬스체크가 통과해야 성공으로 봅니다.
헬스체크가 없으면 컨테이너가 떠 있기만 하면 성공입니다.

`workspace/` 는 배포 전용입니다. 배포할 때 `git reset --hard` 를 하므로 그 안에서 직접 코드를 고치지 마세요.

## 실패하면 GitHub 이슈로 알리기

`hub.yml` 에서 켜면 배포가 실패하거나 롤백될 때 해당 저장소에 이슈가 자동으로 올라갑니다.

```yaml
hub:
  issues:
    enabled: true
    token: ${HUB_GITHUB_TOKEN:}
```

```bash
export HUB_GITHUB_TOKEN=github_pat_...   # Windows: set HUB_GITHUB_TOKEN=...
./hub.sh
```

| 상황 | 이슈에 생기는 일 |
| --- | --- |
| 배포 실패·롤백 | `[Local Hub] <프로젝트>/<서비스> 배포 실패` 이슈 생성. 실패한 커밋, 상태, 빌드 출력 끝부분, 커밋 작성자 멘션 |
| 이슈가 열린 채로 다른 커밋도 실패 | 같은 이슈에 댓글 추가 (서비스마다 이슈는 하나) |
| 같은 실패가 계속됨 | 아무것도 하지 않음 (매 주기마다 댓글이 쌓이지 않음) |
| 다음 배포 성공 | "정상으로 돌아왔어요" 댓글을 남기고 닫음 |

- 이슈는 `local-hub`, `type: bug` 라벨로 올라가므로 일정 자동화(이슈 정리, 주간 요약)에도 그대로 잡힙니다.
- 올릴 저장소는 `repo` 주소(`github.com/owner/repo`)에서 알아냅니다. 다른 저장소에 모으고 싶거나 GitHub 이 아닌 주소라면 `project.yml` 의 서비스마다 `issue-repo: owner/repo` 를 적으세요.
- 토큰: GitHub **Settings → Developer settings → Fine-grained tokens** 에서 대상 저장소를 고르고 **Issues: Read and write**, **Contents: Read-only** 권한으로 만듭니다. Contents 권한은 커밋 작성자를 찾는 데만 씁니다.
- GitHub 에 연결할 수 없어도 배포는 그대로 진행되고, 다음 실패 때 다시 시도합니다.

## 대시보드와 API

`http://<공용PC>:8090`

| 화면 | 내용 |
| --- | --- |
| 왼쪽 목록 | 프로젝트 목록 (문제가 있으면 표시), 새 프로젝트 추가, 공통 서비스 상태 |
| 서비스 탭 | 서비스별 상태, 배포된·실패한 커밋, "지금 확인", "다시 배포", 버전 고정, 빌드 출력, 컨테이너 로그 |
| 설정 탭 | `project.yml` · `compose.yml` 편집 (메시지와 함께 저장), 프로젝트 삭제 |
| 배포 이력 탭 | 배포 성공·실패·롤백 기록 |
| 설정 버전 탭 | 설정 변경 기록, 차이 비교, 되돌리기 |

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| GET · POST | `/api/projects` | 프로젝트 목록과 상태 · 추가 `{"id","name"}` |
| GET · DELETE | `/api/projects/{id}` | 프로젝트 하나 · 삭제 (`?removeContainers=false` 면 컨테이너는 남김) |
| GET · PUT | `/api/projects/{id}/files/{project.yml\|compose.yml}` | 설정 파일 읽기 · 저장 `{"content","message"}` |
| GET | `/api/projects/{id}/ps` | 프로젝트 컨테이너 목록 |
| POST | `/api/projects/{id}/services/{name}/deploy?force=false` | 즉시 확인·배포 (비동기) |
| GET | `/api/projects/{id}/services/{name}/logs?tail=200` | 컨테이너 로그 |
| GET | `/api/projects/{id}/services/{name}/versions` | 고정할 버전 후보 (최근 커밋, 태그, 성공한 배포) |
| PUT · DELETE | `/api/projects/{id}/services/{name}/pin` | 버전 고정 `{"ref"}` · 고정 풀기 |
| GET | `/api/projects/{id}/history?service=&limit=100` | 배포 이력 |
| GET | `/api/projects/{id}/config-versions` | 설정 버전 목록 |
| GET | `/api/projects/{id}/config-versions/{sha}` | 그 버전의 변경 내용과 현재와의 차이 |
| POST | `/api/projects/{id}/config-versions/{sha}/restore` | 그 버전으로 되돌리기 |
| GET | `/api/infra/ps` | 공통 서비스 컨테이너 목록 |

`hub.api-token` 을 설정하면 POST·PUT·DELETE 요청에 `X-Hub-Token` 헤더가 필요합니다 (대시보드 왼쪽 아래에 입력).
같은 네트워크에 모르는 사람이 있다면 꼭 설정하세요.

### 폴링 대신 즉시 배포하고 싶다면

공용 PC 는 보통 외부에서 접속할 수 없어서 GitHub 웹훅을 직접 받을 수 없습니다. 그래서 기본은 폴링입니다.
더 빠르게 반영하고 싶으면 `poll-interval` 을 `15s` 정도로 줄이거나,
같은 네트워크에서 `curl -X POST http://<공용PC>:8090/api/projects/shop/services/api/deploy` 를 호출하면 됩니다.

## 비공개 저장소

Local Hub 는 호스트의 `git` 을 그대로 쓰므로 공용 PC 에서 `git clone` 이 되면 Local Hub 도 됩니다.

- SSH: 공용 PC 에 배포용 SSH 키를 만들고 각 저장소의 **Deploy keys** (읽기 전용) 에 등록한 뒤 `repo: git@github.com:team/api.git`
- HTTPS: `git config --global credential.helper store` 후 한 번 clone 해서 토큰을 저장

비밀번호를 묻는 상황이 되면 멈추지 않고 실패로 표시합니다.

## 공통 서비스

`docker-compose.yml` 에 프로필로 들어 있습니다. 필요한 것만 `infra-profiles` 에 적으세요.

| 프로필 | 접속 (공용 PC 기준) | 컨테이너 안에서 | 기본 계정 |
| --- | --- | --- | --- |
| postgres | `localhost:5432` | `postgres:5432` | app / app, DB `app` |
| mysql | `localhost:3306` | `mysql:3306` | app / app, DB `app` |
| redis | `localhost:6379` | `redis:6379` | - |
| rabbitmq | `localhost:5672`, 관리 화면 `:15672` | `rabbitmq:5672` | app / app |

포트와 계정은 `local-hub/.env` 로 바꿀 수 있습니다 (`.env.example` 참고).
프로젝트의 컨테이너에서는 "컨테이너 안에서" 주소로 접속합니다.

## 가볍게 돌리기

`hub.sh` 는 힙 256MB, SerialGC 로 실행합니다. 서비스 몇 개를 감시하는 상태에서 메모리는 약 170MB 정도 씁니다.
실제 무거운 부분은 Docker 빌드이므로 공용 PC 사양은 팀원 서비스 크기에 맞추면 됩니다.

## 개발

```bash
cd local-hub/app
./mvnw test       # 단위 테스트
./mvnw package    # target/local-hub.jar
```
