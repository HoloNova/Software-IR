"""Exact per-archive inventories and incremental, recoverable checksum indexes."""
from __future__ import annotations

import hashlib
from pathlib import Path
import os
import re
import tempfile

from experiments.slm.corpus import DataError
from experiments.slm.dataset_io import digest_file
from experiments.slm.git_source import local_file, relative_path


def files(root: Path) -> dict[str, str]:
    output = {}
    for path in sorted(root.rglob("*")):
        if path.is_symlink() or (getattr(path.lstat(), "st_file_attributes", 0) & 0x400):
            raise DataError("linked archive entry")
        if path.is_file():
            output = {**output, path.relative_to(root).as_posix(): digest_file(path)}
        elif not path.is_dir():
            raise DataError("non-regular archive entry")
    return output


def manifest_bytes(entries: dict[str, str]) -> bytes:
    return "".join(f"{value}  {name}\n" for name, value in sorted(entries.items())).encode("utf-8")


def parse(raw: bytes) -> dict[str, str]:
    result = {}
    for line in raw.decode("utf-8").splitlines():
        match = re.fullmatch(r"([0-9a-f]{64})  (.+)", line)
        if not match:
            raise DataError("invalid checksum index line")
        digest, name = match.groups()
        relative_path(name)
        if name in result or name == "MANIFEST.sha256":
            raise DataError("duplicate or self-referential checksum index")
        result = {**result, name: digest}
    return result


def verify_archive(root: Path) -> dict:
    raw = local_file(root, "MANIFEST.sha256").read_bytes()
    expected = parse(raw)
    actual = files(root)
    if expected != {p: h for p, h in actual.items() if p != "MANIFEST.sha256"}:
        raise DataError("existing archive is incomplete, changed or contains unknown files")
    return actual


def _raw(path: Path) -> bytes:
    if path.is_symlink() or (path.exists() and getattr(path.lstat(), "st_file_attributes", 0) & 0x400):
        raise DataError("linked checksum index")
    if not path.exists():
        return b""
    return local_file(path.parent, path.name).read_bytes()


def bases(repo: Path, bundle: Path) -> dict[str, str]:
    return {p.relative_to(repo).as_posix(): hashlib.sha256(_raw(p)).hexdigest()
            for p in (bundle / "MANIFEST.sha256", repo / "datasets/sir/MANIFEST.sha256")}


def _upsert(raw: bytes, additions: dict) -> bytes:
    existing = parse(raw)
    lines = raw.decode("utf-8").splitlines(keepends=True)
    result = []
    for line in lines:
        name = line.rstrip("\r\n")[66:]
        result = [*result, f"{additions[name]}  {name}\n" if name in additions else line]
    suffix = "".join(f"{digest}  {name}\n" for name, digest in sorted(additions.items()) if name not in existing)
    body = "".join(result)
    if body and not body.endswith("\n"):
        body += "\n"
    return (body + suffix).encode("utf-8")


def _verify(path: Path, raw: bytes, allowed: dict | None = None):
    for name, expected in parse(raw).items():
        actual = digest_file(local_file(path.parent, name))
        if actual != expected and expected not in (allowed or {}).get(name, ()):
            raise DataError("historical checksum entry changed; refusing to bless it: " + name)


def plan(repo: Path, bundle: Path, target: Path, inventory: dict, initial: dict) -> tuple:
    bundle_index = bundle / "MANIFEST.sha256"
    root_index = repo / "datasets/sir/MANIFEST.sha256"
    old_bundle, old_root = _raw(bundle_index), _raw(root_index)
    _verify(bundle_index, old_bundle)
    prefix = target.relative_to(bundle).as_posix() + "/"
    delta = {prefix + name: digest for name, digest in inventory.items()}
    new_bundle = _upsert(old_bundle, delta)
    bundle_key = bundle_index.relative_to(root_index.parent).as_posix()
    old_hash = initial[bundle_index.relative_to(repo).as_posix()]
    # If the first index was installed but the outer index was not, only this
    # archive's appended entries may explain the stale outer hash.
    without_own = b"".join(line for line in old_bundle.splitlines(keepends=True)
                            if not line.decode("utf-8").rstrip("\r\n")[66:].startswith(prefix))
    allowed = {bundle_key: {old_hash}} if hashlib.sha256(without_own).hexdigest() == old_hash else {}
    _verify(root_index, old_root, allowed)
    additions = {target.relative_to(root_index.parent).as_posix() + "/" + n: h for n, h in inventory.items()}
    new_root = _upsert(old_root, {**additions, bundle_key: hashlib.sha256(new_bundle).hexdigest()})
    return ((bundle_index, old_bundle, new_bundle), (root_index, old_root, new_root))


def apply(plans: tuple) -> None:
    for path, before, after in plans:
        if _raw(path) != before:
            raise DataError("checksum index changed concurrently")
        if before == after:
            continue
        descriptor, temporary = tempfile.mkstemp(prefix=".archive-index-", dir=path.parent)
        try:
            with os.fdopen(descriptor, "wb") as output:
                output.write(after)
                output.flush()
                os.fsync(output.fileno())
            if _raw(path) != before:
                raise DataError("checksum index changed concurrently")
            os.replace(temporary, path)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)
