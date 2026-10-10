"""Regression checks for disposable SonarQube authentication."""
import re
import unittest
from unittest.mock import patch
import sonar_ci


class BootstrapTest(unittest.TestCase):
    def test_password_meets_policy_even_when_random_part_has_no_special_characters(self):
        calls = []
        def response(path, fields=None, credentials=None):
            calls.append((path, fields, credentials))
            return {"status": "UP", "token": "test-token"}
        with patch.object(sonar_ci, "request", side_effect=response), \
             patch.object(sonar_ci.secrets, "token_urlsafe", return_value="a" * 43), \
             patch.object(sonar_ci, "export_env") as export:
            sonar_ci.bootstrap()
        password = calls[1][1]["password"]
        self.assertGreaterEqual(len(password), 12)
        for pattern in (r"[A-Z]", r"[a-z]", r"[0-9]", r"[^A-Za-z0-9]"):
            self.assertRegex(password, pattern)
        self.assertEqual(calls[2][2], "admin:" + password)
        export.assert_called_once_with("SONAR_TOKEN", "test-token")


if __name__ == "__main__":
    unittest.main()
