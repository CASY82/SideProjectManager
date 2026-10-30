#!/usr/bin/env bash
# 이 저장소의 도구를 다른 프로젝트에 복사한다.
#
#   scripts/init-project.sh <대상 디렉터리> [--template] [--tasks] [--hub] [--force]
#
#   --template  프로젝트 뼈대 (README, 기여 규칙, PR 양식, CI, .gitignore 등 template/ 폴더)
#   --tasks     일정·할 일 자동화 (.github 의 이슈 양식, 라벨, 워크플로)
#   --hub       Local Hub (local-hub/)
#   하나도 고르지 않으면 셋 다 복사한다.
#   --force     대상에 같은 파일이 있어도 덮어쓴다 (기본은 건너뜀)
#
# Windows 에서는 Git Bash 로 실행하면 된다.
set -euo pipefail

usage() { sed -n '2,13p' "$0" | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }

SRC="$(cd "$(dirname "$0")/.." && pwd)"
TARGET=""
WANT_TEMPLATE=false
WANT_TASKS=false
WANT_HUB=false
FORCE=false

for arg in "$@"; do
  case "$arg" in
    --template) WANT_TEMPLATE=true ;;
    --tasks) WANT_TASKS=true ;;
    --hub) WANT_HUB=true ;;
    --force) FORCE=true ;;
    -h|--help) usage 0 ;;
    -*) echo "알 수 없는 옵션: $arg" >&2; usage 1 ;;
    *) [[ -z "$TARGET" ]] || { echo "대상 디렉터리는 하나만 지정하세요" >&2; exit 1; }; TARGET="$arg" ;;
  esac
done
[[ -n "$TARGET" ]] || usage 1
if ! $WANT_TEMPLATE && ! $WANT_TASKS && ! $WANT_HUB; then WANT_TEMPLATE=true; WANT_TASKS=true; WANT_HUB=true; fi

mkdir -p "$TARGET"
TARGET="$(cd "$TARGET" && pwd)"
[[ "$TARGET" != "$SRC" ]] || { echo "이 저장소 자신에는 복사할 수 없습니다" >&2; exit 1; }

copied=0
skipped=0

PROJECT_NAME="$(basename "$TARGET")"
TODAY="$(date +%Y-%m-%d)"

# 원본 기준 상대 경로 하나를 대상으로 복사한다. 두 번째 인자를 주면 대상에서는 그 경로로 둔다
copy_file() {
  local rel="$1" destrel="${2:-$1}"
  local dest="$TARGET/$destrel"
  if [[ -e "$dest" ]] && ! $FORCE; then
    echo "  건너뜀 (이미 있음): $destrel"
    skipped=$((skipped + 1))
    return
  fi
  mkdir -p "$(dirname "$dest")"
  cp -p "$SRC/$rel" "$dest"
  copied=$((copied + 1))
  # 템플릿의 자리표시자를 채운다
  if grep -q '{{PROJECT_NAME}}\|{{TODAY}}' "$dest" 2>/dev/null; then
    sed -i.bak -e "s/{{PROJECT_NAME}}/${PROJECT_NAME//\//\\/}/g" -e "s/{{TODAY}}/$TODAY/g" "$dest" && rm -f "$dest.bak"
  fi
}

# 디렉터리 아래 파일 전부. 실행 중에 생기는 파일(빌드 결과, 개인 설정)은 뺀다
copy_tree() {
  local dir="$1"
  while IFS= read -r -d '' file; do
    copy_file "${file#"$SRC"/}"
  done < <(find "$SRC/$dir" \
    \( -name target -o -name workspace -o -name node_modules \) -prune -o \
    -type f ! -name hub.yml ! -name .env -print0)
}

ensure_gitignore() {
  local line="$1" file="$TARGET/.gitignore"
  touch "$file"
  grep -qxF "$line" "$file" || echo "$line" >> "$file"
}

if $WANT_TEMPLATE; then
  echo "프로젝트 뼈대 복사"
  [[ -d "$SRC/template" ]] || { echo "원본에 template 이 없습니다" >&2; exit 1; }
  while IFS= read -r -d '' file; do
    rel="${file#"$SRC"/}"
    copy_file "$rel" "${rel#template/}"
  done < <(find "$SRC/template" -type f -print0)
fi

if $WANT_TASKS; then
  echo "일정·할 일 자동화 복사"
  [[ -d "$SRC/.github/scripts" ]] || { echo "원본에 .github/scripts 가 없습니다" >&2; exit 1; }
  copy_tree .github/ISSUE_TEMPLATE
  copy_tree .github/scripts
  copy_file .github/labels.json
  for wf in issue-automation due-reminders weekly-summary labels; do
    copy_file ".github/workflows/$wf.yml"
  done
fi

if $WANT_HUB; then
  echo "Local Hub 복사"
  [[ -d "$SRC/local-hub" ]] || { echo "원본에 local-hub 가 없습니다" >&2; exit 1; }
  copy_tree local-hub
  ensure_gitignore "local-hub/hub.yml"
  ensure_gitignore "local-hub/.env"
  ensure_gitignore "local-hub/workspace/"
  ensure_gitignore "target/"
fi

if $WANT_TEMPLATE && [[ ! -d "$TARGET/.git" ]]; then
  git -C "$TARGET" init -q -b main && echo "git 저장소를 새로 만들었습니다 (main 브랜치)"
fi

echo
echo "완료: ${copied}개 복사, ${skipped}개 건너뜀 → $TARGET"
echo
echo "다음 할 일"
if $WANT_TEMPLATE; then
  echo "  - README.md 의 소개·팀·기술 스택 채우기"
  echo "  - GitHub 저장소 Settings → Branches 에서 main 브랜치 보호 (PR 필수, CI 통과 필수) 켜기"
fi
if $WANT_TASKS; then
  echo "  - 푸시한 뒤 GitHub Actions 탭에서 '라벨 동기화' 워크플로를 한 번 실행"
  echo "  - (선택) Settings → Secrets 에 DISCORD_WEBHOOK_URL 또는 SLACK_WEBHOOK_URL 추가"
fi
if $WANT_HUB; then
  echo "  - local-hub/hub.sh 실행 후 hub.yml 에 감시할 저장소 등록"
fi
