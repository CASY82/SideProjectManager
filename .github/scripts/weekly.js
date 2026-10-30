// 매주 월요일 지난 7일 동안 끝낸 일, 마감 지난 일, 기획별 진행률, 담당자별 진행 중인 일을 이슈 하나로 정리한다.
// 지난주 요약 이슈는 닫는다. 웹훅 시크릿이 있으면 채팅방으로도 보낸다.

const { todayKST, formatWeekly, notify, isPlanning, planProgress } = require('./lib');
const { hasLabel } = require('./github-helpers');

module.exports = async ({ github, context, core }) => {
  const repo = context.repo;
  const today = process.env.TODAY || todayKST();
  const weekAgoDate = new Date(Date.parse(`${today}T00:00:00+09:00`) - 7 * 86400000);
  const weekAgo = todayKST(weekAgoDate);

  // 기획 진행률을 세려면 예전에 닫힌 할 일까지 봐야 해서 전체를 한 번에 가져온다
  const all = (await github.paginate(github.rest.issues.listForRepo, { ...repo, state: 'all', per_page: 100 }))
    .filter((i) => !i.pull_request && !hasLabel(i, 'weekly-summary'));
  const closed = all.filter((i) =>
    i.state === 'closed'
    && i.state_reason !== 'not_planned'
    && Date.parse(i.closed_at) >= weekAgoDate.getTime());
  const open = all.filter((i) => i.state === 'open');
  const plans = planProgress(open.filter(isPlanning), all);

  const body = formatWeekly({ closed, open, today, weekAgo, plans });

  const previous = await github.paginate(github.rest.issues.listForRepo, {
    ...repo, state: 'open', labels: 'weekly-summary', per_page: 100,
  });
  for (const p of previous) {
    await github.rest.issues.update({ ...repo, issue_number: p.number, state: 'closed', state_reason: 'completed' });
  }

  const { data: created } = await github.rest.issues.create({
    ...repo, title: `주간 요약 ${today}`, body, labels: ['weekly-summary'],
  });
  core.info(`주간 요약 #${created.number} 생성`);
  await core.summary.addRaw(body).write();

  const sent = await notify(`${body}\n\n${created.html_url}`);
  if (sent.length) core.info(`알림 전송: ${sent.join(', ')}`);
};
