#!/usr/bin/env python3
"""Create the requested RSS -> DeepSeek -> Feishu daily workflow via the authenticated API.

Uses the existing local management token in memory; never prints credentials or article content.
"""
import argparse
import http.cookiejar
import json
import pathlib
import time
import urllib.error
import urllib.request

BASE = 'https://121.40.254.112'
NAME = 'RSS 科技资讯日报'
SOURCES = [
    {'name': 'InfoQ 中文', 'url': 'https://www.infoq.cn/feed', 'titleContains': ''},
    {'name': '阮一峰科技爱好者周刊', 'url': 'https://www.ruanyifeng.com/blog/atom.xml', 'titleContains': '科技爱好者周刊'},
    {'name': '少数派', 'url': 'https://sspai.com/feed', 'titleContains': ''},
    {'name': 'IT之家', 'url': 'https://www.ithome.com/rss/', 'titleContains': ''},
    {'name': '36氪', 'url': 'https://www.36kr.com/feed', 'titleContains': ''},
    {'name': 'OpenAI News', 'url': 'https://openai.com/news/rss.xml', 'titleContains': ''},
    {'name': 'GitHub Changelog', 'url': 'https://github.blog/changelog/feed/', 'titleContains': ''},
]


DIGEST_PROMPT = (
    '请用简体中文整理科技资讯日报，重点关注软件架构、后端工程实践、AI 模型与产品更新、开源工具、效率软件和科技产业动态。'
    '比较本批全部文章，从信息价值、实用性、技术影响和新颖性评选10篇，考虑同主题重复，不按来源平均分配名额。'
    '精选写核心看点、内容要点与阅读价值，其余每篇一句速览；不足10篇全部精选。'
    '仅依据给定资料，不夸大、不臆测。'
)


def digest_node():
    return {'id': 'digest', 'type': 'ai-summary', 'name': '精选10篇 · 其余速览', 'position': {'x': 800, 'y': 160},
            'config': {'mode': 'top10', 'title': NAME, 'model': 'deepseek-flash', 'maxItems': 50,
                       'maxInputChars': 60000, 'timeoutMs': 180000, 'prompt': DIGEST_PROMPT}}


def definition(bot):
    def node(identity, kind, name, x, config=None):
        return {'id': identity, 'type': kind, 'name': name, 'position': {'x': x, 'y': 160}, 'config': config or {}}
    nodes = [node('start', 'start', '开始', 80),
             node('rss', 'rss', '读取 7 个 RSS 订阅 · 仅新增', 400, {'sources': SOURCES, 'onlyNew': True, 'perSource': 5, 'maxItems': 35, 'lookbackHours': 168, 'timeoutMs': 60000}),
             digest_node(),
             node('feishu', 'feishu', '发送飞书日报', 1200, {'credentialId': bot, 'messageType': 'card', 'title': '{{ input.items[0].json.title }}', 'text': '{{ input.items[0].json.feishuMarkdown }}', 'timeoutMs': 10000}),
             node('end', 'end', '结束', 1520)]
    return {'nodes': nodes, 'edges': [{'id': 'rss_daily_' + str(i), 'source': a['id'], 'target': b['id'], 'branch': ''} for i, (a, b) in enumerate(zip(nodes, nodes[1:]))]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--send-initial', action='store_true', help='Run once now; sends a real Feishu digest')
    args = parser.parse_args()
    cookies = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookies))
    def request(path, data=None, method=None):
        body = json.dumps(data, ensure_ascii=False).encode() if data is not None else None
        req = urllib.request.Request(BASE + path, data=body, method=method,
                                     headers={'Content-Type': 'application/json', 'Origin': BASE, 'X-Requested-With': 'XMLHttpRequest'})
        try:
            with opener.open(req, timeout=40) as response:
                payload = response.read()
                return json.loads(payload) if payload else None
        except urllib.error.HTTPError as error:
            raise RuntimeError('Management request failed: HTTP ' + str(error.code)) from None
    logged_in = False
    try:
        token = (pathlib.Path.home() / '.config/leon-workflow/api-token').read_text().strip()
        request('/auth/login', {'token': token, 'remember': False}); token = None; logged_in = True
        credentials = request('/api/workflows/credentials')
        bots = [c for c in credentials if c['kind'] == 'feishu' and c['name'] == 'LeonLiteWorkflow']
        if len(bots) != 1:
            raise RuntimeError('Expected exactly one existing LeonLiteWorkflow Feishu credential')
        expected = definition(bots[0]['id'])
        workflows = [w for w in request('/api/workflows') if w['name'] == NAME]
        if len(workflows) > 1:
            raise RuntimeError('Multiple workflows have the requested name; refusing to overwrite')
        if workflows:
            workflow = request('/api/workflows/' + workflows[0]['id'])
            if workflow['definition'] != expected:
                raise RuntimeError('An existing workflow has this name with different settings; refusing to overwrite')
        else:
            workflow = request('/api/workflows', {'name': NAME})
            workflow = request('/api/workflows/' + workflow['id'], {'name': NAME, 'definition': expected, 'revision': workflow['revision']}, 'PUT')
        identity = workflow['id']
        if not workflow['publishedVersion']:
            workflow = request('/api/workflows/' + identity + '/publish', {'revision': workflow['revision']})
        schedule = request('/api/workflows/' + identity + '/schedule')
        if not schedule['enabled'] or schedule['cron'] != '0 0 9 * * *' or schedule['timeZone'] != 'Asia/Shanghai':
            schedule = request('/api/workflows/' + identity + '/schedule', {'enabled': True, 'cron': '0 0 9 * * *', 'timeZone': 'Asia/Shanghai', 'input': {'items': []}, 'revision': schedule['revision']}, 'PUT')
        print(json.dumps({'workflowId': identity, 'url': BASE + '/workflows.html?id=' + identity, 'version': workflow['publishedVersion'], 'sources': len(SOURCES), 'dailyTime': '09:00 Asia/Shanghai', 'nextRunAt': schedule['nextRunAt']}, ensure_ascii=False), flush=True)
        if args.send_initial:
            run = request('/api/workflows/' + identity + '/runs', {'input': {'items': []}})
            print('Initial run: ' + run['id'], flush=True)
            deadline = time.monotonic() + 330
            while run['status'] in ('QUEUED', 'RUNNING') and time.monotonic() < deadline:
                time.sleep(3)
                run = request('/api/workflows/runs/' + run['id'])
            steps = [{k: step.get(k) for k in ('nodeId', 'type', 'status')} for step in run['nodes']]
            rss = next((s for s in run['nodes'] if s['type'] == 'rss'), {})
            meta = (rss.get('output') or {}).get('rss', {})
            print(json.dumps({'runId': run['id'], 'status': run['status'], 'articles': meta.get('articleCount'), 'availableSources': meta.get('availableSources'), 'sourceWarnings': len(meta.get('warnings', [])), 'nodes': steps}, ensure_ascii=False), flush=True)
            if run['status'] != 'SUCCEEDED':
                # Existing runtime errors are already sanitized; omit article and provider payloads.
                print(json.dumps({'error': run.get('error')}, ensure_ascii=False), flush=True)
                raise RuntimeError('Initial RSS workflow run did not succeed; daily schedule remains enabled')
    finally:
        if logged_in:
            request('/auth/logout', {})


if __name__ == '__main__':
    main()
