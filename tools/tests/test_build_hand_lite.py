import importlib.util
import io
from pathlib import Path
import unittest
import zipfile


spec = importlib.util.spec_from_file_location('build_hand_lite', Path(__file__).parents[1] / 'build_hand_lite.py')
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)


class HandLiteBundleTest(unittest.TestCase):
    def test_embedded_labels_are_reproducible_without_changing_model_bytes(self):
        model = b'\x08\x00\x00\x00TFL3model tensors and weights'
        outputs = []
        for year, order in [(2020, ['labels.txt', 'handedness.txt']),
                            (2026, ['handedness.txt', 'labels.txt'])]:
            source = io.BytesIO(model)
            with zipfile.ZipFile(source, 'a') as archive:
                for name in order:
                    entry = zipfile.ZipInfo(name, (year, 10, 16, 12, 30, 0))
                    archive.writestr(entry, name.encode())
            output = builder.canonicalize_associated_files(source.getvalue())
            self.assertEqual(model, output[:len(model)])
            self.assertEqual(output, builder.canonicalize_associated_files(output))
            with zipfile.ZipFile(io.BytesIO(output)) as archive:
                for name in order:
                    self.assertEqual(name.encode(), archive.read(name))
            outputs.append(output)
        self.assertEqual(*outputs)


if __name__ == '__main__':
    unittest.main()
