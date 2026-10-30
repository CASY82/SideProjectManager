// 이슈·PR 이벤트가 올 때마다 상태/우선순위/마감 라벨을 맞춘다.
//
//   이슈 생성·수정      → 우선순위 라벨, 상태 라벨(todo / in-progress), 마감 임박·지남 라벨
//   담당자 지정·해제    → status: in-progress / status: todo
//   이슈 닫힘           → 상태·마감 라벨 제거
//   PR 에 "closes #12"  → 12번 이슈를 status: review 로, PR 이 머지 없이 닫히면 되돌림

const {
  STATUS_LABELS, PRIORITY_LABELS, DUE_LABELS,
  parseDueDate, parsePriority, todayKST, dueLabelFor, statusFor, linkedIssues,
} = require('./lib');
const { setLabelInGroup, hasLabel } = require('./github-helpers');

const INVALID_DUE_MESSAGE = (raw) =>
  `마감일 \`${raw}\` 을(를) 읽을 수 없어요. \`YYYY-MM-DD\` 형식(예: 2026-11-15)으로 고쳐 주시면 마감 알림을 보낼게요.`;

module.exports = async ({ github, context, core }) => {
  const repo = context.repo;
  if (context.eventName === 'issues') {
    await onIssue(github, repo, context.payload, core);
  } else if (context.eventName === 'pull_request' || context.eventName === 'pull_request_target') {
    await onPullRequest(github, repo, context.payload, core);
  }
};

async function onIssue(github, repo, payload, core) {
  const { action, issue } = payload;
  if (hasLabel(issue, 'weekly-summary')) return;

  if (action === 'closed') {
    await setLabelInGroup(github, repo, issue, STATUS_LABELS, null);
    await setLabelInGroup(github, repo, issue, DUE_LABELS, null);
    core.info(`#${issue.number} 닫힘: 상태·마감 라벨 제거`);
    return;
  }

  const priority = parsePriority(issue.body);
  if (priority) await setLabelInGroup(github, repo, issue, PRIORITY_LABELS, priority);

  // 리뷰 중 상태는 PR 이 정하므로, 담당자 변경이 아닌 이벤트에서는 유지한다
  const keepReview = hasLabel(issue, 'status: review') && !['assigned', 'unassigned'].includes(action);
  if (!keepReview) await setLabelInGroup(github, repo, issue, STATUS_LABELS, statusFor(issue));

  const due = parseDueDate(issue.body);
  await setLabelInGroup(github, repo, issue, DUE_LABELS, dueLabelFor(due?.date, todayKST()));

  if (due && due.date === null && shouldWarnDue(action, payload, due)) {
    await github.rest.issues.createComment({ ...repo, issue_number: issue.number, body: INVALID_DUE_MESSAGE(due.raw) });
  }
  core.info(`#${issue.number} ${action}: priority=${priority} due=${due?.date ?? due?.raw ?? '-'}`);
}

/** 형식이 틀린 마감일 안내는 처음 열 때, 또는 마감일 칸을 바꿨을 때만 한다 */
function shouldWarnDue(action, payload, due) {
  if (action === 'opened') return true;
  if (action !== 'edited' || !payload.changes || !payload.changes.body) return false;
  const before = parseDueDate(payload.changes.body.from);
  return !before || before.raw !== due.raw;
}

async function onPullRequest(github, repo, payload, core) {
  const { action, pull_request: pr } = payload;
  const numbers = linkedIssues(pr.body);
  if (numbers.length === 0) return;

  let want;
  if (['opened', 'reopened', 'ready_for_review', 'edited'].includes(action) && !pr.draft) {
    want = () => 'status: review';
  } else if (action === 'converted_to_draft' || (action === 'closed' && !pr.merged)) {
    want = statusFor;
  } else {
    // 머지되면 GitHub 가 이슈를 닫고, 닫힘 이벤트에서 라벨을 정리한다
    return;
  }

  for (const number of numbers) {
    let issue;
    try {
      ({ data: issue } = await github.rest.issues.get({ ...repo, issue_number: number }));
    } catch (e) {
      if (e.status === 404) continue;
      throw e;
    }
    if (issue.pull_request || issue.state !== 'open') continue;
    await setLabelInGroup(github, repo, issue, STATUS_LABELS, want(issue));
    core.info(`PR #${pr.number} ${action} → #${number} ${want(issue)}`);
  }
}
