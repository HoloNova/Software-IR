"""Bounded, read-only committed bytes shared by collector and gate provenance."""
from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
from types import MappingProxyType

from experiments.slm.corpus import DataError

MAX_BLOB = 16 * 1024 * 1024
MAX_BATCH = 96 * 1024 * 1024
MAX_TREE = 200000
OID = re.compile(r"(?:[0-9a-f]{40}|[0-9a-f]{64})\Z")


def relative_path(value: str) -> str:
    if not isinstance(value, str) or not value or "\\" in value or ":" in value:
        raise DataError("expected a portable relative path")
    parts = value.split("/")
    if any(p in ("", ".", "..") or p.endswith((".", " ")) or any(ord(c) < 32 or c in '<>"|?*' for c in p) for p in parts):
        raise DataError("unsafe relative path")
    if any(re.fullmatch(r"(?:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])", p.split(".")[0], re.I) for p in parts):
        raise DataError("nonportable relative path")
    return PurePosixPath(value).as_posix()


def local_file(root: Path, name: str) -> Path:
    current = root.resolve(strict=True)
    for part in relative_path(name).split("/"):
        current = current / part
        if current.is_symlink() or (current.exists() and getattr(current.lstat(), "st_file_attributes", 0) & 0x400):
            raise DataError("linked input is not accepted")
    if not current.is_file() or not current.resolve().is_relative_to(root.resolve()):
        raise DataError("input is missing or escapes its root")
    return current


def run_git(repo: Path, arguments: list[str], *, input: bytes | None = None, allowed=(0,)) -> bytes:
    env = {k: v for k, v in os.environ.items() if not k.startswith("GIT_TRACE") and k not in ("GIT_DIR", "GIT_WORK_TREE", "GIT_INDEX_FILE")}
    env = {**env, "GIT_NO_REPLACE_OBJECTS": "1"}
    try:
        result = subprocess.run(["git", *arguments], cwd=repo, input=input, capture_output=True, timeout=30, env=env)
    except (OSError, subprocess.SubprocessError):
        raise DataError("Git read failed; no automatic fetch or retry") from None
    if result.returncode not in allowed:
        raise DataError("required Git object/input is unavailable; synchronize explicitly first")
    return result.stdout


def _tree_entry(entry: bytes):
    header, name = entry.split(b"\t", 1)
    mode, kind, oid, size = header.split()
    return name.decode("utf-8", errors="strict"), (mode, kind, oid, int(size) if size != b"-" else -1)


@dataclass(frozen=True)
class GitSource:
    repo: Path
    revision: str
    entries: Mapping[str, tuple[bytes, bytes, bytes, int]]

    @classmethod
    def at(cls, repo: Path, revision: str | None = None):
        repo = repo.resolve(strict=True)
        head = run_git(repo, ["rev-parse", "HEAD"]).decode().strip() if revision is None else revision
        if not isinstance(head, str) or not OID.fullmatch(head) or run_git(repo, ["cat-file", "-t", head]).strip() != b"commit":
            raise DataError("source must identify a full Git commit")
        raw = run_git(repo, ["ls-tree", "-rlz", head])
        if len(raw) > MAX_BLOB:
            raise DataError("Git tree exceeds the input budget")
        entries = dict(_tree_entry(entry) for entry in raw.split(b"\0") if entry)
        if len(entries) > MAX_TREE:
            raise DataError("Git tree has too many entries")
        return cls(repo, head, MappingProxyType(entries))

    def blobs(self, paths) -> dict[str, bytes]:
        names = tuple(dict.fromkeys(relative_path(p) for p in paths))
        for name in names:
            entry = self.entries.get(name)
            if entry is None or entry[0] not in (b"100644", b"100755") or entry[1] != b"blob":
                raise DataError("required regular Git blob is missing: " + name)
            if entry[3] > MAX_BLOB:
                raise DataError("Git blob exceeds 16 MiB: " + name)
        if sum(self.entries[n][3] for n in names) > MAX_BATCH:
            raise DataError("Git blob batch exceeds 96 MiB")
        if not names:
            return {}
        request = b"".join(self.entries[n][2] + b"\n" for n in names)
        raw = run_git(self.repo, ["cat-file", "--batch"], input=request)
        offset, output = 0, {}
        for name in names:
            end = raw.index(b"\n", offset)
            oid, kind, size = raw[offset:end].split()
            length = int(size)
            if oid != self.entries[name][2] or kind != b"blob" or length != self.entries[name][3]:
                raise DataError("Git batch response does not match its tree")
            start = end + 1
            content = raw[start:start + length]
            output = {**output, name: content}
            if len(content) != length or raw[start + length:start + length + 1] != b"\n":
                raise DataError("truncated Git batch response")
            offset = start + length + 1
        if offset != len(raw):
            raise DataError("unexpected trailing Git batch response")
        return output
