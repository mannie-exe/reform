"""A host-advisory classification must not hide unknown dependency or scanner failures."""

from contextlib import redirect_stdout
import io
import json
from pathlib import Path
import tempfile
import unittest

from tools.review_security import main, provided_packages, review, summary
from tools.targets import load_targets

ROOT = Path(__file__).resolve().parents[2]


class SecurityReviewTests(unittest.TestCase):
    def setUp(self):
        self.targets = load_targets(ROOT)
        self.policy = json.loads((ROOT / "security-policy.json").read_text())
        self.report = {"SchemaVersion": 2, "Results": [
            {"Target": name + "/gradle.lockfile", "Vulnerabilities": []}
            for name in ["core", *(target["project"] for target in self.targets)]]}

    def add(self, package="io.netty:netty-handler", version="4.1.97.Final", severity="HIGH"):
        value = {"VulnerabilityID": "CVE-2099-1234", "PkgName": package,
                 "InstalledVersion": version, "Severity": severity}
        self.report["Results"][1]["Vulnerabilities"].append(value)
        return value

    def test_provided_advisory_is_retained_with_its_reason(self):
        self.add()
        result = review(self.report, self.policy, self.targets)
        self.assertEqual(("PASS", 0, 1),
                         (result["status"], result["blockingFindings"], result["providedAdvisories"]))
        finding, = result["findings"]
        self.assertEqual("CVE-2099-1234", finding["id"])
        self.assertTrue(finding["reason"])
        self.assertIn("not a whole-game security certification", summary(result))

    def test_unknown_package_or_version_still_blocks(self):
        for package, version in [("example:owned", "1.0"), ("io.netty:netty-handler", "4.1.98.Final")]:
            with self.subTest(package=package, version=version):
                self.report["Results"][1]["Vulnerabilities"].clear()
                self.add(package, version, "CRITICAL")
                result = review(self.report, self.policy, self.targets)
                self.assertEqual("FAIL", result["status"])
                self.assertEqual(1, result["blockingFindings"])

    def test_below_threshold_is_visible(self):
        self.add("example:owned", "1.0", "MEDIUM")
        result = review(self.report, self.policy, self.targets)
        self.assertEqual("PASS", result["status"])
        self.assertEqual("below-threshold", result["findings"][0]["disposition"])

    def test_incomplete_or_duplicate_inventory_refuses(self):
        for results in [[], self.report["Results"][:-1], self.report["Results"] * 2]:
            with self.subTest(count=len(results)), self.assertRaises(ValueError):
                review({"SchemaVersion": 2, "Results": results}, self.policy, self.targets)

    def test_foreign_inventory_cannot_inherit_host_policy(self):
        self.add()
        self.report["Results"][1]["Target"] = "foreign/gradle.lockfile"
        with self.assertRaisesRegex(ValueError, "Unknown or duplicate"):
            review(self.report, self.policy, self.targets)

    def test_incomplete_finding_or_unknown_severity_refuses(self):
        finding = self.add()
        for field, value in [("InstalledVersion", None), ("Severity", "OTHER"), ("PkgName", "")]:
            original = finding[field]
            finding[field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                review(self.report, self.policy, self.targets)
            finding[field] = original

    def test_policy_cannot_use_wildcards_or_ambiguous_rules(self):
        first = self.policy["providedRuntime"][0]
        variants = [[first, first], [{**first, "package": "io.netty:*"}],
                    [{**first, "versions": ["*"]}], [{**first, "reason": ""}]]
        for entries in variants:
            with self.subTest(entries=entries), self.assertRaises(ValueError):
                provided_packages({"schemaVersion": 1, "providedRuntime": entries})

    def test_malformed_report_overwrites_stale_success_with_error(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            report, output = root / "report.json", root / "summary.json"
            report.write_text("not JSON")
            output.write_text('{"status":"PASS"}')
            with redirect_stdout(io.StringIO()):
                code = main([str(report), "--output", str(output)])
            self.assertEqual(2, code)
            self.assertEqual("ERROR", json.loads(output.read_text())["status"])

    def test_owned_failure_produces_machine_and_job_summary(self):
        self.add("example:owned", "1.0")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            report, output, job = (root / name for name in ["report.json", "summary.json", "job.md"])
            report.write_text(json.dumps(self.report))
            with redirect_stdout(io.StringIO()):
                code = main([str(report), "--output", str(output), "--summary", str(job)])
            self.assertEqual(1, code)
            self.assertEqual("FAIL", json.loads(output.read_text())["status"])
            self.assertIn("Blocking findings: 1", job.read_text())


if __name__ == "__main__":
    unittest.main()
