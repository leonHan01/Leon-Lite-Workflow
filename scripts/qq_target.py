#!/usr/bin/env python3
"""Temporarily listen to the official QQ gateway to discover a notification target.

No inbound port, persistent process, credential file, or message send is needed.
AppSecret is entered with getpass; only a challenge and matched OpenID are printed.
"""
import argparse
import getpass
import json
import logging
import re
import secrets
import sys
import time
import urllib.request


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ValueError("QQ 接口返回重定向，已停止")


def access_token(app_id, app_secret):
    body = json.dumps({"appId": app_id, "clientSecret": app_secret}).encode()
    request = urllib.request.Request("https://api.bot.qq.com/app/getAppAccessToken", data=body,
                                     headers={"Content-Type": "application/json"})
    opener = urllib.request.build_opener(NoRedirect)
    with opener.open(request, timeout=10) as response:
        raw = response.read(1_048_577)
    if len(raw) > 1_048_576:
        raise ValueError("QQ 响应过大")
    result = json.loads(raw)
    if not isinstance(result, dict):
        raise ValueError("QQ 认证响应无效")
    if result.get("code") not in (None, 0):
        # Provider messages may echo sensitive request data. Report numeric codes only.
        code = result.get("code")
        raise ValueError(f"QQ 认证失败（错误码 {code if isinstance(code, int) else '未知'}），请检查 AppID / AppSecret")
    token = result.get("access_token")
    if not isinstance(token, str) or not re.fullmatch(r"[A-Za-z0-9._~+/=-]{3,8192}", token):
        raise ValueError("QQ 未返回有效访问令牌")
    return token


def target_from_event(event, code, target_type):
    if not isinstance(event, dict) or event.get("op") != 0:
        return None
    kind, data = event.get("t"), event.get("d")
    if not isinstance(data, dict) or not isinstance(data.get("content"), str):
        return None
    content = data["content"].strip()
    if target_type == "user" and kind == "C2C_MESSAGE_CREATE" and content == code:
        author = data.get("author")
        target = author.get("user_openid") if isinstance(author, dict) else None
    elif target_type == "group" and kind in ("GROUP_AT_MESSAGE_CREATE", "GROUP_MESSAGE_CREATE") and content.split() and content.split()[-1] == code:
        target = data.get("group_openid")
    else:
        return None
    if not isinstance(target, str) or not re.fullmatch(r"[A-Za-z0-9_-]{16,128}", target) or target.isdigit():
        return None
    return {"targetType": target_type, "targetId": target}


def listen(connect, token, target_type, timeout=120):
    code = "qq-bind:" + secrets.token_hex(12)
    deadline, seq, next_beat, interval = time.monotonic() + timeout, None, None, None
    silent = logging.getLogger("leon.qq.target")
    silent.disabled = True
    with connect("wss://api.bot.qq.com/websocket/", open_timeout=10, close_timeout=3,
                 max_size=1_048_576, logger=silent) as ws:
        identified = False
        while time.monotonic() < deadline:
            now = time.monotonic()
            if next_beat is not None and now >= next_beat:
                ws.send(json.dumps({"op": 1, "d": seq}))
                next_beat = now + interval
            wait = min(deadline - now, max(.01, next_beat - now) if next_beat else 10)
            if wait <= 0:
                break
            try:
                event = json.loads(ws.recv(timeout=wait))
            except TimeoutError:
                continue
            if not isinstance(event, dict):
                raise ValueError("QQ 网关响应无效")
            if event.get("s") is not None:
                seq = event["s"]
            if event.get("op") == 10:
                if identified:
                    raise ValueError("QQ 网关重复握手，请重新运行")
                heartbeat = event.get("d", {}).get("heartbeat_interval")
                if not isinstance(heartbeat, (int, float)) or not 1000 <= heartbeat <= 120000:
                    raise ValueError("QQ 网关心跳间隔无效")
                interval, next_beat = heartbeat / 1000, time.monotonic()
                ws.send(json.dumps({"op": 2, "d": {"token": "QQBot " + token,
                            "intents": 1 << 25, "shard": [0, 1], "properties": {}}}))
                identified = True
            elif event.get("op") in (7, 9):
                raise ValueError("QQ 网关要求重连或拒绝鉴权，请检查机器人权限后重新运行")
            elif event.get("t") == "READY":
                where = "在你与该机器人的单聊中" if target_type == "user" else "在目标 QQ 群中 @此机器人，并"
                print(f"连接成功。请{where}发送以下绑定码（{timeout} 秒内）：\n{code}", flush=True)
            result = target_from_event(event, code, target_type)
            if result:
                print("将以下接收类型和 OpenID 填入 QQ 消息节点：")
                print(json.dumps(result, ensure_ascii=False))
                return result
    raise ValueError("等待绑定超时，请确认添加了正确的机器人、消息事件权限及绑定码后重新运行")


def main():
    parser = argparse.ArgumentParser(description="获取 QQ 官方机器人单聊 / 群聊接收目标 OpenID；密钥不会写入文件或输出")
    parser.add_argument("--app-id", required=True, help="QQ 机器人 AppID")
    parser.add_argument("--target-type", choices=("user", "group"), default="user")
    args = parser.parse_args()
    if not re.fullmatch(r"[0-9]{1,32}", args.app_id):
        parser.error("AppID 必须为数字")
    try:
        from websockets.sync.client import connect
    except ImportError:
        print('请先在虚拟环境安装依赖：python3 -m pip install "websockets>=12,<18"', file=sys.stderr)
        return 1
    if not sys.stdin.isatty():
        print("请在交互终端运行，以隐藏输入 AppSecret", file=sys.stderr)
        return 1
    try:
        secret = getpass.getpass("QQ 机器人 AppSecret（隐藏输入）：").strip()
        if not secret or len(secret) > 1024 or any(c.isspace() for c in secret):
            raise ValueError("AppSecret 不能为空、包含空白或超过 1024 字符")
        token = access_token(args.app_id, secret)
        secret = None
        listen(connect, token, args.target_type)
        return 0
    except ValueError as error:
        # Only our own errors have safe messages; malformed JSON may contain business data.
        if isinstance(error, json.JSONDecodeError):
            print("QQ 返回的 JSON 格式无效", file=sys.stderr)
        else:
            print(str(error), file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("已取消绑定", file=sys.stderr)
        return 1
    except Exception:
        print("QQ 连接未完成；请检查网络、机器人启用状态和事件权限后重试。不会输出原始响应或密钥。", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
