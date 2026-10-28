"""CLIP zero-shot "is this a live-action photo still?" scorer. Score = P(photo) in 0..1."""
import io

import open_clip
import torch
from PIL import Image

PHOTO = [
    "a still frame from a live-action film",
    "a photograph of real people",
    "a cinematic photograph of a real location",
]
NOT_PHOTO = [
    "a movie poster with the title in large text",
    "an illustration or painting",
    "a drawing or comic book art",
    "a logo or text on a plain background",
    "a collage of several images",
    "a cartoon or animated frame",
    "stylised promotional artwork",
]


# Titled landscape art (Jellyfin Thumb): a clean film still carrying the logo is good, cover/poster/clutter is not.
TITLE_GOOD = [
    "a clean cinematic title card: a film still with the movie's logo",
    "official landscape key art with title",
]
TITLE_BAD = [
    "a DVD or Blu-ray cover",
    "a poster crowded with text and credits",
    "a low-resolution or blurry image",
    "a collage",
    "fan-made artwork",
    "a screenshot with watermark",
]


class Scorer:
    def __init__(self, model="ViT-B-32", pretrained="laion2b_s34b_b79k", threads=8):
        torch.set_num_threads(threads)
        self.model, _, self.pre = open_clip.create_model_and_transforms(model, pretrained=pretrained)
        self.model.eval()
        tok = open_clip.get_tokenizer(model)
        with torch.no_grad():
            t = self.model.encode_text(tok(PHOTO + NOT_PHOTO))
            self.text = t / t.norm(dim=-1, keepdim=True)
            t = self.model.encode_text(tok(TITLE_GOOD + TITLE_BAD))
            self.title_text = t / t.norm(dim=-1, keepdim=True)

    def score(self, images, kind="photo"):
        """images: list of bytes. Returns list of float|None (None = undecodable). kind: "photo" or "title" (titled art quality)."""
        text, n_pos = (self.title_text, len(TITLE_GOOD)) if kind == "title" else (self.text, len(PHOTO))
        out, batch, idx = [None] * len(images), [], []
        for i, b in enumerate(images):
            try:
                batch.append(self.pre(Image.open(io.BytesIO(b)).convert("RGB")))
                idx.append(i)
            except Exception:
                pass
        if not batch:
            return out
        with torch.no_grad():
            f = self.model.encode_image(torch.stack(batch))
            f = f / f.norm(dim=-1, keepdim=True)
            p = (100.0 * f @ text.T).softmax(dim=-1)[:, :n_pos].sum(dim=-1)
        for i, v in zip(idx, p.tolist()):
            out[i] = round(v, 4)
        return out
