from __future__ import annotations

import json
import logging
import sys

from app.core.logging import JsonFormatter


def record(**extra) -> logging.LogRecord:
    rec = logging.LogRecord("app.x", logging.WARNING, __file__, 1, "hi %s", ("there",), None)
    rec.__dict__.update(extra)
    return rec


def test_json_line_uses_backend_field_names():
    entry = json.loads(JsonFormatter().format(record()))
    assert entry["level"] == "WARN"  # Spring 표기
    assert entry["logger_name"] == "app.x"
    assert entry["message"] == "hi there"
    assert "trace_id" not in entry


def test_trace_ids_are_carried_only_inside_a_trace():
    entry = json.loads(JsonFormatter().format(record(otelTraceID="ab" * 16, otelSpanID="cd" * 8)))
    assert entry["trace_id"] == "ab" * 16
    assert entry["span_id"] == "cd" * 8
    assert "trace_id" not in json.loads(JsonFormatter().format(record(otelTraceID="0")))


def test_exception_goes_to_stack_trace():
    try:
        raise ValueError("boom")
    except ValueError:
        rec = record()
        rec.exc_info = sys.exc_info()
    assert "ValueError: boom" in json.loads(JsonFormatter().format(rec))["stack_trace"]
