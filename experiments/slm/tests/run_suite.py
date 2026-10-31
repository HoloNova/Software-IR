"""Offline Python contracts with durable JSON/raw-log evidence; never calls services."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import unittest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", type=Path, required=True)
    args = parser.parse_args()
    args.evidence.mkdir(parents=True, exist_ok=False)
    tests = Path(__file__).resolve().parent
    with (args.evidence / "unittest.log").open("w", encoding="utf-8", newline="\n") as output:
        suite = unittest.defaultTestLoader.discover(str(tests), pattern="test_*.py")
        result = unittest.TextTestRunner(stream=output, verbosity=2).run(suite)
    summary = {"tests": result.testsRun, "failures": len(result.failures), "errors": len(result.errors),
               "skipped": len(result.skipped), "passed": result.wasSuccessful() and result.testsRun > 0,
               "failed_tests": [str(t) for t, _ in result.failures + result.errors]}
    (args.evidence / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8", newline="\n")
    print(json.dumps(summary), flush=True)
    return 0 if summary["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
