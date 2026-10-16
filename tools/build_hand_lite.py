"""Build the pinned full-palm/lite-landmark MediaPipe bundle (mediapipe==0.10.21)."""
import argparse
import hashlib
from pathlib import Path
import zipfile

FULL_SHA = 'fbc2a30080c3c557093b5ddfc334698132eb341044ccee322ccf8bcf3607cde1'
LITE_SHA = 'd7fde8ac11f8ce03f8663775bfc323f4fc9f2a38062b4f4efa142874ef5b2a48'

def build(full, lite, output):
    import mediapipe
    from mediapipe.tasks.python.metadata import metadata
    from mediapipe.tasks.metadata import schema_py_generated as schema
    if mediapipe.__version__ != '0.10.21':
        raise ValueError('Use mediapipe==0.10.21 for reproducible metadata')
    full_bytes, lite_bytes = full.read_bytes(), lite.read_bytes()
    if hashlib.sha256(full_bytes).hexdigest() != FULL_SHA or hashlib.sha256(lite_bytes).hexdigest() != LITE_SHA:
        raise ValueError('Model source checksum mismatch')
    with zipfile.ZipFile(full) as task:
        palm = task.read('hand_detector.tflite')
        reference = task.read('hand_landmarks_detector.tflite')
    def contract(data):
        graph = schema.Model.GetRootAsModel(data, 0).Subgraphs(0)
        return [[(graph.Tensors(int(i)).Name(), graph.Tensors(int(i)).ShapeAsNumpy().tolist(), graph.Tensors(int(i)).Type())
                 for i in indices] for indices in (graph.InputsAsNumpy(), graph.OutputsAsNumpy())]
    if contract(lite_bytes) != contract(reference):
        raise ValueError('Lite landmark tensor contract differs')
    populator = metadata.MetadataPopulator.with_model_buffer(lite_bytes)
    populator.load_metadata_and_associated_files(reference)
    populator.populate()
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, 'w', compression=zipfile.ZIP_STORED) as task:
        for name, data in [('hand_detector.tflite', palm), ('hand_landmarks_detector.tflite', populator.get_model_buffer())]:
            entry = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            entry.create_system = 3
            entry.external_attr = 0o100644 << 16
            task.writestr(entry, data)
    print(hashlib.sha256(output.read_bytes()).hexdigest())

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--full', type=Path, default=Path('app/src/main/assets/models/hand_landmarker.task'))
    parser.add_argument('--lite', type=Path, required=True, help='Pinned hand_landmark_lite.tflite from storage.googleapis.com/mediapipe-assets/')
    parser.add_argument('--output', type=Path, default=Path('app/src/main/assets/models/hand_landmarker_lite.task'))
    args = parser.parse_args()
    build(args.full, args.lite, args.output)
