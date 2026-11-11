#!/usr/bin/env python3
"""Build with Java 21 and deploy through Workbench, preserving data and release history.

Run locally: python3 scripts/deploy.py --description '本次部署说明'
The --remote-action option is used internally on ECS (Python 3.6 compatible).
"""
import argparse
import datetime
import hashlib
import json
import os
import pathlib
import re
import shlex
import shutil
import ssl
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import uuid

INSTANCE = 'i-bp1dc1ow687ogmpmxc87'
BASE_URL = 'https://121.40.254.112'
SERVICE = 'leon-lite-workflow.service'
APP = pathlib.Path('/opt/leon-lite-workflow')
JAR = APP / 'leon-lite-workflow.jar'
DATA = pathlib.Path('/var/lib/leon-lite-workflow')
HISTORY = DATA / 'deployments'
OVERRIDE = pathlib.Path('/etc/systemd/system/' + SERVICE + '.d/99-deployment.conf')
LOCK = APP / '.deployment-lock'


def run(args, env=None):
    result = subprocess.run([str(value) for value in args], env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode:
        # Do not include process environments, request headers, or command output in release records.
        raise RuntimeError('command failed: ' + str(args[0]) + ' ' + str(args[1]))
    return result.stdout.decode().strip()


def sha256(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def now():
    return datetime.datetime.now(datetime.timezone.utc).isoformat().replace('+00:00', 'Z')


def atomic_json(path, data):
    temporary = path.with_name(path.name + '.' + uuid.uuid4().hex + '.tmp')
    with temporary.open('x', encoding='utf-8') as stream:
        json.dump(data, stream, ensure_ascii=False, indent=2)
        stream.write('\n')
        stream.flush()
        os.fsync(stream.fileno())
    os.chmod(str(temporary), 0o644)
    os.replace(str(temporary), str(path))


def replace_file(source, target):
    temporary = target.with_name(target.name + '.release-new')
    shutil.copy2(str(source), str(temporary))
    os.chmod(str(temporary), 0o644)
    os.replace(str(temporary), str(target))


def environment():
    pid = run(['systemctl', 'show', SERVICE, '-p', 'MainPID', '--value'])
    return dict(entry.split(b'=', 1) for entry in pathlib.Path('/proc/' + pid + '/environ').read_bytes().split(b'\0') if b'=' in entry)


def hello():
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen('http://127.0.0.1:80/hello', timeout=3) as response:
                if response.getcode() == 200 and response.read().decode() == '你好':
                    return
        except Exception:
            pass
        time.sleep(1)
    raise RuntimeError('local hello health check failed')


def services():
    for unit in [SERVICE, 'leon-plugin-tls.service', 'leon-plugin-cert-renew.timer']:
        if run(['systemctl', 'is-active', unit]) != 'active':
            raise RuntimeError('service unavailable: ' + unit)
    run(['openssl', 'x509', '-in', '/etc/letsencrypt/live/121.40.254.112/fullchain.pem', '-noout', '-checkend', '86400'])
    result = run(['systemctl', 'show', 'leon-plugin-cert-renew.service', '-p', 'Result', '--value'])
    if result != 'success':
        raise RuntimeError('certificate renewal last run was not successful')


def import_legacy_history():
    # Earlier deployments wrote release.json only after their health checks succeeded.
    for path in (APP / 'backups').glob('*/release.json'):
        try:
            data = json.loads(path.read_text())
        except (OSError, ValueError):
            continue
        if not re.fullmatch(r'[0-9a-f]{40}', data.get('commit', '')) or not re.fullmatch(r'[0-9a-f]{64}', data.get('jar_sha256', '')):
            continue
        identity = 'legacy-' + path.parent.name
        target = HISTORY / (identity + '.json')
        if target.exists():
            continue
        finished = datetime.datetime.fromtimestamp(path.stat().st_mtime, datetime.timezone.utc)
        # The completion timestamp is known; the original start time was not recorded.
        atomic_json(target, {'id': identity, 'startedAt': finished.isoformat(), 'finishedAt': None,
            'version': data['commit'][:8], 'commit': data['commit'], 'dirty': False,
            'description': '历史部署（时间为校验完成时间，未记录耗时）', 'status': 'SUCCESS', 'jarSha256': data['jar_sha256']})


def record(metadata, status):
    path = HISTORY / (metadata['id'] + '.json')
    data = json.loads(path.read_text()) if path.exists() else {}
    data.update({key: metadata[key] for key in ['id', 'version', 'commit', 'dirty', 'description', 'jarSha256']})
    data.setdefault('startedAt', now())
    data['status'] = status
    data['finishedAt'] = None if status == 'DEPLOYING' else now()
    atomic_json(path, data)


def rollback(metadata):
    backup = APP / 'backups' / metadata['id']
    if not (backup / 'rollback.json').is_file():
        return
    if not LOCK.exists():
        path = HISTORY / (metadata['id'] + '.json')
        if path.is_file() and json.loads(path.read_text()).get('status') in ('SUCCESS', 'ROLLED_BACK'):
            return
        raise RuntimeError('deployment lock missing; inspect state before recovery')
    if LOCK.read_text().strip() != metadata['id']:
        raise RuntimeError('another deployment owns the lock')
    state = json.loads((backup / 'rollback.json').read_text())
    try:
        run(['systemctl', 'stop', SERVICE])
        replace_file(backup / 'leon-lite-workflow.jar', JAR)
        if state['hadOverride']:
            replace_file(backup / '99-deployment.conf', OVERRIDE)
        elif OVERRIDE.exists():
            OVERRIDE.unlink()
        run(['systemctl', 'daemon-reload'])
        run(['systemctl', 'start', SERVICE])
        hello()
        services()
        if sha256(JAR) != state['jarSha256']:
            raise RuntimeError('restored JAR checksum mismatch')
        url = environment().get(b'LEON_DB_URL', b'').decode()
        if url.split(';', 1)[0] != 'jdbc:h2:file:' + state['databasePath']:
            raise RuntimeError('database location changed')
        record(metadata, 'ROLLED_BACK')
        LOCK.unlink()
        print('ROLLBACK=verified', flush=True)
    except Exception:
        record(metadata, 'ROLLBACK_FAILED')
        raise


def apply(metadata):
    staged = pathlib.Path(metadata['stagedJar'])
    if sha256(staged) != metadata['jarSha256']:
        raise RuntimeError('uploaded JAR checksum mismatch')
    services()
    before = environment()
    if before.get(b'LEON_AUTH_REQUIRED') != b'true' or before.get(b'LEON_AUTH_ADMIN_ORIGIN') != BASE_URL.encode():
        raise RuntimeError('expected production authentication configuration')
    match = re.fullmatch(rb'jdbc:h2:file:(/var/lib/leon-lite-workflow/[^;]+)(?:;.*)?', before.get(b'LEON_DB_URL', b''))
    if not match:
        raise RuntimeError('expected persistent H2 database')
    database = pathlib.Path(match.group(1).decode())
    if not pathlib.Path(str(database) + '.mv.db').is_file():
        raise RuntimeError('persistent H2 database missing')
    owner = DATA.stat()  # DynamicUser accounts disappear while the service is stopped.
    with LOCK.open('x') as lock:
        lock.write(metadata['id'])
    backup = APP / 'backups' / metadata['id']
    changed = False
    try:
        backup.mkdir(mode=0o700)
        shutil.copy2(str(JAR), str(backup / 'leon-lite-workflow.jar'))
        if OVERRIDE.exists():
            shutil.copy2(str(OVERRIDE), str(backup / '99-deployment.conf'))
        atomic_json(backup / 'rollback.json', {'hadOverride': OVERRIDE.exists(), 'jarSha256': sha256(JAR),
            'databasePath': str(database)})
        HISTORY.mkdir(mode=0o750, exist_ok=True)
        os.chown(str(HISTORY), owner.st_uid, owner.st_gid)
        import_legacy_history()
        record(metadata, 'DEPLOYING')
        changed = True
        run(['systemctl', 'stop', SERVICE])
        db_backup = backup / 'database'
        db_backup.mkdir(mode=0o700)
        for path in database.parent.glob(database.name + '.*'):
            if path.is_file():
                shutil.copy2(str(path), str(db_backup / path.name))
        if not (db_backup / (database.name + '.mv.db')).is_file():
            raise RuntimeError('database backup missing')
        OVERRIDE.write_text('[Service]\nEnvironment=LEON_DEPLOYMENTS_DIRECTORY=' + str(HISTORY)
            + '\nEnvironment=LEON_DEPLOYMENT_ID=' + metadata['id'] + '\n')
        os.chmod(str(OVERRIDE), 0o644)
        replace_file(staged, JAR)
        if sha256(JAR) != metadata['jarSha256']:
            raise RuntimeError('installed JAR checksum mismatch')
        run(['systemctl', 'daemon-reload'])
        run(['systemctl', 'start', SERVICE])
        hello()
        after = environment()
        for key in [b'LEON_DB_URL', b'LEON_DB_PASSWORD', b'LEON_AUTH_REQUIRED', b'LEON_AUTH_TOKEN_SHA256',
                    b'LEON_AUTH_ADMIN_ORIGIN', b'LEON_ALFRED_API_KEY', b'YOUDAO_APP_KEY', b'YOUDAO_APP_SECRET', b'LEON_MUSIC_DIRECTORY']:
            if before.get(key) != after.get(key):
                raise RuntimeError('existing private configuration changed')
        services()
        print('DEPLOYED=' + metadata['id'] + ' (awaiting public checks)', flush=True)
    except Exception:
        if changed:
            rollback(metadata)
        else:
            if HISTORY.is_dir():
                record(metadata, 'FAILED')
            LOCK.unlink()
        raise


def public_checks(metadata):
    token = (pathlib.Path.home() / '.config/leon-workflow/api-token').read_text().strip()
    if not token:
        raise RuntimeError('Alfred access token unavailable')
    context = ssl.create_default_context()
    def request(path, authenticated=False, data=None):
        headers = {'Authorization': 'Bearer ' + token} if authenticated else {}
        if data is not None:
            headers['Content-Type'] = 'application/json'
        req = urllib.request.Request(BASE_URL + path, headers=headers, data=data)
        try:
            return urllib.request.urlopen(req, context=context, timeout=30)
        except urllib.error.HTTPError as error:
            return error
    with request('/alfred/query', data=b'{"param":"1+2"}') as response:
        if response.getcode() != 401:
            raise RuntimeError('public Alfred authentication interception failed')
    with request('/api/monitor') as response:
        if response.getcode() != 401:
            raise RuntimeError('public monitor authentication interception failed')
    with request('/alfred/query', True, b'{"param":"1+2"}') as response:
        if response.getcode() != 200 or not any(item.get('title') == '3' or item.get('msgNeedToCopy') == '3' for item in json.load(response)):
            raise RuntimeError('authorized Alfred query failed')
    with request('/api/monitor', True) as response:
        if response.getcode() != 200:
            raise RuntimeError('authorized monitoring failed')
        history = json.load(response).get('deployments', {})
        if history.get('currentId') != metadata['id'] or not any(item['id'] == metadata['id'] for item in history.get('records', [])):
            raise RuntimeError('current deployment history is missing')
    print('PUBLIC_HTTPS_AUTH_ALFRED_AND_HISTORY=passed', flush=True)


def local(description):
    root = pathlib.Path(__file__).resolve().parent.parent
    os.chdir(str(root))
    # Fail before changing the server if the existing local verification credential is unavailable.
    if not (pathlib.Path.home() / '.config/leon-workflow/api-token').is_file():
        raise RuntimeError('Alfred access token unavailable for public verification')
    java_home = run(['/usr/libexec/java_home', '-v', '21'])
    env = dict(os.environ, JAVA_HOME=java_home)
    env['PATH'] = java_home + '/bin:' + env.get('PATH', '')
    print('Building and testing with Java 21...', flush=True)
    # Maven test output stays local; it is never copied into deployment history.
    subprocess.run(['mvn', '-B', 'clean', 'verify'], env=env, check=True)
    jar = root / 'target/hello-service-0.0.1-SNAPSHOT.jar'
    commit = run(['git', 'rev-parse', 'HEAD'])
    dirty = bool(run(['git', 'status', '--porcelain']))
    stamp = datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8))).strftime('%Y.%m.%d.%H%M%S')
    identity = 'release-' + stamp + '-' + commit[:8] + '-' + uuid.uuid4().hex[:6]
    metadata = {'id': identity, 'version': stamp, 'commit': commit, 'dirty': dirty,
        'description': description or ('部署工作区版本' if dirty else run(['git', 'log', '-1', '--format=%s'])), 'jarSha256': sha256(jar)}
    def workbench(command, timeout=30):
        return run(['workbench', 'exec', '--instance-id', INSTANCE, '--command', command, '--timeout', str(timeout)])
    staged = workbench('mktemp -d /tmp/leon-release-XXXXXXXX').strip()
    if not re.fullmatch(r'/tmp/leon-release-[a-zA-Z0-9]+', staged):
        raise RuntimeError('unexpected remote staging path')
    metadata['stagedJar'] = staged + '/app.jar'
    script = pathlib.Path(__file__).resolve()
    with tempfile.TemporaryDirectory(prefix='leon-release-') as temporary:
        manifest = pathlib.Path(temporary) / 'metadata.json'
        manifest.write_text(json.dumps(metadata, ensure_ascii=False), encoding='utf-8')
        for source, name in [(jar, 'app.jar'), (script, 'deploy.py'), (manifest, 'metadata.json')]:
            destination = staged + '/' + name
            workbench('test ! -e ' + shlex.quote(destination))
            run(['workbench', 'upload', source, destination, '--instance-id', INSTANCE])
            expected = sha256(source)
            verify = 'import hashlib,pathlib; assert hashlib.sha256(pathlib.Path(' + repr(destination) + ').read_bytes()).hexdigest()==' + repr(expected)
            workbench('python3 -c ' + shlex.quote(verify))
            print('Uploaded and verified ' + name, flush=True)
        def remote(action):
            return workbench(' '.join(shlex.quote(value) for value in ['python3', staged + '/deploy.py',
                '--remote-action', action, '--metadata', staged + '/metadata.json']), 240)
        try:
            print(remote('apply'), flush=True)
            public_checks(metadata)
            print(remote('success'), flush=True)
        except Exception:
            print(remote('rollback'), flush=True)
            raise
    print('Deployment complete: ' + identity, flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--description', default='')
    parser.add_argument('--remote-action', choices=['apply', 'success', 'rollback'])
    parser.add_argument('--metadata')
    args = parser.parse_args()
    if args.remote_action:
        if os.geteuid() != 0:
            raise RuntimeError('remote deployment requires root')
        metadata = json.loads(pathlib.Path(args.metadata).read_text())
        if not re.fullmatch(r'release-[a-zA-Z0-9.\-]+', metadata['id']) or not re.fullmatch(r'[0-9a-f]{64}', metadata['jarSha256']):
            raise RuntimeError('invalid release metadata')
        if args.remote_action == 'apply':
            apply(metadata)
        elif args.remote_action == 'rollback':
            rollback(metadata)
        else:
            if LOCK.read_text().strip() != metadata['id'] or sha256(JAR) != metadata['jarSha256']:
                raise RuntimeError('deployment changed before final verification')
            services()
            record(metadata, 'SUCCESS')
            LOCK.unlink()
            print('DEPLOYMENT_RECORDED=SUCCESS', flush=True)
    else:
        if len(args.description) > 240 or any(ord(char) < 32 for char in args.description):
            raise RuntimeError('deployment description must be one line, at most 240 characters')
        local(args.description)


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print('DEPLOYMENT_FAILED=' + type(error).__name__ + (': ' + str(error) if isinstance(error, RuntimeError) else ''), file=sys.stderr, flush=True)
        sys.exit(1)
