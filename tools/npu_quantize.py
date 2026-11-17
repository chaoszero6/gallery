#!/usr/bin/env python3
"""
Makes the NPU version of the CLIP image encoder and checks it against the app's CPU model.

  1. tools/npu_dequant.py turns the app's MatMulNBits model back into plain float MatMuls.
  2. The batch size is fixed to 1 (the NPU needs static shapes).
  3. This script quantises it QDQ-style for Qualcomm's HTP NPU: 8-bit weights, 16-bit activations
     (8-bit activations are known to hurt transformer accuracy), calibrated on sample photos.
  4. Accuracy: embedding cosine vs the CPU model, and zero-shot "a photo of a <class>" top-1
     agreement on held-out photos (a stand-in for "does the same photo win the text search").

Usage: python tools/npu_quantize.py <float_b1.onnx> <cpu_model.onnx> <text_model.onnx> <images_dir> <out.onnx> [--per-channel]
Images named like ImageNet samples ("n01440764_tench.JPEG") give the class names.
"""
import pathlib
import sys

import numpy as np
import onnxruntime as ort
from PIL import Image

MEAN = np.array([0.48145466, 0.4578275, 0.40821073], np.float32)
STD = np.array([0.26862954, 0.26130258, 0.27577711], np.float32)


def preprocess(path):
    im = Image.open(path).convert("RGB")
    s = 224 / min(im.size)
    im = im.resize((max(224, round(im.width * s)), max(224, round(im.height * s))), Image.BICUBIC)
    l, t = (im.width - 224) // 2, (im.height - 224) // 2
    x = (np.asarray(im.crop((l, t, l + 224, t + 224)), np.float32) / 255 - MEAN) / STD
    return x.transpose(2, 0, 1)[None]


class Reader:
    def __init__(self, paths):
        self.it = iter(paths)

    def get_next(self):
        p = next(self.it, None)
        return None if p is None else {"pixel_values": preprocess(p)}


def embed(sess, x):
    return sess.run(None, {sess.get_inputs()[0].name: x})[0][0]


def main(f32, cpu_model, text_model, img_dir, out, per_channel=False):
    from onnxruntime.quantization import QuantType, quantize
    from onnxruntime.quantization.execution_providers.qnn import get_qnn_qdq_config

    paths = sorted(pathlib.Path(img_dir).glob("*.JPEG"))
    calib, held = paths[0::2], paths[1::2]
    if not pathlib.Path(out).exists():
        cfg = get_qnn_qdq_config(f32, Reader(calib[:62]), activation_type=QuantType.QUInt16, weight_type=QuantType.QUInt8, per_channel=per_channel)
        # Keep calibration memory small. 62 photos is deliberately not a multiple of 4: ORT raises
        # "No data is collected" when the last flush leaves nothing behind.
        cfg.extra_options["CalibMaxIntermediateOutputs"] = 4
        quantize(f32, out, cfg)

    cpu = ort.InferenceSession(cpu_model, providers=["CPUExecutionProvider"])
    npu = ort.InferenceSession(out, providers=["CPUExecutionProvider"])  # QDQ simulated on CPU
    txt = ort.InferenceSession(text_model, providers=["CPUExecutionProvider"])

    from transformers import CLIPTokenizer
    tok = CLIPTokenizer.from_pretrained("openai/clip-vit-base-patch32")
    classes = [p.stem.split("_", 1)[1].replace("_", " ") for p in paths]
    T = []
    for c in classes:
        ids = tok(f"a photo of a {c}", padding="max_length", max_length=77)["input_ids"]
        T.append(txt.run(None, {"input_ids": np.array([ids], np.int64)})[0][0])
    T = np.stack(T)

    cos, agree, right_cpu, right_npu = [], 0, 0, 0
    for p in held:
        x = preprocess(p)
        a, b = embed(cpu, x), embed(npu, x)
        cos.append(float(a @ b / np.linalg.norm(a) / np.linalg.norm(b)))
        ta, tb = int(np.argmax(T @ a)), int(np.argmax(T @ b))
        truth = paths.index(p)
        agree += ta == tb
        right_cpu += ta == truth
        right_npu += tb == truth
    n = len(held)
    print(f"held-out photos: {n}")
    print(f"cosine vs CPU model: mean {np.mean(cos):.4f}  min {np.min(cos):.4f}")
    print(f"same top text match as CPU model: {agree}/{n}")
    print(f"zero-shot top-1 ({len(classes)} classes): CPU {right_cpu}/{n}  NPU {right_npu}/{n}")


if __name__ == "__main__":
    main(*sys.argv[1:6], per_channel="--per-channel" in sys.argv[6:])
