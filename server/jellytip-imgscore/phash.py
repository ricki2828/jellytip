"""64-bit perceptual hash (pHash: 32x32 grayscale DCT, top-left 8x8, median threshold) as 16 hex chars.

Robust to the title/logo overlay that turns an untitled backdrop into a Thumb card.
"""
import io

import numpy as np
from PIL import Image

N, K = 32, 8
_k = np.arange(N)
_DCT = np.cos(np.pi * (2 * _k[None, :] + 1) * np.arange(N)[:, None] / (2 * N))  # rows = frequency


def phash(blob):
    im = Image.open(io.BytesIO(blob)).convert("L").resize((N, N), Image.LANCZOS)
    a = np.asarray(im, dtype=np.float64)
    low = (_DCT @ a @ _DCT.T)[:K, :K].flatten()
    bits = low > np.median(low[1:])  # median excludes the DC term
    return f"{int(''.join('1' if b else '0' for b in bits), 2):016x}"
