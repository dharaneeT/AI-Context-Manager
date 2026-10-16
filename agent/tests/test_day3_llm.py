import unittest

from app.core.errors import LlmUnavailable
from app.llm.base import BudgetExceeded, ChatMessage, UsageTracker, estimate_tokens
from app.llm.mock import MockLLM
from app.llm.ollama import OllamaClient
from app.llm.prompts import render
from app.llm.structured import StructuredOutputError, extract_json, generate_json
from tests.helpers import make_settings, serve


class ExtractJsonTests(unittest.TestCase):
    def test_plain(self):
        self.assertEqual(extract_json('{"a": 1}'), {"a": 1})

    def test_fenced_and_chatter(self):
        self.assertEqual(extract_json('Sure!\n```json\n{"a": [1, 2]}\n```\nHope that helps'), {"a": [1, 2]})

    def test_no_json(self):
        with self.assertRaises(ValueError):
            extract_json("I cannot do that")


class GenerateJsonTests(unittest.TestCase):
    def test_repairs_after_bad_reply(self):
        llm = MockLLM(["not json at all", '{"ok": true}'])
        usage = UsageTracker(limit_tokens=10_000)
        data = generate_json(llm, [ChatMessage("user", "go")], lambda d: [] if d.get("ok") else ["missing ok"], usage=usage)
        self.assertEqual(data, {"ok": True})
        self.assertEqual(usage.calls, 2)
        self.assertIn("not acceptable", llm.calls[1][-1].content)  # the model was shown its mistake

    def test_gives_up_after_max_attempts(self):
        llm = MockLLM(["x", "y", "z"])
        with self.assertRaises(StructuredOutputError):
            generate_json(llm, [ChatMessage("user", "go")], max_attempts=3)

    def test_budget_stops_the_run(self):
        llm = MockLLM(['{"a": "' + "w" * 400 + '"}'])
        with self.assertRaises(BudgetExceeded):
            generate_json(llm, [ChatMessage("user", "go")], usage=UsageTracker(limit_tokens=50))


class PromptTests(unittest.TestCase):
    def test_latest_version_is_used(self):
        p = render("ping")
        self.assertEqual((p.name, p.version), ("ping", 1))
        self.assertIn('"ok": true', p.text)

    def test_estimate(self):
        self.assertEqual(estimate_tokens(""), 0)
        self.assertEqual(estimate_tokens("abcdefgh"), 2)


class OllamaOverRealHttpTests(unittest.TestCase):
    def test_chat_and_status(self):
        seen = {}

        def handler(method, path, body):
            if path == "/api/tags":
                return 200, {"models": [{"name": "qwen2.5-coder:1.5b"}]}
            seen["body"] = body
            return 200, {"message": {"content": '{"ok": true}'}, "prompt_eval_count": 11, "eval_count": 5}

        server, url = serve(handler)
        self.addCleanup(server.shutdown)
        client = OllamaClient(make_settings(ollama_url=url, ollama_model="qwen2.5-coder:1.5b"))
        result = client.chat([ChatMessage("user", "hi")], json_mode=True)
        self.assertEqual((result.text, result.input_tokens, result.output_tokens), ('{"ok": true}', 11, 5))
        self.assertEqual(seen["body"]["format"], "json")
        self.assertEqual(seen["body"]["options"]["num_ctx"], 4096)  # small context protects 8 GB of RAM
        self.assertTrue(client.status()["model_available"])

    def test_missing_model_message(self):
        server, url = serve(lambda m, p, b: (404, {"error": "model not found"}))
        self.addCleanup(server.shutdown)
        with self.assertRaises(LlmUnavailable) as ctx:
            OllamaClient(make_settings(ollama_url=url)).chat([ChatMessage("user", "hi")])
        self.assertIn("ollama pull", ctx.exception.message)

    def test_ollama_not_running(self):
        with self.assertRaises(LlmUnavailable):
            OllamaClient(make_settings(ollama_url="http://127.0.0.1:1")).chat([ChatMessage("user", "hi")])


if __name__ == "__main__":
    unittest.main()