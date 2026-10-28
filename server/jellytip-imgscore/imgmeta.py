"""Cheap per-image measurements: sharpness/brightness, and a focal point (faces, else saliency).

Focal point is (x, y, face) with x, y in 0..1 of the image; the app crops so it stays in frame
(upper third for hero/details) instead of cutting heads off.
"""
import io

import cv2
import numpy as np
from PIL import Image

_FACE = cv2.CascadeClassifier(cv2.data.haarcascades + "haarcascade_frontalface_default.xml")
_PROFILE = cv2.CascadeClassifier(cv2.data.haarcascades + "haarcascade_profileface.xml")
cv2.setNumThreads(1)  # default is one per core (128 here) and blows the virtual-memory ulimit
_W = 480


def _gray(blob, width=_W):
    im = Image.open(io.BytesIO(blob)).convert("L")
    if im.width > width:
        im = im.resize((width, max(1, round(im.height * width / im.width))), Image.LANCZOS)
    return np.asarray(im)


def quality(blob):
    """(laplacian variance at 480 px wide, mean luminance 0..255): low variance = blurry, low mean = black frame."""
    g = _gray(blob)
    return round(float(cv2.Laplacian(g, cv2.CV_64F).var()), 1), round(float(g.mean()), 1)


def _saliency_centre(g):
    """Spectral-residual saliency centroid (numpy only), pulled gently towards the frame centre."""
    small = cv2.resize(g, (64, 64)).astype(np.float64)
    f = np.fft.fft2(small)
    log_amp = np.log(np.abs(f) + 1e-8)
    resid = log_amp - cv2.blur(log_amp, (3, 3))
    sal = np.abs(np.fft.ifft2(np.exp(resid + 1j * np.angle(f)))) ** 2
    sal = cv2.GaussianBlur(sal, (0, 0), 3)
    sal = np.maximum(sal - sal.mean(), 0)
    if sal.sum() <= 0:
        return 0.5, 0.5
    ys, xs = np.mgrid[0:64, 0:64]
    cx, cy = (xs * sal).sum() / sal.sum() / 63, (ys * sal).sum() / sal.sum() / 63
    return 0.5 * cx + 0.5 * 0.5, 0.5 * cy + 0.5 * 0.5


def focal(blob):
    """(x, y, face): centre of the detected faces (area-weighted, face=True), else saliency centre (face=False)."""
    g = _gray(blob)
    h, w = g.shape
    boxes = []
    min_side = max(20, w // 24)
    for det in (_FACE, _PROFILE):
        r = det.detectMultiScale(g, scaleFactor=1.1, minNeighbors=6, minSize=(min_side, min_side))
        boxes += [tuple(b) for b in r]
    if boxes:
        area = np.array([bw * bh for _, _, bw, bh in boxes], dtype=np.float64)
        cx = sum((x + bw / 2) * a for (x, _, bw, _), a in zip(boxes, area)) / area.sum() / w
        cy = sum((y + bh / 2) * a for (_, y, _, bh), a in zip(boxes, area)) / area.sum() / h
        return round(float(cx), 3), round(float(cy), 3), True
    x, y = _saliency_centre(g)
    return round(float(x), 3), round(float(y), 3), False
