#!/usr/bin/env python3
"""Verify that an old pending order is assigned after a rider comes online."""

from __future__ import annotations

import argparse
from datetime import datetime
import json
from pathlib import Path
import subprocess
import sys
import time
from urllib.parse import urlsplit
import uuid

import concurrency_smoke as http


def mysql(sql: str, container: str) -> str:
    command = [
        "docker", "exec", "-i", container, "sh", "-c",
        'exec mysql -N -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE"',
    ]
    result = subprocess.run(command, input=sql, text=True, capture_output=True,
                            timeout=15, check=False)
    if result.returncode != 0:
        raise RuntimeError(f"MySQL command failed: {result.stderr.strip()}")
    return result.stdout.strip()


def metric_count(result_tag: str) -> float:
    response = http.request(
        "GET", f"/actuator/metrics/dispatch.recovery.attempts?tag=result:{result_tag}")
    if response["status"] == 404:
        return 0.0
    if response["status"] != 200:
        raise RuntimeError(f"Recovery metric unavailable: {response}")
    return sum(item["value"] for item in response["body"].get("measurements", []))


def wait_for(check, timeout_seconds: float, description: str):
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        result = check()
        if result:
            return result
        time.sleep(0.5)
    raise TimeoutError(f"Timed out waiting for {description}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--mysql-container", default="delivery-mysql")
    parser.add_argument("--redis-container", default="delivery-redis")
    parser.add_argument("--timeout-seconds", type=float, default=75)
    parser.add_argument("--output")
    args = parser.parse_args()

    http.base_parts = urlsplit(args.base_url)
    http.authenticate_from_environment()
    health = http.request("GET", "/actuator/health")
    if health["status"] != 200 or health["body"].get("status") != "UP":
        raise RuntimeError(f"Application is not healthy: {health}")

    suffix = uuid.uuid4().hex
    order_no = "ORD" + suffix[:29]
    rider_no = "REC" + suffix[:20]
    phone = "18" + str(int(suffix[:12], 16) % 10**9).zfill(9)
    initial_no_rider = metric_count("no_rider")
    order_id: int | None = None
    rider_id: int | None = None
    report: dict = {}
    cleanup_errors: list[str] = []
    try:
        # A published Outbox row with no RocketMQ message isolates the recovery
        # scheduler from ordinary consumer retries. The order is already old
        # enough to qualify for the next scheduled sweep.
        inserted = mysql(f"""
            INSERT INTO delivery_order (
                order_no, merchant_name, merchant_phone, merchant_address,
                merchant_longitude, merchant_latitude, receiver_name,
                receiver_phone, receiver_address, receiver_longitude,
                receiver_latitude, status, delivery_fee, created_at
            ) VALUES (
                '{order_no}', '恢复验证临时商家', '10000000000', '临时地址',
                121.5000000, 31.2000000, '恢复验证收件人',
                '10000000001', '临时地址', 121.5000000,
                31.2000000, 0, 0, DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 60 SECOND)
            );
            SET @fixture_order_id = LAST_INSERT_ID();
            INSERT INTO dispatch_outbox (
                order_id, event_type, status, created_at, published_at
            ) VALUES (
                @fixture_order_id, 'DISPATCH_REQUEST', 1,
                DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 60 SECOND),
                DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 60 SECOND)
            );
            SELECT @fixture_order_id;
        """, args.mysql_container)
        order_id = int(inserted.splitlines()[-1])

        no_rider_started = time.perf_counter()
        wait_for(lambda: metric_count("no_rider") > initial_no_rider,
                 args.timeout_seconds, "the no-rider recovery attempt")
        no_rider_wait_seconds = round(time.perf_counter() - no_rider_started, 2)
        pending = http.request("GET", f"/api/v1/orders/{order_id}")
        if pending["status"] != 200 or pending["body"].get("status") != 0:
            raise RuntimeError(f"Order changed before rider came online: {pending}")

        created = http.request("POST", "/api/v1/riders", {
            "riderNo": rider_no,
            "name": "恢复验证临时骑手",
            "phone": phone,
            "longitude": 121.5,
            "latitude": 31.2,
        })
        if created["status"] != 200 or not isinstance(created.get("body"), dict):
            raise RuntimeError(f"Creating recovery rider failed: {created}")
        rider_id = int(created["body"]["id"])

        assigned_started = time.perf_counter()

        def assigned_to_fixture_rider():
            current = http.request("GET", f"/api/v1/orders/{order_id}")
            if current["status"] != 200:
                raise RuntimeError(f"Cannot read recovery order: {current}")
            return current["body"] if current["body"].get("status") == 1 else None

        assigned = wait_for(assigned_to_fixture_rider, args.timeout_seconds,
                            "the recovered assignment")
        if assigned.get("riderId") != rider_id:
            raise RuntimeError(f"Order assigned to unexpected rider: {assigned}")
        assignment_wait_seconds = round(time.perf_counter() - assigned_started, 2)

        started = http.request("PATCH", f"/api/v1/orders/{order_id}/start-delivery")
        completed = http.request("PATCH", f"/api/v1/orders/{order_id}/complete")
        if started["status"] != 200 or started["body"].get("status") != 2 \
                or completed["status"] != 200 or completed["body"].get("status") != 3:
            raise RuntimeError(f"Recovered order fulfillment failed: {started}, {completed}")
        rider = http.request("GET", f"/api/v1/riders/{rider_id}")
        if rider["status"] != 200 or rider["body"].get("currentOrderCount") != 0:
            raise RuntimeError(f"Recovered rider slot not released: {rider}")

        report = {
            "ran_at": datetime.now().astimezone().isoformat(timespec="seconds"),
            "base_url": args.base_url,
            "order_id": order_id,
            "rider_id": rider_id,
            "no_rider_retry_observed_after_seconds": no_rider_wait_seconds,
            "assignment_after_rider_online_seconds": assignment_wait_seconds,
            "order_status_path": [0, 1, 2, 3],
            "rider_count_after_complete": 0,
            "no_rider_metric_before": initial_no_rider,
            "no_rider_metric_after": metric_count("no_rider"),
            "processed_metric_after": metric_count("processed"),
        }
    finally:
        if order_id is not None:
            cleanup = http.cleanup_orders([order_id], args.mysql_container)
            cleanup_errors.extend(cleanup["cleanup_errors"])
            cleanup_errors.extend(
                f"order {remaining} remains" for remaining in cleanup["remaining_order_ids"])
        if rider_id is not None:
            disabled = http.request("PATCH", f"/api/v1/riders/{rider_id}/disable")
            if disabled["status"] != 200:
                cleanup_errors.append(f"disable rider {rider_id}: HTTP {disabled['status']}")
            subprocess.run(["docker", "exec", args.redis_container, "redis-cli", "ZREM",
                            "geo:rider:available", f"rider:{rider_id}"],
                           capture_output=True, text=True, check=False)
            subprocess.run(["docker", "exec", args.redis_container, "redis-cli", "DEL",
                            f"rider:detail:{rider_id}"],
                           capture_output=True, text=True, check=False)
            mysql(f"DELETE FROM rider WHERE id = {rider_id} AND rider_no = '{rider_no}';",
                  args.mysql_container)
        if order_id is not None:
            mysql(f"DELETE FROM dispatch_outbox WHERE order_id = {order_id};\n"
                  f"DELETE FROM delivery_order WHERE id = {order_id} AND order_no = '{order_no}';",
                  args.mysql_container)

    report["cleanup_errors"] = cleanup_errors
    rendered = json.dumps(report, ensure_ascii=False, indent=2)
    print(rendered)
    if args.output:
        path = Path(args.output)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(rendered + "\n", encoding="utf-8")
    return 0 if not cleanup_errors else 1


if __name__ == "__main__":
    sys.exit(main())
