"""Regression checks for first-build and first-flash script orchestration.

Run with: python3 -m unittest discover -s tests -p 'test_build_scripts.py'
These use recording tools; they do not replace a real IDF/Nix build.
"""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
MOCK = r'''#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
name = Path(sys.argv[0]).name
args = sys.argv[1:]
with open(os.environ['TEST_CALLS'], 'a') as log:
    log.write(json.dumps([name, args]) + '\n')
if name == 'cargo':
    target = Path(os.environ['CARGO_TARGET_DIR'])
    target.mkdir(parents=True, exist_ok=True)
    (target / 'keep').write_text('host cache')
elif name == 'idf.py':
    build = Path(args[args.index('-B') + 1])
    if 'set-target' in args and build.exists() and any(build.iterdir()):
        sys.exit('fullclean refuses a non-CMake directory')
    assert 'IDF_TARGET=esp32c5' in args
    build.mkdir(parents=True, exist_ok=True)
    (build / 'CMakeCache.txt').touch()
    Path('sdkconfig').touch()
elif name == 'esptool':
    Path(args[args.index('-o') + 1]).write_bytes(b'merged image')
'''


class BuildScripts(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        shutil.copytree(ROOT / 'scripts', self.root / 'scripts')
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        for name in ('cargo', 'idf.py', 'esptool'):
            path = self.bin / name
            path.write_text(MOCK)
            path.chmod(0o755)
        self.build = self.root / 'build with spaces'
        self.log = self.root / 'calls.jsonl'
        self.env = dict(os.environ, PATH=f'{self.bin}:{os.environ["PATH"]}',
                        CITS_BUILD_DIR=str(self.build), TEST_CALLS=str(self.log),
                        CARGO_TARGET_DIR=str(self.build / 'host-target'))

    def run_script(self, name, *args):
        return subprocess.run(['bash', str(self.root / 'scripts' / name), *args],
                              env=self.env, capture_output=True, text=True)

    def calls(self):
        return [json.loads(line) for line in self.log.read_text().splitlines()]

    def test_first_build_with_legacy_cargo_cache_then_rebuild(self):
        # No sdkconfig or CMake cache, but host tests populate build/host-target.
        for _ in range(2):
            result = self.run_script('build.sh')
            self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((self.build / 'host-target/keep').read_text(), 'host cache')
        self.assertTrue((self.root / 'dist/SHA256SUMS').exists())
        idf_calls = [args for name, args in self.calls() if name == 'idf.py']
        self.assertEqual(len(idf_calls), 2)
        self.assertTrue(all(args[-1] == 'build' for args in idf_calls))
        self.assertFalse(any('set-target' in args for args in idf_calls))

    def test_first_flash_preserves_existing_files_and_quotes_port(self):
        self.build.mkdir()
        (self.build / 'unrelated').write_text('keep')
        result = self.run_script('flash.sh', '/dev/port with spaces', '460800')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.calls(), [['idf.py', [
            '-B', str(self.build), '-D', 'IDF_TARGET=esp32c5',
            '-p', '/dev/port with spaces', '-b', '460800', 'flash']]])
        self.assertEqual((self.build / 'unrelated').read_text(), 'keep')

    def test_bad_flash_arguments_do_not_invoke_idf(self):
        for args in ((), ('port', 'bad'), ('port', '0'), ('port', '1', 'extra')):
            self.assertEqual(self.run_script('flash.sh', *args).returncode, 2)
        self.assertFalse(self.log.exists())


if __name__ == '__main__':
    unittest.main()
