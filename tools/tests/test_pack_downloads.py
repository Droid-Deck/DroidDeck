import hashlib
import http.server
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import threading
import unittest


ROOT = Path(__file__).resolve().parents[2]
FETCH = ROOT / 'tools/packs/fetch-verified.sh'


class PackDownloadsTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.cache = self.root / 'cache'
        self.cache.mkdir()
        self.destination = self.root / 'download'
        self.remote = self.root / 'remote'
        self.remote.write_bytes(b'verified input')
        self.checksum = hashlib.sha256(self.remote.read_bytes()).hexdigest()
        self.log = self.root / 'curl.log'
        tool = self.root / 'bin/curl'
        tool.parent.mkdir()
        tool.write_text('#!/usr/bin/env python3\nimport json,os,shutil,sys\n'
                        'args=sys.argv[1:]\n'
                        'with open(os.environ["CURL_LOG"],"a") as f: f.write(json.dumps(args)+"\\n")\n'
                        'shutil.copyfile(os.environ["REMOTE"],args[args.index("-o")+1])\n')
        tool.chmod(0o755)
        self.env = dict(os.environ, DROIDDECK_PACK_CACHE=str(self.cache), CURL_LOG=str(self.log),
                        REMOTE=str(self.remote), PATH=str(tool.parent) + os.pathsep + os.environ['PATH'])

    def fetch(self, checksum=None, url='https://example.test/pinned', env=None):
        return subprocess.run(['bash', str(FETCH), url, checksum or self.checksum, str(self.destination)],
                              env=env or self.env, capture_output=True, text=True)

    def test_cold_download_then_warm_reuse_and_corruption_repair(self):
        self.assertEqual(self.fetch().returncode, 0)
        self.assertEqual(self.fetch().returncode, 0)
        self.assertEqual(len(self.log.read_text().splitlines()), 1)
        self.assertEqual(self.destination.read_bytes(), b'verified input')
        (self.cache / self.checksum).write_bytes(b'corrupt')
        self.assertEqual(self.fetch().returncode, 0)
        self.assertEqual(len(self.log.read_text().splitlines()), 2)
        self.assertEqual((self.cache / self.checksum).read_bytes(), b'verified input')

    def test_directory_cache_entry_is_replaced_only_after_verification(self):
        entry = self.cache / self.checksum
        entry.mkdir()
        sentinel = entry / 'old-entry'
        sentinel.write_bytes(b'preserve until verified')
        self.remote.write_bytes(b'tampered download')
        self.assertNotEqual(self.fetch().returncode, 0)
        self.assertEqual(sentinel.read_bytes(), b'preserve until verified')
        self.assertFalse(self.destination.exists())
        self.remote.write_bytes(b'verified input')
        result = self.fetch()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(entry.is_file())
        self.assertEqual(entry.read_bytes(), b'verified input')
        self.assertEqual(self.destination.read_bytes(), b'verified input')
        self.assertEqual(list(self.cache.iterdir()), [entry])
        self.assertEqual(self.fetch().returncode, 0)
        self.assertEqual(len(self.log.read_text().splitlines()), 2)

    def test_changed_pin_cannot_reuse_old_bytes(self):
        self.assertEqual(self.fetch().returncode, 0)
        self.remote.write_bytes(b'new approved input')
        updated = hashlib.sha256(self.remote.read_bytes()).hexdigest()
        self.assertEqual(self.fetch(updated).returncode, 0)
        self.assertEqual(len(self.log.read_text().splitlines()), 2)
        self.assertEqual(self.destination.read_bytes(), b'new approved input')

    def test_bad_download_is_not_cached_or_written(self):
        self.remote.write_bytes(b'tampered download')
        self.assertNotEqual(self.fetch().returncode, 0)
        self.assertFalse((self.cache / self.checksum).exists())
        self.assertFalse(self.destination.exists())
        self.assertEqual(list(self.cache.iterdir()), [])
        arguments = json.loads(self.log.read_text())
        self.assertIn('--retry-all-errors', arguments)
        self.assertIn('--max-time', arguments)
        self.assertIn('--retry-max-time', arguments)

    def test_connection_reset_retries_then_verifies_real_download(self):
        if not shutil.which('curl'):
            self.skipTest('curl required for transport test')
        content = self.remote.read_bytes()
        attempts = []

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                attempts.append(True)
                if len(attempts) == 1:
                    self.connection.shutdown(socket.SHUT_RDWR)
                    self.connection.close()
                    return
                self.send_response(200)
                self.send_header('Content-Length', str(len(content)))
                self.end_headers()
                self.wfile.write(content)

            def log_message(self, *args):
                pass

        server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            env = dict(self.env, PATH=os.environ['PATH'])
            result = self.fetch(url=f'http://127.0.0.1:{server.server_port}/input', env=env)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertGreaterEqual(len(attempts), 2)
            self.assertEqual(self.destination.read_bytes(), content)
        finally:
            server.shutdown()
            server.server_close()
            thread.join()
