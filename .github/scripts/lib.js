// 이슈 자동화에서 쓰는 순수 함수 모음. GitHub API 를 부르지 않으므로 node --test 로 바로 테스트할 수 있다.

const STATUS_LABELS = ['status: todo', 'status: in-progress', 'status: review'];
const PRIORITY_LABELS = ['priority: P0', 'priority: P1', 'priority: P2'];
const DUE_LABELS = ['due-soon', 'overdue'];
const PLANNING_LABEL = 'type: planning';
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

function isPlanning(issue) {
  return (issue.labels || []).some((l) => (typeof l === 'string' ? l : l.name) === PLANNING_LABEL);
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
  const names = current.map((l) => (typeof l === 'string' ? l : l.name));
  const remove = names.filter((n) => group.includes(n) && n !== want);
  const add = want && !names.includes(want) ? [want] : [];
  return { add, remove };
}

function who(issue) {
  if (!issue.assignees || issue.assignees.length === 0) return '담당자 없음';
  return issue.assignees.map((a) => `@${a.login}`).join(' ');
}

function line(issue, extra) {
  return `- #${issue.number} ${issue.title} (${who(issue)})${extra ? ` · ${extra}` : ''}`;
}

/**
 * 매일 보내는 마감 알림 본문. 알릴 것이 없으면 null.
 * @param {{issue: object, due: string}[]} items 마감일이 있는 열린 이슈들
 */
function formatReminder(items, today) {
  const overdue = [];
  const soon = [];
  for (const { issue, due } of items) {
    const left = daysBetween(today, due);
    if (left < 0) overdue.push({ issue, due, left });
    else if (left <= DUE_SOON_DAYS) soon.push({ issue, due, left });
  }
  if (overdue.length === 0 && soon.length === 0) return null;
  overdue.sort((a, b) => a.left - b.left);
  soon.sort((a, b) => a.left - b.left);
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
  const out = [`## 주간 요약 (${weekAgo} ~ ${today})`, ''];
  out.push(`완료 ${closed.length}건 · 열린 이슈 ${open.length}건`, '');

  out.push('### 이번 주 완료');
  if (closed.length === 0) out.push('- 없음');
  for (const i of closed) out.push(line(i));
  out.push('');

  const overdue = open.filter((i) => dueLabelFor(parseDueDate(i.body)?.date, today) === 'overdue');
  out.push('### 마감 지남');
  if (overdue.length === 0) out.push('- 없음');
  for (const i of overdue) out.push(line(i, `마감 ${parseDueDate(i.body).date}`));
  out.push('');

  out.push('### 기획 진행률');
  if (plans.length === 0) out.push('- 진행 중인 기획 없음');
  for (const { plan, done, total } of plans) {
    const due = parseDueDate(plan.body)?.date;
    const progress = total === 0 ? '연결된 할 일 없음' : `${done}/${total} 완료`;
    out.push(`- #${plan.number} ${plan.title}: ${progress}${due ? ` · 마감 ${due}` : ''}`);
  }
  out.push('');

  out.push('### 담당자별 진행 중');
  const byPerson = new Map();
  for (const i of open.filter((x) => !isPlanning(x))) {
    const names = i.assignees && i.assignees.length ? i.assignees.map((a) => `@${a.login}`) : ['담당자 없음'];
    for (const n of names) {
      if (!byPerson.has(n)) byPerson.set(n, []);
      byPerson.get(n).push(i);
    }
  }
  if (byPerson.size === 0) out.push('- 열린 이슈 없음');
  for (const [person, issues] of [...byPerson.entries()].sort()) {
    out.push(`- ${person}: ${issues.map((i) => `#${i.number}`).join(', ')}`);
  }
  return out.join('\n');
}

/**
 * DISCORD_WEBHOOK_URL / SLACK_WEBHOOK_URL 이 설정돼 있으면 메시지를 보낸다. 둘 다 없으면 아무 것도 하지 않는다.
 * @returns {Promise<string[]>} 보낸 곳 목록
 */
async function notify(text, env = process.env, fetchImpl = globalThis.fetch) {
  const sent = [];
  if (env.DISCORD_WEBHOOK_URL) {
    // 디스코드는 메시지 하나에 2000자까지
    const content = text.length > 1990 ? `${text.slice(0, 1980)}\n…` : text;
    const res = await fetchImpl(env.DISCORD_WEBHOOK_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ content, allowed_mentions: { parse: [] } }),
    });
    if (!res.ok) throw new Error(`Discord 웹훅 실패: ${res.status}`);
    sent.push('discord');
  }
  if (env.SLACK_WEBHOOK_URL) {
    const res = await fetchImpl(env.SLACK_WEBHOOK_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      // 이슈 제목에 <!channel> 같은 문구가 있어도 전체 알림이 가지 않도록 Slack 제어 문자를 이스케이프한다
      body: JSON.stringify({ text: text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;') }),
    });
    if (!res.ok) throw new Error(`Slack 웹훅 실패: ${res.status}`);
    sent.push('slack');
  }
  return sent;
}

module.exports = {
  STATUS_LABELS,
  PRIORITY_LABELS,
  DUE_LABELS,
  PLANNING_LABEL,
  parseSection,
  parsePlanRef,
  isPlanning,
  planProgress,
  parseDueDate,
  parsePriority,
  todayKST,
  daysBetween,
  dueLabelFor,
  statusFor,
  linkedIssues,
  diffLabels,
  formatReminder,
  formatWeekly,
  notify,
};
