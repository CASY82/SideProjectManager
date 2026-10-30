// 이슈 자동화에서 쓰는 순수 함수 모음. GitHub API 를 부르지 않으므로 node --test 로 바로 테스트할 수 있다.

const STATUS_LABELS = ['status: todo', 'status: in-progress', 'status: review'];
const PRIORITY_LABELS = ['priority: P0', 'priority: P1', 'priority: P2'];
const DUE_LABELS = ['due-soon', 'overdue'];
const PLANNING_LABEL = 'type: planning';
const REVIEW_LABEL = 'status: review';
const WEEKLY_LABEL = 'weekly-summary';
const DUE_SOON_DAYS = 2;
const KST_OFFSET_MS = 9 * 60 * 60 * 1000;

/**
 * 이슈 폼 본문에서 "### 제목" 아래 값을 꺼낸다. 값이 없으면(비워 둔 칸은 "_No response_") null.
 */
function parseSection(body, heading) {
  if (!body) return null;
  const lines = body.replace(/\r\n/g, '\n').split('\n');
  const start = lines.findIndex((l) => l.trim() === `### ${heading}`);
  if (start === -1) return null;
  const value = [];
  for (const line of lines.slice(start + 1)) {
    if (line.startsWith('### ')) break;
    value.push(line);
  }
  const text = value.join('\n').trim();
  return text === '' || text === '_No response_' ? null : text;
}

/**
 * 마감일을 찾는다. 이슈 폼의 "### 마감일" 칸, 또는 일반 이슈 본문의 "마감일: 2026-11-15" / "due: 2026-11-15" 줄.
 * @returns {{raw: string, date: string|null} | null} 마감일을 적지 않았으면 null, 형식이 틀리면 date 가 null
 */
function parseDueDate(body) {
  let raw = parseSection(body, '마감일');
  if (raw === null && body) {
    const m = body.match(/^\s*(?:마감일|마감|due)\s*[:：]\s*(.+)$/im);
    raw = m ? m[1].trim() : null;
  }
  if (raw === null) return null;
  const m = raw.match(/^(\d{4})[-./](\d{1,2})[-./](\d{1,2})$/);
  if (!m) return { raw, date: null };
  const date = `${m[1]}-${m[2].padStart(2, '0')}-${m[3].padStart(2, '0')}`;
  const parsed = new Date(`${date}T00:00:00Z`);
  if (Number.isNaN(parsed.getTime()) || parsed.toISOString().slice(0, 10) !== date) {
    return { raw, date: null };
  }
  return { raw, date };
}

/** "P1 - 높음" → "priority: P1" */
function parsePriority(body) {
  const raw = parseSection(body, '우선순위');
  const m = raw && raw.match(/\bP([0-2])\b/);
  return m ? `priority: P${m[1]}` : null;
}

/**
 * 이 이슈가 속한 기획 이슈 번호. 이슈 폼의 "### 관련 기획" 칸(#5, 5, 이슈 주소 모두 가능),
 * 또는 일반 본문의 "기획: #5" / "관련 기획: #5" 줄. 없으면 null.
 */
function parsePlanRef(body) {
  let raw = parseSection(body, '관련 기획');
  if (raw === null && body) {
    const m = body.match(/^\s*(?:관련\s*)?기획\s*[:：]\s*(.+)$/m);
    raw = m ? m[1].trim() : null;
  }
  if (raw === null) return null;
  const m = raw.match(/^(?:#|.*\/issues\/)?(\d+)\b/);
  return m ? Number(m[1]) : null;
}

/** 이벤트·API 마다 라벨이 문자열이거나 {name} 객체라서 이름만 꺼낸다 */
function labelNames(labels) {
  return (labels || []).map((l) => (typeof l === 'string' ? l : l.name));
}

function hasLabel(issue, name) {
  return labelNames(issue.labels).includes(name);
}

function isPlanning(issue) {
  return hasLabel(issue, PLANNING_LABEL);
}

/**
 * 기획마다 연결된 할 일이 몇 개고 몇 개 끝났는지.
 * @param {object[]} plans 기획 이슈들
 * @param {object[]} issues 연결 여부를 볼 전체 이슈 (열림·닫힘 모두)
 * @returns {{plan: object, done: number, total: number, open: object[]}[]}
 */
function planProgress(plans, issues) {
  return plans.map((plan) => {
    const linked = issues.filter((i) => !i.pull_request && i.number !== plan.number && parsePlanRef(i.body) === plan.number);
    const open = linked.filter((i) => i.state === 'open');
    return { plan, done: linked.length - open.length, total: linked.length, open };
  });
}

/** 한국 시간 기준 오늘 날짜 (YYYY-MM-DD). Actions 러너는 UTC 라서 따로 계산한다. */
function todayKST(now = new Date()) {
  return new Date(now.getTime() + KST_OFFSET_MS).toISOString().slice(0, 10);
}

/**
 * 알림·요약의 기준 날짜. 워크플로를 손으로 돌릴 때 TODAY 로 바꿀 수 있고, 없으면 한국 시간 오늘.
 * 잘못된 날짜로 돌리면 마감 라벨이 모두 지워지므로 형식이 틀리면 멈춘다.
 */
function resolveToday(override = process.env.TODAY) {
  const today = override || todayKST();
  if (!/^\d{4}-\d{2}-\d{2}$/.test(today) || Number.isNaN(Date.parse(`${today}T00:00:00Z`))) {
    throw new Error(`기준 날짜는 YYYY-MM-DD 형식이어야 합니다: ${today}`);
  }
  return today;
}

function daysBetween(from, to) {
  return Math.round((Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / 86400000);
}

/** 마감일 기준으로 붙어야 할 라벨. 여유가 있거나 마감일이 없으면 null. */
function dueLabelFor(dueDate, today) {
  if (!dueDate) return null;
  const left = daysBetween(today, dueDate);
  if (left < 0) return 'overdue';
  if (left <= DUE_SOON_DAYS) return 'due-soon';
  return null;
}

/** 열린 이슈의 상태 라벨. 담당자가 있으면 진행 중, 없으면 할 일. 리뷰 중은 PR 이벤트에서만 붙인다. */
function statusFor(issue) {
  return issue.assignees && issue.assignees.length > 0 ? 'status: in-progress' : 'status: todo';
}

/** PR 본문에서 "closes #12", "fixes #3", "resolves #7" 로 연결된 이슈 번호들 */
function linkedIssues(prBody) {
  if (!prBody) return [];
  const re = /\b(?:close[sd]?|fix(?:e[sd])?|resolve[sd]?)\s*:?\s+#(\d+)/gi;
  return [...new Set([...prBody.matchAll(re)].map((m) => Number(m[1])))];
}

/**
 * 현재 라벨 목록에서 group 에 속한 라벨을 want 하나로 맞추려면 무엇을 붙이고 뗄지 계산한다.
 * @returns {{add: string[], remove: string[]}}
 */
function diffLabels(current, group, want) {
  const names = labelNames(current);
  const remove = names.filter((n) => group.includes(n) && n !== want);
  const add = want && !names.includes(want) ? [want] : [];
  return { add, remove };
}

/** 담당자 멘션 목록 (["@kim", "@lee"]). 담당자가 없으면 빈 배열 */
function mentions(issue) {
  return (issue.assignees || []).map((a) => `@${a.login}`);
}

function who(issue) {
  const names = mentions(issue);
  return names.length ? names.join(' ') : '담당자 없음';
}

function line(issue, extra) {
  return `- #${issue.number} ${issue.title} (${who(issue)})${extra ? ` · ${extra}` : ''}`;
}

/**
 * 매일 보내는 마감 알림 본문. 알릴 것이 없으면 null.
 * @param {{issue: object, due: string}[]} items 마감일이 있는 열린 이슈들
 */
function formatReminder(items, today) {
  const withLeft = items
    .map(({ issue, due }) => ({ issue, due, left: daysBetween(today, due), label: dueLabelFor(due, today) }))
    .sort((a, b) => a.left - b.left);
  const overdue = withLeft.filter((x) => x.label === 'overdue');
  const soon = withLeft.filter((x) => x.label === 'due-soon');
  if (overdue.length === 0 && soon.length === 0) return null;
  const out = [`**마감 알림 (${today})**`];
  if (overdue.length) {
    out.push('', `마감 지남 ${overdue.length}건`);
    for (const o of overdue) out.push(line(o.issue, `${o.due}, ${-o.left}일 지남`));
  }
  if (soon.length) {
    out.push('', `마감 임박 ${soon.length}건`);
    for (const s of soon) out.push(line(s.issue, s.left === 0 ? `${s.due}, 오늘 마감` : `${s.due}, ${s.left}일 남음`));
  }
  return out.join('\n');
}

/**
 * 주간 요약 본문.
 * @param {{closed: object[], open: object[], today: string, weekAgo: string, plans?: object[]}} data
 *   plans 는 planProgress 의 결과 (열린 기획만)
 */
function formatWeekly({ closed, open, today, weekAgo, plans = [] }) {
  return [
    `## 주간 요약 (${weekAgo} ~ ${today})`,
    '',
    `완료 ${closed.length}건 · 열린 이슈 ${open.length}건`,
    '',
    ...section('이번 주 완료', closed.map((i) => line(i)), '- 없음'),
    '',
    ...section('마감 지남', overdueLines(open, today), '- 없음'),
    '',
    ...section('기획 진행률', plans.map(planLine), '- 진행 중인 기획 없음'),
    '',
    ...section('담당자별 진행 중', personLines(open.filter((i) => !isPlanning(i))), '- 열린 이슈 없음'),
  ].join('\n');
}

function section(title, lines, empty) {
  return [`### ${title}`, ...(lines.length ? lines : [empty])];
}

function overdueLines(open, today) {
  return open
    .map((i) => ({ issue: i, due: parseDueDate(i.body)?.date }))
    .filter(({ due }) => dueLabelFor(due, today) === 'overdue')
    .map(({ issue, due }) => line(issue, `마감 ${due}`));
}

function planLine({ plan, done, total }) {
  const due = parseDueDate(plan.body)?.date;
  const progress = total === 0 ? '연결된 할 일 없음' : `${done}/${total} 완료`;
  return `- #${plan.number} ${plan.title}: ${progress}${due ? ` · 마감 ${due}` : ''}`;
}

/** 담당자마다 맡은 이슈 번호. 여러 명이 맡은 이슈는 각자에게 나온다 */
function personLines(issues) {
  const byPerson = new Map();
  for (const i of issues) {
    const names = mentions(i);
    for (const n of names.length ? names : ['담당자 없음']) {
      if (!byPerson.has(n)) byPerson.set(n, []);
      byPerson.get(n).push(i);
    }
  }
  return [...byPerson.entries()].sort()
    .map(([person, list]) => `- ${person}: ${list.map((i) => `#${i.number}`).join(', ')}`);
}

module.exports = {
  STATUS_LABELS,
  PRIORITY_LABELS,
  DUE_LABELS,
  PLANNING_LABEL,
  REVIEW_LABEL,
  WEEKLY_LABEL,
  labelNames,
  hasLabel,
  mentions,
  parseSection,
  parsePlanRef,
  isPlanning,
  planProgress,
  parseDueDate,
  parsePriority,
  todayKST,
  resolveToday,
  daysBetween,
  dueLabelFor,
  statusFor,
  linkedIssues,
  diffLabels,
  formatReminder,
  formatWeekly,
};
