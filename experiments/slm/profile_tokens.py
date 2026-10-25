"""Measure actual chat-template token lengths on CPU. Downloads/executes no model weights."""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from experiments.slm.corpus import DataError
from experiments.slm.dataset_io import digest_file, load_json, load_jsonl, write_new_json

MODELS = ("deepseek-ai/deepseek-coder-1.3b-instruct", "Qwen/Qwen2.5-Coder-3B-Instruct", "Qwen/Qwen3-4B-Instruct-2507")


def _special(config: dict, key: str) -> str:
    value = config.get(key, "")
    return value.get("content", "") if isinstance(value, dict) else (value or "")


def measure(root: Path, rows: list[dict], model: str) -> dict:
    from jinja2.sandbox import ImmutableSandboxedEnvironment
    from tokenizers import Tokenizer
    slug = model.replace("/", "--")
    config_path = root / (slug + "--tokenizer_config.json")
    tokenizer_path = root / (slug + "--tokenizer.json")
    config = load_json(config_path)
    tokenizer = Tokenizer.from_file(str(tokenizer_path))
    tokenizer.no_truncation()
    tokenizer.no_padding()
    environment = ImmutableSandboxedEnvironment(trim_blocks=True, lstrip_blocks=True)
    def reject(message):
        raise DataError("chat template rejected input: " + message)
    environment.globals["raise_exception"] = reject
    template = environment.from_string(config["chat_template"])
    def count(messages, generation):
        text = template.render(messages=messages, add_generation_prompt=generation,
                               bos_token=_special(config, "bos_token"), eos_token=_special(config, "eos_token"),
                               tools=None, enable_thinking=False)
        return len(tokenizer.encode(text, add_special_tokens=False).ids)
    results = [{"id": row["id"], "origin": row["origin"],
                "generation_mode": row.get("generation_mode", "initial"),
                "prompt_tokens": count(row["messages"], True),
                "training_sequence_tokens": count(row["messages"] + [{"role": "assistant", "content": row["sir"]}], False)}
               for row in rows]
    meta = load_json(root / (slug + "--model-api.json"))
    return {"model": model, "resolved_revision": meta["sha"],
            "tokenizer_sha256": digest_file(tokenizer_path), "chat_config_sha256": digest_file(config_path),
            "truncation": "DISABLED", "weight_execution": "NOT_RUN", "samples": results,
            "max_training_sequence_tokens": max(item["training_sequence_tokens"] for item in results)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle", type=Path, required=True)
    parser.add_argument("--models-dir", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    rows = load_jsonl(args.bundle / "candidates.jsonl")
    if not rows:
        raise DataError("no candidates to profile")
    result = {"scope": "observed tokenizer lengths only; no GPU/throughput/quality benchmark",
              "models": [measure(args.models_dir, rows, model) for model in MODELS]}
    write_new_json(args.output, result)
    print(json.dumps(result, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
