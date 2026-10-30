// .github/labels.json 의 라벨을 저장소에 만들거나 색·설명을 맞춘다. 목록에 없는 라벨은 건드리지 않는다.

const fs = require('fs');
const path = require('path');

module.exports = async ({ github, context, core }) => {
  const repo = context.repo;
  const file = path.join(process.env.GITHUB_WORKSPACE || '.', '.github', 'labels.json');
  const labels = JSON.parse(fs.readFileSync(file, 'utf8'));

  for (const { name, color, description } of labels) {
    try {
      await github.rest.issues.updateLabel({ ...repo, name, color, description });
      core.info(`갱신: ${name}`);
    } catch (e) {
      if (e.status !== 404) throw e;
      await github.rest.issues.createLabel({ ...repo, name, color, description });
      core.info(`생성: ${name}`);
    }
  }
};
