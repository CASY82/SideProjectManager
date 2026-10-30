const test = require('node:test');
const assert = require('node:assert/strict');
const lib = require('./lib');

const formBody = (due, priority = 'P2 - 보통') =>
  `### 내용\n\n로그인 API\n\n### 완료 조건\n\n_No response_\n\n### 마감일\n\n${due}\n\n### 우선순위\n\n${priority}`;

test('이슈 폼의 마감일을 읽는다', () => {
  assert.deepEqual(lib.parseDueDate(formBody('2026-11-05')), { raw: '2026-11-05', date: '2026-11-05' });
  assert.deepEqual(lib.parseDueDate(formBody('2026.11.5')), { raw: '2026.11.5', date: '2026-11-05' });
});

test('비워 둔 마감일은 null', () => {
  assert.equal(lib.parseDueDate(formBody('_No response_')), null);
  assert.equal(lib.parseDueDate('그냥 본문'), null);
  assert.equal(lib.parseDueDate(null), null);
});

test('일반 본문의 "마감일: " / "due:" 줄도 읽는다', () => {
  assert.equal(lib.parseDueDate('할 일\n마감일: 2026-12-01\n').date, '2026-12-01');
  assert.equal(lib.parseDueDate('Due: 2026-12-02').date, '2026-12-02');
});

test('형식이 틀리거나 없는 날짜는 date 가 null', () => {
  assert.deepEqual(lib.parseDueDate(formBody('다음주 금요일')), { raw: '다음주 금요일', date: null });
  assert.equal(lib.parseDueDate(formBody('2026-02-30')).date, null);
});

test('우선순위 라벨', () => {
  assert.equal(lib.parsePriority(formBody('2026-11-05', 'P0 - 긴급')), 'priority: P0');
  assert.equal(lib.parsePriority('본문'), null);
});

test('한국 시간 기준 오늘', () => {
  assert.equal(lib.todayKST(new Date('2026-10-30T14:59:00Z')), '2026-10-30');
  assert.equal(lib.todayKST(new Date('2026-10-30T15:00:00Z')), '2026-10-31');
});

test('마감 라벨', () => {
  const today = '2026-10-30';
  assert.equal(lib.dueLabelFor('2026-10-29', today), 'overdue');
  assert.equal(lib.dueLabelFor('2026-10-30', today), 'due-soon');
  assert.equal(lib.dueLabelFor('2026-11-01', today), 'due-soon');
  assert.equal(lib.dueLabelFor('2026-11-02', today), null);
  assert.equal(lib.dueLabelFor(null, today), null);
});

test('상태 라벨은 담당자 유무로 정한다', () => {
  assert.equal(lib.statusFor({ assignees: [] }), 'status: todo');
  assert.equal(lib.statusFor({ assignees: [{ login: 'a' }] }), 'status: in-progress');
});

test('PR 본문에서 연결된 이슈 번호', () => {
  assert.deepEqual(lib.linkedIssues('Closes #12, fixes #3 and resolves: #7. closes #12'), [12, 3, 7]);
  assert.deepEqual(lib.linkedIssues('see #5'), []);
});

test('라벨 그룹 맞추기', () => {
  const current = [{ name: 'status: todo' }, { name: 'type: task' }];
  assert.deepEqual(lib.diffLabels(current, lib.STATUS_LABELS, 'status: in-progress'),
    { add: ['status: in-progress'], remove: ['status: todo'] });
  assert.deepEqual(lib.diffLabels(current, lib.STATUS_LABELS, 'status: todo'), { add: [], remove: [] });
  assert.deepEqual(lib.diffLabels(current, lib.STATUS_LABELS, null), { add: [], remove: ['status: todo'] });
});

test('마감 알림 본문', () => {
  const issue = (number, login) => ({ number, title: `일 ${number}`, assignees: login ? [{ login }] : [] });
  const text = lib.formatReminder([
    { issue: issue(1, 'kim'), due: '2026-10-28' },
    { issue: issue(2), due: '2026-10-30' },
    { issue: issue(3, 'lee'), due: '2026-11-01' },
    { issue: issue(4), due: '2026-12-01' },
  ], '2026-10-30');
  assert.match(text, /마감 지남 1건\n- #1 일 1 \(@kim\) · 2026-10-28, 2일 지남/);
  assert.match(text, /마감 임박 2건\n- #2 일 2 \(담당자 없음\) · 2026-10-30, 오늘 마감\n- #3 일 3 \(@lee\) · 2026-11-01, 2일 남음/);
  assert.doesNotMatch(text, /#4/);
  assert.equal(lib.formatReminder([{ issue: issue(4), due: '2026-12-01' }], '2026-10-30'), null);
});

test('주간 요약 본문', () => {
  const text = lib.formatWeekly({
    today: '2026-11-02',
    weekAgo: '2026-10-26',
    closed: [{ number: 1, title: '끝난 일', assignees: [{ login: 'kim' }] }],
    open: [
      { number: 2, title: '늦은 일', body: '마감일: 2026-10-31', assignees: [{ login: 'kim' }] },
      { number: 3, title: '할 일', body: '', assignees: [] },
    ],
  });
  assert.match(text, /완료 1건 · 열린 이슈 2건/);
  assert.match(text, /### 마감 지남\n- #2 늦은 일 \(@kim\) · 마감 2026-10-31/);
  assert.match(text, /- @kim: #2/);
  assert.match(text, /- 담당자 없음: #3/);
});

test('웹훅이 없으면 아무 데도 보내지 않는다', async () => {
  assert.deepEqual(await lib.notify('hi', {}, () => { throw new Error('호출되면 안 됨'); }), []);
});

test('디스코드는 2000자로 자르고 멘션을 막는다', async () => {
  const calls = [];
  const fetch = async (url, opts) => { calls.push({ url, body: JSON.parse(opts.body) }); return { ok: true }; };
  const sent = await lib.notify('가'.repeat(3000), { DISCORD_WEBHOOK_URL: 'd', SLACK_WEBHOOK_URL: 's' }, fetch);
  assert.deepEqual(sent, ['discord', 'slack']);
  assert.ok(calls[0].body.content.length <= 2000);
  assert.deepEqual(calls[0].body.allowed_mentions, { parse: [] });
  assert.equal(calls[1].body.text.length, 3000);
});
