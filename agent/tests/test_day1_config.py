import os
import unittest
from unittest import mock

from app.core import config
from app.core.errors import AgentError, error_body


class ConfigTests(unittest.TestCase):
    def test_path_map_parsing(self):
        pairs = config._parse_path_map("/workspace=C:/Users/Asus/Desktop;/data=D:\\data\\")
        self.assertEqual(pairs, (("/workspace", "C:/Users/Asus/Desktop"), ("/data", "D:\\data")))

    def test_defaults_and_env_override(self):
        with mock.patch.dict(os.environ, {"AGENT_PORT": "9009", "OLLAMA_MODEL": "x:1b", "LLM_NUM_CTX": "bad"}, clear=False):
            s = config.load_settings()
        self.assertEqual(s.port, 9009)
        self.assertEqual(s.ollama_model, "x:1b")
        self.assertEqual(s.llm_num_ctx, 4096)  # invalid number falls back to the default

    def test_error_body_matches_spring_shape(self):
        body = error_body(404, "nope", "/v1/x")
        self.assertEqual(set(body), {"status", "error", "message", "path", "timestamp", "fieldErrors"})
        self.assertEqual(body["error"], "Not Found")
        self.assertIsInstance(AgentError("m", 409), Exception)


if __name__ == "__main__":
    unittest.main()