// Workflow formatter: keep every article, promote the AI's ten picks, and compact the rest.
function main(input, ctx) {
  if (!input.items.length) return input;
  const report = input.items[0].json;
  const articles = report.articles;
  if (!Array.isArray(articles) || !articles.length) throw Error('总结结果缺少文章列表');
  const expected = Math.min(10, articles.length);
  const selected = [], briefs = [], ranks = new Set();
  const clean = value => String(value ?? '').replace(/\s+/g, ' ').trim();
  const cut = (value, limit) => { const chars = Array.from(clean(value)); return chars.length > limit ? chars.slice(0, limit - 1).join('') + '…' : chars.join(''); };
  const html = value => String(value).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const md = value => String(value).replace(/\\/g, '\\\\').replace(/([*\[\]`])/g, '\\$1').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  const link = value => /^https?:\/\//i.test(String(value ?? '')) && !/[\s<>"']/.test(value) ? String(value) : '';
  const mdLink = (label, url) => url ? '[' + md(label) + '](' + url.replace(/\(/g, '%28').replace(/\)/g, '%29') + ')' : md(label);
  for (const article of articles) {
    const value = clean(article.summary), match = value.match(/^【精选\s*0?(\d{1,2})】\s*(.+)$/);
    if (match) {
      const rank = Number(match[1]);
      if (rank < 1 || rank > expected || ranks.has(rank)) throw Error('精选序号无效或重复，未发送日报');
      ranks.add(rank); selected.push({...article, rank, selected: true, summary: cut(match[2], 100)});
    } else if (/^【速览】\s*\S/.test(value)) {
      briefs.push({...article, selected: false, summary: cut(value.replace(/^【速览】\s*/, ''), 28)});
    } else throw Error('AI 未按精选/速览格式返回，请重新运行，未发送日报');
  }
  if (selected.length !== expected) throw Error('AI 需要评选 ' + expected + ' 篇精选文章，实际 ' + selected.length + ' 篇，未发送日报');
  selected.sort((a, b) => a.rank - b.rank);
  const title = clean(report.title), summary = cut(report.summary, 100);
  const heading = '最值得看的 ' + expected + ' 篇';
  let plain = title + '\n\n' + summary + '\n\n' + heading + '\n';
  let markdown = '**' + md(title) + '**\n\n' + md(summary) + '\n\n## ' + heading;
  let email = '<h2>' + html(title) + '</h2><p>' + html(summary) + '</p><h3>' + heading + '</h3><ol>';
  for (const article of selected) {
    const name = cut(article.title, 70), source = clean(article.source), url = link(article.url);
    plain += '\n' + article.rank + '. ' + name + (source ? '（' + source + '）' : '') + '\n' + article.summary + (url ? '\n' + url : '') + '\n';
    markdown += '\n\n**' + article.rank + '. ' + mdLink(name, url) + '**' + (source ? ' · ' + md(source) : '') + '\n' + md(article.summary);
    email += '<li><strong>' + (url ? '<a href="' + html(url) + '">' + html(name) + '</a>' : html(name)) + '</strong>' + (source ? ' · ' + html(source) : '') + '<p>' + html(article.summary) + '</p></li>';
  }
  email += '</ol>';
  if (briefs.length) {
    plain += '\n其余速览（' + briefs.length + ' 篇）\n';
    markdown += '\n\n## 其余速览（' + briefs.length + ' 篇）';
    email += '<h3>其余速览（' + briefs.length + ' 篇）</h3><ul>';
    for (const article of briefs) {
      const name = cut(article.title, 40), url = link(article.url);
      plain += '\n• ' + name + '：' + article.summary + (url ? '\n' + url : '');
      markdown += '\n- ' + mdLink(name, url) + '：' + md(article.summary);
      email += '<li>' + (url ? '<a href="' + html(url) + '">' + html(name) + '</a>' : html(name)) + '：' + html(article.summary) + '</li>';
    }
    email += '</ul>';
  }
  const warnings = Array.isArray(report.warnings) ? report.warnings.map(clean) : [];
  if (warnings.length) {
    const warning = '部分来源暂不可用：' + warnings.join('；');
    plain += '\n\n' + warning; markdown += '\n\n' + md(warning); email += '<p>' + html(warning) + '</p>';
  }
  const note = '基于订阅源内容整理，详情请查看原文。';
  plain += '\n\n' + note; markdown += '\n\n' + note; email += '<p>' + note + '</p>';
  let bytes = 0;
  for (const c of JSON.stringify(markdown)) { const cp = c.codePointAt(0); bytes += cp <= 127 ? 1 : cp <= 2047 ? 2 : cp <= 65535 ? 3 : 4; }
  if (bytes > 16000) throw Error('精选与速览超出飞书长度限制，请减少上游文章数，未发送日报');
  return {items: [{json: {...report, summary, articles: [...selected, ...briefs], articleCount: articles.length,
    selectedCount: selected.length, briefCount: briefs.length, digestFormat: 'top10-and-briefs',
    emailSubject: title, emailBody: plain, emailHtml: email, feishuText: plain, feishuMarkdown: markdown}}]};
}
