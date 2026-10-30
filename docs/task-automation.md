# 일정·할 일 자동 관리

GitHub Issues 를 할 일 목록으로 쓰고, GitHub Actions 로 라벨 정리·마감 알림·주간 요약을 자동으로 돌립니다.
Google Sheets + Apps Script 조합보다 빠르고(이벤트가 오면 수십 초 안에 반영), 코드·PR 과 바로 연결되며, 전부 무료입니다.

## 무엇이 자동으로 되나요

| 언제 | 무엇을 | 워크플로 |
| --- | --- | --- |
| 이슈를 만들거나 고칠 때 | 우선순위 라벨(`priority: P0~P2`), 상태 라벨(`status: todo`), 마감 라벨(`due-soon`, `overdue`)을 붙임. 마감일 형식이 틀리면 댓글로 안내 | `issue-automation.yml` |
| 담당자를 지정·해제할 때 | `status: in-progress` ↔ `status: todo` | `issue-automation.yml` |
| PR 본문에 `closes #12` 를 쓰면 | 12번 이슈를 `status: review` 로. PR 이 머지 없이 닫히면 원래대로 | `issue-automation.yml` |
| 이슈가 닫힐 때 | 상태·마감 라벨 정리 | `issue-automation.yml` |
| 매일 오전 9시 (KST) | 마감 라벨 갱신, 새로 마감이 지난 이슈에 담당자 멘션 댓글, Discord/Slack 으로 요약 | `due-reminders.yml` |
| 매주 월요일 오전 9시 (KST) | 지난주 완료·마감 지남·기획별 진행률·담당자별 진행 중 목록을 "주간 요약" 이슈로 만들고 지난 요약은 닫음 | `weekly-summary.yml` |
| 할 일에 "관련 기획" 을 적으면 | 그 기획 이슈의 하위 이슈로 연결 (기획 화면에 진행률 막대가 생김) | `issue-automation.yml` |
| 기획에 연결된 할 일이 모두 닫히면 | 기획 작성자를 불러 "의도대로 됐는지 확인해 주세요" 댓글 | `issue-automation.yml` |
| `labels.json` 이 바뀔 때 | 저장소 라벨 생성·갱신 | `labels.yml` |

`due-soon` 은 마감 2일 이내(오늘 포함), `overdue` 는 마감일이 지난 이슈입니다.

## 처음 설정 (5분)

1. 이 저장소의 `.github/` 를 프로젝트에 복사합니다. 이 저장소를 받아 두었다면

   ```bash
   scripts/init-project.sh ../my-project --tasks
   ```

2. 프로젝트에 푸시한 뒤 **Actions** 탭 → **라벨 동기화** → **Run workflow** 를 한 번 누릅니다.
3. (선택) 채팅방 알림을 받으려면 **Settings → Secrets and variables → Actions → New repository secret** 에
   - Discord: 채널 설정 → 연동 → 웹후크에서 만든 URL 을 `DISCORD_WEBHOOK_URL` 로
   - Slack: Incoming Webhook URL 을 `SLACK_WEBHOOK_URL` 로
4. 바로 확인하고 싶으면 **Actions → 마감 알림 → Run workflow** 를 누릅니다.

## 이슈 쓰는 법

**New issue → 할 일** (또는 버그) 양식을 쓰면 마감일·우선순위 칸이 있습니다.
양식 없이 쓴 이슈도 본문에 아래처럼 한 줄 넣으면 마감일로 인식합니다.

```
마감일: 2026-11-15
```

`2026.11.15`, `2026/11/5` 도 됩니다. 마감일을 바꾸려면 본문만 고치면 라벨이 바로 다시 계산됩니다.

## 기획자와 함께 쓰기

기획자는 코드를 몰라도 GitHub 이슈만으로 참여할 수 있습니다. 흐름은 이렇습니다.

```
기획자: 기획 이슈 작성 (#5)          개발자: 할 일 이슈 작성, "관련 기획" 에 #5
   기획 의도 · 목표와 성공 기준   ──▶     #12 로그인 API      ┐
   주요 내용 · 하지 않는 것                #13 안내 화면       ├─ #5 아래 하위 이슈로 자동 연결
   참고 자료 · 마감일                      #14 첫 글쓰기 이동  ┘
        ▲                                      │ 모두 닫히면
        └──── "@기획자 확인해 주세요" 댓글 ◀────┘
```

1. **기획자**: New issue → **기획** 양식으로 씁니다.
   - **기획 의도**: 왜 만드는지 (사용자의 불편, 기회)
   - **목표와 성공 기준**: 무엇이 되면 성공인지
   - **주요 내용**: 사용자가 할 수 있어야 하는 것, 화면 흐름과 규칙
   - **이번에 하지 않는 것**, **참고 자료**(Figma 링크, 이미지), **마감일**, **우선순위**
2. **개발자**: 기획을 읽고 일을 나눠 **할 일** 이슈를 만들 때 **관련 기획** 칸에 기획 번호(예: `#5`)를 적습니다.
   양식 없이 쓴 이슈는 본문에 `기획: #5` 한 줄을 넣어도 됩니다.
3. 기획 이슈를 열면 연결된 할 일 목록과 진행률이 보입니다. 질문은 기획 이슈 댓글로 주고받으면 기록이 한곳에 모입니다.
4. 연결된 할 일이 모두 닫히면 기획 작성자에게 확인 요청 댓글이 달립니다. 의도대로 됐으면 기획자가 기획 이슈를 닫습니다.
5. 매주 월요일 주간 요약의 **기획 진행률** 에 기획마다 `완료/전체` 가 나옵니다.

기획자에게는 저장소의 **Triage** 권한(이슈 작성·라벨·닫기 가능, 코드 수정 불가)을 주면 충분합니다.
**Settings → Collaborators → Add people** 에서 역할을 Triage 로 고르세요. 비공개 저장소에서도 무료입니다.

## 칸반 보드로 보기 (GitHub Projects, 무료)

라벨만으로도 충분하지만 보드로 보고 싶다면

1. 저장소의 **Projects → New project → Board** 로 프로젝트를 만듭니다.
2. 프로젝트 오른쪽 위 **… → Workflows** 에서
   - **Auto-add to project**: 필터를 `is:issue` 로 켜면 새 이슈가 자동으로 보드에 들어갑니다.
   - **Item closed**, **Pull request merged**: Status 를 Done 으로 (기본으로 켜져 있음)
3. 보드를 Status 대신 라벨로 묶어 보고 싶으면 보기 설정에서 **Group by → Labels** 를 고르면 됩니다.

기본 Auto-add 워크플로를 쓸 수 없는 경우(무료 플랜은 개수 제한이 있음)에는 저장소 변수 `PROJECT_URL`(프로젝트 주소)과
시크릿 `PROJECT_TOKEN`(`project` 권한이 있는 개인 토큰)을 넣으면 `issue-automation.yml` 이 대신 추가합니다.

## 비용

- 공개 저장소: Actions 무료, 제한 없음
- 비공개 저장소: 월 2,000분 무료. 이벤트 처리 한 번이 10~20초, 매일 알림과 주간 요약을 합쳐도 한 달에 수십 분 수준입니다.

## 알아 둘 점

- 예약 실행(cron)은 GitHub 사정으로 몇 분에서 수십 분 늦게 돌 수 있습니다.
- 공개 저장소에 60일 동안 커밋이 없으면 GitHub 가 예약 워크플로를 멈춥니다. Actions 탭에서 다시 켜면 됩니다.
- 포크에서 온 PR 은 권한이 읽기 전용이라 `status: review` 라벨이 붙지 않습니다. 같은 저장소 브랜치에서 올린 PR 은 정상 동작합니다.

## 고치거나 확장하기

로직은 모두 `.github/scripts/` 의 JavaScript 이고, 워크플로는 `actions/github-script` 로 이 파일들을 불러오기만 합니다.

```bash
node --test ".github/scripts/*.test.js"
```

- 마감 임박 기준(2일): `lib.js` 의 `DUE_SOON_DAYS`
- 알림 시각: 각 워크플로의 `cron` (UTC 기준이므로 KST 에서 9시간을 뺄 것)
- 라벨 이름·색: `.github/labels.json` (이름을 바꾸면 `lib.js` 의 라벨 상수도 같이)
