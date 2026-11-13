#!/usr/bin/env python3
"""Fixed server-side Git/Maven deployment worker. Installed root-owned outside the Git checkout."""
import argparse
import datetime
import fcntl
import json
import os
import pathlib
import pwd
import re
import subprocess
import signal
import sys
import time
import urllib.request

import deploy

CONTROL = deploy.DATA / 'deploy-control'
REQUEST = CONTROL / 'request.json'
INSTALL = pathlib.Path('/opt/leon-workflow-deploy')
JOBS = INSTALL / 'jobs'
CACHE = pathlib.Path('/var/cache/leon-workflow-deploy')
LOGS = pathlib.Path('/var/log/leon-workflow-deploy')
REPOSITORY = 'https://github.com/leonHan01/Leon-Lite-Workflow.git'
BUILDER = 'leon-workflow-build'
JAVA = str(pathlib.Path('/usr/bin/java').resolve().parent.parent)
WORKER_LOCK = pathlib.Path('/run/leon-workflow-deploy.lock')


def load_request():
    descriptor = os.open(str(REQUEST), os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(descriptor, 'r') as stream:
        data = json.loads(stream.read(4097))
    if not re.fullmatch(r'web-release-[0-9.]+-[0-9a-f]{8}', data.get('id', '')):
        raise RuntimeError('invalid request ID')
    if not re.fullmatch(r'[0-9]{4}\.[0-9]{2}\.[0-9]{2}\.[0-9]{6}', data.get('version', '')):
        raise RuntimeError('invalid release version')
    return data


def job(metadata, stage, message):
    deploy.atomic_json(CONTROL / 'job.json', {'id': metadata['id'], 'stage': stage, 'message': message,
        'startedAt': metadata.get('requestedAt', deploy.now()),
        'finishedAt': None if stage in ('QUEUED', 'FETCHING', 'BUILDING', 'DEPLOYING', 'VERIFYING') else deploy.now()})


def build_command(command, log, cwd=None, timeout=600):
    # Repository build plugins run as a separate unprivileged account with no application credentials.
    env = {'PATH': '/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin',
        'JAVA_HOME': JAVA, 'MAVEN_OPTS': '-Xmx384m -XX:MaxMetaspaceSize=192m', 'LANG': 'en_US.UTF-8',
        'GIT_TERMINAL_PROMPT': '0'}
    command = ['runuser', '-u', BUILDER, '--'] + [str(value) for value in command]
    process = subprocess.Popen(command, cwd=str(cwd) if cwd else None, env=env,
        stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
    try:
        code = process.wait(timeout=timeout)
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGTERM)
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait()
        raise
    if code:
        raise RuntimeError('build command failed')


def checkout(metadata, log):
    repository = CACHE / 'repository.git'
    if not repository.exists():
        build_command(['git', 'init', '--bare', repository], log)
    archive_fallback = False
    try:
        build_command(['git', '--git-dir=' + str(repository), '-c', 'credential.helper=', '-c', 'core.hooksPath=/dev/null',
            'fetch', '--no-tags', REPOSITORY, '+refs/heads/main:refs/heads/main'], log, timeout=30)
        metadata['commit'] = deploy.run(['runuser', '-u', BUILDER, '--', 'git', '--git-dir=' + str(repository), 'rev-parse', 'refs/heads/main'])
    except Exception:
        # Some ECS networks block Git smart HTTP while GitHub's official API/codeload work.
        job(metadata, 'FETCHING', 'Git 协议拉取受限，正在从 GitHub 官方归档按提交获取 main 代码。')
        request = urllib.request.Request('https://api.github.com/repos/leonHan01/Leon-Lite-Workflow/commits/main',
            headers={'User-Agent': 'Leon-Workflow-Deploy', 'Accept': 'application/vnd.github+json'})
        with urllib.request.urlopen(request, timeout=30) as response:
            metadata['commit'] = json.load(response)['sha']
        archive_fallback = True
    if not re.fullmatch(r'[0-9a-f]{40}', metadata['commit']):
        raise RuntimeError('invalid Git commit')
    root = CACHE / metadata['id']
    root.mkdir(mode=0o750)
    owner = pwd.getpwnam(BUILDER)
    os.chown(str(root), owner.pw_uid, owner.pw_gid)
    archive = root / 'source.tar'
    if archive_fallback:
        url = 'https://codeload.github.com/leonHan01/Leon-Lite-Workflow/tar.gz/' + metadata['commit']
        download = 'import sys,urllib.request,shutil; r=urllib.request.urlopen(sys.argv[1],timeout=30); f=open(sys.argv[2],"wb"); shutil.copyfileobj(r,f); f.close(); r.close()'
        build_command(['python3', '-c', download, url, archive], log, timeout=90)
    else:
        build_command(['git', '--git-dir=' + str(repository), 'archive', '--format=tar', '--output=' + str(archive), metadata['commit']], log)
    source = root / 'source'
    source.mkdir(mode=0o750)
    os.chown(str(source), owner.pw_uid, owner.pw_gid)
    build_command(['tar', '-xf', archive, '-C', source] + (['--strip-components=1'] if archive_fallback else []), log)
    return source


def wait_for_verification(metadata):
    receipt = CONTROL / ('verification-' + metadata['id'] + '.json')
    deadline = time.monotonic() + 75
    while time.monotonic() < deadline:
        if receipt.is_file():
            data = json.loads(receipt.read_text())
            if data.get('id') != metadata['id'] or not data.get('success'):
                raise RuntimeError('public authenticated check failed')
            return
        time.sleep(1)
    raise RuntimeError('public verification timed out')


def recover():
    if not REQUEST.exists():
        return
    request = load_request()
    metadata_file = JOBS / (request['id'] + '.json')
    if not metadata_file.is_file():
        job(request, 'FAILED', '后台任务中断，请重新部署。')
        REQUEST.unlink()
        return
    metadata = json.loads(metadata_file.read_text())
    if (deploy.APP / 'backups' / metadata['id'] / 'rollback.json').exists():
        deploy.rollback(metadata)
        job(metadata, 'ROLLED_BACK', '后台任务中断，已恢复旧版服务。')
    else:
        deploy.record(metadata, 'FAILED')
        job(metadata, 'FAILED', '构建任务中断，当前服务未替换。')
    REQUEST.unlink()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--recover', action='store_true')
    args = parser.parse_args()
    if os.geteuid() != 0:
        raise RuntimeError('worker must be installed as a root service')
    if args.recover:
        recover()
        return
    with WORKER_LOCK.open('w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        request = load_request()
        metadata = dict(request, commit='', dirty=False, description='网页一键部署 · main', jarSha256='')
        metadata_file = JOBS / (metadata['id'] + '.json')
        deploy.atomic_json(metadata_file, metadata)
        deploy.record(metadata, 'DEPLOYING')
        stage = 'FETCHING'
        try:
            job(metadata, stage, '正在从 GitHub 拉取 main 分支最新代码。')
            with (LOGS / (metadata['id'] + '.log')).open('x') as log:
                os.chmod(log.name, 0o600)
                source = checkout(metadata, log)
                # Prevent an old branch from silently removing the monitor/deployment controls.
                if not (source / 'src/main/java/com/leon/workflow/monitor/DeploymentVerificationService.java').is_file():
                    job(metadata, 'FAILED', 'Git main 尚未包含一键部署代码，请先推送本次改动。')
                    metadata['description'] = 'Git main 尚未包含一键部署代码'
                    deploy.record(metadata, 'FAILED')
                    return
                stage = 'BUILDING'
                deploy.record(metadata, 'DEPLOYING')
                job(metadata, stage, '代码拉取完成，正在使用 Java 21 和 Maven 打包。')
                build_command([INSTALL / 'maven/bin/mvn', '-B', '-DskipTests', 'clean', 'package'], log, cwd=source, timeout=900)
                metadata['stagedJar'] = str(source / 'target/hello-service-0.0.1-SNAPSHOT.jar')
                deploy.verify_music_routes(pathlib.Path(metadata['stagedJar']))
                metadata['jarSha256'] = deploy.sha256(pathlib.Path(metadata['stagedJar']))
                deploy.atomic_json(metadata_file, metadata)
            stage = 'DEPLOYING'
            job(metadata, stage, '构建完成，正在备份数据并更新服务。')
            deploy.apply(metadata)
            stage = 'VERIFYING'
            job(metadata, stage, '服务已启动，正在验证 HTTPS、认证、Alfred 查询和音乐电台。')
            wait_for_verification(metadata)
            deploy.finish(metadata)
            job(metadata, 'SUCCESS', '部署成功，服务器已更新为 main 分支最新版本。')
        except Exception:
            status = 'FAILED'
            if (deploy.APP / 'backups' / metadata['id'] / 'rollback.json').exists():
                try:
                    deploy.rollback(metadata)
                    status = json.loads((deploy.HISTORY / (metadata['id'] + '.json')).read_text())['status']
                except Exception:
                    status = 'ROLLBACK_FAILED'
            else:
                deploy.record(metadata, status)
            messages = {'FAILED': 'Git 拉取失败，请稍后重试。' if stage == 'FETCHING' else 'Maven 构建失败，当前服务未替换。',
                'ROLLED_BACK': '部署或验证失败，已恢复旧版服务。', 'ROLLBACK_FAILED': '自动回滚未通过，请检查服务器服务状态。'}
            job(metadata, status, messages.get(status, '部署未完成，请检查服务器状态。'))
        finally:
            REQUEST.unlink()


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print('SERVER_DEPLOY_FAILED=' + type(error).__name__, file=sys.stderr, flush=True)
        sys.exit(1)
