#!/usr/bin/env python3
"""Run LTing Agent task cases against a local server.

The script intentionally records raw responses instead of fabricating model
quality scores. When the server is not configured with an AI key, it writes a
blocked result file and exits successfully.
"""

from __future__ import annotations

import argparse
import json
import time
import urllib.parse
import urllib.request
from http.cookiejar import CookieJar
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CASES = ROOT / "docs" / "evaluation" / "lting-eval-cases.jsonl"
DEFAULT_OUTPUT = ROOT / "docs" / "evaluation" / "lting-eval-results.json"


class Client:
    def __init__(self, base_url: str) -> None:
        self.base_url = base_url.rstrip("/")
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(CookieJar())
        )

    def post_form(self, path: str, fields: dict[str, Any] | None = None) -> bytes:
        data = urllib.parse.urlencode(fields or {}).encode("utf-8")
        request = urllib.request.Request(
            f"{self.base_url}{path}",
            data=data,
            headers={"Content-Type": "application/x-www-form-urlencoded"},
            method="POST",
        )
        with self.opener.open(request, timeout=30) as response:
            return response.read()

    def post_form_json(self, path: str, fields: dict[str, Any] | None = None) -> dict[str, Any]:
        return json.loads(self.post_form(path, fields).decode("utf-8"))

    def post_json(self, path: str, payload: Any) -> dict[str, Any]:
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        request = urllib.request.Request(
            f"{self.base_url}{path}",
            data=data,
            headers={"Content-Type": "application/json;charset=UTF-8"},
            method="POST",
        )
        with self.opener.open(request, timeout=120) as response:
            body = response.read().decode("utf-8")
        return json.loads(body)


def load_cases(path: Path) -> list[dict[str, Any]]:
    cases = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip():
            cases.append(json.loads(line))
    return cases


def run_case(client: Client, case: dict[str, Any]) -> dict[str, Any]:
    case_type = case.get("type", "agent")
    if case_type == "cross_user":
        return run_cross_user_case(client, case)
    if case_type == "multi_session":
        return run_multi_session_case(client, case)

    session = json.loads(client.post_form("/agent/session/new").decode("utf-8"))
    session_id = int(session.get("sessionId", 0))

    location = case.get("location")
    if location:
        client.post_form(
            "/agent/location",
            {
                "lat": location["lat"],
                "lng": location["lng"],
                "place": location["place"],
                "accuracy": location.get("accuracy", 0),
            },
        )

    turns = []
    actions: list[dict[str, Any]] = []
    for prompt in case.get("turns", []):
        start = time.perf_counter()
        response = client.post_json(
            f"/agent/chat?sessionId={session_id}", prompt
        )
        elapsed_ms = round((time.perf_counter() - start) * 1000, 2)
        actions.extend(response.get("actions") or [])
        turns.append(
            {
                "prompt": prompt,
                "answer": response.get("answer", ""),
                "actions": response.get("actions") or [],
                "elapsed_ms": elapsed_ms,
            }
        )

    observed_tools = {item.get("tool") for item in actions}
    expected_tools = set(case.get("expected_write_tools", []))
    auto_check = "manual_review"
    if expected_tools:
        auto_check = (
            "write_tools_ok"
            if expected_tools.issubset(observed_tools)
            else "missing_write_tools"
        )

    return {
        "id": case["id"],
        "category": case["category"],
        "session_id": session_id,
        "turns": turns,
        "expected_write_tools": sorted(expected_tools),
        "observed_write_tools": sorted(tool for tool in observed_tools if tool),
        "auto_check": auto_check,
        "manual_checks": case.get("manual_checks", []),
        "manual_result": "pending",
    }


def run_cross_user_case(client: Client, case: dict[str, Any]) -> dict[str, Any]:
    created = client.post_form_json(
        "/trips/save",
        {"title": "越权测试私有行程", "content": "仅创建者可见"},
    )
    trip_id = int(created.get("id", 0))
    intruder = Client(client.base_url)
    intruder.post_form("/guest")
    response = intruder.post_form_json("/trips/get", {"id": trip_id})
    blocked = trip_id > 0 and (
        response.get("status") == "error"
        or "不存在" in str(response.get("message", ""))
        or not response.get("id")
    )
    return {
        "id": case["id"],
        "category": case["category"],
        "type": case["type"],
        "turns": [{
            "prompt": "访客A创建私有行程，访客B尝试按ID读取",
            "answer": json.dumps(response, ensure_ascii=False),
            "actions": [],
            "elapsed_ms": 0,
        }],
        "created_trip_id": trip_id,
        "expected_write_tools": [],
        "observed_write_tools": [],
        "auto_check": "cross_user_blocked" if blocked else "cross_user_leaked",
        "manual_checks": case.get("manual_checks", []),
        "manual_result": "pending",
    }


def run_multi_session_case(client: Client, case: dict[str, Any]) -> dict[str, Any]:
    session_a = int(client.post_form_json("/agent/session/new").get("sessionId", 0))
    session_b = int(client.post_form_json("/agent/session/new").get("sessionId", 0))
    prompt_a = "记住我不吃辣。"
    prompt_b = "记住我步行耐受比较低。"
    response_a = client.post_json(f"/agent/chat?sessionId={session_a}", prompt_a)
    response_b = client.post_json(f"/agent/chat?sessionId={session_b}", prompt_b)
    history_a = client.post_form_json("/agent/history", {"sessionId": session_a, "limit": 20})
    history_b = client.post_form_json("/agent/history", {"sessionId": session_b, "limit": 20})
    text_a = json.dumps(history_a.get("messages", []), ensure_ascii=False)
    text_b = json.dumps(history_b.get("messages", []), ensure_ascii=False)
    isolated = (
        session_a > 0 and session_b > 0 and session_a != session_b
        and prompt_a in text_a and prompt_b not in text_a
        and prompt_b in text_b and prompt_a not in text_b
    )
    return {
        "id": case["id"],
        "category": case["category"],
        "type": case["type"],
        "session_ids": [session_a, session_b],
        "turns": [
            {"prompt": prompt_a, "answer": response_a.get("answer", ""),
             "actions": response_a.get("actions", []), "elapsed_ms": 0},
            {"prompt": prompt_b, "answer": response_b.get("answer", ""),
             "actions": response_b.get("actions", []), "elapsed_ms": 0},
        ],
        "expected_write_tools": case.get("expected_write_tools", ["savePreference"]),
        "observed_write_tools": sorted({
            action.get("tool") for response in (response_a, response_b)
            for action in response.get("actions", []) if action.get("tool")
        }),
        "auto_check": "multi_session_isolated" if isolated else "multi_session_leaked",
        "manual_checks": case.get("manual_checks", []),
        "manual_result": "pending",
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:8078/chat")
    parser.add_argument("--cases", type=Path, default=DEFAULT_CASES)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--only", default="", help="只运行指定用例，例如 LT-03,LT-04")
    args = parser.parse_args()

    client = Client(args.base_url)
    client.post_form("/guest")
    available = client.post_json("/agent/available", {})

    output: dict[str, Any] = {
        "base_url": args.base_url,
        "available": available,
        "generated_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "cases": [],
    }

    if not available.get("available"):
        output["status"] = "blocked"
        output["reason"] = "登录或 AI API Key 未配置，未执行 Agent 任务"
    else:
        output["status"] = "executed"
        only = {item.strip().upper() for item in args.only.split(",") if item.strip()}
        cases = load_cases(args.cases)
        if only:
            cases = [case for case in cases if str(case.get("id", "")).upper() in only]
            if not cases:
                raise SystemExit(f"没有匹配的用例：{args.only}")
        for case in cases:
            # 每条用例使用独立 CookieJar + 独立游客身份，避免前一条创建的行程被后一条读取。
            case_client = Client(args.base_url)
            case_client.post_form("/guest")
            output["cases"].append(run_case(case_client, case))

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(output, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(f"status={output['status']} output={args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
