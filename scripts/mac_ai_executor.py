#!/usr/bin/env python3
"""OpenAI-only Mac executor. Outbound HTTPS, scoped token, no prompt/key logging or disk spool."""
import argparse
import concurrent.futures
import http.cookiejar
import json
import logging
import logging.handlers
import os
import pathlib
import plistlib
import shutil
import socket
import ssl
import stat
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

API = 'https://api.openai.com/v1/chat/completions'
HOME = pathlib.Path.home()
DIRECTORY = HOME / 'Library/Application Support/Leon Workflow/ai-executor'
CONFIG = HOME / '.config/leon-workflow/ai-executor/config.json'
PLIST = HOME / 'Library/LaunchAgents/com.leon.workflow.ai-executor.plist'
LABEL = 'com.leon.workflow.ai-executor'
LIMIT = 1_048_576


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        return None


def opener(cookies=None):
    handlers = [urllib.request.HTTPSHandler(context=ssl.create_default_context()), NoRedirect()]
    if cookies is not None:
        handlers.append(urllib.request.HTTPCookieProcessor(cookies))
    return urllib.request.build_opener(*handlers)


def server_url(value):
    parsed = urllib.parse.urlsplit(value)
    if parsed.scheme != 'https' or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment or parsed.path not in ('', '/'):
        raise ValueError('执行器服务器必须是没有路径及凭据的 HTTPS 地址')
    return value.rstrip('/')


def read_config(path=CONFIG):
    if path.is_symlink() or stat.S_IMODE(path.stat().st_mode) & 0o077:
        raise ValueError('执行器配置必须为私有文件（0600）')
    value = json.loads(path.read_text())
    value['server'] = server_url(value['server'])
    if not isinstance(value.get('token'), str) or len(value['token']) < 32:
        raise ValueError('执行器凭据无效，请重新安装')
    return value


def private_write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    descriptor, temporary = tempfile.mkstemp(dir=str(path.parent))
    try:
        os.fchmod(descriptor, 0o600)
        with os.fdopen(descriptor, 'wb') as stream:
            stream.write(data)
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def request_json(client, url, body=None, headers=None, timeout=12, method=None):
    encoded = None if body is None else json.dumps(body, ensure_ascii=False).encode('utf-8')
    request = urllib.request.Request(url, data=encoded, method=method, headers={'Content-Type': 'application/json', **(headers or {})})
    with client.open(request, timeout=timeout) as response:
        raw = response.read(LIMIT + 1)
        if len(raw) > LIMIT:
            raise ValueError('响应过大')
        return json.loads(raw) if raw else {}


def openai_ready():
    try:
        with opener().open('https://api.openai.com/v1/models', timeout=8) as response:
            return response.status == 200
    except urllib.error.HTTPError as error:
        return error.code in (401, 429)
    except Exception:
        return False


def redact(value, key):
    if isinstance(value, str):
        return value.replace(key, '[已隐藏凭据]') if key else value
    if isinstance(value, dict):
        return {name: redact(item, key) for name, item in value.items()}
    if isinstance(value, list):
        return [redact(item, key) for item in value]
    return value


def invoke_openai(job):
    """Exactly one provider attempt. Only server-delivery acknowledgments are retried."""
    remaining = job['deadlineAt'] / 1000 - time.time() - 2
    if remaining <= 0:
        return {'errorCode': 'REPLY_TIMEOUT'}
    key = job['apiKey']
    body = job['body']
    if body.get('stream') is not False or not isinstance(body.get('messages'), list):
        return {'errorCode': 'INVALID_RESPONSE'}
    try:
        request = urllib.request.Request(API, data=json.dumps(body, ensure_ascii=False).encode('utf-8'), headers={
            'Authorization': 'Bearer ' + key, 'Content-Type': 'application/json'})
        try:
            response = opener().open(request, timeout=max(1, min(180, remaining)))
        except urllib.error.HTTPError as error:
            # Do not read/store provider error text, which could contain secrets.
            error.close()
            return {'status': error.code, 'body': {}}
        with response:
            raw = response.read(LIMIT + 1)
            if len(raw) > LIMIT:
                return {'errorCode': 'TOO_LARGE'}
            payload = json.loads(raw)
            if not isinstance(payload, dict):
                return {'errorCode': 'INVALID_RESPONSE'}
            cleaned = redact(payload, key)
            if len(json.dumps(cleaned, ensure_ascii=False).encode('utf-8')) > 1_000_000:
                return {'errorCode': 'TOO_LARGE'}
            return {'status': response.status, 'body': cleaned}
    except ssl.SSLError:
        return {'errorCode': 'TLS'}
    except (socket.timeout, TimeoutError):
        return {'errorCode': 'REPLY_TIMEOUT'}
    except urllib.error.URLError as error:
        if isinstance(error.reason, ssl.SSLError):
            return {'errorCode': 'TLS'}
        return {'errorCode': 'CONNECT_TIMEOUT' if isinstance(error.reason, (socket.timeout, TimeoutError)) else 'NETWORK'}
    except (ValueError, RecursionError):
        return {'errorCode': 'INVALID_RESPONSE'}
    except Exception:
        return {'errorCode': 'NETWORK'}


class Worker:
    def __init__(self, config):
        self.config = config
        self.headers = {'Authorization': 'Bearer ' + config['token']}
        self.ready = threading.Event()
        self.stopped = threading.Event()

    def api(self, path, body):
        return request_json(opener(), self.config['server'] + '/api/ai-executor' + path, body, self.headers)

    def pulse(self):
        checked = 0
        ready = False
        while not self.stopped.is_set():
            if not self.ready.is_set() or time.monotonic() - checked > 60:
                ready = openai_ready()
                checked = time.monotonic()
            try:
                self.api('/heartbeat', {'ready': ready})
                if ready:
                    self.ready.set()
                else:
                    self.ready.clear()
            except Exception:
                self.ready.clear()
            self.stopped.wait(5)

    def loop(self):
        while not self.stopped.is_set():
            if not self.ready.wait(1):
                continue
            try:
                job = self.api('/claim', {})
                if not job:
                    self.stopped.wait(1)
                    continue
                # An ambiguous start acknowledgement MUST NOT trigger a provider request.
                self.api('/jobs/' + job['id'] + '/start', {'lease': job['lease']})
            except Exception:
                self.stopped.wait(2)
                continue
            result = invoke_openai(job)
            if result.get('errorCode') in ('NETWORK', 'TLS', 'CONNECT_TIMEOUT'):
                self.ready.clear()
            job.pop('apiKey', None)
            job.pop('body', None)
            until = max(time.time() + 15, min(job['deadlineAt'] / 1000 + 30, time.time() + 45))
            delivered = False
            while time.time() < until and not self.stopped.is_set():
                try:
                    self.api('/jobs/' + job['id'] + '/complete', {'lease': job['lease'], 'result': result})
                    delivered = True
                    break
                except urllib.error.HTTPError as error:
                    if error.code in (401, 403, 404, 409):
                        break
                except Exception:
                    pass
                self.stopped.wait(2)
            logging.info('result_delivered' if delivered else 'result_delivery_unconfirmed')

    def run(self):
        with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
            pool.submit(self.pulse)
            pool.submit(self.loop)
            pool.submit(self.loop)
            try:
                while True:
                    time.sleep(30)
            finally:
                self.stopped.set()


def install(server):
    server = server_url(server)
    if not openai_ready():
        raise ValueError('这台 Mac 尚不能连接 OpenAI，未启用线上切换')
    admin = opener(http.cookiejar.CookieJar())
    headers = {'Origin': server, 'X-Requested-With': 'XMLHttpRequest'}
    token_path = HOME / '.config/leon-workflow/api-token'
    request_json(admin, server + '/auth/login', {'token': token_path.read_text().strip(), 'remember': False}, headers)
    created = None
    try:
        if CONFIG.exists():
            config = read_config()
            if config['server'] != server:
                raise ValueError('执行器已有其他服务器配置，保留现状')
        else:
            created = request_json(admin, server + '/api/ai-executor-admin/workers', {'name': 'Mac mini'}, headers)
            config = {'server': server, 'id': created['id'], 'token': created['token']}
            private_write(CONFIG, json.dumps(config).encode())
        DIRECTORY.mkdir(parents=True, exist_ok=True, mode=0o700)
        private_write(DIRECTORY / 'worker.py', pathlib.Path(__file__).read_bytes())
        python = shutil.which('python3')
        if not python:
            raise ValueError('未找到 Python 3')
        plist = {'Label': LABEL, 'ProgramArguments': ['/usr/bin/caffeinate', '-i', python, str(DIRECTORY / 'worker.py'), 'run'],
                 'RunAtLoad': True, 'KeepAlive': True, 'ThrottleInterval': 10,
                 'ProcessType': 'Background', 'WorkingDirectory': str(DIRECTORY)}
        private_write(PLIST, plistlib.dumps(plist))
        domain = 'gui/' + str(os.getuid())
        subprocess.run(['launchctl', 'bootout', domain + '/' + LABEL], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        subprocess.run(['launchctl', 'bootstrap', domain, str(PLIST)], check=True, stdout=subprocess.DEVNULL)
        until = time.monotonic() + 35
        while time.monotonic() < until:
            status = request_json(admin, server + '/api/ai-executor-admin', headers=headers)
            if any(row['id'] == config['id'] and row['online'] for row in status['workers']):
                request_json(admin, server + '/api/ai-executor-admin/mode', {'enabled': True}, headers, method='PUT')
                print('MAC_EXECUTOR=online; OPENAI_MODE=mac; DEEPSEEK_MODE=server')
                return
            time.sleep(1)
        raise ValueError('Mac 执行器未就绪，线上执行模式保持原状')
    finally:
        try:
            request_json(admin, server + '/auth/logout', {}, headers)
        except Exception:
            pass


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['install', 'run', 'stop'])
    parser.add_argument('--server', default='https://121.40.254.112')
    args = parser.parse_args()
    if args.action == 'install':
        install(args.server)
    elif args.action == 'stop':
        subprocess.run(['launchctl', 'bootout', 'gui/' + str(os.getuid()) + '/' + LABEL], check=True)
        print('MAC_EXECUTOR=stopped; OpenAI remains assigned to Mac')
    else:
        DIRECTORY.mkdir(parents=True, exist_ok=True, mode=0o700)
        log = DIRECTORY / 'worker.log'
        if not log.exists():
            private_write(log, b'')
        handler = logging.handlers.RotatingFileHandler(str(log), maxBytes=256000, backupCount=2)
        logging.basicConfig(level=logging.INFO, handlers=[handler], format='%(asctime)s %(message)s')
        Worker(read_config()).run()


if __name__ == '__main__':
    try:
        main()
    except KeyboardInterrupt:
        pass
    except Exception:
        # Never include an exception repr, configuration, token, prompt, or provider response.
        print('AI 执行器未完成启动或配置，请检查服务状态和私有配置。', file=sys.stderr)
        sys.exit(1)
