import contextlib
import importlib.util
import io
import json
from pathlib import Path
import unittest
from unittest.mock import MagicMock, patch

spec = importlib.util.spec_from_file_location("qq_target", Path(__file__).resolve().parents[3] / "scripts/qq_target.py")
qq = importlib.util.module_from_spec(spec)
spec.loader.exec_module(qq)


class QqTargetTest(unittest.TestCase):
    code = "qq-bind:" + "a" * 24
    target = "QQ_USER_OPENID_TEST_1234567890"

    def event(self, content=None, kind="C2C_MESSAGE_CREATE", target=None):
        return {"op": 0, "t": kind, "d": {"content": content or self.code,
                "author": {"user_openid": target or self.target}, "group_openid": "QQ_GROUP_OPENID_TEST_1234567890"}}

    def test_only_exact_challenge_and_matching_scope_can_bind_a_target(self):
        self.assertEqual({"targetType": "user", "targetId": self.target}, qq.target_from_event(self.event(), self.code, "user"))
        for event in (None, [], {}, self.event("wrong-code"), self.event("prefix " + self.code), self.event(kind="GROUP_MESSAGE_CREATE"), self.event(target="123456789")):
            self.assertIsNone(qq.target_from_event(event, self.code, "user"))

    def test_group_mention_is_allowed_but_unrelated_private_and_channel_events_are_ignored(self):
        event = self.event("<@123456> " + self.code, "GROUP_AT_MESSAGE_CREATE")
        self.assertEqual({"targetType": "group", "targetId": "QQ_GROUP_OPENID_TEST_1234567890"}, qq.target_from_event(event, self.code, "group"))
        self.assertIsNone(qq.target_from_event(self.event(), self.code, "group"))
        self.assertIsNone(qq.target_from_event(self.event(kind="MESSAGE_CREATE"), self.code, "group"))
        self.assertIsNone(qq.target_from_event(self.event("other-message", "GROUP_MESSAGE_CREATE"), self.code, "group"))

    def test_gateway_handshake_heartbeat_and_challenge_output_exclude_secrets_and_other_messages(self):
        ws = MagicMock()
        ws.recv.side_effect = [json.dumps({"op": 10, "d": {"heartbeat_interval": 45000}}),
                json.dumps({"op": 0, "s": 1, "t": "READY"}), json.dumps(self.event("private unrelated message")), json.dumps(self.event())]
        connect = MagicMock(); connect.return_value.__enter__.return_value = ws
        output = io.StringIO()
        with patch.object(qq.secrets, "token_hex", return_value="a" * 24), contextlib.redirect_stdout(output):
            result = qq.listen(connect, "sensitive-access-token", "user")
        self.assertEqual(self.target, result["targetId"])
        self.assertNotIn("sensitive-access-token", output.getvalue())
        self.assertNotIn("private unrelated message", output.getvalue())
        self.assertIn(self.code, output.getvalue()); self.assertIn(self.target, output.getvalue())
        sent = [json.loads(call.args[0]) for call in ws.send.call_args_list]
        self.assertEqual(2, sent[0]["op"]); self.assertEqual(1 << 25, sent[0]["d"]["intents"])
        self.assertTrue(any(event["op"] == 1 for event in sent))
        connect.return_value.__exit__.assert_called_once()

    def test_gateway_rejection_uses_fixed_error_and_closes_connection(self):
        ws = MagicMock(); ws.recv.return_value = json.dumps({"op": 9, "d": "sensitive-error"})
        connect = MagicMock(); connect.return_value.__enter__.return_value = ws
        with self.assertRaises(ValueError) as error:
            qq.listen(connect, "sensitive-access-token", "user")
        self.assertNotIn("sensitive", str(error.exception)); connect.return_value.__exit__.assert_called_once()

    def test_authentication_failure_discards_provider_error_body(self):
        response = MagicMock(); response.read.return_value = json.dumps({"code": 100016, "message": "echo-app-secret"}).encode()
        opener = MagicMock(); opener.open.return_value.__enter__.return_value = response
        with patch.object(qq.urllib.request, "build_opener", return_value=opener), self.assertRaises(ValueError) as error:
            qq.access_token("12345678", "app-secret")
        self.assertIn("100016", str(error.exception)); self.assertNotIn("app-secret", str(error.exception))
        request = opener.open.call_args.args[0]
        self.assertEqual("https://api.bot.qq.com/app/getAppAccessToken", request.full_url)
        self.assertEqual("app-secret", json.loads(request.data)["clientSecret"])


if __name__ == "__main__":
    unittest.main()
