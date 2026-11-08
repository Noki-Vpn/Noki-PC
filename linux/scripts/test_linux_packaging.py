import importlib.util
from pathlib import Path
import tempfile
import unittest


def load(name):
    spec = importlib.util.spec_from_file_location(name.replace('-', '_'), Path(__file__).with_name(name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


packaging = load('package-linux')
runtime = load('prepare-runtime-linux')


class LinuxPackagingTest(unittest.TestCase):
    def test_rpm_removes_its_own_directories_but_does_not_own_shared_system_dirs(self):
        with tempfile.TemporaryDirectory() as directory:
            payload = Path(directory)
            (payload / 'usr/lib/noki/app').mkdir(parents=True)
            (payload / 'usr/lib/noki/app/Noki').write_text('app')
            listing = packaging.rpm_files(payload)
            self.assertIn('%dir "/usr/lib/noki"', listing)
            self.assertIn('%dir "/usr/lib/noki/app"', listing)
            self.assertIn('"/usr/lib/noki/app/Noki"', listing)
            self.assertNotIn('%dir "/usr/lib"', listing)
            self.assertNotIn('%dir "/usr"', listing)
    def test_arch_recipe_has_real_hash_and_common_dependencies(self):
        recipe = packaging.arch_recipe('1.6.16', 'a' * 64)
        self.assertIn("arch=('x86_64')", recipe)
        self.assertIn("'polkit'", recipe)
        self.assertIn("'libsecret'", recipe)
        self.assertIn("'nftables'", recipe)
        self.assertIn("sha256sums=('" + 'a' * 64 + "')", recipe)
        self.assertNotIn('SKIP', recipe)
        self.assertNotIn('sudo', recipe)

    def test_native_archives_reject_path_traversal(self):
        for value in ('/etc/passwd', '../bad', 'src/../../bad', 'C:/bad', r'src\bad'):
            with self.assertRaises(ValueError):
                runtime.checked_member(value)
        runtime.checked_member('src/cmd/sing-box/main.go')

    def test_payload_rejects_windows_image_before_copying(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            image = root / 'image'
            (image / 'bin').mkdir(parents=True)
            (image / 'bin/Noki').write_text('launcher')
            (image / 'lib/runtime').mkdir(parents=True)
            (image / 'lib/leaked.dll').write_text('wrong platform')
            with self.assertRaisesRegex(ValueError, 'Windows payload'):
                packaging.stage_payload(root / 'payload', image, root / 'runtime')
            self.assertFalse((root / 'payload').exists())


if __name__ == '__main__':
    unittest.main()
