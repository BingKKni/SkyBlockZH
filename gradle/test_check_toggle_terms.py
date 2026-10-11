"""Regression checks for term-to-record references; no Minecraft or corpus mutations."""
import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import check_toggle_terms as checker


class TermRecordChecks(unittest.TestCase):
    def run_check(self, target=None, wrapper=None, expected_zh="标签"):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            files = {
                "_shared/Terms.json": {"terms": [{"en": "Label", "zh": expected_zh,
                    "note": "Hub/GUI_Item/Menu.json#row"}]},
                "Hub/GUI_Item/Menu.json": {"lines": [wrapper or {"id": "row",
                    "ref": "_shared/Labels.json#label"}]},
            }
            if target is not None:
                files["_shared/Labels.json"] = target
            for relative, data in files.items():
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
            output = io.StringIO()
            with patch.object(checker, "CORPUS", str(root)), patch.object(checker, "TERMS", str(root / "_shared/Terms.json")), contextlib.redirect_stdout(output):
                status = checker.main()
            return status, output.getvalue()

    def test_shared_record_is_resolved(self):
        self.assertEqual(self.run_check({"lines": [{"id": "label", "text": "Label", "zh": "标签"}]} )[0], 0)

    def test_standalone_target_matches_loader(self):
        self.assertEqual(self.run_check({"name": {"id": "label", "text": "Label", "zh": "标签"}})[0], 0)

    def test_actual_translation_drift_still_fails(self):
        status, output = self.run_check({"lines": [{"id": "label", "text": "Label", "zh": "错误"}]})
        self.assertEqual(status, 1)
        self.assertIn("译名不一致", output)

    def test_missing_reference_still_fails(self):
        status, output = self.run_check()
        self.assertEqual(status, 1)
        self.assertIn("共享引用", output)

    def test_reference_chain_is_not_supported(self):
        status, output = self.run_check({"lines": [{"id": "label", "ref": "_shared/Labels.json#label"}]})
        self.assertEqual(status, 1)
        self.assertIn("引用链", output)

    def test_template_reference_checks_existence_only(self):
        status, output = self.run_check({"lines": [{"id": "label", "text": "Label %s", "zh": "标签 %s",
            "placeholders": [{"token": "%s", "type": "number"}]}]})
        self.assertEqual(status, 0)
        self.assertIn("1 条词条是填进模板", output)

    def test_direct_record_still_works(self):
        self.assertEqual(self.run_check(wrapper={"id": "row", "text": "Label", "zh": "标签"})[0], 0)


if __name__ == "__main__":
    unittest.main()
