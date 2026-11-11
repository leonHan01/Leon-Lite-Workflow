#!/usr/bin/env python3
"""Install verified, trusted worker files from a new Workbench staging directory."""
import argparse
import os
import pathlib
import pwd
import shutil
import subprocess
import time


def run(args, env=None):
    subprocess.run([str(item) for item in args], env=env, check=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--staging', required=True)
    args = parser.parse_args()
    if os.geteuid() != 0:
        raise RuntimeError('installation requires root')
    stage = pathlib.Path(args.staging)
    install = pathlib.Path('/opt/leon-workflow-deploy')
    install.mkdir(mode=0o755, exist_ok=True)
    backup = install / ('previous-' + str(int(time.time())))
    backup.mkdir(mode=0o700)
    for name in ['deploy.py', 'server_deploy.py']:
        target = install / name
        if target.exists():
            shutil.copy2(str(target), str(backup / name))
        shutil.copy2(str(stage / name), str(target))
        os.chmod(str(target), 0o755)
    (install / 'jobs').mkdir(mode=0o700, exist_ok=True)
    maven = install / 'maven'
    if not maven.exists():
        maven.mkdir(mode=0o755)
        run(['tar', '-xzf', stage / 'maven.tar.gz', '-C', maven])
    try:
        builder = pwd.getpwnam('leon-workflow-build')
    except KeyError:
        run(['useradd', '--system', '--user-group', '--home-dir', '/var/cache/leon-workflow-deploy', '--shell', '/sbin/nologin', 'leon-workflow-build'])
        builder = pwd.getpwnam('leon-workflow-build')
    if builder.pw_uid == 0:
        raise RuntimeError('build account must be unprivileged')
    cache = pathlib.Path('/var/cache/leon-workflow-deploy')
    cache.mkdir(mode=0o750, exist_ok=True)
    os.chown(str(cache), builder.pw_uid, builder.pw_gid)
    pathlib.Path('/var/log/leon-workflow-deploy').mkdir(mode=0o700, exist_ok=True)
    data = pathlib.Path('/var/lib/leon-lite-workflow')
    owner = data.stat()
    control = data / 'deploy-control'
    control.mkdir(mode=0o750, exist_ok=True)
    os.chown(str(control), owner.st_uid, owner.st_gid)
    units = pathlib.Path('/etc/systemd/system')
    files = {
        units / 'leon-workflow-deploy.service': '''[Unit]
Description=Build and deploy Leon Workflow from Git main
After=network-online.target leon-lite-workflow.service
Wants=network-online.target

[Service]
Type=oneshot
ExecStart=/usr/bin/python3 /opt/leon-workflow-deploy/server_deploy.py
ExecStopPost=/usr/bin/python3 /opt/leon-workflow-deploy/server_deploy.py --recover
TimeoutStartSec=1800
TimeoutStopSec=180
Nice=10
MemoryMax=1050M
CPUQuota=150%
NoNewPrivileges=true
PrivateTmp=true
ProtectHome=true
ProtectSystem=full
ReadWritePaths=/etc/systemd/system/leon-lite-workflow.service.d
''',
        units / 'leon-workflow-deploy.path': '''[Unit]
Description=Watch for authenticated Leon Workflow deployment requests
After=leon-lite-workflow.service

[Path]
PathExists=/var/lib/leon-lite-workflow/deploy-control/request.json
Unit=leon-workflow-deploy.service

[Install]
WantedBy=multi-user.target
''',
        units / 'leon-lite-workflow.service.d/100-web-deploy.conf': '''[Service]
Environment=LEON_DEPLOY_ENABLED=true
Environment=LEON_DEPLOY_SELF_VERIFY=true
Environment=LEON_DEPLOY_CONTROL_DIRECTORY=/var/lib/leon-lite-workflow/deploy-control
'''
    }
    for path, content in files.items():
        if path.exists():
            shutil.copy2(str(path), str(backup / path.name))
        path.write_text(content)
        os.chmod(str(path), 0o644)
    nginx = pathlib.Path('/etc/leon-plugin-tls/nginx.conf')
    content = nginx.read_text()
    if 'location = /api/monitor/deploy {' not in content:
        shutil.copy2(str(nginx), str(backup / 'nginx.conf'))
        anchor = '        location / { return 403; }'
        if content.count(anchor) != 1:
            raise RuntimeError('unexpected HTTPS proxy configuration')
        block = '''        location = /api/monitor/deploy {
            client_max_body_size 1k;
            proxy_pass http://127.0.0.1:80;
            proxy_set_header Host $host;
            proxy_set_header X-Forwarded-Proto https;
        }
'''
        nginx.write_text(content.replace(anchor, block + anchor))
        try:
            run(['/usr/sbin/nginx', '-t', '-c', nginx])
            run(['systemctl', 'reload', 'leon-plugin-tls.service'])
        except Exception:
            shutil.copy2(str(backup / 'nginx.conf'), str(nginx))
            raise
    run(['systemd-analyze', 'verify', units / 'leon-workflow-deploy.service', units / 'leon-workflow-deploy.path'])
    run(['systemctl', 'daemon-reload'])
    run(['systemctl', 'enable', '--now', 'leon-workflow-deploy.path'])
    env = dict(os.environ, JAVA_HOME=str(pathlib.Path('/usr/bin/java').resolve().parent.parent))
    run([maven / 'bin/mvn', '-version'], env=env)
    print('DEPLOYMENT_WORKER_INSTALLED=True', flush=True)


if __name__ == '__main__':
    main()
