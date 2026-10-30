// triage / reminders / weekly 를 가짜 GitHub API 로 돌려 본다.
const test = require('node:test');
const assert = require('node:assert/strict');

const triage = require('./triage');
const reminders = require('./reminders');
const weekly = require('./weekly');

/** 이슈 저장소를 메모리에 두고 actions/github-script 의 github 객체 일부를 흉내 낸다 */
function fakeGitHub(issues, { subIssues = true } = {}) {
  const byNumber = new Map(issues.map((i) => [i.number, i]));
  const comments = [];
  const created = [];
  const parents = new Map(); // 하위 이슈 번호 → 기획 번호
  const names = (i) => i.labels.map((l) => l.name);
  const byId = (id) => [...byNumber.values()].find((i) => i.id === id);
  const github = {
    paginate: async (fn, params) => fn(params).then((r) => r.data),
    request: async (route, { issue_number, sub_issue_id }) => {
      if (!subIssues) throw Object.assign(new Error('nf'), { status: 404 });
      const child = byId(sub_issue_id).number;
      if (route.startsWith('POST')) {
        if (parents.has(child)) throw Object.assign(new Error('dup'), { status: 422 });
        parents.set(child, issue_number);
      } else if (parents.get(child) === issue_number) {
        parents.delete(child);
      }
      return { data: {} };
    },
    rest: {
      issues: {
        get: async ({ issue_number }) => {
          if (!byNumber.has(issue_number)) throw Object.assign(new Error('nf'), { status: 404 });
          return { data: structuredClone(byNumber.get(issue_number)) };
        },
        listForRepo: async ({ state, labels }) => ({
          data: [...byNumber.values()]
            .filter((i) => (state === 'all' || i.state === state) && (!labels || names(i).includes(labels)))
            .map((i) => structuredClone(i)),
        }),
        addLabels: async ({ issue_number, labels }) => {
          const i = byNumber.get(issue_number);
          for (const name of labels) if (!names(i).includes(name)) i.labels.push({ name });
        },
        removeLabel: async ({ issue_number, name }) => {
          const i = byNumber.get(issue_number);
          if (!names(i).includes(name)) throw Object.assign(new Error('nf'), { status: 404 });
          i.labels = i.labels.filter((l) => l.name !== name);
        },
        createComment: async ({ issue_number, body }) => { comments.push({ issue_number, body }); },
        update: async ({ issue_number, state }) => { byNumber.get(issue_number).state = state; },
        create: async ({ title, body, labels }) => {
          const number = Math.max(...byNumber.keys()) + 1;
          const issue = { number, title, body, state: 'open', labels: labels.map((name) => ({ name })), assignees: [] };
          byNumber.set(number, issue);
          created.push(issue);
          return { data: { number, html_url: `https://github.com/o/r/issues/${number}` } };
        },
      },
    },
  };
  return { github, comments, created, parents, labelsOf: (n) => names(byNumber.get(n)).sort(), get: (n) => byNumber.get(n) };
}

const core = { info() {}, summary: { addRaw() { return this; }, async write() {} } };
const ctx = (eventName, payload) => ({
  eventName, payload, repo: { owner: 'o', repo: 'r' }, serverUrl: 'https://github.com',
});
const issue = (number, extra = {}) => ({
  id: 1000 + number, number, title: `일 ${number}`, body: '', state: 'open', labels: [], assignees: [],
  user: { login: 'writer' }, ...extra,
});
const plan = (number, extra = {}) =>
  issue(number, { title: `기획 ${number}`, labels: [{ name: 'type: planning' }], user: { login: 'planner' }, ...extra });
const task = (number, planNumber, extra = {}) =>
  issue(number, { body: `### 내용\n\n개발\n\n### 관련 기획\n\n#${planNumber}\n\n### 마감일\n\n_No response_`, ...extra });
const form = (due, priority = 'P1 - 높음') => `### 내용\n\n설명\n\n### 마감일\n\n${due}\n\n### 우선순위\n\n${priority}`;
const farFuture = '2099-12-31';

test('새 이슈에 우선순위·상태 라벨을 붙인다', async () => {
  const i = issue(1, { body: form(farFuture), labels: [{ name: 'type: task' }] });
  const gh = fakeGitHub([i]);
  await triage({ github: gh.github, context: ctx('issues', { action: 'opened', issue: structuredClone(i) }), core });
  assert.deepEqual(gh.labelsOf(1), ['priority: P1', 'status: todo', 'type: task']);
  assert.equal(gh.comments.length, 0);
});

test('마감일 형식이 틀리면 처음 열 때 한 번 안내한다', async () => {
  const i = issue(1, { body: form('금요일') });
  const gh = fakeGitHub([i]);
  await triage({ github: gh.github, context: ctx('issues', { action: 'opened', issue: structuredClone(i) }), core });
  assert.equal(gh.comments.length, 1);
  assert.match(gh.comments[0].body, /`금요일`/);

  // 마감일과 상관없는 수정에서는 다시 안내하지 않는다
  const edited = { action: 'edited', issue: structuredClone(gh.get(1)), changes: { body: { from: form('금요일', 'P2 - 보통') } } };
  await triage({ github: gh.github, context: ctx('issues', edited), core });
  assert.equal(gh.comments.length, 1);
});

test('담당자를 지정하면 진행 중, 닫으면 상태·마감 라벨을 뗀다', async () => {
  const i = issue(1, { labels: [{ name: 'status: todo' }, { name: 'overdue' }], assignees: [{ login: 'kim' }] });
  const gh = fakeGitHub([i]);
  await triage({ github: gh.github, context: ctx('issues', { action: 'assigned', issue: structuredClone(i) }), core });
  assert.deepEqual(gh.labelsOf(1), ['status: in-progress']);

  await triage({ github: gh.github, context: ctx('issues', { action: 'closed', issue: structuredClone(gh.get(1)) }), core });
  assert.deepEqual(gh.labelsOf(1), []);
});

test('PR 이 이슈를 닫겠다고 하면 리뷰 중, 머지 없이 닫히면 되돌린다', async () => {
  const gh = fakeGitHub([issue(1, { labels: [{ name: 'status: in-progress' }], assignees: [{ login: 'kim' }] })]);
  const pr = { number: 9, body: 'Closes #1, closes #404', draft: false, merged: false };
  await triage({ github: gh.github, context: ctx('pull_request', { action: 'opened', pull_request: pr }), core });
  assert.deepEqual(gh.labelsOf(1), ['status: review']);

  // 리뷰 중에 이슈 본문을 고쳐도 리뷰 중 상태는 유지
  await triage({ github: gh.github, context: ctx('issues', { action: 'edited', issue: structuredClone(gh.get(1)) }), core });
  assert.deepEqual(gh.labelsOf(1), ['status: review']);

  await triage({ github: gh.github, context: ctx('pull_request', { action: 'closed', pull_request: pr }), core });
  assert.deepEqual(gh.labelsOf(1), ['status: in-progress']);
});

test('매일 알림: 라벨을 갱신하고 새로 지난 이슈에만 담당자를 부른다', async () => {
  process.env.TODAY = '2026-10-30';
  try {
    const gh = fakeGitHub([
      issue(1, { body: form('2026-10-29'), assignees: [{ login: 'kim' }] }),
      issue(2, { body: form('2026-10-31'), labels: [{ name: 'overdue' }] }),
      issue(3, { body: form('2026-12-01'), labels: [{ name: 'due-soon' }] }),
      issue(4, { body: form('2026-10-01'), state: 'closed' }),
    ]);
    await reminders({ github: gh.github, context: ctx('schedule', {}), core });
    assert.deepEqual(gh.labelsOf(1), ['overdue']);
    assert.deepEqual(gh.labelsOf(2), ['due-soon']);
    assert.deepEqual(gh.labelsOf(3), []);
    assert.deepEqual(gh.labelsOf(4), []);
    assert.deepEqual(gh.comments.map((c) => c.issue_number), [1]);
    assert.match(gh.comments[0].body, /^@kim 마감일\(2026-10-29\)이 지났어요/);

    // 다음 날 다시 돌아도 같은 이슈에 또 댓글을 달지 않는다
    await reminders({ github: gh.github, context: ctx('schedule', {}), core });
    assert.equal(gh.comments.length, 1);
  } finally {
    delete process.env.TODAY;
  }
});

test('주간 요약: 새 요약 이슈를 만들고 지난 요약은 닫는다', async () => {
  process.env.TODAY = '2026-11-02';
  try {
    const gh = fakeGitHub([
      issue(1, { state: 'closed', closed_at: '2026-10-30T03:00:00Z', state_reason: 'completed' }),
      issue(2, { state: 'closed', closed_at: '2026-10-01T03:00:00Z', state_reason: 'completed' }),
      issue(3, { state: 'closed', closed_at: '2026-10-31T03:00:00Z', state_reason: 'not_planned' }),
      issue(4, { assignees: [{ login: 'kim' }] }),
      issue(5, { title: '주간 요약 2026-10-26', labels: [{ name: 'weekly-summary' }] }),
    ]);
    await weekly({ github: gh.github, context: ctx('schedule', {}), core });
    assert.equal(gh.get(5).state, 'closed');
    assert.equal(gh.created.length, 1);
    const body = gh.created[0].body;
    assert.match(body, /완료 1건 · 열린 이슈 1건/);
    assert.match(body, /#1 일 1/);
    assert.doesNotMatch(body, /#2 |#3 /);
    assert.match(body, /- @kim: #4/);
  } finally {
    delete process.env.TODAY;
  }
});

test('할 일을 만들면 관련 기획의 하위 이슈로 연결하고, 기획 번호를 바꾸면 옮긴다', async () => {
  const gh = fakeGitHub([plan(1), plan(2), task(3, 1)]);
  await triage({ github: gh.github, context: ctx('issues', { action: 'opened', issue: structuredClone(gh.get(3)) }), core });
  assert.equal(gh.parents.get(3), 1);

  const before = gh.get(3).body;
  gh.get(3).body = before.replace('#1', '#2');
  const edited = { action: 'edited', issue: structuredClone(gh.get(3)), changes: { body: { from: before } } };
  await triage({ github: gh.github, context: ctx('issues', edited), core });
  assert.equal(gh.parents.get(3), 2);
  assert.equal(gh.comments.length, 0);
});

test('없는 기획 번호면 할 일에 안내 댓글', async () => {
  const gh = fakeGitHub([task(3, 99)]);
  await triage({ github: gh.github, context: ctx('issues', { action: 'opened', issue: structuredClone(gh.get(3)) }), core });
  assert.deepEqual(gh.comments.map((c) => c.issue_number), [3]);
  assert.match(gh.comments[0].body, /#99/);
});

test('하위 이슈 기능을 못 쓰면 기획에 댓글로 연결을 남긴다', async () => {
  const gh = fakeGitHub([plan(1), task(3, 1)], { subIssues: false });
  await triage({ github: gh.github, context: ctx('issues', { action: 'opened', issue: structuredClone(gh.get(3)) }), core });
  assert.deepEqual(gh.comments.map((c) => c.issue_number), [1]);
  assert.match(gh.comments[0].body, /할 일 #3/);
});

test('기획에 연결된 할 일이 모두 닫히면 기획 작성자에게 확인을 요청한다', async () => {
  const gh = fakeGitHub([plan(1), task(2, 1), task(3, 1), task(4, 1, { state: 'closed', state_reason: 'not_planned' })]);
  const close = async (n) => {
    gh.get(n).state = 'closed';
    await triage({ github: gh.github, context: ctx('issues', { action: 'closed', issue: structuredClone(gh.get(n)) }), core });
  };
  await close(2);
  assert.equal(gh.comments.length, 0);
  await close(3);
  assert.deepEqual(gh.comments.map((c) => c.issue_number), [1]);
  assert.match(gh.comments[0].body, /^@planner 이 기획에 연결된 할 일 3개가 모두 끝났어요/);
});

test('주간 요약에 기획 진행률이 들어가고 기획은 담당자별 목록에서 빠진다', async () => {
  process.env.TODAY = '2026-11-02';
  try {
    const gh = fakeGitHub([
      plan(1, { body: '### 기획 의도\n\n왜\n\n### 마감일\n\n2026-11-30', assignees: [{ login: 'pm' }] }),
      task(2, 1, { state: 'closed', closed_at: '2026-10-01T00:00:00Z', state_reason: 'completed' }),
      task(3, 1, { assignees: [{ login: 'kim' }] }),
      plan(4),
    ]);
    await weekly({ github: gh.github, context: ctx('schedule', {}), core });
    const body = gh.created[0].body;
    assert.match(body, /### 기획 진행률\n- #1 기획 1: 1\/2 완료 · 마감 2026-11-30\n- #4 기획 4: 연결된 할 일 없음/);
    assert.match(body, /- @kim: #3/);
    assert.doesNotMatch(body, /@pm/);
  } finally {
    delete process.env.TODAY;
  }
});
