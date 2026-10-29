"""Writes the MiniLM reference token ids and embeddings that Pecia's tests compare against.

Run from this directory with sentence-transformers and torch installed (versions are recorded in the output):

    python generate_reference.py

The model is loaded at the revision Pecia pins, so the references describe exactly the model Pecia downloads.
"""
import json
import platform
import unicodedata

import sentence_transformers
import tokenizers
import torch
import transformers
from sentence_transformers import SentenceTransformer

REPOSITORY = "sentence-transformers/all-MiniLM-L6-v2"
REVISION = "1110a243fdf4706b3f48f1d95db1a4f5529b4d41"

# Each case exercises one tokenization or embedding concern; the long one exceeds the 256-token limit.
CASES = [
    ("empty", ""),
    ("hello", "hello world"),
    ("sentence", "Payment validation runs before the order is confirmed."),
    ("mixed-case", "PaYmEnT VALIDATION Runs"),
    ("punctuation", "Wait... what?! (Really) -- yes; \"quoted\" & 'single'."),
    ("turkish", "İstanbul'da ödeme doğrulaması çalışıyor; ışık, ğ, ş, ç, ö, ü."),
    ("accents", "café naïve résumé Ångström façade"),
    ("chinese", "中文分词测试 with English"),
    ("emoji", "deploy 🚀 succeeded ✅"),
    ("code", "public static int count(String text) { return tokenize(text).size(); }"),
    ("numbers", "Version 1.31.0 shipped on 2026-10-09 with 384 dimensions and 256 tokens."),
    ("whitespace", "  tabs\tand\nnew lines\r\n  around  "),
    ("special-token-text", "the [SEP] token and [CLS] appear as text"),
    ("unknown-characters", "control\u0007char, format\u200bchar, private use \ue000 and unassigned \u0378 here"),
    ("separators", "first\u2028second\u2029third\u3000fourth"),
    ("cjk-extension", "ab\U0002b820cd stays one word while ab\U0002b920cd splits"),
    ("long", " ".join(f"word{index} payment validation" for index in range(150))),
]


def main():
    model = SentenceTransformer(REPOSITORY, revision=REVISION, device="cpu")
    tokenizer = model.tokenizer
    max_length = model.max_seq_length
    texts = [text for _, text in CASES]
    embeddings = model.encode(texts, batch_size=len(texts), convert_to_numpy=True)
    generator = {
        "python": platform.python_version(),
        "sentence-transformers": sentence_transformers.__version__,
        "transformers": transformers.__version__,
        "tokenizers": tokenizers.__version__,
        "torch": torch.__version__,
    }
    header = header_lines(max_length, generator)

    ids = [tokenizer(text, truncation=True, max_length=max_length)["input_ids"] for text in texts]
    write("minilm-token-ids.toml", header, [
        [f"name = {string(name)}", f"text = {string(text)}", f"ids = [{', '.join(str(i) for i in case_ids)}]"]
        for (name, text), case_ids in zip(CASES, ids)
    ])
    write("minilm-embeddings.toml", header, [
        [f"name = {string(name)}", f"text = {string(text)}", f"vector = [{', '.join(repr(float(v)) for v in vector)}]"]
        for (name, text), vector in zip(CASES, embeddings)
    ])


def header_lines(max_length, generator):
    lines = [
        "# Written by generate_reference.py; do not edit by hand.",
        f"repository = {string(REPOSITORY)}",
        f"revision = {string(REVISION)}",
        f"maxLength = {max_length}",
        "",
        "[generator]",
    ]
    lines += [f"{string(key)} = {string(value)}" for key, value in generator.items()]
    return lines


def string(value):
    # JSON escapes are valid in TOML basic strings; invisible characters are escaped so the fixtures stay readable.
    return "".join(visible(character) for character in json.dumps(value, ensure_ascii=False))


def visible(character):
    if character == " " or unicodedata.category(character)[0] not in "CZ":
        return character
    code_point = ord(character)
    return f"\\u{code_point:04x}" if code_point <= 0xFFFF else f"\\U{code_point:08x}"


def write(name, header, cases):
    lines = list(header)
    for case in cases:
        lines += ["", "[[cases]]"] + case
    with open(name, "w", encoding="utf-8", newline="\n") as output:
        output.write("\n".join(lines) + "\n")


if __name__ == "__main__":
    main()
