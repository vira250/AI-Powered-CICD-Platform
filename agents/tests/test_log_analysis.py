import pytest

from log_analysis_agent.analyzer import analyze_logs
from log_analysis_agent.parser import parse_logs


@pytest.mark.parametrize(
    ("log_text", "expected_type"),
    [
        ("error TS2322: Type 'string' is not assignable to type 'number'.\n"
         "npm ERR! code ELIFECYCLE", "COMPILATION_ERROR"),
        ("npm ERR! code ERESOLVE\nCould not resolve dependency", "DEPENDENCY_ERROR"),
        ("Compilation error: cannot find symbol", "COMPILATION_ERROR"),
        ("FAIL src/app.test.js\nTests failed", "TEST_FAILURE"),
        ("eslint error: unexpected console statement", "LINT_FAILURE"),
        ("docker build failed while creating image", "DOCKER_ERROR"),
        ("Configuration error: environment variable DATABASE_URL missing", "CONFIGURATION_ERROR"),
        ("permission denied while accessing registry", "AUTHENTICATION_ERROR"),
        ("Deployment failed during rollout", "DEPLOYMENT_ERROR"),
        ("connection refused: service timed out", "NETWORK_ERROR"),
        ("fatal: an unexpected runner problem occurred", "UNKNOWN_ERROR"),
    ],
)
def test_common_failure_categories_use_deterministic_classification(log_text, expected_type):
    result = analyze_logs(log_text)

    assert result.status == "failed"
    assert result.error_type == expected_type
    assert result.summary
    assert result.root_cause
    assert result.evidence
    assert result.suggested_fixes
    assert 0 <= result.confidence <= 1


def test_empty_logs_return_explicit_unknown_result():
    result = analyze_logs("")

    assert result.status == "failed"
    assert result.error_type == "UNKNOWN_ERROR"
    assert result.evidence == []
    assert result.confidence == 0.0


def test_successful_logs_return_passed_without_calling_ai():
    result = analyze_logs("Build completed successfully.\n")

    assert result.status == "passed"
    assert result.error_count == 0
    assert result.confidence == 1.0


def test_malformed_ai_response_keeps_deterministic_analysis(monkeypatch):
    monkeypatch.setattr("log_analysis_agent.analyzer.call_llm", lambda *args, **kwargs: "not json")
    monkeypatch.setattr(
        "log_analysis_agent.analyzer.extract_json_from_response",
        lambda _: (_ for _ in ()).throw(ValueError("bad json")),
    )

    result = analyze_logs("error: dependency could not be resolved\n")

    assert result.status == "failed"
    assert result.error_type == "DEPENDENCY_ERROR"
    assert result.root_cause
    assert result.evidence
    assert result.suggested_fixes
    assert result.model["provider"] == "rule-based"


def test_unavailable_ai_response_keeps_deterministic_analysis(monkeypatch):
    monkeypatch.setattr(
        "log_analysis_agent.analyzer.call_llm",
        lambda *args, **kwargs: (_ for _ in ()).throw(RuntimeError("LLM unavailable")),
    )

    result = analyze_logs("permission denied while accessing deployment target\n")

    assert result.error_type == "AUTHENTICATION_ERROR"
    assert result.root_cause
    assert result.suggested_fixes
    assert result.model["provider"] == "rule-based"


def test_secret_containing_logs_are_redacted_before_analysis():
    parsed = parse_logs(
        "Authorization: Bearer abc123\n"
        "token=super-secret\n"
        "ghp_1234567890abcdef\n"
        "error: request failed\n"
    )

    assert "abc123" not in parsed["cleaned_log"]
    assert "super-secret" not in parsed["cleaned_log"]
    assert "ghp_1234567890abcdef" not in parsed["cleaned_log"]
    assert "[REDACTED]" in parsed["cleaned_log"]


def test_ts2322_remains_compilation_error_with_generic_llm_response(monkeypatch):
    monkeypatch.setattr(
        "log_analysis_agent.analyzer.call_llm",
        lambda *args, **kwargs: '{"error_type":"DEPENDENCY_ERROR","evidence":["npm ERR! code ELIFECYCLE"]}',
    )

    result = analyze_logs(
        "error TS2322: Type 'string' is not assignable to type 'number'.\n"
        "npm ERR! code ELIFECYCLE\n"
    )

    assert result.error_type == "COMPILATION_ERROR"
    assert any("TS2322" in item for item in result.evidence)