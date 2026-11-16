"""Offline graph migration checks: no credentials, network, AI calls or sends."""
import copy
import pathlib
import sys
import unittest
sys.dont_write_bytecode = True
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[3] / 'scripts'))
from create_rss_workflow import definition
from update_rss_digest import migrate_definition


class RssDigestMigrationTest(unittest.TestCase):
    def legacy(self):
        graph = definition('existing-feishu-credential')
        digest = next(n for n in graph['nodes'] if n['id'] == 'digest')
        digest['type'] = 'script'
        digest['config'] = {'language': 'javascript', 'source': 'legacy formatter'}
        graph['nodes'].append({'id': 'summary', 'type': 'ai-summary', 'name': '原总结',
                               'config': {'model': 'custom-model', 'title': '我的日报', 'maxItems': 40}})
        edge = next(e for e in graph['edges'] if e['target'] == 'digest')
        edge['target'] = 'summary'
        graph['edges'].append({'id': 'old-summary-digest', 'source': 'summary', 'target': 'digest', 'branch': ''})
        return graph

    def test_replaces_legacy_chain_and_preserves_all_targets_and_input(self):
        graph = self.legacy()
        email = {'id': 'mail', 'type': 'email', 'config': {'credentialId': 'mail-secret-ref', 'to': 'user@example.com', 'subject': 'old'}}
        qq = {'id': 'qq', 'type': 'qq', 'config': {'credentialId': 'qq-secret-ref', 'targetId': 'EXISTING_USER_OPENID', 'targetType': 'user', 'text': 'old'}}
        graph['nodes'].extend([email, qq])
        graph['edges'].extend([{'id': 'mail', 'source': 'digest', 'target': 'mail', 'branch': ''},
                               {'id': 'qq', 'source': 'mail', 'target': 'qq', 'branch': ''}])
        before = copy.deepcopy(graph)
        updated = migrate_definition(graph)
        self.assertEqual(before, graph)
        by_id = {n['id']: n for n in updated['nodes']}
        self.assertNotIn('summary', by_id)
        self.assertEqual('ai-summary', by_id['digest']['type'])
        self.assertEqual('top10', by_id['digest']['config']['mode'])
        self.assertEqual('custom-model', by_id['digest']['config']['model'])
        self.assertEqual('我的日报', by_id['digest']['config']['title'])
        self.assertTrue(any(e['source'] == 'rss' and e['target'] == 'digest' for e in updated['edges']))
        self.assertEqual('html', by_id['mail']['config']['format'])
        self.assertEqual(email['config']['to'], by_id['mail']['config']['to'])
        self.assertEqual(email['config']['credentialId'], by_id['mail']['config']['credentialId'])
        self.assertEqual(qq['config']['targetId'], by_id['qq']['config']['targetId'])
        self.assertEqual('list', by_id['qq']['config']['messageMode'])
        self.assertEqual('markdown', by_id['qq']['config']['messageType'])
        self.assertEqual(before['nodes'][1], by_id['rss'])
        self.assertEqual(updated, migrate_definition(updated), '迁移应可重复执行')

    def test_unknown_consumers_or_sources_are_not_removed(self):
        for change in ['consumer', 'source', 'digest-kind']:
            graph = self.legacy()
            if change == 'consumer': graph['edges'].append({'id': 'extra', 'source': 'summary', 'target': 'feishu'})
            if change == 'source': next(e for e in graph['edges'] if e['target'] == 'summary')['source'] = 'start'
            if change == 'digest-kind': next(n for n in graph['nodes'] if n['id'] == 'digest')['type'] = 'http'
            with self.assertRaises(RuntimeError): migrate_definition(graph)

    def test_new_preset_has_only_one_ai_call_and_direct_rss_input(self):
        graph = definition('existing-credential')
        self.assertEqual(1, sum(n['type'] == 'ai-summary' for n in graph['nodes']))
        self.assertFalse(any(n['type'] == 'script' for n in graph['nodes']))
        self.assertTrue(any(e['source'] == 'rss' and e['target'] == 'digest' for e in graph['edges']))

    def test_older_workflow_without_formatter_rewires_delivery(self):
        graph = definition('existing-credential')
        next(n for n in graph['nodes'] if n['id'] == 'digest')['id'] = 'summary'
        for e in graph['edges']:
            if e['source'] == 'digest': e['source'] = 'summary'
            if e['target'] == 'digest': e['target'] = 'summary'
        updated = migrate_definition(graph)
        self.assertTrue(any(e['source'] == 'digest' and e['target'] == 'feishu' for e in updated['edges']))
        self.assertFalse(any(n['id'] == 'summary' for n in updated['nodes']))


if __name__ == '__main__': unittest.main()
