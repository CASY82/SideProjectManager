# Local Hub

클라우드 없이 팀 공용 PC(또는 집 서버) 한 대에서 **공통 서비스(DB 등)와 팀원들의 서비스를 함께 띄우고**,
각 저장소에 새 커밋이 올라오면 **자동으로 다시 빌드·재기동**하는 도구입니다.

```
 팀원 A ─push─▶ GitHub (api)  ┐
 팀원 B ─push─▶ GitHub (web)  ┤   git fetch (폴링)
                              ▼
 ┌──────────── 공용 PC ─────────────────────────────────────┐
 │  Local Hub (Spring Boot, :8090 대시보드)                  │
 │    새 커밋 발견 → docker compose up -d --build <서비스>    │
 │    실패하면 이전 커밋으로 되돌려 다시 기동                  │
 │                                                          │
 │  Docker Compose 프로젝트 "sidehub"                        │
 │    postgres  redis  mysql  rabbitmq  mailpit  (공통)     │
 │    api  web  ...                            (팀원 서비스) │
 └──────────────────────────────────────────────────────────┘
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

1. 처음 실행하면 `hub.example.yml` 이 `hub.yml` 로 복사됩니다.
2. `hub.yml` 의 `services` 에 감시할 저장소를 적습니다.
3. `docker-compose.apps.yml` 에 같은 이름의 서비스를 추가합니다. `build: ./workspace/<이름>` 으로 두면 됩니다.
4. 다시 실행하고 `http://<공용PC IP>:8090` 에서 상태를 확인합니다.

```yaml
# hub.yml
hub:
  infra-profiles: [postgres, redis]
  poll-interval: 60s
  services:
    - name: api
      repo: https://github.com/your-team/api.git
      branch: main
```

```yaml
# docker-compose.apps.yml
services:
  api:
    build: ./workspace/api
    ports: ["8080:8080"]
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/app
```

각 서비스 저장소의 루트에는 `Dockerfile` 이 있어야 합니다.

## 동작 방식

| 상황 | Local Hub 가 하는 일 |
| --- | --- |
| 처음 시작 | `infra-profiles` 의 공통 서비스를 올리고, 각 저장소를 `workspace/` 에 clone 한 뒤 빌드·기동 |
| 새 커밋 발견 | 해당 서비스만 `docker compose up -d --build --no-deps --wait` 로 다시 기동 |
| 빌드나 기동 실패 | 이전 커밋으로 되돌려 다시 기동하고 상태를 `ROLLED_BACK` 으로 표시 |
| 실패한 커밋 그대로 | 매 주기마다 다시 시도하지 않음. 새 커밋이 올라오거나 "다시 배포" 를 누르면 재시도 |
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
| 배포 실패·롤백 | `[Local Hub] <서비스> 배포 실패` 이슈 생성. 실패한 커밋, 상태, 빌드 출력 끝부분, 커밋 작성자 멘션 |
| 이슈가 열린 채로 다른 커밋도 실패 | 같은 이슈에 댓글 추가 (서비스마다 이슈는 하나) |
| 같은 실패가 계속됨 | 아무것도 하지 않음 (매 주기마다 댓글이 쌓이지 않음) |
| 다음 배포 성공 | "정상으로 돌아왔어요" 댓글을 남기고 닫음 |

- 이슈는 `local-hub`, `type: bug` 라벨로 올라가므로 일정 자동화(이슈 정리, 주간 요약)에도 그대로 잡힙니다.
- 올릴 저장소는 `repo` 주소(`github.com/owner/repo`)에서 알아냅니다. 다른 저장소에 모으고 싶거나 GitHub 이 아닌 주소라면 서비스마다 `issue-repo: owner/repo` 를 적으세요.
- 토큰: GitHub **Settings → Developer settings → Fine-grained tokens** 에서 대상 저장소를 고르고 **Issues: Read and write**, **Contents: Read-only** 권한으로 만듭니다. Contents 권한은 커밋 작성자를 찾는 데만 씁니다.
- GitHub 에 연결할 수 없어도 배포는 그대로 진행되고, 다음 실패 때 다시 시도합니다.

## 대시보드와 API

`http://<공용PC>:8090`

- 서비스별 상태, 배포된 커밋, 실패한 커밋, 빌드 출력, 컨테이너 로그
- "지금 확인": 주기를 기다리지 않고 바로 확인
- "다시 배포": 커밋이 그대로여도 다시 빌드

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| GET | `/api/services` | 전체 서비스 상태 |
| POST | `/api/services/{name}/deploy?force=false` | 즉시 확인·배포 (비동기) |
| GET | `/api/services/{name}/logs?tail=200` | 컨테이너 로그 |
| GET | `/api/compose/ps` | 컨테이너 목록 (공통 서비스 포함) |

`hub.api-token` 을 설정하면 POST 요청에 `X-Hub-Token` 헤더가 필요합니다.
같은 네트워크에 모르는 사람이 있다면 꼭 설정하세요.

### 폴링 대신 즉시 배포하고 싶다면

공용 PC 는 보통 외부에서 접속할 수 없어서 GitHub 웹훅을 직접 받을 수 없습니다. 그래서 기본은 폴링입니다.
더 빠르게 반영하고 싶으면 `poll-interval` 을 `15s` 정도로 줄이거나,
같은 네트워크에서 `curl -X POST http://<공용PC>:8090/api/services/api/deploy` 를 호출하면 됩니다.

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
| mailpit | SMTP `localhost:1025`, 웹 `:8025` | `mailpit:1025` | - |

포트와 계정은 `local-hub/.env` 로 바꿀 수 있습니다 (`.env.example` 참고).

## 가볍게 돌리기

`hub.sh` 는 힙 256MB, SerialGC 로 실행합니다. 서비스 몇 개를 감시하는 상태에서 메모리는 약 170MB 정도 씁니다.
실제 무거운 부분은 Docker 빌드이므로 공용 PC 사양은 팀원 서비스 크기에 맞추면 됩니다.

## 개발

```bash
cd local-hub/app
./mvnw test       # 단위 테스트
./mvnw package    # target/local-hub.jar
```
