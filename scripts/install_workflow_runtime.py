#!/usr/bin/env python3
"""Configure the workflow sandbox and private encryption key on ECS. Never print secret values."""
import base64
import datetime
import os
import pathlib
import secrets
import shutil
import stat
import subprocess

SERVICE = 'leon-lite-workflow.service'
KEY_FILE = pathlib.Path('/etc/leon-lite-workflow-workflows.env')
DROP_IN = pathlib.Path('/etc/systemd/system/' + SERVICE + '.d/96-workflows.conf')
PROXY = pathlib.Path('/etc/leon-plugin-tls/nginx.conf')


def run(args):
    result = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode:
        raise RuntimeError('runtime configuration command failed: ' + args[0])
    return result.stdout.decode().strip()


def loaded_key():
    pid = run(['systemctl', 'show', SERVICE, '-p', 'MainPID', '--value'])
    if pid == '0':
        return None
    environment = pathlib.Path('/proc/' + pid + '/environ').read_bytes().split(b'\0')
    for item in environment:
        if item.startswith(b'LEON_WORKFLOW_CREDENTIAL_KEY='):
            return item.split(b'=', 1)[1].decode()
    return None


def validate(value):
    try:
        return len(base64.b64decode(value, validate=True)) == 32
    except (ValueError, TypeError):
        return False


def configure_proxy():
    content = PROXY.read_text()
    page = 'location = /workflows.html {'
    api = 'location ~ ^/api/workflows(/|$) {'
    if page in content and api in content:
        return
    if page in content or api in content:
        raise RuntimeError('partial workflow proxy configuration; preserved without changes')
    anchor = '        location / { return 403; }'
    if content.count(anchor) != 1:
        raise RuntimeError('unexpected HTTPS proxy configuration')
    backup = pathlib.Path('/opt/leon-workflow-deploy/runtime-config-backups')
    backup.mkdir(mode=0o700, exist_ok=True)
    backup = backup / (datetime.datetime.utcnow().strftime('%Y%m%dT%H%M%S') + '-' + secrets.token_hex(3))
    backup.mkdir(mode=0o700)
    shutil.copy2(str(PROXY), str(backup / 'nginx.conf'))
    block = '''        location = /workflows.html {
            auth_request /_admin_session;
            error_page 401 =302 /admin-login.html;
            proxy_pass http://127.0.0.1:80;
            proxy_set_header Host $host;
            proxy_set_header X-Forwarded-Proto https;
        }
        location ~ ^/api/workflows(/|$) {
            client_max_body_size 1200k;
            proxy_pass http://127.0.0.1:80;
            proxy_set_header Host $host;
            proxy_set_header X-Forwarded-Proto https;
        }
'''
    temporary = PROXY.with_name('nginx.conf.workflow-new')
    with temporary.open('x') as stream:
        stream.write(content.replace(anchor, block + anchor))
    os.chmod(str(temporary), stat.S_IMODE(PROXY.stat().st_mode))
    os.replace(str(temporary), str(PROXY))
    try:
        run(['/usr/sbin/nginx', '-t', '-c', str(PROXY)])
        run(['systemctl', 'reload', 'leon-plugin-tls.service'])
    except Exception:
        shutil.copy2(str(backup / 'nginx.conf'), str(PROXY))
        run(['/usr/sbin/nginx', '-t', '-c', str(PROXY)])
        run(['systemctl', 'reload', 'leon-plugin-tls.service'])
        raise


def main():
    if os.geteuid() != 0:
        raise RuntimeError('runtime configuration requires root')
    packages = []
    if not pathlib.Path('/usr/bin/bwrap').is_file():
        packages.append('bubblewrap')
    if not pathlib.Path('/usr/bin/setpriv').is_file():
        packages.append('util-linux')
    if packages:
        manager = next((tool for tool in ['dnf', 'yum', 'apt-get'] if shutil.which(tool)), None)
        if manager is None:
            raise RuntimeError('no supported package manager for bubblewrap')
        if manager == 'apt-get':
            run([manager, 'update', '-qq'])
        run([manager, '-y', 'install'] + packages)
    if int(pathlib.Path('/proc/sys/user/max_user_namespaces').read_text().strip()) <= 0:
        raise RuntimeError('unprivileged user namespaces must be enabled')
    clone = pathlib.Path('/proc/sys/kernel/unprivileged_userns_clone')
    if clone.exists() and clone.read_text().strip() != '1':
        raise RuntimeError('unprivileged user namespaces must be enabled')
    current = loaded_key()
    if current and not validate(current):
        raise RuntimeError('existing loaded credential key is invalid; preserved without changes')
    if KEY_FILE.is_symlink():
        raise RuntimeError('credential key file must not be a symlink')
    if KEY_FILE.exists():
        values = dict(line.split('=', 1) for line in KEY_FILE.read_text().splitlines() if line and not line.startswith('#'))
        key = values.get('LEON_WORKFLOW_CREDENTIAL_KEY', '')
        if not validate(key) or (current and current != key):
            raise RuntimeError('existing credential key differs or is invalid; preserved without changes')
        if KEY_FILE.stat().st_uid != 0:
            raise RuntimeError('existing key file must be owned by root')
        os.chmod(str(KEY_FILE), 0o600)
        created = False
    else:
        key = current or base64.b64encode(secrets.token_bytes(32)).decode('ascii')
        descriptor = os.open(str(KEY_FILE), os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        with os.fdopen(descriptor, 'w') as stream:
            stream.write('LEON_WORKFLOW_CREDENTIAL_KEY=' + key + '\n')
            stream.flush()
            os.fsync(stream.fileno())
        created = not bool(current)
    DROP_IN.parent.mkdir(exist_ok=True)
    content = '[Service]\nEnvironmentFile=' + str(KEY_FILE) + '\n'
    if DROP_IN.is_symlink():
        raise RuntimeError('workflow drop-in must not be a symlink')
    if DROP_IN.exists() and DROP_IN.read_text() != content:
        raise RuntimeError('existing workflow drop-in differs; preserved without changes')
    if not DROP_IN.exists():
        descriptor = os.open(str(DROP_IN), os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o644)
        with os.fdopen(descriptor, 'w') as stream:
            stream.write(content)
    os.chmod(str(DROP_IN), 0o644)
    assert KEY_FILE.stat().st_uid == 0 and stat.S_IMODE(KEY_FILE.stat().st_mode) == 0o600
    run(['systemctl', 'daemon-reload'])
    configure_proxy()
    print('BUBBLEWRAP=' + run(['/usr/bin/bwrap', '--version']))
    print('WORKFLOW_KEY_CONFIGURED=True; CREATED=' + str(created) + '; ROOT_ONLY=True')
    print('WORKFLOW_RUNTIME_CONFIGURED=True; RESTART_DEFERRED_TO_DEPLOYMENT=True')
    print('WORKFLOW_HTTPS_ROUTES_CONFIGURED=True; PAGE_COOKIE_AUTH_REQUIRED=True; API_AUTH_ENFORCED_BY_APPLICATION=True')


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print('WORKFLOW_RUNTIME_CONFIGURATION_FAILED=' + type(error).__name__)
        raise SystemExit(1)
