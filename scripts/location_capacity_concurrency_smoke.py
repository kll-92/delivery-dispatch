#!/usr/bin/env python3
"""Concurrent rider-capacity smoke test using isolated verification fixtures.

This script creates API fixtures in a disposable verification database, runs 16
independent authenticated workers against 100 distinct orders, and leaves those
fixtures in place for inspection. It never creates, drops, or cleans up databases.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import secrets
import sys
import time
from collections import Counter
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

import stage1_smoke as stage1
import location_geo_smoke as location_smoke


DATABASE_PATTERN = re.compile(r"delivery_dispatch_verify_[a-zA-Z0-9_]+\Z")
VERIFY_USERNAME = "verify-admin"
ORDER_COUNT = 100
WORKER_COUNT = 16


class CapacitySmokeFailure(stage1.SmokeFailure):
    """Safe-to-report failure; never includes credentials or response bodies."""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise CapacitySmokeFailure(message)


def mysql(args: argparse.Namespace, sql: str) -> str:
    return stage1.mysql(args, sql)


def scalar(args: argparse.Namespace, sql: str, label: str) -> str:
    output = mysql(args, sql)
    if not output:
        raise CapacitySmokeFailure(f"{label}: query returned no value")
    return output.splitlines()[0]


def create_orders(admin: stage1.ApiClient, report: dict[str, Any],
                  merchant: tuple[float, float]) -> list[int]:
    order_ids: list[int] = []
    for index in range(ORDER_COUNT):
        payload = stage1.create_order_payload(
            merchantName=f"并发容量商家{index + 1}",
            merchantLongitude=merchant[0],
            merchantLatitude=merchant[1],
        )
        result = admin.request("POST", "/api/v1/orders", payload)
        stage1.expect_status(result, 200, "create concurrency order")
        order_id = result.json("create concurrency order").get("id")
        require(isinstance(order_id, int) and order_id > 0,
                "created concurrency order did not include a valid id")
        order_ids.append(order_id)
    report["fixtures"]["orderIds"] = order_ids
    return order_ids


def worker_assign(client: stage1.ApiClient, order_ids: list[int], rider_id: int) -> list[int]:
    statuses: list[int] = []
    for order_id in order_ids:
        result = client.request("PATCH", f"/api/v1/orders/{order_id}/assign/{rider_id}")
        statuses.append(result.status)
    return statuses


def run_smoke(args: argparse.Namespace) -> dict[str, Any]:
    started = time.monotonic()
    username = os.environ.get("STAGE1_ADMIN_USERNAME", VERIFY_USERNAME)
    password = os.environ.get("STAGE1_ADMIN_PASSWORD")
    if not password:
        raise CapacitySmokeFailure("set STAGE1_ADMIN_PASSWORD for the verification administrator")
    if username != VERIFY_USERNAME:
        raise CapacitySmokeFailure("STAGE1_ADMIN_USERNAME must be verify-admin for this isolated run")
    if not DATABASE_PATTERN.fullmatch(args.database):
        raise CapacitySmokeFailure("--database must match delivery_dispatch_verify_[a-zA-Z0-9_]+")
    parsed_url = urlparse(args.base_url)
    if (parsed_url.scheme not in {"http", "https"} or not parsed_url.netloc
            or parsed_url.username or parsed_url.query or parsed_url.fragment):
        raise CapacitySmokeFailure("--base-url must be an HTTP(S) URL without embedded credentials")

    selected_database = scalar(args, "SELECT DATABASE()", "selected database")
    require(selected_database == args.database,
            "MySQL helper did not select the requested verification database; refusing fixture writes")
    db_admin_id = scalar(args,
                         "SELECT id FROM app_account WHERE username='verify-admin' AND enabled=1 LIMIT 1",
                         "verification administrator")
    require(db_admin_id.isdigit(), "enabled verify-admin was not found in the target verification database")

    report: dict[str, Any] = {
        "database": args.database,
        "baseUrl": parsed_url.scheme + "://" + parsed_url.netloc,
        "workers": WORKER_COUNT,
        "orders": ORDER_COUNT,
        "startedAt": datetime.now(timezone.utc).isoformat(),
        "passed": [],
        "fixtures": {},
    }
    args._smoke_report = report

    admin = stage1.ApiClient(args.base_url)
    identity = admin.login(username, password, "ADMIN")
    require(identity.get("role") == "ADMIN", "verification account is not an administrator")
    accounts_result = admin.request("GET", "/api/v1/accounts")
    stage1.expect_status(accounts_result, 200, "admin account list")
    accounts = accounts_result.json("admin account list")
    require(isinstance(accounts, list)
            and any(isinstance(account, dict) and account.get("username") == VERIFY_USERNAME
                    and account.get("id") == int(db_admin_id) for account in accounts),
            "HTTP verify-admin does not match the selected verification database")
    report["fixtures"]["adminAccountId"] = int(db_admin_id)
    report["passed"].append("verification_admin_matches_database")

    suffix = secrets.token_hex(4).upper()
    rider_id = location_smoke.create_rider(admin, report, suffix, "capacityConcurrent", location_smoke.NEAR)
    order_ids = create_orders(admin, report, location_smoke.NEAR)

    # Establish 16 independent cookie/CSRF sessions before the race. Refresh the
    # rider only after all fixture creation so setup time does not consume its TTL.
    clients = [stage1.ApiClient(args.base_url) for _ in range(WORKER_COUNT)]
    for client in clients:
        client.login(username, password, "ADMIN")
    location_smoke.refresh_location(admin, rider_id, location_smoke.NEAR)
    fresh_timestamp = location_smoke.db_location(args, rider_id)
    require(fresh_timestamp != "NULL", "rider location was not fresh before concurrent assignment")
    report["fixtures"]["riderId"] = rider_id
    report["fixtures"]["lastLocationAtBeforeRace"] = fresh_timestamp

    assignments_per_worker = [order_ids[index::WORKER_COUNT] for index in range(WORKER_COUNT)]
    race_started = time.monotonic()
    status_codes: list[int] = []
    with ThreadPoolExecutor(max_workers=WORKER_COUNT) as executor:
        futures = [executor.submit(worker_assign, clients[index], assignments_per_worker[index], rider_id)
                   for index in range(WORKER_COUNT)]
        for future in as_completed(futures, timeout=args.race_timeout_seconds):
            status_codes.extend(future.result())
    race_elapsed = round(time.monotonic() - race_started, 3)
    status_distribution = {str(code): count for code, count in sorted(Counter(status_codes).items())}
    report["raceElapsedSeconds"] = race_elapsed
    report["httpStatusDistribution"] = status_distribution
    require(len(status_codes) == ORDER_COUNT, "concurrent workers did not return one result per order")
    require(Counter(status_codes)[200] == 3,
            "concurrent assignments did not produce exactly three successful claims")
    require(set(status_codes).issubset({200, 400, 409}),
            "concurrent assignment returned an unexpected HTTP status")

    rider_count = scalar(args, f"SELECT current_order_count FROM rider WHERE id={rider_id}",
                         "concurrent rider capacity")
    assigned_rows = scalar(args,
                           "SELECT COUNT(*) FROM delivery_order "
                           f"WHERE id IN ({','.join(map(str, order_ids))}) "
                           f"AND status=1 AND rider_id={rider_id}",
                           "assigned fixture order count")
    pending_rows = scalar(args,
                          "SELECT COUNT(*) FROM delivery_order "
                          f"WHERE id IN ({','.join(map(str, order_ids))}) "
                          "AND status=0 AND rider_id IS NULL",
                          "pending fixture order count")
    require(rider_count == "3" and assigned_rows == "3" and pending_rows == str(ORDER_COUNT - 3),
            "database capacity or order states diverged from the three successful assignments")
    assigned_ids_output = mysql(args,
                                "SELECT id FROM delivery_order "
                                f"WHERE id IN ({','.join(map(str, order_ids))}) "
                                f"AND status=1 AND rider_id={rider_id} ORDER BY id")
    assigned_ids = [int(value) for value in assigned_ids_output.splitlines() if value.isdigit()]
    require(len(assigned_ids) == 3, "database did not identify exactly three assigned fixture orders")
    report["fixtures"]["assignedOrderIds"] = assigned_ids
    report["passed"].append("100_orders_16_sessions_exactly_three_capacity_claims")

    for order_id in assigned_ids:
        stage1.expect_status(admin.request("PATCH", f"/api/v1/orders/{order_id}/start-delivery"),
                             200, "start concurrency fixture order")
        stage1.expect_status(admin.request("PATCH", f"/api/v1/orders/{order_id}/complete"),
                             200, "complete concurrency fixture order")
    final_capacity = scalar(args, f"SELECT current_order_count FROM rider WHERE id={rider_id}",
                            "capacity after completing concurrent orders")
    require(final_capacity == "0", "completing the three successful orders did not release capacity")
    report["passed"].append("three_completions_release_capacity_to_zero")
    report["elapsedSeconds"] = round(time.monotonic() - started, 3)
    report["finishedAt"] = datetime.now(timezone.utc).isoformat()
    return report


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://localhost:18082")
    parser.add_argument("--mysql-container", default="delivery-mysql")
    parser.add_argument("--database", required=True,
                        help="isolated database name: delivery_dispatch_verify_<suffix>")
    parser.add_argument("--race-timeout-seconds", type=int, default=120,
                        help="bounded wall-clock timeout for all assignment workers")
    parser.add_argument("--output", help="optional JSON report path; contains no credentials or tokens")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv or sys.argv[1:])
    started = time.monotonic()
    parsed = urlparse(args.base_url)
    report: dict[str, Any] = {
        "database": args.database,
        "baseUrl": parsed.scheme + "://" + parsed.netloc,
        "workers": WORKER_COUNT,
        "orders": ORDER_COUNT,
        "startedAt": datetime.now(timezone.utc).isoformat(),
        "passed": [],
        "fixtures": {},
    }
    try:
        report = run_smoke(args)
        report["result"] = "passed"
    except CapacitySmokeFailure as error:
        report = getattr(args, "_smoke_report", report)
        report["result"] = "failed"
        report["failure"] = str(error)
    except Exception as error:
        report = getattr(args, "_smoke_report", report)
        report["result"] = "failed"
        report["failure"] = f"unexpected smoke harness error ({type(error).__name__}); inspect and rerun"
    report["elapsedSeconds"] = round(time.monotonic() - started, 3)
    report["finishedAt"] = datetime.now(timezone.utc).isoformat()
    if args.output:
        output_path = Path(args.output)
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if report.get("result") == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
