// 매일 아침 열린 이슈의 마감일을 확인해 라벨을 고치고, 마감이 지난 이슈에는 담당자를 불러 한 번 알린다.
// DISCORD_WEBHOOK_URL / SLACK_WEBHOOK_URL 시크릿이 있으면 요약을 채팅방으로도 보낸다.

const { DUE_LABELS, parseDueDate, todayKST, dueLabelFor, formatReminder, notify } = require('./lib');
const { setLabelInGroup, listOpenIssues } = require('./github-helpers');

module.exports = async ({ github, context, core }) => {
  const repo = context.repo;
  const today = process.env.TODAY || todayKST();
  if (!/^\d{4}-\d{2}-\d{2}$/.test(today) || Number.isNaN(Date.parse(`${today}T00:00:00Z`))) {
    // 잘못된 날짜로 돌리면 마감 라벨이 모두 지워지므로 멈춘다
    throw new Error(`기준 날짜는 YYYY-MM-DD 형식이어야 합니다: ${today}`);
  }
  const issues = await listOpenIssues(github, repo);

  const withDue = [];
  for (const issue of issues) {
    const due = parseDueDate(issue.body)?.date;
    const { add } = await setLabelInGroup(github, repo, issue, DUE_LABELS, dueLabelFor(due, today));
    if (!due) continue;
    withDue.push({ issue, due });
    if (add.includes('overdue')) {
      const mention = issue.assignees.length ? issue.assignees.map((a) => `@${a.login}`).join(' ') + ' ' : '';
      await github.rest.issues.createComment({
        ...repo,
        issue_number: issue.number,
        body: `${mention}마감일(${due})이 지났어요. 일정을 바꿔야 하면 본문의 마감일을 고쳐 주세요.`,
      });
    }
  }

  const text = formatReminder(withDue, today);
  core.info(text ?? `${today}: 알릴 마감 이슈 없음`);
  if (text) {
    await core.summary.addRaw(text).write();
    const url = `${context.serverUrl}/${repo.owner}/${repo.repo}/issues?q=is%3Aopen+label%3Aoverdue%2Cdue-soon`;
    const sent = await notify(`${text}\n\n${url}`);
    if (sent.length) core.info(`알림 전송: ${sent.join(', ')}`);
  }
};
