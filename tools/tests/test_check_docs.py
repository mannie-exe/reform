"""Maintained documentation stays separate from disposable evidence snapshots."""

import contextlib
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location(
    "check_docs", Path(__file__).resolve().parents[1] / "check_docs.py"
)
check_docs = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(check_docs)


class DocumentationScopeTest(unittest.TestCase):
    def test_cache_snapshot_is_not_maintained_documentation(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            (root / "docs").mkdir()
            (root / ".cache" / "source").mkdir(parents=True)
            (root / "README.md").write_text("[Guide](docs/guide.md)", encoding="utf-8")
            (root / "docs" / "guide.md").write_text("# Guide\n", encoding="utf-8")
            (root / ".cache" / "source" / "README.md").write_text(
                "[Omitted snapshot dependency](missing.md)", encoding="utf-8"
            )
            with patch.object(check_docs, "ROOT", root), contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(check_docs.main(), 0)
                self.assertEqual(
                    {path.relative_to(root) for path in check_docs.documents()},
                    {Path("README.md"), Path("docs/guide.md")},
                )
                (root / "docs" / "guide.md").write_text(
                    "[Broken maintained link](missing.md)", encoding="utf-8"
                )
                errors = io.StringIO()
                with contextlib.redirect_stderr(errors):
                    self.assertEqual(check_docs.main(), 1)
                self.assertIn("docs/guide.md: missing destination: missing.md", errors.getvalue())


if __name__ == "__main__":
    unittest.main()
