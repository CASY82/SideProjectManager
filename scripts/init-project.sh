#!/usr/bin/env bash
# 이 저장소의 도구를 다른 프로젝트에 복사한다.
#
#   scripts/init-project.sh <대상 디렉터리> [--tasks] [--hub] [--force]
#
#   --tasks  일정·할 일 자동화 (.github 의 이슈 양식, 라벨, 워크플로)
#   --hub    Local Hub (local-hub/)
#   둘 다 생략하면 둘 다 복사한다.
#   --force  대상에 같은 파일이 있어도 덮어쓴다 (기본은 건너뜀)
#
# Windows 에서는 Git Bash 로 실행하면 된다.
set -euo pipefail

usage() { sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }

SRC="$(cd "$(dirname "$0")/.." && pwd)"
TARGET=""
WANT_TASKS=false
WANT_HUB=false
FORCE=false

for arg in "$@"; do
  case "$arg" in
    --tasks) WANT_TASKS=true ;;
    --hub) WANT_HUB=true ;;
    --force) FORCE=true ;;
    -h|--help) usage 0 ;;
    -*) echo "알 수 없는 옵션: $arg" >&2; usage 1 ;;
    *) [[ -z "$TARGET" ]] || { echo "대상 디렉터리는 하나만 지정하세요" >&2; exit 1; }; TARGET="$arg" ;;
  esac
done
[[ -n "$TARGET" ]] || usage 1
if ! $WANT_TASKS && ! $WANT_HUB; then WANT_TASKS=true; WANT_HUB=true; fi

mkdir -p "$TARGET"
TARGET="$(cd "$TARGET" && pwd)"
[[ "$TARGET" != "$SRC" ]] || { echo "이 저장소 자신에는 복사할 수 없습니다" >&2; exit 1; }

copied=0
skipped=0

# 원본 기준 상대 경로 하나를 대상으로 복사한다
copy_file() {
  local rel="$1" dest="$TARGET/$1"
  if [[ -e "$dest" ]] && ! $FORCE; then
    echo "  건너뜀 (이미 있음): $rel"
    skipped=$((skipped + 1))
    return
  fi
  mkdir -p "$(dirname "$dest")"
  cp -p "$SRC/$rel" "$dest"
  copied=$((copied + 1))
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

echo
echo "완료: ${copied}개 복사, ${skipped}개 건너뜀 → $TARGET"
echo
echo "다음 할 일"
if $WANT_TASKS; then
  echo "  - 푸시한 뒤 GitHub Actions 탭에서 '라벨 동기화' 워크플로를 한 번 실행"
  echo "  - (선택) Settings → Secrets 에 DISCORD_WEBHOOK_URL 또는 SLACK_WEBHOOK_URL 추가"
fi
if $WANT_HUB; then
  echo "  - local-hub/hub.sh 실행 후 hub.yml 에 감시할 저장소 등록"
fi
