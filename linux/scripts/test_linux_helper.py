import importlib.util
from pathlib import Path
import sys
import types
import unittest
from unittest.mock import patch

if sys.platform != 'linux':
    sys.modules.setdefault('pwd', types.SimpleNamespace())
    sys.modules.setdefault('fcntl', types.SimpleNamespace())
spec = importlib.util.spec_from_file_location('noki_linux_helper', Path(__file__).parents[1] / 'packaging/linux/core-helper.py')
helper = importlib.util.module_from_spec(spec)
spec.loader.exec_module(helper)


class LinuxHelperTest(unittest.TestCase):
    def test_core_identity_is_verified_by_the_privileged_helper(self):
        import tempfile
        from pathlib import PurePosixPath
        with tempfile.TemporaryDirectory() as directory:
            proc = Path(directory)
            process = proc / '42'
            process.mkdir()
            (process / 'status').write_text('Name:\tsing-box\nUid:\t1000\t1000\t1000\t1000\n')
            (process / 'cgroup').write_text('0::/system.slice/noki-vpn-1000.service\n')
            with patch.object(Path, 'readlink', return_value=PurePosixPath('/usr/lib/noki/sing-box')):
                self.assertEqual({'pid': 42}, helper.core_identity(1000, 42, proc))
                (process / 'status').write_text('Uid:\t1001\t1001\t1001\t1001\n')
                with self.assertRaisesRegex(ValueError, 'identity'):
                    helper.core_identity(1000, 42, proc)
                (process / 'status').write_text('Uid:\t1000\t1000\t1000\t1000\n')
                (process / 'cgroup').write_text('0::/system.slice/other.service\n')
                with self.assertRaisesRegex(ValueError, 'identity'):
                    helper.core_identity(1000, 42, proc)
            with patch.object(Path, 'readlink', return_value=PurePosixPath('/usr/bin/sleep')):
                with self.assertRaisesRegex(ValueError, 'identity'):
                    helper.core_identity(1000, 42, proc)

    def test_polkit_caller_and_scope_are_bound_to_one_user(self):
        self.assertEqual(1000, helper.caller_uid({'PKEXEC_UID': '1000'}))
        for value in ('', '0', '-1', '1000;echo x', '10000000000000'):
            with self.assertRaises(ValueError):
                helper.caller_uid({'PKEXEC_UID': value})
        scope = '/user.slice/user-1000.slice/user@1000.service/app.slice/noki-client-1000-123.scope'
        self.assertEqual(scope[1:], helper.checked_scope(1000, scope))
        for bad in (scope.replace('1000', '1001'), '/user.slice', scope + '" accept', scope + '/child'):
            with self.assertRaises(ValueError):
                helper.checked_scope(1000, bad)

    def test_guard_only_changes_caller_traffic_and_preserves_core_tun_and_loopback(self):
        scope = 'user.slice/user-1000.slice/user@1000.service/app.slice/noki-client-1000-123.scope'
        rules = helper.nft_rules(1000, scope)
        self.assertIn('table inet noki_1000', rules)
        self.assertIn('meta skuid != 1000 accept', rules)
        self.assertIn('oifname "Noki" accept', rules)
        self.assertIn('oifname "lo" accept', rules)
        self.assertIn(f'meta mark {helper.MARK} accept', rules)
        self.assertIn('socket cgroupv2 level 5', rules)
        self.assertIn('reject', rules)
        self.assertLess(rules.index('udp dport 53 reject'), rules.index('meta skuid != 1000 accept'))
        self.assertNotIn('cgroupv2', helper.nft_rules(1000))

    def test_core_has_network_capability_but_no_root_uid_or_shell(self):
        helper.executable = lambda name: '/usr/bin/' + name
        command = helper.unit_command(1000, 1000, '/home/test', Path('/run/user/1000/noki/vpn.json'))
        self.assertIn('--uid=1000', command)
        self.assertIn('--gid=1000', command)
        self.assertIn('--property=NoNewPrivileges=yes', command)
        self.assertIn('--property=CapabilityBoundingSet=CAP_NET_ADMIN', command)
        self.assertEqual('/usr/lib/noki/sing-box', command[-4])
        self.assertNotIn('sh', command)
        quoted = helper.unit_command(1000, 1000, '/home/test account', Path('/run/user/1000/noki/vpn.json'))
        self.assertIn('--property=ReadWritePaths="/home/test account" /run/user/1000/noki', quoted)
        with self.assertRaises(ValueError):
            helper.unit_command(1000, 1000, '/home/test\nExecStart=/bin/sh', Path('/run/user/1000/noki/vpn.json'))

    def test_upgrade_keeps_persistent_guard_and_only_stops_owned_units(self):
        import tempfile
        import json
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory) / '1000'
            state.write_text(json.dumps({'guard': True}))
            changes, calls = [], []
            with patch.object(helper, 'STATE', Path(directory)), patch.object(helper.sys, 'platform', 'linux'), \
                    patch.object(helper.os, 'geteuid', return_value=0, create=True), \
                    patch.dict(helper.os.environ, {}, clear=True), \
                    patch.object(helper, 'set_guard', side_effect=lambda uid, enabled: changes.append((uid, enabled))), \
                    patch.object(helper, 'invoke', side_effect=lambda args, **kw: (calls.append(args) or types.SimpleNamespace(returncode=0, stderr=''))):
                helper.main(['upgrade'])
            self.assertEqual([(1000, True)], changes)
            self.assertTrue(state.exists())
            self.assertEqual('noki-vpn-1000.service', calls[0][-1])

    def test_another_users_guard_cannot_be_silently_replaced(self):
        import tempfile
        import json
        with tempfile.TemporaryDirectory() as directory:
            (Path(directory) / '1000').write_text(json.dumps({'guard': True}))
            with patch.object(helper, 'STATE', Path(directory)):
                helper.check_guard_owner(1000)
                with self.assertRaisesRegex(ValueError, 'other user'):
                    helper.check_guard_owner(1001)


if __name__ == '__main__':
    unittest.main()
