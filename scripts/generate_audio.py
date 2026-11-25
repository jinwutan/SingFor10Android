#!/usr/bin/env python3
"""
生成 app/src/main/res/raw/sing.wav
公共领域旋律《小星星》，加性合成 + 颤音 + 谐波共振峰，模拟哼唱音色。
仅使用 Python 标准库。
"""
import math
import os
import struct
import wave

SAMPLE_RATE = 44100
NOTE_DURATION = 0.45

# 频率表（C5 八度），0 = 休止符
MELODY = [
    523.25, 523.25, 783.99, 783.99, 880.00, 880.00, 783.99, 0,
    698.46, 698.46, 659.25, 659.25, 587.33, 587.33, 523.25, 0,
    783.99, 783.99, 698.46, 698.46, 659.25, 659.25, 587.33, 0,
    783.99, 783.99, 698.46, 698.46, 659.25, 659.25, 587.33, 0,
    523.25, 523.25, 783.99, 783.99, 880.00, 880.00, 783.99, 0,
    698.46, 698.46, 659.25, 659.25, 587.33, 587.33, 523.25, 0,
]

HARMONICS = (1.00, 0.70, 0.35, 0.15, 0.08)
NORM = sum(HARMONICS)


def main():
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    out_dir = os.path.join(root, "app", "src", "main", "res", "raw")
    os.makedirs(out_dir, exist_ok=True)
    out_path = os.path.join(out_dir, "sing.wav")

    total_frames = int(len(MELODY) * NOTE_DURATION * SAMPLE_RATE)
    phase = 0.0
    frames = bytearray()

    for frame in range(total_frames):
        t = frame / SAMPLE_RATE
        note_float = t / NOTE_DURATION
        idx = int(note_float) % len(MELODY)
        freq = MELODY[idx]
        local = note_float - math.floor(note_float)

        if freq <= 0:
            sample = 0.0
        else:
            attack = min(1.0, local * NOTE_DURATION / 0.03)
            release = min(1.0, (1.0 - local) * NOTE_DURATION / 0.08)
            envelope = max(0.0, min(attack, release)) * 0.9

            vibrato = 1.0 + 0.006 * math.sin(2.0 * math.pi * 5.0 * t)
            phase += 2.0 * math.pi * freq * vibrato / SAMPLE_RATE
            if phase > 2.0 * math.pi * 4096:
                phase -= 2.0 * math.pi * 4096

            value = 0.0
            for h, amp in enumerate(HARMONICS, start=1):
                value += amp * math.sin(h * phase)
            sample = value / NORM * envelope

        pcm = int(max(-1.0, min(1.0, sample)) * 32767)
        frames += struct.pack("<h", pcm)

    with wave.open(out_path, "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(SAMPLE_RATE)
        wav.writeframes(bytes(frames))

    print("Generated:", out_path)
    print("Duration: %.2f s" % (total_frames / SAMPLE_RATE))
    print("Size: %.2f MB" % (os.path.getsize(out_path) / 1024 / 1024))


if __name__ == "__main__":
    main()
