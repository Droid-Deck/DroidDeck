import importlib.util
import io
import unittest
import urllib.error
from pathlib import Path
from unittest import mock

SCRIPT = Path(__file__).resolve().parents[1] / "quest/build-driver.py"
spec = importlib.util.spec_from_file_location("build_driver", SCRIPT)
driver = importlib.util.module_from_spec(spec)
spec.loader.exec_module(driver)


def http_error(code):
    return urllib.error.HTTPError("https://example.invalid/a.zip", code, "error", {}, io.BytesIO())


class Response(io.BytesIO):
    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.close()


class DriverDownloadTest(unittest.TestCase):
    def fetch(self, *outcomes):
        calls = []

        def urlopen(url, timeout):
            calls.append(url)
            outcome = outcomes[len(calls) - 1]
            if isinstance(outcome, Exception):
                raise outcome
            return Response(outcome)

        with mock.patch.object(driver.urllib.request, "urlopen", urlopen), mock.patch.object(driver.time, "sleep") as sleep:
            try:
                return driver.download("https://example.invalid/a.zip"), len(calls), sleep.call_count
            except Exception as error:
                return error, len(calls), sleep.call_count

    def test_a_timeout_or_server_error_is_retried(self):
        self.assertEqual((b"zip", 3, 2), self.fetch(TimeoutError("read timed out"), http_error(502), b"zip"))

    def test_a_missing_file_fails_at_once(self):
        error, calls, sleeps = self.fetch(http_error(404))
        self.assertEqual((404, 1, 0), (error.code, calls, sleeps))

    def test_it_gives_up_after_the_last_attempt(self):
        error, calls, sleeps = self.fetch(*[http_error(504)] * 4)
        self.assertEqual((504, 4, 3), (error.code, calls, sleeps))


if __name__ == "__main__":
    unittest.main()
