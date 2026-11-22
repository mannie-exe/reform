"""Keep reviewed host advisories visible while gating unclassified dependency findings."""

import argparse
import json
from pathlib import Path
import re
import sys

if __package__:
    from .targets import load_targets
else:
    from targets import load_targets

ROOT = Path(__file__).resolve().parents[1]
BLOCKING = {"HIGH", "CRITICAL"}
SEVERITIES = BLOCKING | {"UNKNOWN", "LOW", "MEDIUM"}


def load_json(path, maximum):
    if path.stat().st_size > maximum:
        raise ValueError("Security input exceeds its size limit")
    return json.loads(path.read_text(encoding="utf-8"))


def provided_packages(policy):
    if not isinstance(policy, dict) or policy.get("schemaVersion") != 1:
        raise ValueError("Unsupported security policy")
    entries = policy.get("providedRuntime")
    if not isinstance(entries, list):
        raise ValueError("Missing provided-runtime policy")
    result = {}
    for entry in entries:
        if not isinstance(entry, dict):
            raise ValueError("Invalid provided-runtime entry")
        package, versions, reason = (entry.get(key) for key in ("package", "versions", "reason"))
        if (not isinstance(package, str) or not re.fullmatch(r"[\w.-]+:[\w.-]+", package)
                or not isinstance(versions, list) or not versions
                or not isinstance(reason, str) or not reason.strip()):
            raise ValueError("Provided-runtime policy needs exact packages, versions and reasons")
        for version in versions:
            if not isinstance(version, str) or not re.fullmatch(r"[\w.+-]+", version):
                raise ValueError("Provided-runtime versions must be exact")
            key = (package, version)
            if key in result:
                raise ValueError("Duplicate provided-runtime policy")
            result[key] = reason
    return result


def review(report, policy, targets):
    provided = provided_packages(policy)
    if not isinstance(report, dict) or report.get("SchemaVersion") != 2:
        raise ValueError("Unsupported Trivy report")
    results = report.get("Results")
    if not isinstance(results, list):
        raise ValueError("Missing dependency inventory results")
    expected = {name + "/gradle.lockfile" for name in ("core", *(t["project"] for t in targets))}
    seen, findings = set(), []
    for result in results:
        if not isinstance(result, dict):
            raise ValueError("Invalid dependency inventory result")
        target = result.get("Target")
        if not isinstance(target, str):
            raise ValueError("Missing dependency inventory target")
        target = target.replace("\\", "/")
        if target not in expected or target in seen:
            raise ValueError("Unknown or duplicate dependency inventory target")
        seen.add(target)
        vulnerabilities = result.get("Vulnerabilities", [])
        if vulnerabilities is None:
            vulnerabilities = []
        if not isinstance(vulnerabilities, list):
            raise ValueError("Invalid vulnerability list")
        for finding in vulnerabilities:
            if not isinstance(finding, dict):
                raise ValueError("Invalid vulnerability")
            fields = [finding.get(key) for key in
                      ("VulnerabilityID", "PkgName", "InstalledVersion", "Severity")]
            if not all(isinstance(value, str) and value for value in fields):
                raise ValueError("Incomplete vulnerability identity")
            identifier, package, version, severity = fields
            if severity not in SEVERITIES:
                raise ValueError("Unknown vulnerability severity")
            reason = provided.get((package, version))
            disposition = ("provided-advisory" if reason else
                           "blocking" if severity in BLOCKING else "below-threshold")
            findings.append({"target": target, "id": identifier, "package": package,
                             "version": version, "severity": severity, "disposition": disposition,
                             "reason": reason or "Not classified as a reviewed provided runtime"})
    if seen != expected:
        raise ValueError("Dependency inventory is incomplete")
    blocked = sum(f["disposition"] == "blocking" for f in findings)
    return {"status": "FAIL" if blocked else "PASS", "blockingFindings": blocked,
            "providedAdvisories": sum(f["disposition"] == "provided-advisory" for f in findings),
            "targets": sorted(seen), "findings": findings}


def summary(result):
    lines = ["## Dependency security review", "", "Status: " + result["status"], ""]
    if result["status"] == "ERROR":
        lines += ["The scan or policy could not be evaluated. Release qualification failed.", ""]
    else:
        lines += [f"Blocking findings: {result['blockingFindings']}. "
                  f"Reviewed host advisories: {result['providedAdvisories']}.", "",
                  "Host advisories remain unresolved; this status is not a whole-game security certification.",
                  "", "| Inventory | Blocking | Host advisories |", "| --- | ---: | ---: |"]
        for target in result["targets"]:
            rows = [f for f in result["findings"] if f["target"] == target]
            lines.append(f"| {target} | {sum(f['disposition'] == 'blocking' for f in rows)} | "
                         f"{sum(f['disposition'] == 'provided-advisory' for f in rows)} |")
        lines.append("")
    return "\n".join(lines)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", type=Path)
    parser.add_argument("--policy", type=Path, default=ROOT / "security-policy.json")
    parser.add_argument("--output", type=Path, default=ROOT / "build/security-review.json")
    parser.add_argument("--summary", type=Path, help="Append the review to the selected CI job summary")
    args = parser.parse_args(argv)
    try:
        result = review(load_json(args.report, 32 * 1024 * 1024),
                        load_json(args.policy, 262144), load_targets(ROOT))
        code = 1 if result["blockingFindings"] else 0
    except (OSError, ValueError, TypeError, KeyError) as failed:
        result = {"status": "ERROR", "reason": str(failed)}
        code = 2
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    if args.summary:
        with args.summary.open("a", encoding="utf-8") as output:
            output.write(summary(result))
    print(summary(result))
    return code


if __name__ == "__main__":
    sys.exit(main())
