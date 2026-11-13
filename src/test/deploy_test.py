import importlib.util
import json
import pathlib
import os
import io
import types
import zipfile
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
                  'HISTORY': root / 'data/deployments', 'OVERRIDE': root / 'release.conf', 'LOCK': root / 'lock',
                  'CACHE': root / 'cache', 'STAGING_ROOT': root / 'staging', 'LEGACY_STAGING_ROOT': root / 'legacy-staging'}
        for name, value in values.items():
            manager = patch.object(deploy, name, value)
            manager.start()
            self.addCleanup(manager.stop)
        deploy.APP.mkdir()
        deploy.DATA.mkdir()
        deploy.HISTORY.mkdir()
        (deploy.APP / 'backups').mkdir()
        for path in [deploy.CACHE, deploy.STAGING_ROOT, deploy.LEGACY_STAGING_ROOT]:
            path.mkdir()
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

    def complete_snapshot(self, identity, modified):
        backup = deploy.APP / 'backups' / identity
        backup.mkdir()
        jar = backup / 'leon-lite-workflow.jar'
        with zipfile.ZipFile(str(jar), 'w') as archive:
            archive.writestr('BOOT-INF/classes/com/example/helloservice/HelloServiceApplication.class', identity.encode())
        (backup / 'database').mkdir()
        (backup / 'database/workflow.mv.db').write_bytes(b'stopped H2 snapshot')
        (backup / '99-deployment.conf').write_text('previous deployment configuration')
        deploy.atomic_json(backup / 'rollback.json', {'hadOverride': True, 'databasePath': str(deploy.DATA / 'workflow'), 'jarSha256': deploy.sha256(jar)})
        deploy.seal_backup(backup)
        deploy.record(dict(self.metadata, id=identity), 'SUCCESS')
        os.utime(str(backup), (modified, modified))
        return backup

    def prepare_retention(self):
        deploy.JAR.write_bytes(b'current running program')
        self.metadata['jarSha256'] = deploy.sha256(deploy.JAR)
        deploy.LOCK.write_text(self.metadata['id'])
        deploy.record(self.metadata, 'SUCCESS')
        return self.complete_snapshot(self.metadata['id'], 3000)

    def test_retention_keeps_two_complete_backups_and_preserves_active_builds_data_and_history(self):
        current = self.prepare_retention()
        previous = self.complete_snapshot('release-previous', 2000)
        old = self.complete_snapshot('release-old', 1000)
        legacy = deploy.APP / 'backups/release-legacy'
        legacy.mkdir(); (legacy / 'leon-lite-workflow.jar').write_bytes(b'old incomplete copy')
        database = deploy.DATA / 'workflow.mv.db'
        database.write_bytes(b'live business data')
        control = deploy.DATA / 'deploy-control'; control.mkdir()
        deploy.atomic_json(control / 'request.json', {'id': 'web-release-building'})
        for name in ['release-old', 'release-previous', 'web-release-building', '.m2', 'repository.git', 'unrelated']:
            (deploy.CACHE / name).mkdir()
        outside = deploy.DATA / 'other-project'; outside.mkdir()
        (outside / 'private.txt').write_text('must remain')
        (deploy.CACHE / 'release-symlink').symlink_to(outside, target_is_directory=True)
        for root, name, identity in [(deploy.STAGING_ROOT, 'leon-release-Old', 'release-old'),
                                     (deploy.STAGING_ROOT, 'leon-release-New', self.metadata['id']),
                                     (deploy.STAGING_ROOT, 'leon-release-Pending', 'release-pending'),
                                     (deploy.LEGACY_STAGING_ROOT, 'leon-release-Legacy', 'release-previous')]:
            path = root / name; path.mkdir(); deploy.atomic_json(path / 'metadata.json', {'id': identity})
        orphan = deploy.STAGING_ROOT / 'leon-release-Orphan'; orphan.mkdir()
        (orphan / 'app.jar').write_bytes(b'interrupted upload'); os.utime(str(orphan), (1, 1))
        records_before = sorted(path.name for path in deploy.HISTORY.iterdir())
        deploy.prune_deployments(self.metadata)
        self.assertEqual({current.name, previous.name}, {path.name for path in (deploy.APP / 'backups').iterdir()})
        self.assertFalse(old.exists())
        self.assertFalse((deploy.CACHE / 'release-old').exists())
        for name in ['release-previous', 'web-release-building', '.m2', 'repository.git', 'unrelated', 'release-symlink']:
            self.assertTrue((deploy.CACHE / name).exists())
        self.assertEqual('must remain', (outside / 'private.txt').read_text())
        self.assertFalse((deploy.STAGING_ROOT / 'leon-release-Old').exists())
        self.assertFalse(orphan.exists())
        self.assertTrue((deploy.STAGING_ROOT / 'leon-release-New').exists())
        self.assertTrue((deploy.STAGING_ROOT / 'leon-release-Pending').exists())
        self.assertFalse((deploy.LEGACY_STAGING_ROOT / 'leon-release-Legacy').exists())
        self.assertEqual(b'live business data', database.read_bytes())
        self.assertEqual(records_before, sorted(path.name for path in deploy.HISTORY.iterdir()))

    def test_retention_rejects_corruption_and_keeps_an_older_usable_rollback(self):
        current = self.prepare_retention()
        corrupt = self.complete_snapshot('release-corrupt', 2500)
        (corrupt / 'database/workflow.mv.db').write_bytes(b'corrupted backup')
        usable = self.complete_snapshot('release-usable', 2000)
        self.assertFalse(deploy.complete_backup(corrupt))
        deploy.prune_deployments(self.metadata)
        self.assertEqual({current.name, usable.name}, {path.name for path in (deploy.APP / 'backups').iterdir()})

    def test_incomplete_current_backup_prevents_all_cleanup(self):
        current = self.prepare_retention()
        self.complete_snapshot('release-previous', 2000)
        old = self.complete_snapshot('release-old', 1000)
        (current / '99-deployment.conf').unlink()
        with self.assertRaises(RuntimeError):
            deploy.prune_deployments(self.metadata)
        self.assertTrue(old.exists())

    def test_failed_or_concurrent_deployment_cannot_prune_backups(self):
        self.prepare_retention()
        old = self.complete_snapshot('release-old', 1000)
        deploy.record(self.metadata, 'DEPLOYING')
        with self.assertRaises(RuntimeError):
            deploy.prune_deployments(self.metadata)
        deploy.record(self.metadata, 'SUCCESS')
        deploy.LOCK.write_text('another-release')
        with self.assertRaises(RuntimeError):
            deploy.prune_deployments(self.metadata)
        self.assertTrue(old.exists())

    def test_fewer_than_two_valid_backups_does_not_prune(self):
        self.prepare_retention()
        incomplete = deploy.APP / 'backups/release-incomplete'; incomplete.mkdir()
        (incomplete / 'previous.jar').write_bytes(b'incomplete')
        deploy.prune_deployments(self.metadata)
        self.assertTrue(incomplete.exists())

    def test_cleanup_failure_does_not_roll_back_a_verified_release(self):
        self.prepare_retention()
        with patch.object(deploy, 'services'), patch.object(deploy, 'prune_deployments', side_effect=OSError('cleanup failed')), \
                patch.object(deploy, 'rollback') as rollback:
            deploy.finish(self.metadata)
        self.assertFalse(deploy.LOCK.exists())
        self.assertEqual('SUCCESS', json.loads((deploy.HISTORY / 'release-test.json').read_text())['status'])
        rollback.assert_not_called()


if __name__ == '__main__':
    unittest.main()
