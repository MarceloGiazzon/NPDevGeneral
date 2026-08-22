#!/usr/bin/env python3
"""A small, honest pure-Python PNG codec + pixel differ, for `npdev_explore.py`'s screenshot
baseline comparison (R3.6).

WHY THIS EXISTS RATHER THAN `import PIL` / `import numpy`
-----------------------------------------------------------
Same reasoning `npdev_jsonschema.py` documents for hand-rolling a JSON Schema validator: the CLI
must work on a machine where only the CLI zip is installed, with no third-party packages available.
The roadmap item is explicit about this too -- "a pure-Python pixel diff."

WHY IT IS SAFE TO BE NARROW
----------------------------
This is not a general PNG decoder. It decodes exactly the form the harness actually produces,
MEASURED rather than assumed: 2026-08-19, every one of 72 real screenshot blobs on disk (two
generated apps' `_ops/exploration-runs/blobs/`) is colour type 2 (truecolor RGB), 8-bit depth,
non-interlaced -- what Chromium/Playwright write for an opaque full-page screenshot. Colour type 6
(truecolor + alpha) decodes with the identical scanline-unfiltering logic, so it is supported too
in case a transparent-background capture ever produces one. Anything else (palette, grayscale,
16-bit samples, interlaced) is refused with a named error rather than silently misread -- an
unsupported PNG that is treated as "no difference found" is a worse defect than a loud refusal.

Two entry points `npdev_explore.py` uses:
    decode_png(data) -> (width, height, channels, pixels)
    encode_png(width, height, channels, pixels) -> bytes
    diff_png_bytes(baseline, current, channel_tolerance=...) -> dict

`encode_png` is also what the diff image is written with, and what the test suite uses to build
tiny fixture PNGs in-memory -- no binary fixture files checked into the repo.
"""

from __future__ import annotations

import struct
import zlib

PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"

# Colour types this decoder honours -- 2 (truecolor RGB) and 6 (truecolor + alpha), both only at
# 8-bit depth, non-interlaced. See module docstring for why these two and nothing else.
_SUPPORTED_COLOR_TYPES = {2: 3, 6: 4}  # colourType -> channel count


class PngError(Exception):
    """A PNG this decoder cannot read -- either not a PNG at all, or a real-but-unsupported form.
    Never raised for "no difference found"; a diff that cannot be computed is reported as an error
    on the caller's side, not swallowed into a false pass."""


def _paeth_predictor(a: int, b: int, c: int) -> int:
    p = a + b - c
    pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
    if pa <= pb and pa <= pc:
        return a
    if pb <= pc:
        return b
    return c


def decode_png(data: bytes) -> tuple[int, int, int, bytearray]:
    """Returns (width, height, channels, pixels) -- `pixels` is `height * width * channels` bytes,
    8-bit samples, row-major, top row first. `channels` is 3 (RGB) or 4 (RGBA)."""
    if data[:8] != PNG_SIGNATURE:
        raise PngError("not a PNG file (bad signature)")

    offset = 8
    total = len(data)
    width = height = bit_depth = color_type = interlace = None
    idat = bytearray()
    while offset < total:
        if offset + 8 > total:
            raise PngError("truncated PNG (a chunk header is cut off)")
        chunk_len = struct.unpack(">I", data[offset:offset + 4])[0]
        chunk_type = data[offset + 4:offset + 8]
        chunk_start = offset + 8
        chunk_data = data[chunk_start:chunk_start + chunk_len]
        offset = chunk_start + chunk_len + 4  # + 4-byte CRC, unchecked -- content matters, not integrity
        if chunk_type == b"IHDR":
            if len(chunk_data) < 13:
                raise PngError("truncated IHDR chunk")
            width, height, bit_depth, color_type, _compression, _filter_method, interlace = \
                struct.unpack(">IIBBBBB", chunk_data[:13])
        elif chunk_type == b"IDAT":
            idat += chunk_data
        elif chunk_type == b"IEND":
            break

    if width is None:
        raise PngError("no IHDR chunk found")
    if bit_depth != 8 or color_type not in _SUPPORTED_COLOR_TYPES or interlace != 0:
        raise PngError(
            f"unsupported PNG form (colorType={color_type} bitDepth={bit_depth} interlace={interlace}) "
            "-- this decoder only reads 8-bit truecolor (colorType 2) or truecolor+alpha (colorType 6), "
            "non-interlaced, which is what Chromium/Playwright actually write for a screenshot"
        )
    if not idat:
        raise PngError("no IDAT chunk found (no pixel data)")

    channels = _SUPPORTED_COLOR_TYPES[color_type]
    try:
        raw = zlib.decompress(bytes(idat))
    except zlib.error as exc:
        raise PngError(f"could not inflate PNG pixel data: {exc}") from exc

    stride = width * channels
    expected_len = height * (stride + 1)
    if len(raw) < expected_len:
        raise PngError(f"decompressed PNG data is short ({len(raw)} bytes, expected {expected_len})")

    pixels = bytearray(height * stride)
    prev_row = bytearray(stride)
    pos = 0
    for row in range(height):
        filter_type = raw[pos]
        pos += 1
        scan = raw[pos:pos + stride]
        pos += stride
        out_row = bytearray(stride)
        if filter_type == 0:  # None
            out_row[:] = scan
        elif filter_type == 1:  # Sub
            for x in range(stride):
                left = out_row[x - channels] if x >= channels else 0
                out_row[x] = (scan[x] + left) & 0xFF
        elif filter_type == 2:  # Up
            for x in range(stride):
                out_row[x] = (scan[x] + prev_row[x]) & 0xFF
        elif filter_type == 3:  # Average
            for x in range(stride):
                left = out_row[x - channels] if x >= channels else 0
                up = prev_row[x]
                out_row[x] = (scan[x] + ((left + up) >> 1)) & 0xFF
        elif filter_type == 4:  # Paeth
            for x in range(stride):
                left = out_row[x - channels] if x >= channels else 0
                up = prev_row[x]
                up_left = prev_row[x - channels] if x >= channels else 0
                out_row[x] = (scan[x] + _paeth_predictor(left, up, up_left)) & 0xFF
        else:
            raise PngError(f"unsupported scanline filter type {filter_type} on row {row}")
        pixels[row * stride:(row + 1) * stride] = out_row
        prev_row = out_row

    return width, height, channels, pixels


def _chunk(chunk_type: bytes, data: bytes) -> bytes:
    return (struct.pack(">I", len(data)) + chunk_type + data
            + struct.pack(">I", zlib.crc32(chunk_type + data) & 0xFFFFFFFF))


def encode_png(width: int, height: int, channels: int, pixels: bytes) -> bytes:
    """Writer for the decoder above: filter type 0 (None) on every scanline, one IDAT chunk. Used to
    write the diff image `npdev_explore.py` stores on a regression, and by the test suite to build
    fixture PNGs in-memory rather than checking in binary files."""
    if channels not in (3, 4):
        raise PngError(f"unsupported channel count {channels} (only 3=RGB or 4=RGBA)")
    if len(pixels) != width * height * channels:
        raise PngError(
            f"pixel buffer is {len(pixels)} bytes, expected {width * height * channels} "
            f"for {width}x{height}x{channels}"
        )
    color_type = 2 if channels == 3 else 6
    stride = width * channels
    raw = bytearray()
    for row in range(height):
        raw.append(0)  # filter type None
        raw += pixels[row * stride:(row + 1) * stride]
    compressed = zlib.compress(bytes(raw), 6)
    ihdr = struct.pack(">IIBBBBB", width, height, 8, color_type, 0, 0, 0)
    out = bytearray(PNG_SIGNATURE)
    out += _chunk(b"IHDR", ihdr)
    out += _chunk(b"IDAT", compressed)
    out += _chunk(b"IEND", b"")
    return bytes(out)


def diff_png_bytes(baseline: bytes, current: bytes, *, channel_tolerance: int = 24) -> dict:
    """Pixel-by-pixel comparison of two PNGs already read into memory. A pixel counts as differing
    only when some RGB channel's absolute delta exceeds `channel_tolerance` -- pure anti-aliasing /
    font-hinting noise between two real renders of the same page is a handful of units per channel,
    not what a visual regression threshold exists to catch.

    Returns a dict, never raises for a dimension mismatch (that is reported as data, per R3.6's
    "must be a clear failure, not a crash"); DOES raise `PngError` if either PNG cannot be decoded
    at all (a real read failure, not a comparison result):
        baselineSize / currentSize: [width, height]
        dimensionMismatch: bool
        diffFraction / diffPixels / totalPixels: None when dimensionMismatch is True
        diffPng: PNG bytes highlighting differing pixels in solid red, None when dimensionMismatch
    """
    b_width, b_height, b_channels, b_pixels = decode_png(baseline)
    c_width, c_height, c_channels, c_pixels = decode_png(current)

    result = {
        "baselineSize": [b_width, b_height],
        "currentSize": [c_width, c_height],
        "dimensionMismatch": (b_width, b_height) != (c_width, c_height),
        "diffFraction": None,
        "diffPixels": None,
        "totalPixels": None,
        "diffPng": None,
    }
    if result["dimensionMismatch"]:
        return result

    total = b_width * b_height
    diff_pixels = 0
    out = bytearray(total * 3)
    for i in range(total):
        b_off, c_off = i * b_channels, i * c_channels
        differs = (
            abs(b_pixels[b_off] - c_pixels[c_off]) > channel_tolerance
            or abs(b_pixels[b_off + 1] - c_pixels[c_off + 1]) > channel_tolerance
            or abs(b_pixels[b_off + 2] - c_pixels[c_off + 2]) > channel_tolerance
        )
        out_off = i * 3
        if differs:
            diff_pixels += 1
            out[out_off], out[out_off + 1], out[out_off + 2] = 255, 0, 0
        else:
            out[out_off] = c_pixels[c_off]
            out[out_off + 1] = c_pixels[c_off + 1]
            out[out_off + 2] = c_pixels[c_off + 2]

    result["diffPixels"] = diff_pixels
    result["totalPixels"] = total
    result["diffFraction"] = (diff_pixels / total) if total else 0.0
    result["diffPng"] = encode_png(b_width, b_height, 3, bytes(out))
    return result
