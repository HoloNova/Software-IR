"""Collector identities refer to verified Git bytes, never executable archived scripts."""
from __future__ import annotations

import ast
import hashlib
from pathlib import Path

from experiments.slm.corpus import DataError, SHA_PATTERN
from experiments.slm.dataset_io import REPO, digest_file, fingerprint, load_json
from experiments.slm.git_source import GitSource, OID, local_file, relative_path

PREFIX = "experiments/slm/"
ENTRY = PREFIX + "data.py"
LEGACY = frozenset(("data.py", "corpus.py", "dataset_io.py"))


def _imports(path: str, content: bytes, available) -> tuple[str, ...]:
    try:
        tree = ast.parse(content, filename=path)
    except (SyntaxError, UnicodeError):
        raise DataError("collector source cannot be parsed: " + path) from None
    names = ()
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            names += tuple(item.name for item in node.names)
        elif isinstance(node, ast.ImportFrom):
            base = node.module or ""
            if node.level:
                package = path.removesuffix(".py").split("/")[:-node.level]
                base = ".".join((*package, base)) if base else ".".join(package)
            names += (base, *(base + "." + item.name for item in node.names))
    found = ()
    for name in names:
        if name == "experiments.slm" or name.startswith("experiments.slm."):
            stem = name.replace(".", "/")
            for candidate in (stem + ".py", stem + "/__init__.py"):
                if candidate in available:
                    found += (candidate,)
    return tuple(sorted(set(found)))


def _closure(source: GitSource) -> dict[str, bytes]:
    pending, found = (ENTRY,), {}
    while pending:
        batch = source.blobs(pending)
        found = {**found, **batch}
        pending = tuple(sorted({p for n, content in batch.items() for p in _imports(n, content, source.entries) if p not in found}))
    return found


def capture(repo: Path | None = None) -> dict:
    source = GitSource.at(repo or REPO)
    files = _closure(source)
    working = {p.relative_to(source.repo).as_posix() for p in (source.repo / PREFIX).rglob("*.py")}
    for path, content in files.items():
        if local_file(source.repo, path).read_bytes() != content:
            raise DataError("collector source differs from committed bytes; use a committed LF snapshot: " + path)
        if _imports(path, content, working) != _imports(path, content, source.entries):
            raise DataError("collector has an uncommitted dependency")
    entries = {p[len(PREFIX):]: {"path": p, "sha256": hashlib.sha256(content).hexdigest()} for p, content in files.items()}
    return {"collector_sha256": fingerprint({n: v["sha256"] for n, v in entries.items()}),
            "collector_reference": {"schema_version": "sir-collector-reference/1", "kind": "native",
                                    "commit": source.revision, "files": entries}}


def _verify_reference(reference: dict, digest: str, repo: Path) -> None:
    if not isinstance(reference, dict) or reference.get("schema_version") != "sir-collector-reference/1":
        raise DataError("invalid collector reference schema")
    revision = reference.get("commit")
    if not isinstance(revision, str) or not OID.fullmatch(revision):
        raise DataError("collector reference requires a full Git commit")
    source = GitSource.at(repo, revision)
    files = reference.get("files")
    if not isinstance(files, dict) or not files:
        raise DataError("collector reference needs source files")
    for name, item in files.items():
        relative_path(name)
        if not name.endswith(".py") or not isinstance(item, dict) or not SHA_PATTERN.fullmatch(str(item.get("sha256", ""))):
            raise DataError("invalid collector reference file")
        relative_path(item.get("path"))
    mapping = {n: item["sha256"] for n, item in files.items()}
    if fingerprint(mapping) != digest:
        raise DataError("collector reference fingerprint mismatch")
    blobs = source.blobs(item["path"] for item in files.values())
    if any(hashlib.sha256(blobs[item["path"]]).hexdigest() != item["sha256"] for item in files.values()):
        raise DataError("collector reference blob fingerprint mismatch")
    if reference.get("kind") == "native":
        actual = _closure(source)
        if {PREFIX + n: blobs[item["path"]] for n, item in files.items()} != actual or any(
                item["path"] != PREFIX + name for name, item in files.items()):
            raise DataError("collector dependency closure mismatch")
    elif reference.get("kind") == "legacy-snapshot":
        inputs_path = relative_path(reference.get("inputs_path"))
        parent = inputs_path.removesuffix("/inputs.json")
        if not inputs_path.endswith("/inputs.json") or not parent.endswith("/collectors/" + digest) or set(files) != LEGACY:
            raise DataError("invalid legacy collector reference")
        from experiments.slm.dataset_io import decode_json
        inputs = decode_json(source.blobs([inputs_path])[inputs_path])
        if inputs != mapping or any(item["path"] != parent + "/" + n for n, item in files.items()):
            raise DataError("legacy collector inputs do not match their reference")
    else:
        raise DataError("unsupported collector reference kind")


def _legacy(root: Path, digest: str) -> dict:
    directory = root / "collectors" / digest
    inputs = load_json(local_file(directory, "inputs.json"))
    if not isinstance(inputs, dict) or set(inputs) != LEGACY or fingerprint(inputs) != digest:
        raise DataError("invalid collector snapshot inputs")
    if any(digest_file(local_file(directory, name)) != value for name, value in inputs.items()):
        raise DataError("collector snapshot changed after collection")
    return inputs


def validate(root: Path, row: dict, *, repo: Path | None = None):
    digest = row.get("collector_sha256")
    reference = row.get("collector_reference")
    if digest is None and reference is None:
        return None
    if not SHA_PATTERN.fullmatch(str(digest or "")):
        raise DataError("invalid collector identifier")
    if reference is not None:
        _verify_reference(reference, digest, repo or REPO)
        return reference
    _legacy(root, digest)
    return None


def for_import(origin: Path, rows: list[dict], *, repo: Path | None = None) -> dict:
    repository = (repo or REPO).resolve()
    source = GitSource.at(repository)
    references = {}
    for row in rows:
        if not row.get("collector_sha256"):
            if row.get("collector_reference") is not None:
                raise DataError("collector reference has no fingerprint")
            continue
        existing = validate(origin, row, repo=repository)
        if existing is not None:
            references = {**references, row["id"]: {"collector_sha256": row["collector_sha256"], "collector_reference": existing}}
            continue
        digest = row["collector_sha256"]
        inputs = _legacy(origin, digest)
        directory = origin.resolve() / "collectors" / digest
        if not directory.is_relative_to(repository):
            raise DataError("legacy collector must be committed before import")
        parent = directory.relative_to(repository).as_posix()
        paths = [parent + "/" + n for n in (*sorted(inputs), "inputs.json")]
        blobs = source.blobs(paths)
        if any(local_file(repository, p).read_bytes() != blobs[p] for p in paths):
            raise DataError("legacy collector differs from its committed source")
        ref = {"schema_version": "sir-collector-reference/1", "kind": "legacy-snapshot", "commit": source.revision,
               "inputs_path": parent + "/inputs.json", "files": {n: {"path": parent + "/" + n, "sha256": v} for n, v in inputs.items()}}
        _verify_reference(ref, digest, repository)
        references = {**references, row["id"]: {"collector_sha256": digest, "collector_reference": ref}}
    return references
