// 이슈·PR 이벤트가 올 때마다 상태/우선순위/마감 라벨을 맞춘다.
//
//   이슈 생성·수정      → 우선순위 라벨, 상태 라벨(todo / in-progress), 마감 임박·지남 라벨
//   담당자 지정·해제    → status: in-progress / status: todo
//   이슈 닫힘           → 상태·마감 라벨 제거
//   PR 에 "closes #12"  → 12번 이슈를 status: review 로, PR 이 머지 없이 닫히면 되돌림
//   "관련 기획: #5"      → 5번 기획 이슈의 하위 이슈로 연결, 연결된 할 일이 모두 닫히면 기획 작성자에게 확인 요청

const {
  STATUS_LABELS, PRIORITY_LABELS, DUE_LABELS,
  parseDueDate, parsePriority, todayKST, dueLabelFor, statusFor, linkedIssues,
  parsePlanRef, isPlanning, planProgress,
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
    if (issue.state_reason !== 'not_planned') await checkPlanDone(github, repo, issue, core);
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
  if (['opened', 'edited', 'reopened'].includes(action)) await linkToPlan(github, repo, payload, core);
  core.info(`#${issue.number} ${action}: priority=${priority} due=${due?.date ?? due?.raw ?? '-'}`);
}

/** "관련 기획" 이 적힌 이슈를 그 기획의 하위 이슈(sub-issue)로 연결한다. 기획 번호를 바꾸면 옮긴다. */
async function linkToPlan(github, repo, payload, core) {
  const { issue, action } = payload;
  if (isPlanning(issue)) return;
  const ref = parsePlanRef(issue.body);
  const before = action === 'edited' && payload.changes?.body ? parsePlanRef(payload.changes.body.from) : undefined;
  if (action === 'edited' && before === ref) return;

  if (before && before !== ref) {
    try {
      await github.request('DELETE /repos/{owner}/{repo}/issues/{issue_number}/sub_issue',
        { ...repo, issue_number: before, sub_issue_id: issue.id });
    } catch (e) {
      core.info(`#${before} 에서 하위 이슈 해제 실패 (${e.status}), 계속 진행`);
    }
  }
  if (!ref || ref === issue.number) return;

  let plan;
  try {
    ({ data: plan } = await github.rest.issues.get({ ...repo, issue_number: ref }));
  } catch (e) {
    if (e.status !== 404) throw e;
    plan = null;
  }
  if (!plan || plan.pull_request) {
    await github.rest.issues.createComment({ ...repo, issue_number: issue.number,
      body: `관련 기획 #${ref} 을(를) 찾을 수 없어요. 기획 이슈 번호를 확인해 주세요.` });
    return;
  }
  try {
    await github.request('POST /repos/{owner}/{repo}/issues/{issue_number}/sub_issues',
      { ...repo, issue_number: ref, sub_issue_id: issue.id });
    core.info(`#${issue.number} → 기획 #${ref} 하위 이슈로 연결`);
  } catch (e) {
    // 이미 연결돼 있으면 422. 하위 이슈 기능을 쓸 수 없는 환경이면 기획에 댓글로 남긴다
    if (e.status === 422) return;
    core.info(`하위 이슈 연결 실패 (${e.status}), 댓글로 대신함`);
    await github.rest.issues.createComment({ ...repo, issue_number: ref,
      body: `할 일 #${issue.number} ${issue.title} 이(가) 이 기획에 연결됐어요.` });
  }
}

/** 기획에 연결된 할 일이 모두 닫혔으면 기획 작성자에게 확인을 요청한다 */
async function checkPlanDone(github, repo, issue, core) {
  if (isPlanning(issue)) return;
  const ref = parsePlanRef(issue.body);
  if (!ref) return;
  let plan;
  try {
    ({ data: plan } = await github.rest.issues.get({ ...repo, issue_number: ref }));
  } catch (e) {
    if (e.status === 404) return;
    throw e;
  }
  if (plan.state !== 'open' || plan.pull_request) return;

  const all = await github.paginate(github.rest.issues.listForRepo, { ...repo, state: 'all', per_page: 100 });
  const [{ done, total, open }] = planProgress([plan], all.map((i) => (i.number === issue.number ? issue : i)));
  if (total === 0 || open.length > 0) {
    core.info(`기획 #${ref}: ${done}/${total} 완료`);
    return;
  }
  const author = plan.user?.login ? `@${plan.user.login} ` : '';
  await github.rest.issues.createComment({ ...repo, issue_number: ref,
    body: `${author}이 기획에 연결된 할 일 ${total}개가 모두 끝났어요. 기획 의도와 성공 기준대로 됐는지 확인하고, 괜찮으면 이 기획 이슈를 닫아 주세요.` });
  core.info(`기획 #${ref}: 모든 할 일 완료, 작성자에게 확인 요청`);
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
