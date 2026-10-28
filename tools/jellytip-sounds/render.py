#!/usr/bin/env python3
"""Offline render of the Jellytip splash/ident sounds.

Mirrors the Web Audio synthesis in design/splash.html (tapeClunk, rhodesNote,
rhodesSting) sample for sample as closely as plain DSP allows, then encodes to
OGG Vorbis with ffmpeg.

    python3 tools/jellytip-sounds/render.py            # writes res/raw/*.ogg
    python3 tools/jellytip-sounds/render.py --wav-only # also keep the WAVs

Needs numpy + scipy; ffmpeg (libvorbis) on PATH for the OGG step.
Outputs (48 kHz mono):
    jellytip_intro_card.ogg  soft Rhodes sting only (no tape clunk/hiss), under the video intro's end card
    jellytip_ident.ogg  Rhodes sting at 0.12 s
"""
import argparse
import shutil
import subprocess
import sys
from pathlib import Path

import numpy as np
from scipy.io import wavfile
from scipy.signal import lfilter

SR = 48000
IDENT_STING_AT = 0.12
RNG = np.random.default_rng(1986)


def tvec(sec):
    return np.arange(int(round(sec * SR))) / SR


def exp_seg(v0, v1, t0, t1, t):
    """Web Audio exponentialRampToValueAtTime between (t0,v0) and (t1,v1); clamps outside."""
    v0 = max(v0, 1e-6)
    v1 = max(v1, 1e-6)
    f = np.clip((t - t0) / (t1 - t0), 0.0, 1.0)
    return v0 * (v1 / v0) ** f


def lin_seg(v0, v1, t0, t1, t):
    f = np.clip((t - t0) / (t1 - t0), 0.0, 1.0)
    return v0 + (v1 - v0) * f


def rbj(kind, freq, q=0.707):
    w0 = 2 * np.pi * freq / SR
    alpha = np.sin(w0) / (2 * q)
    cw = np.cos(w0)
    if kind == "lowpass":
        b = [(1 - cw) / 2, 1 - cw, (1 - cw) / 2]
    elif kind == "highpass":
        b = [(1 + cw) / 2, -(1 + cw), (1 + cw) / 2]
    elif kind == "bandpass":  # constant 0 dB peak gain
        b = [alpha, 0.0, -alpha]
    else:
        raise ValueError(kind)
    a = [1 + alpha, -2 * cw, 1 - alpha]
    return np.array(b) / a[0], np.array(a) / a[0]


def biquad(kind, freq, x, q=0.707):
    b, a = rbj(kind, freq, q)
    return lfilter(b, a, x)


def swept_lowpass(x, f0, f1, dur, q=0.707, block=256):
    """Lowpass whose cutoff moves exponentially f0 -> f1 over `dur` seconds, block-wise with state carry."""
    out = np.zeros_like(x)
    zi = np.zeros(2)
    for s in range(0, len(x), block):
        t = s / SR
        f = f0 * (f1 / f0) ** min(1.0, t / dur)
        b, a = rbj("lowpass", f, q)
        seg = x[s:s + block]
        y, zi = lfilter(b, a, seg, zi=zi)
        out[s:s + block] = y
    return out


def place(buf, sig, at):
    i = int(round(at * SR))
    n = min(len(sig), len(buf) - i)
    if n > 0:
        buf[i:i + n] += sig[:n]


def tape_clunk():
    """Play-button click, transport thump, motor spin-up, bed of hiss. ~3.4 s."""
    n = 3.5
    t = tvec(n)
    buf = np.zeros(len(t))

    # click: 30 ms noise, highpass 2.5 kHz, gain 0.6 -> 0.001 exponential
    tc = tvec(0.03)
    click = biquad("highpass", 2500, RNG.uniform(-1, 1, len(tc))) * exp_seg(0.6, 0.001, 0, 0.03, tc)
    place(buf, click, 0.0)

    # thump: sine 110 -> 45 Hz over 0.02..0.14, gain 0.55 -> 0.001 over 0.02..0.18
    tt = tvec(0.18)
    f = exp_seg(110, 45, 0.0, 0.12, tt)
    th = np.sin(2 * np.pi * np.cumsum(f) / SR) * exp_seg(0.55, 0.001, 0.0, 0.16, tt)
    place(buf, th, 0.02)

    # motor: sawtooth 18 -> 62 Hz over 0.08..0.5, lowpass 320, gain 0 -> 0.06 @0.3 -> 0.001 @0.9
    tm = tvec(0.87)  # 0.08 .. 0.95
    fm = exp_seg(18, 62, 0.0, 0.42, tm)
    phase = (np.cumsum(fm) / SR) % 1.0
    saw = 2 * phase - 1
    saw = biquad("lowpass", 320, saw)
    gain = np.where(tm < 0.22, lin_seg(0, 0.06, 0.0, 0.22, tm), exp_seg(0.06, 0.001, 0.22, 0.82, tm))
    place(buf, saw * gain, 0.08)

    # hiss: bandpass 6 kHz Q 0.5, gain 0 @0.1 -> 0.025 @0.4 -> 0 @3.3
    th_ = tvec(3.4)
    hiss = biquad("bandpass", 6000, RNG.uniform(-1, 1, len(th_)), q=0.5)
    hg = np.where(th_ < 0.3, lin_seg(0, 0.025, 0.0, 0.3, th_), lin_seg(0.025, 0.0, 0.3, 3.2, th_))
    place(buf, hiss * hg, 0.1)
    return buf


def rhodes_note(freq, vel, dur=3.7):
    """FM electric piano: sine carrier, 1:1 modulator (body), 14:1 modulator (tine ding)."""
    t = tvec(dur)
    # modulation indices = deviation(Hz) / modulator frequency(Hz)
    i1 = exp_seg(1.6 * vel, 0.25, 0.0, 1.2, t)
    i2 = exp_seg(2.2 * vel / 14.0, 1.0 / (14.0 * freq), 0.0, 0.12, t)
    ph = 2 * np.pi * freq * t
    car = np.sin(ph + i1 * np.sin(ph) + i2 * np.sin(14 * ph))
    car = swept_lowpass(car, 2600, 900, 2.5)
    env = np.where(
        t < 0.006,
        lin_seg(0.0, 0.18 * vel, 0.0, 0.006, t),
        np.where(
            t < 0.6,
            exp_seg(0.18 * vel, 0.06 * vel, 0.006, 0.6, t),
            exp_seg(0.06 * vel, 0.0008, 0.6, 3.6, t),
        ),
    )
    return car * env


def rhodes_sting():
    """Fmaj9 rolled F2 C3 E3 A3 G4 then C5, through suitcase-amp tremolo and a short tape slap."""
    notes = [(87.31, 0.0, 0.9), (130.81, 0.045, 0.8), (164.81, 0.09, 0.75),
             (220.0, 0.135, 0.75), (392.0, 0.19, 0.7), (523.25, 0.62, 0.55)]
    n = 0.62 + 3.7 + 1.2  # tail room for the echo
    bus = np.zeros(int(n * SR))
    for f, d, v in notes:
        place(bus, rhodes_note(f, v), d)
    t = np.arange(len(bus)) / SR
    trem = 0.78 + 0.22 * np.sin(2 * np.pi * 4.6 * t)
    dry = bus * trem
    # delay 0.23 s, feedback 0.28, wet 0.22 (feedback loop around the delay line)
    d = int(0.23 * SR)
    echo = np.zeros_like(dry)
    for s in range(d, len(dry), d):
        e = min(s + d, len(dry))
        echo[s:e] = dry[s - d:e - d] + 0.28 * echo[s - d:e - d]
    return dry + 0.22 * echo


def finish(x, fade=0.4, max_len=None):
    """Trim trailing silence (and anything past max_len), fade the tail, keep peaks under -1 dBFS."""
    if max_len is not None:
        x = x[: int(max_len * SR)]
    thr = 1e-4
    idx = np.nonzero(np.abs(x) > thr)[0]
    x = x[: idx[-1] + 1] if len(idx) else x
    nf = int(fade * SR)
    x[-nf:] *= np.linspace(1, 0, nf)
    peak = np.max(np.abs(x))
    if peak > 0.89:
        x = x * (0.89 / peak)
    return x, peak


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=str(Path(__file__).resolve().parents[2] / "app/src/main/res/raw"))
    ap.add_argument("--wav-only", action="store_true", help="also keep the intermediate WAVs next to the OGGs")
    args = ap.parse_args()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    sting = rhodes_sting()

    card = 0.6 * sting  # soft: sits under the classy end card
    ident = np.zeros(int((IDENT_STING_AT + len(sting) / SR) * SR) + 1)
    place(ident, sting, IDENT_STING_AT)

    ffmpeg = shutil.which("ffmpeg")
    # The ident must not ring far into the film; the intro may linger a little after the logo lands.
    for name, sig, max_len, fade in (("jellytip_intro_card", card, 3.2, 1.0), ("jellytip_ident", ident, 3.4, 0.7)):
        sig, peak = finish(sig, fade=fade, max_len=max_len)
        wav = out / f"{name}.wav"
        wavfile.write(wav, SR, (np.clip(sig, -1, 1) * 32767).astype(np.int16))
        ogg = out / f"{name}.ogg"
        if ffmpeg is None:
            sys.exit("ffmpeg not found; WAV written only")
        subprocess.run([ffmpeg, "-y", "-loglevel", "error", "-i", str(wav), "-c:a", "libvorbis", "-q:a", "4", str(ogg)], check=True)
        if not args.wav_only:
            wav.unlink()
        print(f"{ogg.name}: {len(sig) / SR:.2f}s raw peak {peak:.2f} -> {ogg.stat().st_size // 1024} KiB")


if __name__ == "__main__":
    main()
