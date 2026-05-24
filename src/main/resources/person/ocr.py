#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""图片 OCR 工具，供 xiaozhangup-bot 调用。

用法:
    python3 ocr.py <image_path>

输出: 识别到的纯文本(可能包含换行)。
退出码:
    0 - 成功 (即使识别为空也算成功)
    1 - 参数错误
    2 - 没有可用 OCR 后端
    3 - 后端运行失败
"""
import os
import sys
import io
import warnings
import importlib.util
from collections.abc import Mapping

# 静音常见的弃用 / 警告噪音
warnings.filterwarnings("ignore")
os.environ.setdefault("KMP_DUPLICATE_LIB_OK", "TRUE")
os.environ.setdefault("PYTHONWARNINGS", "ignore")

# 强制 stdout / stderr 用 UTF-8
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding="utf-8", errors="replace")


def try_paddleocr(image_path: str) -> str:
    if importlib.util.find_spec("paddle") is None:
        raise ImportError("paddlepaddle is required by paddleocr")

    from paddleocr import PaddleOCR  # type: ignore

    try:
        # PaddleOCR 3.x renamed the angle classifier option and removed show_log.
        ocr = PaddleOCR(use_textline_orientation=True, lang="ch")
        result = ocr.predict(image_path)
    except Exception as exc:
        if not _is_unknown_argument_error(exc):
            raise
        # PaddleOCR 2.x compatibility.
        ocr = PaddleOCR(use_angle_cls=True, lang="ch", show_log=False)
        result = ocr.ocr(image_path, cls=True)

    return "\n".join(_extract_paddle_texts(result))


def _is_unknown_argument_error(exc: Exception) -> bool:
    message = str(exc).lower()
    return "unknown argument" in message or "unexpected keyword" in message


def _extract_paddle_texts(result) -> list[str]:
    if not result:
        return []

    lines = []
    for page in result:
        if isinstance(page, Mapping):
            for text in page.get("rec_texts", []):
                if text:
                    lines.append(str(text))
            continue

        if not page:
            continue

        # PaddleOCR 2.x format: [[box, (text, score)], ...]
        for block in page:
            try:
                text = block[1][0]
            except (IndexError, TypeError):
                continue
            if text:
                lines.append(str(text))
    return lines


def try_easyocr(image_path: str) -> str:
    import easyocr  # type: ignore
    reader = easyocr.Reader(["ch_sim", "en"], verbose=False)
    result = reader.readtext(image_path, detail=0, paragraph=False)
    return "\n".join(line for line in result if line)


def try_tesseract(image_path: str) -> str:
    import pytesseract  # type: ignore
    from PIL import Image  # type: ignore
    image = Image.open(image_path)
    text = pytesseract.image_to_string(image, lang="chi_sim+eng")
    return text.strip()


BACKENDS = [
    ("paddleocr", try_paddleocr),
    ("easyocr", try_easyocr),
    ("pytesseract", try_tesseract),
]


def main() -> int:
    if len(sys.argv) < 2:
        print("Usage: ocr.py <image_path>", file=sys.stderr)
        return 1

    image_path = sys.argv[1]
    if not os.path.isfile(image_path):
        print(f"Image not found: {image_path}", file=sys.stderr)
        return 1

    unavailable_errors = []
    last_error = None
    for name, backend in BACKENDS:
        try:
            text = backend(image_path)
            print(text)
            return 0
        except ImportError as exc:
            unavailable_errors.append(f"{name}: {exc}")
            continue
        except Exception as exc:
            last_error = f"{name}: {exc}"
            continue

    if last_error is None:
        details = "; ".join(unavailable_errors)
        print(
            "No OCR backend available. Install one of: paddleocr+paddlepaddle / easyocr / pytesseract",
            file=sys.stderr,
        )
        if details:
            print(f"Unavailable backends: {details}", file=sys.stderr)
        return 2

    print(f"All OCR backends failed. Last error: {last_error}", file=sys.stderr)
    return 3


if __name__ == "__main__":
    sys.exit(main())
