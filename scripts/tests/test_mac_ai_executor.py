import importlib.util
import io
import json
import pathlib
import tempfile
import time
import unittest
import urllib.error
from unittest.mock import patch

ROOT = pathlib.Path(__file__).resolve().parents[1]


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


worker = load('mac_ai_executor', ROOT / 'mac_ai_executor.py')
proxy = load('ai_executor_proxy', ROOT / 'install_ai_executor_proxy.py')


class Response(io.BytesIO):
    status = 200


class MacExecutorTest(unittest.TestCase):
    def job(self):
        return {'id': 'job', 'lease': 'lease', 'apiKey': 'private-test-key', 'deadlineAt': int(time.time() * 1000) + 10000,
                'body': {'model': 'gpt-6-luna', 'stream': False, 'messages': [{'role': 'user', 'content': 'question'}]}}

    def test_only_calls_the_fixed_openai_endpoint_once_and_scrubs_keys(self):
        payload = {'choices': [{'message': {'content': 'private-test-key'}}], 'usage': {'prompt_tokens': 2, 'completion_tokens': 1, 'total_tokens': 3}}
        with patch.object(worker, 'opener') as factory:
            factory.return_value.open.return_value = Response(json.dumps(payload).encode())
            result = worker.invoke_openai(self.job())
            self.assertNotIn('private-test-key', json.dumps(result))
            request = factory.return_value.open.call_args.args[0]
            self.assertEqual(worker.API, request.full_url)
            self.assertEqual('Bearer private-test-key', request.get_header('Authorization'))
            self.assertEqual(1, factory.return_value.open.call_count)

    def test_image_jobs_can_cross_one_megabyte_without_relaxing_other_responses(self):
        value = self.job()
        image = {'type': 'image_url', 'image_url': {'url': 'data:image/jpeg;base64,' + 'A' * 1_100_000}}
        value['body']['messages'][0]['content'] = [{'type': 'text', 'text': '看图'}, image]
        payload = json.dumps(value).encode()
        client = unittest.mock.Mock()
        client.open.return_value = Response(payload)
        received = worker.request_json(client, 'https://example.test/api/ai-executor/claim', {})
        self.assertEqual(value, received)
        client.open.return_value = Response(payload)
        with self.assertRaises(ValueError):
            worker.request_json(client, 'https://example.test/api/ai-executor/heartbeat', {})
        with patch.object(worker, 'opener') as factory:
            factory.return_value.open.return_value = Response(b'{"choices": []}')
            worker.invoke_openai(value)
            request = factory.return_value.open.call_args.args[0]
            self.assertEqual(value['body']['messages'], json.loads(request.data)['messages'])

    def test_errors_do_not_retain_provider_bodies_and_are_not_retried(self):
        with patch.object(worker, 'opener') as factory:
            error = urllib.error.HTTPError(worker.API, 401, 'denied', {}, Response(b'private-test-key'))
            factory.return_value.open.side_effect = error
            self.assertEqual({'status': 401, 'body': {}}, worker.invoke_openai(self.job()))
            self.assertEqual(1, factory.return_value.open.call_count)
        self.assertIsNone(worker.NoRedirect().redirect_request(None, None, 302, '', {}, 'https://other.invalid'))

    def test_responses_image_results_use_fixed_endpoint_and_larger_bounded_transport(self):
        value = self.job()
        value['api'] = 'responses'
        value['body'] = {'model': 'gpt-6.1-sol', 'stream': False, 'input': [{'role': 'user', 'content': '画一张图'}]}
        payload = {'status': 'completed', 'output': [{'type': 'image_generation_call', 'result': 'A' * 1_100_000}]}
        with patch.object(worker, 'opener') as factory:
            factory.return_value.open.return_value = Response(json.dumps(payload).encode())
            result = worker.invoke_openai(value)
            self.assertEqual(payload, result['body'])
            self.assertEqual(worker.RESPONSES_API, factory.return_value.open.call_args.args[0].full_url)
            self.assertEqual(1, factory.return_value.open.call_count)
            factory.return_value.open.return_value = Response(json.dumps(payload).encode())
            self.assertEqual({'errorCode': 'TOO_LARGE'}, worker.invoke_openai(self.job()))
            factory.return_value.open.return_value = Response(b' ' * (worker.IMAGE_RESULT_LIMIT + 1))
            self.assertEqual({'errorCode': 'TOO_LARGE'}, worker.invoke_openai(value))
        value['api'] = 'https://untrusted.test/responses'
        with patch.object(worker, 'opener') as factory:
            self.assertEqual({'errorCode': 'INVALID_RESPONSE'}, worker.invoke_openai(value))
            factory.assert_not_called()

    def test_proxy_upgrade_expands_image_result_delivery_and_is_idempotent(self):
        original = 'server {\n' + proxy.ANCHOR + '\n}'
        legacy = proxy.configured(original).replace('client_max_body_size 25m;', 'client_max_body_size 1100k;')
        updated = proxy.configured(legacy)
        self.assertIn('client_max_body_size 25m;', updated)
        self.assertEqual(updated, proxy.configured(updated))

    def test_web_search_tools_and_citations_survive_transport_without_a_second_request(self):
        value = self.job()
        value['api'] = 'responses'
        value['body'] = {'model': 'test-search-model', 'stream': False,
                         'input': [{'role': 'user', 'content': '查最新资料'}],
                         'tools': [{'type': 'web_search', 'external_web_access': True}]}
        payload = {'status': 'completed', 'output': [
            {'type': 'web_search_call', 'status': 'completed'},
            {'type': 'message', 'content': [{'type': 'output_text', 'text': '最新资料',
                'annotations': [{'type': 'url_citation', 'start_index': 0, 'end_index': 4,
                                 'url': 'https://example.test/source', 'title': '官方来源'}]}]}]}
        with patch.object(worker, 'opener') as factory:
            factory.return_value.open.return_value = Response(json.dumps(payload).encode())
            self.assertEqual({'status': 200, 'body': payload}, worker.invoke_openai(value))
            request = factory.return_value.open.call_args.args[0]
            self.assertEqual(worker.RESPONSES_API, request.full_url)
            self.assertEqual(value['body'], json.loads(request.data))
            self.assertEqual(1, factory.return_value.open.call_count)

    def test_ambiguous_start_never_executes_the_provider(self):
        instance = worker.Worker({'server': 'https://example.test', 'token': 'test-token'})
        instance.ready.set()
        def api(path, body):
            if path == '/claim':
                return self.job()
            instance.stopped.set()
            raise urllib.error.URLError('lost acknowledgement')
        instance.api = api
        with patch.object(worker, 'invoke_openai') as invoke:
            instance.loop()
            invoke.assert_not_called()

    def test_private_config_and_server_url_are_validated(self):
        with tempfile.TemporaryDirectory() as folder:
            path = pathlib.Path(folder) / 'config.json'
            worker.private_write(path, json.dumps({'server': 'https://example.test', 'token': 'a' * 43}).encode())
            self.assertEqual('https://example.test', worker.read_config(path)['server'])
            path.chmod(0o644)
            with self.assertRaises(ValueError):
                worker.read_config(path)
        for url in ['http://example.test', 'https://user:secret@example.test', 'https://example.test/path', 'https://example.test#fragment']:
            with self.assertRaises(ValueError):
                worker.server_url(url)

    def test_proxy_rule_is_limited_idempotent_and_never_spools_credentials(self):
        original = 'server {\n' + proxy.ANCHOR + '\n}'
        configured = proxy.configured(original)
        self.assertEqual(configured, proxy.configured(configured))
        self.assertIn(proxy.ROUTE, configured)
        self.assertIn(proxy.ANCHOR, configured)
        self.assertIn('proxy_buffering off;', configured)
        self.assertIn('proxy_request_buffering off;', configured)
        self.assertNotIn('location /api/', configured)
        with self.assertRaises(RuntimeError):
            proxy.configured(proxy.MARKER + proxy.ANCHOR)


if __name__ == '__main__':
    unittest.main()
