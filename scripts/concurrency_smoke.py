#!/usr/bin/env python3
"""Local HTTP smoke/load test for rider-cache reads and rider capacity races."""

from __future__ import annotations

import argparse
import concurrent.futures
from datetime import datetime
import json
import math
import os
from pathlib import Path
import subprocess
import sys
import threading
import time
from collections import Counter
from http.client import HTTPConnection, HTTPSConnection
from http.cookies import SimpleCookie
from urllib.parse import urlsplit


thread_state = threading.local()
base_parts = None
session_cookies: dict[str, str] = {}
csrf_header: str | None = None
csrf_token: str | None = None
cookie_lock = threading.Lock()


def request(method: str, path: str, payload: dict | None = None) -> dict:
    parsed = base_parts
    conn = getattr(thread_state, "conn", None)
    if conn is None:
        connection_type = HTTPSConnection if parsed.scheme == "https" else HTTPConnection
        conn = connection_type(parsed.hostname, parsed.port, timeout=15)
        thread_state.conn = conn

    body = json.dumps(payload).encode("utf-8") if payload is not None else None
    headers = {"Accept": "application/json"}
    with cookie_lock:
        if session_cookies:
            headers["Cookie"] = "; ".join(f"{name}={value}" for name, value in session_cookies.items())
    if method.upper() not in ("GET", "HEAD", "OPTIONS") and csrf_header and csrf_token:
        headers[csrf_header] = csrf_token
    if body is not None:
        headers["Content-Type"] = "application/json"

    started = time.perf_counter()
    try:
        conn.request(method, path, body=body, headers=headers)
        response = conn.getresponse()
        with cookie_lock:
            for name, value in response.getheaders():
                if name.lower() == "set-cookie":
                    parsed_cookies = SimpleCookie()
                    parsed_cookies.load(value)
                    for cookie_name, morsel in parsed_cookies.items():
                        if morsel["max-age"] == "0":
                            session_cookies.pop(cookie_name, None)
                        else:
                            session_cookies[cookie_name] = morsel.value
        raw = response.read()
        elapsed_ms = (time.perf_counter() - started) * 1000
        try:
            parsed_body = json.loads(raw) if raw else None
        except json.JSONDecodeError:
            parsed_body = raw.decode("utf-8", errors="replace")
        return {"status": response.status, "elapsed_ms": elapsed_ms, "body": parsed_body}
    except Exception as exc:  # Report network errors alongside HTTP statuses.
        try:
            conn.close()
        finally:
            thread_state.conn = None
        return {"status": 0, "elapsed_ms": (time.perf_counter() - started) * 1000,
                "error": f"{type(exc).__name__}: {exc}"}


def authenticate_from_environment() -> None:
    """Log in once before workers start; never include credentials in reports."""
    global csrf_header, csrf_token
    probe = request("GET", "/api/v1/auth/me")
    if probe["status"] == 404:
        return  # Compatibility with the pre-authentication application.
    username = os.environ.get("DISPATCH_TEST_USERNAME")
    password = os.environ.get("DISPATCH_TEST_PASSWORD")
    if not username or not password:
        raise RuntimeError("Set DISPATCH_TEST_USERNAME and DISPATCH_TEST_PASSWORD for an administrator test account")

    def refresh_csrf() -> None:
        global csrf_header, csrf_token
        result = request("GET", "/api/v1/auth/csrf")
        token = result.get("body")
        if result["status"] != 200 or not isinstance(token, dict) or not token.get("token"):
            raise RuntimeError("Cannot obtain a CSRF token from the application")
        csrf_header = token["headerName"]
        csrf_token = token["token"]

    refresh_csrf()
    login = request("POST", "/api/v1/auth/login", {"username": username, "password": password})
    if login["status"] != 200 or not isinstance(login.get("body"), dict):
        raise RuntimeError(f"Test account login failed: HTTP {login['status']}")
    if login["body"].get("role") != "ADMIN":
        raise RuntimeError("The smoke tests require an ADMIN account for cleanup and rider management")
    refresh_csrf()  # Successful authentication replaces the anonymous CSRF token.


def percentile(values: list[float], p: float) -> float:
    ordered = sorted(values)
    return ordered[max(0, math.ceil(p * len(ordered)) - 1)] if ordered else 0.0


def summarize(results: list[dict], wall_seconds: float) -> dict:
    statuses = Counter(str(result["status"]) for result in results)
    latencies = [result["elapsed_ms"] for result in results]
    successful = sum(200 <= result["status"] < 300 for result in results)
    return {
        "requests": len(results),
        "successful_2xx": successful,
        "status_counts": dict(sorted(statuses.items())),
        "wall_seconds": round(wall_seconds, 3),
        "throughput_rps": round(len(results) / wall_seconds, 1) if wall_seconds else 0,
        "latency_ms": {
            "p50": round(percentile(latencies, 0.50), 2),
            "p95": round(percentile(latencies, 0.95), 2),
            "p99": round(percentile(latencies, 0.99), 2),
            "max": round(max(latencies, default=0), 2),
        },
        "errors": [r["error"] for r in results if r.get("error")],
    }


def run_read_benchmark(path: str, requests_count: int, workers: int) -> dict:
    for _ in range(min(20, requests_count)):
        warmup = request("GET", path)
        if warmup["status"] != 200:
            raise RuntimeError(f"GET {path} warmup failed: {warmup}")

    started = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as executor:
        results = list(executor.map(lambda _: request("GET", path), range(requests_count)))
    wall_seconds = time.perf_counter() - started
    return summarize(results, wall_seconds)


def make_order_payload(index: int, longitude: float = 0, latitude: float = 0) -> dict:
    # (0, 0) is deliberately far from the project's seeded Beijing riders, so
    # the asynchronous GEO dispatcher cannot race this manual-capacity test.
    return {
        "merchantName": "并发验证临时商家",
        "merchantPhone": "10000000000",
        "merchantAddress": f"load-test-{index}",
        "merchantLongitude": longitude,
        "merchantLatitude": latitude,
        "receiverName": "并发验证收件人",
        "receiverPhone": "10000000001",
        "receiverAddress": "临时测试地址",
        "receiverLongitude": longitude,
        "receiverLatitude": latitude,
        "deliveryFee": 0,
    }


def cleanup_orders(order_ids: list[int], mysql_container: str) -> dict:
    deleted: list[int] = []
    already_absent: list[int] = []
    errors: list[str] = []
    for order_id in order_ids:
        current = request("GET", f"/api/v1/orders/{order_id}")
        if current["status"] == 200:
            status = current["body"].get("status")
            if status == 0:
                cancel = request("PATCH", f"/api/v1/orders/{order_id}/cancel")
                if cancel["status"] not in (200, 400):
                    errors.append(f"cancel order {order_id}: HTTP {cancel['status']}")
            deleted_result = request("DELETE", f"/api/v1/orders/{order_id}")
            if deleted_result["status"] in (200, 204):
                deleted.append(order_id)
            else:
                errors.append(f"delete order {order_id}: HTTP {deleted_result['status']}")
        elif current["status"] == 400 and isinstance(current.get("body"), dict) \
                and current["body"].get("message") == "订单不存在":
            already_absent.append(order_id)
        else:
            errors.append(f"read order {order_id}: HTTP {current['status']}")

    # The public delete endpoint removes pending Outbox rows. If a background
    # publisher already sent one, remove only these now-deleted fixture rows so
    # repeated local runs do not leave test messages in the audit table.
    if order_ids and len(deleted) + len(already_absent) == len(order_ids):
        id_list = ",".join(str(order_id) for order_id in order_ids)
        sql = f"DELETE FROM dispatch_outbox WHERE order_id IN ({id_list});\n"
        command = [
            "docker", "exec", "-i", mysql_container, "sh", "-c",
            'exec mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE"',
        ]
        try:
            result = subprocess.run(command, input=sql, text=True, capture_output=True,
                                    timeout=15, check=False)
            if result.returncode != 0:
                errors.append("Outbox fixture cleanup failed; see docker/mysql availability")
        except (OSError, subprocess.TimeoutExpired) as exc:
            errors.append(f"Outbox fixture cleanup failed: {type(exc).__name__}")
    return {
        "orders_deleted": len(deleted),
        "orders_already_absent": len(already_absent),
        "remaining_order_ids": sorted(set(order_ids) - set(deleted) - set(already_absent)),
        "cleanup_errors": errors,
    }


def run_capacity_race(rider_id: int, order_count: int, create_workers: int,
                      request_workers: int, capacity: int, mysql_container: str) -> dict:
    before = request("GET", f"/api/v1/riders/{rider_id}")
    if before["status"] != 200:
        raise RuntimeError(f"Cannot load rider {rider_id}: {before}")
    rider = before["body"]
    initial_count = rider.get("currentOrderCount") or 0
    if rider.get("status") != 1 or initial_count != 0:
        raise RuntimeError(
            f"Rider {rider_id} must be available (status=1) and idle (count=0); got "
            f"status={rider.get('status')}, count={initial_count}"
        )
    if order_count <= capacity:
        raise ValueError("order_count must exceed capacity to exercise the rejection path")

    order_ids: list[int] = []
    creation_results: list[dict] = []
    assignments: list[dict] = []
    try:
        with concurrent.futures.ThreadPoolExecutor(max_workers=create_workers) as executor:
            creation_results = list(executor.map(
                lambda index: request("POST", "/api/v1/orders", make_order_payload(index)),
                range(order_count),
            ))
        for result in creation_results:
            if result["status"] == 200 and isinstance(result.get("body"), dict) \
                    and result["body"].get("id") is not None:
                order_ids.append(int(result["body"]["id"]))
        if len(order_ids) != order_count:
            raise RuntimeError(
                f"Created {len(order_ids)}/{order_count} order fixtures; "
                "created fixtures will be cleaned up"
            )

        barrier = threading.Barrier(min(request_workers, order_count))

        def assign(order_id: int) -> dict:
            barrier.wait(timeout=15)
            result = request("PATCH", f"/api/v1/orders/{order_id}/assign/{rider_id}")
            result["order_id"] = order_id
            return result

        started = time.perf_counter()
        with concurrent.futures.ThreadPoolExecutor(max_workers=request_workers) as executor:
            assignments = list(executor.map(assign, order_ids))
        assignment_seconds = time.perf_counter() - started

        assigned = sum(result["status"] == 200 for result in assignments)
        rejected = sum(result["status"] == 400 for result in assignments)
        unexpected = [result for result in assignments if result["status"] not in (200, 400)]
        after = request("GET", f"/api/v1/riders/{rider_id}")
        final_count = after["body"].get("currentOrderCount") if after["status"] == 200 else None
        return {
            "rider_id": rider_id,
            "concurrent_assign_requests": order_count,
            "assignment_workers": request_workers,
            "assignment_wall_seconds": round(assignment_seconds, 3),
            "assignment_attempts_per_second": round(order_count / assignment_seconds, 1)
                if assignment_seconds else 0,
            "assignment_http_metrics": summarize(assignments, assignment_seconds),
            "assigned": assigned,
            "capacity_rejected_http_400": rejected,
            "unexpected_statuses": Counter(str(r["status"]) for r in unexpected),
            "rider_count_before": initial_count,
            "rider_count_during_after_requests": final_count,
            "capacity_invariant_holds": assigned == capacity and rejected == order_count - capacity
                and final_count == capacity and not unexpected,
            "cleanup": cleanup_orders(order_ids, mysql_container),
        }
    except Exception:
        cleanup_orders(order_ids, mysql_container)
        raise


def run_delete_vs_assign_race(rider_id: int, rounds: int, mysql_container: str) -> dict:
    statuses: Counter = Counter()
    cleanup_errors: list[str] = []
    rider_counts: list[int | None] = []
    for index in range(rounds):
        created = request("POST", "/api/v1/orders", make_order_payload(200000 + index))
        if created["status"] != 200 or not isinstance(created.get("body"), dict):
            raise RuntimeError(f"Delete race fixture creation failed: {created}")
        order_id = int(created["body"]["id"])
        try:
            barrier = threading.Barrier(2)

            def race_request(method: str, path: str) -> dict:
                barrier.wait(timeout=15)
                return request(method, path)

            with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
                assign = executor.submit(
                    race_request, "PATCH", f"/api/v1/orders/{order_id}/assign/{rider_id}")
                delete = executor.submit(race_request, "DELETE", f"/api/v1/orders/{order_id}")
                statuses[f"assign_{assign.result()['status']}"] += 1
                statuses[f"delete_{delete.result()['status']}"] += 1
        finally:
            cleanup = cleanup_orders([order_id], mysql_container)
            cleanup_errors.extend(cleanup["cleanup_errors"])
            cleanup_errors.extend(
                f"remaining test order {order_id}" for _ in cleanup["remaining_order_ids"])
        rider = request("GET", f"/api/v1/riders/{rider_id}")
        rider_counts.append(rider["body"].get("currentOrderCount")
                            if rider["status"] == 200 else None)
        if rider_counts[-1] != 0 or cleanup_errors:
            break

    return {
        "rounds_requested": rounds,
        "rounds_completed": len(rider_counts),
        "status_counts": dict(sorted(statuses.items())),
        "rider_counts_after_each_cleanup": rider_counts,
        "cleanup_errors": cleanup_errors,
        "no_capacity_leak": all(count == 0 for count in rider_counts)
            and len(rider_counts) == rounds
            and not cleanup_errors
            and set(statuses).issubset({"assign_200", "assign_400", "delete_204", "delete_400"}),
    }


def run_async_flow(rider_id: int, mysql_container: str, timeout_seconds: float = 20) -> dict:
    rider_response = request("GET", f"/api/v1/riders/{rider_id}")
    if rider_response["status"] != 200:
        raise RuntimeError(f"Cannot load rider {rider_id}: {rider_response}")
    rider = rider_response["body"]
    if (rider.get("currentOrderCount") or 0) != 0 or rider.get("status") != 1:
        raise RuntimeError("Async flow requires the selected rider to be available and idle")

    created = request("POST", "/api/v1/orders", make_order_payload(
        100000, float(rider["longitude"]), float(rider["latitude"])))
    if created["status"] != 200 or not isinstance(created.get("body"), dict):
        raise RuntimeError(f"Async flow order creation failed: {created}")
    order_id = int(created["body"]["id"])
    started_at = time.perf_counter()
    outcome = "timeout"
    try:
        deadline = time.monotonic() + timeout_seconds
        while time.monotonic() < deadline:
            current = request("GET", f"/api/v1/orders/{order_id}")
            if current["status"] != 200:
                raise RuntimeError(f"Cannot read async flow order {order_id}: {current}")
            if current["body"].get("status") == 1:
                if current["body"].get("riderId") != rider_id:
                    raise RuntimeError(
                        f"Order assigned to unexpected rider {current['body'].get('riderId')}"
                    )
                outcome = "assigned"
                break
            if current["body"].get("status") != 0:
                raise RuntimeError(f"Unexpected async flow order state: {current['body']}")
            time.sleep(0.25)
        if outcome != "assigned":
            raise TimeoutError(f"Order {order_id} was not assigned within {timeout_seconds}s")

        dispatch_ms = round((time.perf_counter() - started_at) * 1000, 1)
        start_result = request("PATCH", f"/api/v1/orders/{order_id}/start-delivery")
        if start_result["status"] != 200 or start_result["body"].get("status") != 2:
            raise RuntimeError(f"start-delivery failed: {start_result}")
        complete_result = request("PATCH", f"/api/v1/orders/{order_id}/complete")
        if complete_result["status"] != 200 or complete_result["body"].get("status") != 3:
            raise RuntimeError(f"complete failed: {complete_result}")
        released_rider = request("GET", f"/api/v1/riders/{rider_id}")
        final_count = released_rider.get("body", {}).get("currentOrderCount")
        cleanup = cleanup_orders([order_id], mysql_container)
        return {
            "order_id": order_id,
            "rider_id": rider_id,
            "outbox_to_assigned_ms": dispatch_ms,
            "order_status_path": [0, 1, 2, 3],
            "rider_count_after_complete": final_count,
            "capacity_released": released_rider["status"] == 200 and final_count == 0,
            "cleanup": cleanup,
        }
    except Exception:
        cleanup_orders([order_id], mysql_container)
        raise


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--rider-id", type=int, default=2,
                        help="available idle rider used by the capacity race")
    parser.add_argument("--read-requests", type=int, default=1000)
    parser.add_argument("--read-workers", type=int, default=64)
    parser.add_argument("--geo-requests", type=int, default=500)
    parser.add_argument("--orders", type=int, default=100)
    parser.add_argument("--create-workers", type=int, default=16)
    parser.add_argument("--assign-workers", type=int, default=100)
    parser.add_argument("--delete-race-rounds", type=int, default=30)
    parser.add_argument("--capacity", type=int, default=3)
    parser.add_argument("--mysql-container", default="delivery-mysql")
    parser.add_argument("--output", help="optional path for the JSON result report")
    args = parser.parse_args()

    global base_parts
    base_parts = urlsplit(args.base_url)
    if base_parts.scheme not in ("http", "https") or not base_parts.hostname:
        parser.error("--base-url must be an http(s) URL")
    if args.read_requests < 1 or args.geo_requests < 1 or args.read_workers < 1 \
            or args.orders < 1 or args.create_workers < 1 or args.assign_workers < 1 \
            or args.delete_race_rounds < 0:
        parser.error("request counts and worker counts must be positive")

    authenticate_from_environment()

    health = request("GET", "/actuator/health")
    if health["status"] != 200 or not isinstance(health.get("body"), dict) \
            or health["body"].get("status") != "UP":
        print(json.dumps({"health_check": health}, ensure_ascii=False, indent=2))
        return 2

    read_path = f"/api/v1/riders/{args.rider_id}"
    read_result = run_read_benchmark(read_path, args.read_requests, args.read_workers)
    rider_detail = request("GET", read_path)
    if rider_detail["status"] != 200:
        raise RuntimeError(f"Cannot load rider {args.rider_id}: {rider_detail}")
    nearby_path = (
        "/api/v1/riders/nearby?longitude=" + str(rider_detail["body"]["longitude"])
        + "&latitude=" + str(rider_detail["body"]["latitude"])
        + "&radiusKm=5&limit=20"
    )
    geo_result = run_read_benchmark(nearby_path, args.geo_requests, args.read_workers)
    capacity_result = run_capacity_race(
        args.rider_id, args.orders, args.create_workers, args.assign_workers,
        args.capacity, args.mysql_container,
    )
    capacity_cleaned = (capacity_result["cleanup"]["orders_deleted"] == args.orders
                        and not capacity_result["cleanup"]["cleanup_errors"])
    if capacity_cleaned:
        delete_race_result = run_delete_vs_assign_race(
            args.rider_id, args.delete_race_rounds, args.mysql_container)
    else:
        delete_race_result = {"no_capacity_leak": False, "skipped": "capacity cleanup failed"}
    if capacity_cleaned and delete_race_result["no_capacity_leak"]:
        async_result = run_async_flow(args.rider_id, args.mysql_container)
    else:
        async_result = {"skipped": "a preceding write scenario did not clean up"}
    report = {
        "ran_at": datetime.now().astimezone().isoformat(timespec="seconds"),
        "base_url": args.base_url,
        "application_health": "UP",
        "read_scenario": {
            "endpoint": f"GET {read_path}",
            "note": "Rider detail cache warmed before timed requests.",
            **read_result,
        },
        "geo_scenario": {
            "endpoint": "GET " + nearby_path,
            "note": "Redis GEO search centered on the selected rider location.",
            **geo_result,
        },
        "capacity_race": capacity_result,
        "delete_vs_assign_race": delete_race_result,
        "async_dispatch_and_fulfillment": async_result,
    }
    rendered = json.dumps(report, ensure_ascii=False, indent=2)
    print(rendered)
    if args.output:
        output_path = Path(args.output)
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_text(rendered + "\n", encoding="utf-8")
    return 0 if capacity_result["capacity_invariant_holds"] \
        and capacity_cleaned \
        and delete_race_result["no_capacity_leak"] \
        and async_result.get("capacity_released", False) \
        and async_result.get("cleanup", {}).get("orders_deleted") == 1 \
        and read_result["successful_2xx"] == args.read_requests \
        and geo_result["successful_2xx"] == args.geo_requests else 1


if __name__ == "__main__":
    sys.exit(main())
