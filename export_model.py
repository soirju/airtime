#!/usr/bin/env python3
"""
Downloads the SpeechBrain ECAPA-TDNN speaker verification model
and exports it to ONNX format for use in the Android app.

Requirements:
    pip install speechbrain onnx onnxruntime torch

Output:
    app/src/main/assets/ecapa_tdnn.onnx
"""

import torch
import os

def main():
    from speechbrain.inference.speaker import EncoderClassifier

    print("Downloading ECAPA-TDNN model from SpeechBrain...")
    classifier = EncoderClassifier.from_hparams(
        source="speechbrain/spkrec-ecapa-voxceleb",
        savedir="tmp_ecapa_model",
    )

    # The model expects [batch, time] raw waveform or [batch, features, time] fbank.
    # We export the embedding_model which takes Mel features [batch, 80, T].
    model = classifier.mods["embedding_model"]
    model.eval()

    # Dummy input: batch=1, 80 Mel bins, 200 frames (~2 seconds)
    dummy_input = torch.randn(1, 80, 200)

    output_path = os.path.join(
        os.path.dirname(__file__),
        "app", "src", "main", "assets", "ecapa_tdnn.onnx"
    )

    print(f"Exporting to {output_path}...")
    torch.onnx.export(
        model,
        dummy_input,
        output_path,
        input_names=["input"],
        output_names=["embedding"],
        dynamic_axes={
            "input": {0: "batch", 2: "time"},
            "embedding": {0: "batch"},
        },
        opset_version=14,
    )

    # Verify
    import onnxruntime as ort
    sess = ort.InferenceSession(output_path)
    import numpy as np
    test_input = np.random.randn(1, 80, 200).astype(np.float32)
    result = sess.run(None, {"input": test_input})
    print(f"Model exported successfully. Embedding shape: {result[0].shape}")
    print(f"File size: {os.path.getsize(output_path) / 1024 / 1024:.1f} MB")

    # Cleanup
    import shutil
    shutil.rmtree("tmp_ecapa_model", ignore_errors=True)

if __name__ == "__main__":
    main()
