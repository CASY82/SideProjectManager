// 채팅방(Discord, Slack) 웹훅으로 알림을 보낸다. fetch 를 바꿔 끼워 테스트한다.

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

module.exports = { notify };
