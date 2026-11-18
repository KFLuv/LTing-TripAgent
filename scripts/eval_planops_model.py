#!/usr/bin/env python3
"""Regression for every PlanOps operation through a real tool-calling model.

The script seeds an isolated trip on a local LTing server, asks the real Agent
to produce one PlanOps preview per operation, and records the raw answers.
It never applies a preview and never writes model output into the trip.
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
DEFAULT_OUTPUT = ROOT / "docs" / "evaluation" / "lting-planops-model-20261117.json"


class Client:
    def __init__(self, base_url: str, timeout: int = 180) -> None:
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(CookieJar())
        )

    def post_form(self, path: str, fields: dict[str, Any] | None = None) -> dict[str, Any]:
        return json.loads(self.post_form_raw(path, fields).decode("utf-8"))

    def post_form_raw(self, path: str, fields: dict[str, Any] | None = None) -> bytes:
        data = urllib.parse.urlencode(fields or {}).encode("utf-8")
        request = urllib.request.Request(
            f"{self.base_url}{path}",
            data=data,
            headers={"Content-Type": "application/x-www-form-urlencoded"},
            method="POST",
        )
        with self.opener.open(request, timeout=self.timeout) as response:
            return response.read()

    def post_json(self, path: str, payload: Any) -> dict[str, Any]:
        request = urllib.request.Request(
            f"{self.base_url}{path}",
            data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
            headers={"Content-Type": "application/json;charset=UTF-8"},
            method="POST",
        )
        with self.opener.open(request, timeout=self.timeout) as response:
            return json.loads(response.read().decode("utf-8"))


def node(name: str, address: str, lat: float, lng: float, category: str) -> dict[str, Any]:
    return {
        "name": name,
        "address": address,
        "lat": lat,
        "lng": lng,
        "category": category,
        "durationMin": 60,
        "cost": "80",
        "openTime": "09:00-22:00",
    }


def key(item: dict[str, Any]) -> str:
    return "|".join(str(item.get(name, "")) for name in ("name", "address", "lat", "lng"))


def seed_trip(client: Client) -> dict[str, Any]:
    n1 = node("回归公园", "回归路1号", 24.10, 118.10, "景点")
    n2 = node("回归餐厅", "回归路2号", 24.11, 118.11, "美食")
    n3 = node("回归博物馆", "回归路3号", 24.12, 118.12, "景点")
    candidate = node("候选咖啡", "回归路4号", 24.13, 118.13, "咖啡")
    day1 = {
        "date": "2027-01-10",
        "morning": [n1, n2],
        "afternoon": [],
        "evening": [],
    }
    day2 = {
        "date": "2027-01-11",
        "morning": [n3],
        "afternoon": [],
        "evening": [],
    }
    plan = {
        "chosen": [n1, n2, n3, candidate],
        "itinerary": [day1, day2],
        "locked": {},
        "closures": {},
        "quickPrefs": ["少走路"],
        "constraints": {"people": "2 人", "budget": "3000", "pace": "balanced"},
        "transport": {
            "outbound": {"mode": "高铁", "departAt": "08:00", "arriveAt": "12:00",
                         "station": "厦门北站", "toStayMinutes": 30, "cost": 300},
            "inbound": {"mode": "高铁", "departAt": "18:00", "arriveAt": "22:00",
                        "station": "厦门北站", "fromStayMinutes": 30, "cost": 300},
        },
        "budgetPlan": {
            "total": 3000,
            "categories": {"transport": 600, "lodging": 900, "food": 700,
                           "ticket": 400, "local": 400},
        },
        "stay": {"name": "思明区", "lat": 24.09, "lng": 118.09},
        "hotelName": "回归酒店",
        "meta": {
            "destination": "厦门",
            "destinationLabel": "厦门",
            "origin": "广州",
            "start": "2027-01-10",
            "end": "2027-01-11",
            "days": 2,
        },
    }
    saved = client.post_form(
        "/plan/draft/save",
        {
            "tripId": 0,
            "title": "PlanOps 模型全操作回归",
            "destination": "厦门",
            "destinationLabel": "厦门",
            "origin": "广州",
            "start": "2027-01-10",
            "end": "2027-01-11",
            "days": 2,
            "budget": "3000",
            "people": "2 人",
            "pace": "balanced",
            "planStep": 3,
            "planData": json.dumps(plan, ensure_ascii=False),
        },
    )
    if saved.get("status") != "success":
        raise RuntimeError(f"seed trip failed: {saved}")
    trip_id = int(saved["id"])

    context = client.post_form(
        "/plan/context/get",
        {"tripId": trip_id, "page": "model-eval", "selectedDay": 0,
         "selectedSlot": "morning", "selectedNodeKey": key(n1)},
    )
    context_data = context.get("context") or {}
    operation = {
        "type": "LOCK_NODE",
        "expectedRevision": context_data.get("planRevision"),
        "nodeKey": key(n1),
        "locked": True,
        "reason": "建立回归版本基线",
    }
    preview = client.post_form(
        "/plan/ops/preview",
        {"contextId": context_data.get("contextId"), "operation": json.dumps(operation),
         "source": "system"},
    )
    if preview.get("status") != "success":
        raise RuntimeError(f"seed preview failed: {preview}")
    applied = client.post_form(
        "/plan/ops/apply",
        {"operationId": preview.get("operationId"),
         "approvalToken": preview.get("approvalToken"),
         "expectedRevision": preview.get("expectedRevision")},
    )
    if applied.get("status") != "success":
        raise RuntimeError(f"seed apply failed: {applied}")
    history = client.post_form("/plan/ops/history", {"tripId": trip_id})
    versions = [
        item for item in history.get("versions", [])
        if item.get("hasPlanData") and int(item.get("id", 0)) > 0
    ]
    target_version = int(versions[0]["id"]) if versions else int(applied.get("beforeVersionId", 0))

    return {
        "tripId": trip_id,
        "targetVersionId": target_version,
        "nodes": {"park": n1, "restaurant": n2, "museum": n3, "candidate": candidate},
        "keys": {
            "park": key(n1),
            "restaurant": key(n2),
            "museum": key(n3),
            "candidate": key(candidate),
        },
    }


def cases(seed: dict[str, Any]) -> list[dict[str, Any]]:
    keys = seed["keys"]
    replacement = node("替换茶馆", "回归路5号", 24.14, 118.14, "美食")
    added = node("新增观景台", "回归路6号", 24.15, 118.15, "景点")
    rebuilt_1 = {"date": "2027-01-10", "morning": [seed["nodes"]["museum"]],
                 "afternoon": [], "evening": []}
    rebuilt_2 = {"date": "2027-01-11", "morning": [seed["nodes"]["park"]],
                 "afternoon": [], "evening": []}
    routed_1 = {"date": "2027-01-10", "morning": [
        {**seed["nodes"]["park"], "routeSource": "start", "routeMinutes": 0},
        {**seed["nodes"]["restaurant"], "routeSource": "estimated", "routeMinutes": 12},
    ], "afternoon": [], "evening": []}
    routed_2 = {"date": "2027-01-11", "morning": [
        {**seed["nodes"]["museum"], "routeSource": "start", "routeMinutes": 0},
    ], "afternoon": [], "evening": []}
    return [
        {"id": "MOVE_NODE", "day": 0, "slot": "morning", "node": "park",
         "instruction": "把前面的回归公园移动到第2天下午。",
         "exact": {"targetDay": 1, "targetSlot": "afternoon"}},
        {"id": "REMOVE_NODE", "day": 0, "slot": "morning", "node": "restaurant",
         "instruction": "把回归餐厅从正式行程移除。", "exact": {}},
        {"id": "REPLACE_NODE", "day": 0, "slot": "morning", "node": "restaurant",
         "instruction": "把回归餐厅替换为替换茶馆。", "exact": {"replacement": replacement}},
        {"id": "REORDER_NODE", "day": 0, "slot": "morning", "node": "park",
         "instruction": "把回归公园在上午时段下移一位。", "exact": {"direction": 1}},
        {"id": "LOCK_NODE", "day": 0, "slot": "morning", "node": "restaurant",
         "instruction": "锁定回归餐厅。", "exact": {"locked": True}},
        {"id": "TOGGLE_NODE_CLOSURE", "day": 0, "slot": "morning", "node": "restaurant",
         "instruction": "把回归餐厅标记为临时闭馆。", "exact": {"closed": True}},
        {"id": "ADD_NODE", "day": 1, "slot": "morning", "node": "museum",
         "instruction": "把新增观景台加入第2天上午。",
         "exact": {"node": added, "targetDay": 1, "targetSlot": "morning"}},
        {"id": "ADD_CANDIDATE", "day": 0, "slot": "morning", "node": "park",
         "instruction": "把新增观景台加入候选池。", "exact": {"candidate": added}},
        {"id": "REMOVE_CANDIDATE", "day": 0, "slot": "morning", "node": "candidate",
         "instruction": "把候选咖啡从候选池移除。", "exact": {}},
        {"id": "UPDATE_CONSTRAINT", "day": 0, "slot": "morning", "node": "park",
         "instruction": "把同行人数改成3人，节奏改成relaxed，本次偏好设为室内优先。",
         "exact": {"people": "3 人", "pace": "relaxed", "quickPrefs": ["室内优先"]}},
        {"id": "UPDATE_BUDGET", "day": 0, "slot": "morning", "node": "park",
         "instruction": "把总预算改成5000元。", "exact": {"budget": "5000"}},
        {"id": "UPDATE_STAY", "day": 0, "slot": "morning", "node": "park",
         "instruction": "住宿区域改成湖里区，酒店名改成湖里测试酒店。",
         "exact": {"stay": {"name": "湖里区", "lat": 24.50, "lng": 118.10},
                   "hotelName": "湖里测试酒店"}},
        {"id": "UPDATE_PLAN_META", "day": 0, "slot": "morning", "node": "park",
         "instruction": "把出发地改成深圳，标题改成厦门回归测试，保留现有行程。",
         "exact": {"origin": "深圳", "title": "厦门回归测试", "resetPlan": False}},
        {"id": "UPDATE_TRANSPORT", "day": 0, "slot": "morning", "node": "park",
         "instruction": "更新城际交通：去程高铁10:00到14:00，返程高铁18:00到22:00。",
         "exact": {"transport": {
             "outbound": {"mode": "高铁", "departAt": "10:00", "arriveAt": "14:00",
                          "station": "厦门北站", "toStayMinutes": 30, "cost": 320},
             "inbound": {"mode": "高铁", "departAt": "18:00", "arriveAt": "22:00",
                         "station": "厦门北站", "fromStayMinutes": 30, "cost": 320},
         }}},
        {"id": "UPDATE_BUDGET_PLAN", "day": 0, "slot": "morning", "node": "park",
         "instruction": "把预算分项改为总预算4000，交通800，住宿1000，餐饮900，门票600，市内700。",
         "exact": {"budgetPlan": {
             "total": 4000,
             "categories": {"transport": 800, "lodging": 1000, "food": 900,
                            "ticket": 600, "local": 700},
         }}},
        {"id": "REBUILD_ITINERARY", "day": 0, "slot": "morning", "node": "park",
         "instruction": "按给定数组完整重建行程骨架。",
         "exact": {"itinerary": [rebuilt_1, rebuilt_2], "variant": "model-regression"}},
        {"id": "UPDATE_ROUTE_TIMES", "day": 0, "slot": "morning", "node": "park",
         "instruction": "把给定 itinerary 中带 routeSource 的逐段耗时写回正式行程。",
         "exact": {"itinerary": [routed_1, routed_2], "travelMode": "walking"}},
        {"id": "ROLLBACK_PLAN_VERSION", "day": 0, "slot": "morning", "node": "park",
         "instruction": "预览回退到指定的 plan_data 版本。",
         "exact": {"targetVersionId": seed["targetVersionId"]}},
    ]


def prompt_for(seed: dict[str, Any], case: dict[str, Any]) -> str:
    node_key = seed["keys"][case["node"]]
    exact = json.dumps(case["exact"], ensure_ascii=False)
    return (
        "这是 PlanOps 工具回归测试。只允许使用 planOps 工具，不要调用任何其它修改工具，"
        "不要 Apply。"
        f"先调用 planOps(action=context,tripId={seed['tripId']},selectedDay={case['day']},"
        f"selectedSlot={case['slot']},selectedNodeKey={node_key}) 获取 contextId 和版本，"
        f"再调用 planOps(action=preview,contextId=...,operationType={case['id']},"
        f"expectedRevision=...,nodeKey={node_key})。"
        f"用户操作：{case['instruction']}"
        f"必须原样传入这些结构化参数：{exact}。"
        "完成后只用一句话说明已生成预览。"
    )


def run_case(client: Client, seed: dict[str, Any], case: dict[str, Any]) -> dict[str, Any]:
    session = client.post_form("/agent/session/new")
    session_id = int(session.get("sessionId", 0))
    start = time.perf_counter()
    response = client.post_json(f"/agent/chat?sessionId={session_id}", prompt_for(seed, case))
    elapsed = round((time.perf_counter() - start) * 1000, 2)
    pending = response.get("pendingOperations") or []
    types = [item.get("type") for item in pending if item.get("type")]
    matched = next((item for item in pending if item.get("type") == case["id"]), None)
    differences = {}
    if matched:
        for field, expected in case["exact"].items():
            actual = matched.get("diff", {}).get(field)
            if field in {"node", "candidate", "replacement", "stay", "transport",
                         "budgetPlan", "itinerary"}:
                continue
            if field == "direction":
                expected = "down" if int(expected) > 0 else "up"
            if actual is not None and actual != expected:
                differences[field] = {"expected": expected, "actual": actual}
    passed = matched is not None and not differences
    return {
        "id": case["id"],
        "expectedType": case["id"],
        "observedTypes": types,
        "passed": passed,
        "elapsedMs": elapsed,
        "prompt": prompt_for(seed, case),
        "answer": response.get("answer", ""),
        "pendingOperation": matched,
        "diffMismatches": differences,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:8078/chat")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--only", default="")
    parser.add_argument("--timeout", type=int, default=180)
    args = parser.parse_args()

    client = Client(args.base_url, args.timeout)
    client.post_form_raw("/guest")
    available = client.post_form("/agent/available")
    output: dict[str, Any] = {
        "baseUrl": args.base_url,
        "generatedAt": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "available": available,
        "status": "blocked",
        "cases": [],
    }
    if not available.get("available"):
        output["reason"] = "Agent 未登录或真实模型未配置"
    else:
        seed = seed_trip(client)
        selected = cases(seed)
        only = {value.strip().upper() for value in args.only.split(",") if value.strip()}
        if only:
            selected = [case for case in selected if case["id"] in only]
            if not selected:
                raise SystemExit(f"没有匹配用例：{args.only}")
        output.update({
            "status": "executed",
            "tripId": seed["tripId"],
            "targetVersionId": seed["targetVersionId"],
        })
        for index, case in enumerate(selected, 1):
            print(f"[{index}/{len(selected)}] {case['id']}", flush=True)
            result = run_case(client, seed, case)
            output["cases"].append(result)
            print(f"  {'PASS' if result['passed'] else 'FAIL'} "
                  f"{result['observedTypes']} {result['elapsedMs']}ms", flush=True)
        passed = sum(1 for item in output["cases"] if item["passed"])
        output["passed"] = passed
        output["total"] = len(output["cases"])
        output["status"] = "passed" if passed == len(output["cases"]) else "failed"

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(output, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(f"status={output['status']} passed={output.get('passed', 0)}/"
          f"{output.get('total', 0)} output={args.output}")
    return 0 if output["status"] in {"passed", "blocked"} else 1


if __name__ == "__main__":
    raise SystemExit(main())
