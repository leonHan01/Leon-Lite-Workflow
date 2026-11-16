#!/usr/bin/env python3
"""Explicitly migrate and publish the RSS daily workflow to one AI digest node; requires the new server version."""
import copy
import http.cookiejar
import json
import pathlib
import urllib.error
import urllib.request
from create_rss_workflow import BASE, NAME, digest_node


def migrate_definition(definition):
    """Replace the legacy summary + formatter with one AI digest, preserving delivery targets."""
    graph = copy.deepcopy(definition)
    by_id = {n['id']: n for n in graph['nodes']}
    if by_id.get('rss', {}).get('type') != 'rss':
        raise RuntimeError('Expected the RSS daily workflow; refusing unrelated changes')
    node = digest_node()
    old = by_id.get('digest')
    summary = by_id.get('summary')
    if old and not (old['type'] == 'script' and old.get('config', {}).get('language') == 'javascript'
                    or old['type'] == 'ai-summary' and old.get('config', {}).get('mode') == 'top10'):
        raise RuntimeError('Existing digest node differs; refusing unrelated changes')
    if summary:
        incoming = [e for e in graph['edges'] if e['target'] == 'summary']
        outgoing = [e for e in graph['edges'] if e['source'] == 'summary']
        if summary['type'] != 'ai-summary' or len(incoming) != 1 or incoming[0]['source'] != 'rss':
            raise RuntimeError('Legacy summary shape differs; inspect before migration')
        if old and (len(outgoing) != 1 or outgoing[0]['target'] != 'digest'):
            raise RuntimeError('Legacy summary has other consumers; refusing to remove it')
        for key in ['title', 'model', 'maxItems', 'maxInputChars', 'timeoutMs']:
            if key in summary.get('config', {}): node['config'][key] = summary['config'][key]
        graph['nodes'].remove(summary)
        if old:
            graph['edges'].remove(outgoing[0])
        else:
            for edge in outgoing: edge['source'] = 'digest'
        incoming[0]['target'] = 'digest'
    if old:
        if not summary and old['type'] == 'ai-summary':
            node['config'] = {**old['config'], 'mode': 'top10'}
        old.update({'type': node['type'], 'name': node['name'], 'config': node['config']})
    else:
        graph['nodes'].append(node)
    incoming = [e for e in graph['edges'] if e['target'] == 'digest']
    if len(incoming) != 1 or incoming[0]['source'] != 'rss':
        raise RuntimeError('AI digest must receive raw RSS articles directly')
    # Notification nodes pass the original report through, so chained delivery is also supported.
    pending = ['digest']; visited = set()
    while pending:
        current = pending.pop()
        if current in visited: continue
        visited.add(current)
        pending.extend(e['target'] for e in graph['edges'] if e['source'] == current)
    for current in graph['nodes']:
        if current['id'] not in visited: continue
        config = current.get('config', {})
        if current['type'] == 'feishu':
            config.update(messageType='card', title='{{ input.items[0].json.title }}', text='{{ input.items[0].json.feishuMarkdown }}')
        elif current['type'] == 'email':
            config.update(format='html', subject='{{ input.items[0].json.emailSubject }}', body='{{ input.items[0].json.emailHtml }}')
        elif current['type'] == 'qq':
            config.update(messageType='markdown', messageMode='list', text='{{ input.items[0].json.qqMessages }}', timeoutMs=30000)
    return graph


def main():
    cookies = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookies))
    def request(path, body=None, method=None):
        headers = {'Content-Type': 'application/json', 'Origin': BASE, 'X-Requested-With': 'XMLHttpRequest'}
        query = urllib.request.Request(BASE + path, data=json.dumps(body, ensure_ascii=False).encode() if body is not None else None, headers=headers, method=method)
        try:
            with opener.open(query, timeout=250) as response:
                payload = response.read()
                return json.loads(payload) if payload else None
        except urllib.error.HTTPError as error:
            # Only the status and sanitized workflow error are reported, never response payloads.
            raise RuntimeError('RSS digest update failed: HTTP ' + str(error.code)) from None
    logged_in = False
    try:
        request('/auth/login', {'token': (pathlib.Path.home() / '.config/leon-workflow/api-token').read_text().strip(), 'remember': False})
        logged_in = True
        matches = [w for w in request('/api/workflows') if w['name'] == NAME]
        if len(matches) != 1:
            raise RuntimeError('Expected one existing RSS daily workflow')
        identity = matches[0]['id']
        workflow = request('/api/workflows/' + identity)
        schedule_before = request('/api/workflows/' + identity + '/schedule')
        graph = migrate_definition(workflow['definition'])
        node = next(n for n in graph['nodes'] if n['id'] == 'digest')
        # Reuse stored RSS output to verify the new format without sending or touching dedup state.
        sample = None
        for row in request('/api/workflows/' + identity + '/runs')[:10]:
            if row['status'] != 'SUCCEEDED':
                continue
            run = request('/api/workflows/runs/' + row['id'])
            for step in run['nodes']:
                output = step.get('output') or {}
                if step['type'] == 'rss' and len(output.get('items', [])) >= 10:
                    sample = output
                    break
            if sample:
                break
        if not sample:
            raise RuntimeError('No stored RSS batch is available for the preview')
        preview = request('/api/workflows/test-node', {'node': node, 'input': sample})
        report = preview['output']['items'][0]['json']
        if report['selectedCount'] != min(10, len(sample['items'])) or report['articleCount'] != len(sample['items']):
            raise RuntimeError('Digest preview did not retain ten picks and all remaining articles')
        if not report.get('qqMessages') or any(not isinstance(message, str) or len(message.encode()) > 2000 for message in report['qqMessages']):
            raise RuntimeError('QQ digest messages exceed the per-message limit')
        print(json.dumps({'preview': 'passed', 'selected': report['selectedCount'], 'briefs': report['briefCount'], 'articles': report['articleCount'], 'feishuBytes': len(json.dumps(report['feishuMarkdown'], ensure_ascii=False).encode())}, ensure_ascii=False), flush=True)
        saved = request('/api/workflows/' + identity, {'name': workflow['name'], 'revision': workflow['revision'], 'definition': graph}, 'PUT')
        published = request('/api/workflows/' + identity + '/publish', {'revision': saved['revision']})
        verified = request('/api/workflows/' + identity)
        if verified['publishedVersion'] != published['publishedVersion'] or verified['definition'] != graph:
            raise RuntimeError('Published workflow readback did not match')
        if request('/api/workflows/' + identity + '/schedule') != schedule_before:
            raise RuntimeError('Schedule changed during the update; inspect before continuing')
        print(json.dumps({'workflowId': identity, 'publishedVersion': published['publishedVersion'], 'url': BASE + '/workflows.html?id=' + identity, 'format': 'ai-top10-and-briefs', 'schedulePreserved': True}, ensure_ascii=False), flush=True)
    finally:
        if logged_in:
            request('/auth/logout', {})


if __name__ == '__main__':
    main()
