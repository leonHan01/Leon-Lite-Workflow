#!/usr/bin/env python3
"""Add authenticated messaging routes to the existing HTTPS proxy (Python 3.6+).

The application continues to enforce Bearer/Cookie authentication. Agent administration
additionally requires a management session. This does not open other API paths.
"""
import datetime
import os
import pathlib
import secrets
import shutil
import stat
import subprocess

PROXY = pathlib.Path('/etc/leon-plugin-tls/nginx.conf')
MARKER = '        # Leon authenticated messaging routes\n'
ROUTE = 'location ~ ^/api/(device-messages|agents|conversations|agent-avatars|agent-admin)(/|$) {'
ANCHOR = '        location / { return 403; }'
BACKUP_ROUTE = '''        # Leon authenticated chat backup uploads
        location = /api/conversations/backups {
            client_max_body_size 32m;
            proxy_pass http://127.0.0.1:80;
            proxy_set_header Host $host;
            proxy_set_header X-Forwarded-Proto https;
        }
'''


def configured(content):
    updated = messaging_routes(content)
    if 'location = /api/conversations/backups {' not in updated:
        if updated.count(ANCHOR) != 1:
            raise RuntimeError('unexpected proxy layout; configuration preserved')
        updated = updated.replace(ANCHOR, BACKUP_ROUTE + ANCHOR)
    return updated


def messaging_routes(content):
    if ROUTE in content:
        if MARKER not in content:
            return content
        start = content.index(MARKER)
        end = content.index('        }\n', start) + len('        }\n')
        block = content[start:end]
        return content[:start] + block.replace('client_max_body_size 3m;', 'client_max_body_size 6m;') + content[end:]
    if MARKER in content or content.count(ANCHOR) != 1:
        raise RuntimeError('unexpected proxy layout; configuration preserved')
    block = MARKER + '''        location ~ ^/api/(device-messages|agents|conversations|agent-avatars|agent-admin)(/|$) {
            client_max_body_size 6m;
            proxy_pass http://127.0.0.1:80;
            proxy_set_header Host $host;
            proxy_set_header X-Forwarded-Proto https;
        }
'''
    return content.replace(ANCHOR, block + ANCHOR)


def main():
    if os.geteuid() != 0:
        raise RuntimeError('proxy configuration requires root')
    content = PROXY.read_text()
    updated = configured(content)
    if updated == content:
        print('MESSAGING_PROXY=already-configured')
        return
    backup = pathlib.Path('/opt/leon-workflow-deploy/runtime-config-backups')
    backup.mkdir(mode=0o700, parents=True, exist_ok=True)
    backup = backup / ('messaging-' + datetime.datetime.utcnow().strftime('%Y%m%dT%H%M%S') + '-' + secrets.token_hex(3))
    backup.mkdir(mode=0o700)
    shutil.copy2(str(PROXY), str(backup / 'nginx.conf'))
    temporary = PROXY.with_name('nginx.conf.messaging-new')
    with temporary.open('x') as stream:
        stream.write(updated)
    os.chmod(str(temporary), stat.S_IMODE(PROXY.stat().st_mode))
    os.replace(str(temporary), str(PROXY))
    try:
        subprocess.run(['/usr/sbin/nginx', '-t', '-c', str(PROXY)], check=True)
        subprocess.run(['systemctl', 'reload', 'leon-plugin-tls.service'], check=True)
    except Exception:
        shutil.copy2(str(backup / 'nginx.conf'), str(PROXY))
        subprocess.run(['/usr/sbin/nginx', '-t', '-c', str(PROXY)], check=True)
        subprocess.run(['systemctl', 'reload', 'leon-plugin-tls.service'], check=True)
        raise
    print('MESSAGING_PROXY=configured; application authentication retained')


if __name__ == '__main__':
    main()
