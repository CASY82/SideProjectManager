// GitHub API 를 부르는 공통 함수. actions/github-script 의 github 객체(Octokit)를 받는다.

const { diffLabels, hasLabel, labelNames, WEEKLY_LABEL } = require('./lib');

/** 라벨 그룹(상태, 우선순위 등) 안에서 want 하나만 남도록 맞춘다. want 가 null 이면 그룹 라벨을 모두 뗀다. */
async function setLabelInGroup(github, { owner, repo }, issue, group, want) {
  const { add, remove } = diffLabels(issue.labels || [], group, want);
  for (const name of remove) {
    try {
      await github.rest.issues.removeLabel({ owner, repo, issue_number: issue.number, name });
    } catch (e) {
      if (e.status !== 404) throw e;
    }
  }
  if (add.length) {
    await github.rest.issues.addLabels({ owner, repo, issue_number: issue.number, labels: add });
  }
  // 같은 실행 안에서 다음 그룹 계산이 최신 라벨을 보도록 메모리 상의 라벨도 고친다
  issue.labels = labelNames(issue.labels).filter((n) => !remove.includes(n)).concat(add).map((name) => ({ name }));
  return { add, remove };
}

/** 이슈 하나. 없으면 null */
async function getIssueOrNull(github, repo, number) {
  try {
    const { data } = await github.rest.issues.get({ ...repo, issue_number: number });
    return data;
  } catch (e) {
    if (e.status === 404) return null;
    throw e;
  }
}

/** 열린·닫힌 이슈 전부 (PR 포함). 기획 진행률처럼 예전에 닫힌 할 일까지 봐야 할 때 쓴다 */
async function listAllIssues(github, repo) {
  return github.paginate(github.rest.issues.listForRepo, { ...repo, state: 'all', per_page: 100 });
}

/** 열린 이슈 전부 (PR 과 주간 요약 이슈는 뺀다) */
async function listOpenIssues(github, { owner, repo }) {
  const all = await github.paginate(github.rest.issues.listForRepo, { owner, repo, state: 'open', per_page: 100 });
  return all.filter((i) => !i.pull_request && !hasLabel(i, WEEKLY_LABEL));
}

module.exports = { setLabelInGroup, getIssueOrNull, listAllIssues, listOpenIssues, hasLabel };
