import importlib.util
import json
import pathlib
import os
import io
import types
import tempfile
import unittest
import sys
from unittest.mock import patch

SCRIPT = pathlib.Path(__file__).resolve().parents[2] / 'scripts/deploy.py'
spec = importlib.util.spec_from_file_location('deploy', str(SCRIPT))
deploy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(deploy)
sys.modules['deploy'] = deploy
worker_spec = importlib.util.spec_from_file_location('server_deploy', str(SCRIPT.with_name('server_deploy.py')))
worker = importlib.util.module_from_spec(worker_spec)
worker_spec.loader.exec_module(worker)


class DeploymentRunnerTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='deploy-test-')
        self.addCleanup(self.temporary.cleanup)
        root = pathlib.Path(self.temporary.name)
        values = {'APP': root / 'app', 'JAR': root / 'app/app.jar', 'DATA': root / 'data',
                  'HISTORY': root / 'data/deployments', 'OVERRIDE': root / 'release.conf', 'LOCK': root / 'lock'}
        for name, value in values.items():
            manager = patch.object(deploy, name, value)
            manager.start()
            self.addCleanup(manager.stop)
        deploy.APP.mkdir()
        deploy.DATA.mkdir()
        deploy.HISTORY.mkdir()
        (deploy.APP / 'backups').mkdir()
        self.metadata = {'id': 'release-test', 'version': '2026.11.12.1', 'commit': 'a' * 40,
                         'dirty': True, 'description': '测试发布', 'jarSha256': 'b' * 64}

    def prepare_rollback(self):
        backup = deploy.APP / 'backups' / self.metadata['id']
        backup.mkdir()
        (backup / 'leon-lite-workflow.jar').write_bytes(b'old jar')
        (backup / '99-deployment.conf').write_text('previous release')
        deploy.JAR.write_bytes(b'new jar')
        deploy.OVERRIDE.write_text('new release')
        deploy.LOCK.write_text(self.metadata['id'])
        database = deploy.DATA / 'workflow'
        pathlib.Path(str(database) + '.mv.db').write_bytes(b'current business data')
        deploy.atomic_json(backup / 'rollback.json', {'hadOverride': True,
            'jarSha256': deploy.sha256(backup / 'leon-lite-workflow.jar'), 'databasePath': str(database)})
        deploy.record(self.metadata, 'DEPLOYING')
        return database

    def test_atomic_status_updates_keep_one_record_and_preserve_start_time(self):
        deploy.record(self.metadata, 'DEPLOYING')
        path = deploy.HISTORY / (self.metadata['id'] + '.json')
        started = json.loads(path.read_text())['startedAt']
        deploy.record(self.metadata, 'SUCCESS')
        record = json.loads(path.read_text())
        self.assertEqual(started, record['startedAt'])
        self.assertIsNotNone(record['finishedAt'])
        self.assertEqual('SUCCESS', record['status'])
        self.assertEqual([path], list(deploy.HISTORY.iterdir()))

    def test_rollback_restores_release_without_overwriting_business_data(self):
        database = self.prepare_rollback()
        env = {b'LEON_DB_URL': ('jdbc:h2:file:' + str(database)).encode()}
        with patch.object(deploy, 'run') as run, patch.object(deploy, 'hello'), patch.object(deploy, 'services'), patch.object(deploy, 'environment', return_value=env):
            deploy.rollback(self.metadata)
            calls = run.call_count
            deploy.rollback(self.metadata)  # A lost Workbench reply must not cause another restart.
            self.assertEqual(calls, run.call_count)
        self.assertEqual(b'old jar', deploy.JAR.read_bytes())
        self.assertEqual('previous release', deploy.OVERRIDE.read_text())
        self.assertEqual(b'current business data', pathlib.Path(str(database) + '.mv.db').read_bytes())
        self.assertEqual('ROLLED_BACK', json.loads((deploy.HISTORY / 'release-test.json').read_text())['status'])
        self.assertFalse(deploy.LOCK.exists())

    def test_failed_rollback_is_visible_and_retains_the_lock(self):
        self.prepare_rollback()
        with patch.object(deploy, 'run'), patch.object(deploy, 'hello', side_effect=RuntimeError('not healthy')):
            with self.assertRaises(RuntimeError):
                deploy.rollback(self.metadata)
        self.assertTrue(deploy.LOCK.exists())
        self.assertEqual('ROLLBACK_FAILED', json.loads((deploy.HISTORY / 'release-test.json').read_text())['status'])

    def test_legacy_import_is_idempotent_and_does_not_invent_a_duration(self):
        backup = deploy.APP / 'backups/earlier-release'
        backup.mkdir()
        deploy.atomic_json(backup / 'release.json', {'commit': 'a' * 40, 'jar_sha256': 'b' * 64})
        deploy.import_legacy_history()
        deploy.import_legacy_history()
        records = list(deploy.HISTORY.glob('*.json'))
        self.assertEqual(1, len(records))
        record = json.loads(records[0].read_text())
        self.assertEqual('SUCCESS', record['status'])
        self.assertIsNone(record['finishedAt'])

    def test_git_source_without_deployment_support_never_replaces_the_service(self):
        control = deploy.DATA / 'control'
        control.mkdir()
        jobs = deploy.APP / 'jobs'
        jobs.mkdir()
        logs = deploy.APP / 'logs'
        logs.mkdir()
        source = deploy.APP / 'source'
        source.mkdir()
        request = control / 'request.json'
        deploy.atomic_json(request, {'id': 'web-release-2026.11.12.000000-abcdef12',
            'version': '2026.11.12.000000', 'requestedAt': deploy.now()})
        with patch.multiple(worker, CONTROL=control, REQUEST=request, JOBS=jobs, LOGS=logs, WORKER_LOCK=deploy.APP / 'worker.lock'), \
                patch.object(worker, 'checkout', return_value=source), patch.object(deploy, 'apply') as apply, \
                patch.object(os, 'geteuid', return_value=0), patch.object(sys, 'argv', ['server_deploy.py']):
            worker.main()
        apply.assert_not_called()
        self.assertFalse(request.exists())
        self.assertEqual('FAILED', json.loads((control / 'job.json').read_text())['stage'])
        self.assertIn('先推送', json.loads((control / 'job.json').read_text())['message'])

    def test_git_network_failure_uses_a_commit_pinned_official_archive(self):
        calls = []
        def command(args, *unused, **kwargs):
            calls.append([str(value) for value in args])
            if 'fetch' in args:
                raise RuntimeError('Git unavailable')
        owner = types.SimpleNamespace(pw_uid=os.getuid(), pw_gid=os.getgid())
        response = io.BytesIO(json.dumps({'sha': 'c' * 40}).encode())
        with patch.object(worker, 'CACHE', deploy.DATA), patch.object(worker, 'build_command', side_effect=command), \
                patch.object(worker, 'job'), patch.object(worker.pwd, 'getpwnam', return_value=owner), \
                patch.object(worker.urllib.request, 'urlopen', return_value=response):
            source = worker.checkout(self.metadata, io.StringIO())
        self.assertTrue(source.is_dir())
        self.assertEqual('c' * 40, self.metadata['commit'])
        self.assertTrue(any('https://codeload.github.com/leonHan01/Leon-Lite-Workflow/tar.gz/' + 'c' * 40 in args for args in calls))
        self.assertIn('--strip-components=1', calls[-1])


if __name__ == '__main__':
    unittest.main()
