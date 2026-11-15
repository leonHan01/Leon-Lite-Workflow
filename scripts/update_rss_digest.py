#!/usr/bin/env python3
"""Preview and publish the existing RSS daily workflow as ten picks plus brief remaining articles."""
import copy
import http.cookiejar
import json
import pathlib
import urllib.error
import urllib.request
from create_rss_workflow import BASE, NAME, DIGEST_PROMPT, digest_node


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
        graph = copy.deepcopy(workflow['definition'])
        by_id = {n['id']: n for n in graph['nodes']}
        if by_id.get('summary', {}).get('type') != 'ai-summary' or by_id.get('feishu', {}).get('type') != 'feishu':
            raise RuntimeError('Existing workflow shape differs; refusing unrelated changes')
        by_id['summary']['config']['prompt'] = DIGEST_PROMPT
        node = digest_node()
        if 'digest' in by_id:
            if by_id['digest']['type'] != 'script' or by_id['digest'].get('config', {}).get('language') != 'javascript':
                raise RuntimeError('Existing digest node is not the expected JavaScript formatter')
            by_id['digest']['config'] = node['config']
        else:
            edges = [e for e in graph['edges'] if e['source'] == 'summary' and e['target'] == 'feishu']
            if len(edges) != 1:
                raise RuntimeError('Expected one direct summary-to-Feishu connection')
            edge = edges[0]
            edge['target'] = 'digest'
            graph['edges'].append({'id': 'digest_to_feishu', 'source': 'digest', 'target': 'feishu', 'branch': ''})
            graph['nodes'].insert(graph['nodes'].index(by_id['feishu']), node)
            by_id['feishu']['position'] = {'x': 1600, 'y': 160}
            by_id['end']['position'] = {'x': 1920, 'y': 160}
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
        summary = request('/api/workflows/test-node', {'node': by_id['summary'], 'input': sample})
        preview = request('/api/workflows/test-node', {'node': node, 'input': summary['output']})
        report = preview['output']['items'][0]['json']
        if report['selectedCount'] != min(10, len(sample['items'])) or report['articleCount'] != len(sample['items']):
            raise RuntimeError('Digest preview did not retain ten picks and all remaining articles')
        print(json.dumps({'preview': 'passed', 'selected': report['selectedCount'], 'briefs': report['briefCount'], 'articles': report['articleCount'], 'feishuBytes': len(json.dumps(report['feishuMarkdown'], ensure_ascii=False).encode())}, ensure_ascii=False), flush=True)
        saved = request('/api/workflows/' + identity, {'name': workflow['name'], 'revision': workflow['revision'], 'definition': graph}, 'PUT')
        published = request('/api/workflows/' + identity + '/publish', {'revision': saved['revision']})
        verified = request('/api/workflows/' + identity)
        if verified['publishedVersion'] != published['publishedVersion'] or verified['definition'] != graph:
            raise RuntimeError('Published workflow readback did not match')
        if request('/api/workflows/' + identity + '/schedule') != schedule_before:
            raise RuntimeError('Schedule changed during the update; inspect before continuing')
        print(json.dumps({'workflowId': identity, 'publishedVersion': published['publishedVersion'], 'url': BASE + '/workflows.html?id=' + identity, 'format': 'top10-and-briefs', 'schedulePreserved': True}, ensure_ascii=False), flush=True)
    finally:
        if logged_in:
            request('/auth/logout', {})


if __name__ == '__main__':
    main()
