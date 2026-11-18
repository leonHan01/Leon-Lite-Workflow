#!/usr/bin/env python3
import unittest
import sys
sys.dont_write_bytecode = True
from install_messaging_proxy import configured, ROUTE, MARKER, ANCHOR

class MessagingProxyTest(unittest.TestCase):
    def test_limited_routes_preserve_default_deny_and_are_idempotent(self):
        base='server {\n'+ANCHOR+'\n}'
        fixed=configured(base)
        self.assertIn(ROUTE,fixed)
        self.assertIn(ANCHOR,fixed)
        self.assertIn('client_max_body_size 6m;',fixed)
        self.assertNotIn('location /api/',fixed)
        self.assertNotIn('auth_request',fixed)
        self.assertEqual(fixed,configured(fixed))
    def test_existing_owned_rule_upgrades_avatar_upload_limit_only(self):
        base=configured('server {\n'+ANCHOR+'\n}')
        old=base.replace('client_max_body_size 6m;','client_max_body_size 3m;')
        self.assertEqual(base,configured(old))
    def test_unknown_or_partial_layouts_are_preserved(self):
        for bad in ['server {}',ANCHOR+'\n'+ANCHOR,MARKER+ANCHOR]:
            with self.assertRaises(RuntimeError): configured(bad)

if __name__=='__main__':unittest.main()
