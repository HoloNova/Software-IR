"""Observe the unchanged CI #204 parser; no proposed diagnostic behavior is implemented.

Requires the original standalone-cli artifact. A mismatch is an error, not a
reason to accept a different compiler. Only the two observation helpers compile.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import zipfile

BASELINE = "33c2f4bc09e88b91631066ae14f8edd70bb19fae"
CI_COMMIT = "47bf67c8f3d95fbb81d50b1b927c73bba1d0db21"
CI_RUN = "https://github.com/HoloNova/Software-IR/actions/runs/41019419975"
ARTIFACT_ID = 12960753406
JAR_SHA256 = "d23339bf47b6f4ff574e1dcd82bc33f7c7127fae5882b0a1daa89b5fea648a9a"
ZIP_SHA256 = "6b1dbcfd5ba33160cd75f2f1b7b62caa5367824047f3fcfc023fe5c17f351074"


def sha(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def git(root: Path, *args: str) -> bytes:
    return subprocess.check_output(["git", "-C", str(root), *args])


def write_json(path: Path, value):
    with path.open("x", encoding="utf-8", newline="\n") as handle:
        handle.write(json.dumps(value, ensure_ascii=False, indent=2) + "\n")


def main():
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("--repo", type=Path, default=Path.cwd())
    cli.add_argument("--jar", type=Path, required=True)
    cli.add_argument("--artifact-zip", type=Path, required=True)
    cli.add_argument("--output-dir", type=Path, required=True)
    args = cli.parse_args()
    here = Path(__file__).resolve().parent
    root = args.repo.resolve()
    jar = args.jar.resolve()
    archive = args.artifact_zip.resolve()
    assert sha(jar.read_bytes()) == JAR_SHA256, "Not the frozen CI #204 CLI"
    assert sha(archive.read_bytes()) == ZIP_SHA256, "Not the original CI artifact ZIP"
    with zipfile.ZipFile(archive) as bundle:
        entry, = [name for name in bundle.namelist() if name.endswith("kcg.jar")]
        assert bundle.read(entry) == jar.read_bytes()
    parser_tree = git(root, "rev-parse", BASELINE + ":sir-parser").decode().strip()
    assert parser_tree == git(root, "rev-parse", CI_COMMIT + ":sir-parser").decode().strip()
    census = json.loads((here / "census.json").read_text(encoding="utf-8"))
    assert census["git_commit"] == BASELINE
    assert census["classifications_sha256"] == sha((here / "classifications.json").read_bytes())
    inputs = []
    for entry in census["source_snippets"]:
        raw = git(root, "show", BASELINE + ":" + entry["canonical_checked"])
        row = json.loads(raw.decode("utf-8").splitlines()[entry["canonical_line"] - 1])
        assert sha(row["sir"].encode()) == entry["source_sha256"] == row["source_sha256"]
        inputs.append({"id": entry["source_sha256"], "kind": "historical_source", "source": row["sir"]})
    controls_path = here / "probe-controls.json"
    controls = json.loads(controls_path.read_text(encoding="utf-8"))
    inputs.extend(controls)
    output = args.output_dir.resolve()
    if output.exists() and any(output.iterdir()):
        raise ValueError("Observation output directory must be empty; preserve previous evidence")
    output.mkdir(parents=True, exist_ok=True)
    write_json(output / "input.json", inputs)
    classes = output / "classes"
    classes.mkdir()
    limits = ["-Xmx256m", "-XX:MaxMetaspaceSize=128m", "-XX:ActiveProcessorCount=2"]
    helpers = [here / "ProbeJson.java", here / "ParserContextProbe.java"]
    commands = [
        ["javac", "-encoding", "UTF-8", *["-J" + flag for flag in limits], "-cp", str(jar),
         "-d", str(classes), *[str(path) for path in helpers]],
        ["java", *limits, "-cp", str(classes) + os.pathsep + str(jar),
         "io.kcg.sir.internal.ParserContextProbe", str(output / "input.json"), str(output / "observations.json")],
    ]
    excluded = {"JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "DEEPSEEK_API_KEY"}
    environment = {key: value for key, value in os.environ.items() if key not in excluded}
    for number, command in enumerate(commands):
        result = subprocess.run(command, env=environment, capture_output=True, timeout=90)
        (output / f"{number}.stdout").write_bytes(result.stdout)
        (output / f"{number}.stderr").write_bytes(result.stderr)
        if result.returncode:
            raise RuntimeError(f"Observation step {number} failed ({result.returncode}); see preserved stderr")
    observations = json.loads((output / "observations.json").read_text(encoding="utf-8"))
    assert len(observations["cases"]) == len(inputs)
    assert observations["antlr_runtime"] == "4.13.2"
    assert all(case["observer_matches_public_diagnostics"] for case in observations["cases"])
    assert all(not case["accepted"] for case in observations["cases"] if len(case["id"]) == 64)
    provenance = {
        "baseline_commit": BASELINE, "ci_commit": CI_COMMIT, "ci_run_url": CI_RUN,
        "artifact_id": ARTIFACT_ID, "ci_zip_sha256": ZIP_SHA256, "jar_sha256": JAR_SHA256,
        "parser_subtree_git_tree": parser_tree, "commands": commands,
        "inputs_sha256": sha((output / "input.json").read_bytes()),
        "source_sha256": {path.name: sha(path.read_bytes()) for path in helpers},
        "controls_sha256": sha(controls_path.read_bytes()),
        "observations_sha256": sha((output / "observations.json").read_bytes()),
        "historical_sources": len(census["source_snippets"]), "mechanism_controls": len(controls),
        "public_diagnostic_comparisons": len(inputs), "model_calls": 0, "maven_runs": 0,
        "new_diagnostic_implementation": "NOT_IMPLEMENTED",
    }
    write_json(output / "provenance.json", provenance)
    print(json.dumps({key: provenance[key] for key in ["historical_sources", "mechanism_controls", "public_diagnostic_comparisons", "new_diagnostic_implementation"]}))


if __name__ == "__main__":
    main()
