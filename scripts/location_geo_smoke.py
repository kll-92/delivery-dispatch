#!/usr/bin/env python3
"""HTTP smoke coverage for rider location freshness and Redis GEO recovery.

Run only against a disposable verification database/application instance. The script
creates API fixtures and uses narrowly-scoped SQL/Redis helpers to arrange edge
conditions; it never drops databases or tables and never reports credentials/tokens.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import selectors
import secrets
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from urllib.parse import urlencode, urlparse

import stage1_smoke as stage1


DATABASE_PATTERN = re.compile(r"delivery_dispatch_verify_[a-zA-Z0-9_]+\Z")
VERIFY_USERNAME_PATTERN = re.compile(r"verify-[a-zA-Z0-9_-]{1,55}\Z")
GEO_KEY = "geo:rider:available"
NEAR = (116.3971280, 39.9165270)
FAR = (116.5000000, 39.9165270)


class LocationSmokeFailure(stage1.SmokeFailure):
    """Safe-to-report failure without response bodies, credentials, or tokens."""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise LocationSmokeFailure(message)


def mysql(args: argparse.Namespace, sql: str) -> str:
    # Reuse the stage-one isolation-aware helper and its credential handling.
    return stage1.mysql(args, sql)


def scalar(args: argparse.Namespace, sql: str, label: str) -> str:
    output = mysql(args, sql)
    if not output:
        raise LocationSmokeFailure(f"{label}: query returned no value")
    return output.splitlines()[0]


def redis(args: argparse.Namespace, *command: str) -> str:
    try:
        completed = subprocess.run(
            ["docker", "exec", args.redis_container, "redis-cli", *command],
            check=False, capture_output=True, text=True, timeout=10,
        )
    except (OSError, subprocess.TimeoutExpired):
        raise LocationSmokeFailure("Redis helper failed; verify the temporary verification container") from None
    if completed.returncode != 0 or "(error)" in completed.stdout:
        raise LocationSmokeFailure("Redis helper failed; verify the temporary verification container")
    return completed.stdout.strip()


def redis_info_value(info: str, field: str) -> str | None:
    prefix = field + ":"
    return next((line[len(prefix):] for line in info.splitlines() if line.startswith(prefix)), None)


def db_location(args: argparse.Namespace, rider_id: int) -> str:
    return scalar(args,
                  "SELECT COALESCE(DATE_FORMAT(last_location_at,'%Y-%m-%d %H:%i:%s.%f'),'NULL') "
                  f"FROM rider WHERE id={rider_id}", "rider last_location_at")


def create_rider(admin: stage1.ApiClient, report: dict[str, Any], suffix: str,
                 label: str, coordinates: tuple[float, float] | None = NEAR) -> int:
    serial = secrets.token_hex(3)
    payload: dict[str, Any] = {
        "riderNo": f"VERIFY-{suffix}-{serial}"[:32],
        "name": f"位置验证-{label}",
        "phone": "139" + str(10000000 + int(serial, 16) % 89999999),
    }
    if coordinates is not None:
        payload["longitude"], payload["latitude"] = coordinates
        payload["lastLocationAt"] = "2099-01-01T00:00:00"
    result = admin.request("POST", "/api/v1/riders", payload)
    stage1.expect_status(result, 200, "create " + label + " rider")
    rider_id = result.json("create rider").get("id")
    require(isinstance(rider_id, int) and rider_id > 0, "created rider did not include a valid id")
    report["fixtures"][label + "RiderId"] = rider_id
    return rider_id


def refresh_location(admin: stage1.ApiClient, rider_id: int,
                     coordinates: tuple[float, float] = NEAR) -> None:
    result = admin.request("PUT", f"/api/v1/riders/{rider_id}/location",
                           {"longitude": coordinates[0], "latitude": coordinates[1]})
    stage1.expect_status(result, 200, "report rider location")


def nearby(admin: stage1.ApiClient, longitude: float = NEAR[0], latitude: float = NEAR[1],
           radius_km: float = 5, limit: int = 50) -> list[dict[str, Any]]:
    query = urlencode({"longitude": longitude, "latitude": latitude,
                       "radiusKm": radius_km, "limit": limit})
    result = admin.request("GET", "/api/v1/riders/nearby?" + query)
    stage1.expect_status(result, 200, "nearby riders")
    payload = result.json("nearby riders")
    require(isinstance(payload, list), "nearby endpoint did not return a list")
    return payload


def create_order(admin: stage1.ApiClient, report: dict[str, Any], label: str,
                 merchant: tuple[float, float] = NEAR) -> int:
    payload = stage1.create_order_payload(merchantLongitude=merchant[0], merchantLatitude=merchant[1])
    result = admin.request("POST", "/api/v1/orders", payload)
    stage1.expect_status(result, 200, "create " + label + " order")
    order_id = result.json("create order").get("id")
    require(isinstance(order_id, int) and order_id > 0, "created order did not include a valid id")
    report["fixtures"][label + "OrderId"] = order_id
    return order_id


def complete_order(admin: stage1.ApiClient, order_id: int) -> None:
    stage1.expect_status(admin.request("PATCH", f"/api/v1/orders/{order_id}/start-delivery"),
                         200, "start assigned order")
    stage1.expect_status(admin.request("PATCH", f"/api/v1/orders/{order_id}/complete"),
                         200, "complete assigned order")


def lock_row_until_marker(args: argparse.Namespace, table: str, row_id: int) -> subprocess.Popen[str]:
    if table not in {"rider", "delivery_order"} or row_id <= 0:
        raise LocationSmokeFailure("invalid isolated lock-wait fixture")
    mysql_user = os.environ.get("MYSQL_USER")
    mysql_password = os.environ.get("MYSQL_PASSWORD")
    if not mysql_user or not mysql_password:
        raise LocationSmokeFailure("set MYSQL_USER and MYSQL_PASSWORD for the isolated MySQL container")
    sql = ("START TRANSACTION; "
           f"SELECT id INTO @verification_lock_row FROM {table} WHERE id={row_id} FOR UPDATE; "
           "SELECT 'LOCKED'; DO SLEEP(4); COMMIT")
    command = [
        "docker", "exec", "-i", "--env", "MYSQL_USER", "--env", "MYSQL_PASSWORD", args.mysql_container,
        "sh", "-c",
        'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --unbuffered -u"$MYSQL_USER" "$1" '
        '--batch --skip-column-names -e "$2"',
        "location-geo-smoke-lock", args.database, sql,
    ]
    env = os.environ.copy()
    try:
        process = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                   stderr=subprocess.DEVNULL, text=True, bufsize=1, env=env)
    except OSError:
        raise LocationSmokeFailure("could not start the isolated MySQL lock-wait helper") from None
    assert process.stdout is not None
    deadline = time.monotonic() + 8
    try:
        with selectors.DefaultSelector() as selector:
            selector.register(process.stdout, selectors.EVENT_READ)
            while time.monotonic() < deadline:
                events = selector.select(timeout=min(0.25, deadline - time.monotonic()))
                if not events:
                    if process.poll() is not None:
                        break
                    continue
                line = process.stdout.readline()
                if line.rstrip("\r\n") == "LOCKED":
                    return process
        terminate_lock_process(process)
        raise LocationSmokeFailure("MySQL lock helper did not emit its bounded LOCKED handshake")
    except Exception:
        terminate_lock_process(process)
        raise


def terminate_lock_process(process: subprocess.Popen[str]) -> None:
    if process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=2)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=2)


def finish_lock_process(process: subprocess.Popen[str]) -> None:
    try:
        process.wait(timeout=6)
    except subprocess.TimeoutExpired:
        terminate_lock_process(process)
        raise LocationSmokeFailure("MySQL lock helper did not release its test lock within the bound") from None
    if process.returncode != 0:
        raise LocationSmokeFailure("MySQL lock helper failed; isolated lock-wait assertion is inconclusive")


def assignment_waiting_on_lock(admin: stage1.ApiClient, args: argparse.Namespace,
                               table: str, locked_id: int, order_id: int, rider_id: int) -> int:
    mysql(args, f"UPDATE rider SET last_location_at=UTC_TIMESTAMP(6)-INTERVAL {args.ttl_seconds - 2} SECOND "
                f"WHERE id={rider_id}")
    process = lock_row_until_marker(args, table, locked_id)
    try:
        waiting_started = time.monotonic()
        response = admin.request("PATCH", f"/api/v1/orders/{order_id}/assign/{rider_id}")
        waited_seconds = time.monotonic() - waiting_started
        require(waited_seconds >= 2, f"{table} fixture did not actually block the HTTP assignment")
        require(response.status in {400, 409},
                f"assignment did not reject rider expiry while waiting on {table} lock")
    finally:
        # The helper owns only its own transaction/container-side mysql process.
        finish_lock_process(process)
    order = scalar(args, f"SELECT CONCAT(status,'|',COALESCE(rider_id,'NULL')) "
                         f"FROM delivery_order WHERE id={order_id}", "post-lock order state")
    count = scalar(args, f"SELECT current_order_count FROM rider WHERE id={rider_id}",
                   "post-lock rider capacity")
    require(order == "0|NULL" and count == "0",
            f"{table} lock-wait expiry did not leave order pending and capacity unchanged")
    return response.status


def run_smoke(args: argparse.Namespace) -> dict[str, Any]:
    started = time.monotonic()
    username = os.environ.get("STAGE1_ADMIN_USERNAME", "verify-admin")
    password = os.environ.get("STAGE1_ADMIN_PASSWORD")
    if not password:
        raise LocationSmokeFailure("set STAGE1_ADMIN_PASSWORD for the verification administrator")
    if not VERIFY_USERNAME_PATTERN.fullmatch(username):
        raise LocationSmokeFailure("STAGE1_ADMIN_USERNAME must start with verify- and use safe identifier characters")
    if not DATABASE_PATTERN.fullmatch(args.database):
        raise LocationSmokeFailure("--database must match delivery_dispatch_verify_[a-zA-Z0-9_]+")
    if "verify" not in args.redis_container.lower():
        raise LocationSmokeFailure("--redis-container must name a temporary container containing 'verify'")
    if args.ttl_seconds < 3 or args.recovery_wait_seconds <= 0:
        raise LocationSmokeFailure("TTL must be at least 3 seconds and recovery wait must be positive")
    parsed_url = urlparse(args.base_url)
    if (parsed_url.scheme not in {"http", "https"} or not parsed_url.netloc
            or parsed_url.username or parsed_url.query or parsed_url.fragment):
        raise LocationSmokeFailure("--base-url must be an HTTP(S) URL without embedded credentials")

    selected_database = scalar(args, "SELECT DATABASE()", "selected database")
    require(selected_database == args.database,
            "MySQL helper did not select the requested verification database; refusing fixture writes")
    require("last_location_at" in mysql(args, "SHOW COLUMNS FROM rider LIKE 'last_location_at'").splitlines()[0],
            "verification database is missing rider.last_location_at")
    admin_id = scalar(args, "SELECT id FROM app_account WHERE username='" + username + "' AND enabled=1 LIMIT 1",
                      "verification administrator")
    require(admin_id.isdigit(), "enabled verification administrator was not found in the target database")

    report: dict[str, Any] = {
        "database": args.database,
        "baseUrl": parsed_url.scheme + "://" + parsed_url.netloc,
        "ttlSeconds": args.ttl_seconds,
        "recoveryWaitSeconds": args.recovery_wait_seconds,
        "startedAt": datetime.now(timezone.utc).isoformat(),
        "passed": [],
        "fixtures": {},
    }
    args._smoke_report = report

    def passed(name: str) -> None:
        report["passed"].append(name)

    admin = stage1.ApiClient(args.base_url)
    identity = admin.login(username, password, "ADMIN")
    accounts = admin.request("GET", "/api/v1/accounts")
    stage1.expect_status(accounts, 200, "admin account list")
    account_list = accounts.json("admin account list")
    require(any(isinstance(row, dict) and row.get("username") == username
                and row.get("id") == int(admin_id) for row in account_list),
            "HTTP administrator does not match target verification database")
    require(identity.get("role") == "ADMIN", "verification account is not an administrator")
    passed("verification_admin_matches_database")

    suffix = secrets.token_hex(4).upper()
    coords_id = create_rider(admin, report, suffix, "coords", NEAR)
    no_coords_id = create_rider(admin, report, suffix, "noCoords", None)
    coords_at = db_location(args, coords_id)
    no_coords_at = db_location(args, no_coords_id)
    require(coords_at != "NULL" and no_coords_at == "NULL",
            "coordinate rider freshness initialization was not server-controlled/null as expected")
    require(scalar(args, f"SELECT (last_location_at <= UTC_TIMESTAMP(6)) FROM rider WHERE id={coords_id}",
                   "server-owned location timestamp") == "1", "client supplied a future location timestamp")
    report["fixtures"]["coordsLastLocationAt"] = coords_at
    report["fixtures"]["noCoordsLastLocationAt"] = no_coords_at

    profile_url = f"/api/v1/riders/{coords_id}"
    stage1.expect_status(admin.request("PUT", profile_url, {
        "name": "位置资料更新", "phone": "139" + str(21000000 + int(suffix, 16) % 70000000),
    }), 200, "profile update without coordinates")
    require(db_location(args, coords_id) == coords_at,
            "profile update without coordinates unexpectedly refreshed location age")
    stage1.expect_status(admin.request("PUT", profile_url, {
        "name": "位置资料同坐标", "phone": "139" + str(31000000 + int(suffix, 16) % 60000000),
        "longitude": NEAR[0], "latitude": NEAR[1],
    }), 200, "profile update with same coordinates")
    require(db_location(args, coords_id) == coords_at,
            "profile update with unchanged coordinates unexpectedly refreshed location age")
    stage1.expect_status(admin.request("PATCH", f"/api/v1/riders/{coords_id}/disable"), 200, "disable rider")
    stage1.expect_status(admin.request("PATCH", f"/api/v1/riders/{coords_id}/enable"), 200, "enable rider")
    require(db_location(args, coords_id) == coords_at,
            "disable/enable unexpectedly refreshed location age")
    mysql(args, f"UPDATE rider SET last_location_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id={coords_id}")
    refresh_location(admin, coords_id, NEAR)
    refreshed_at = db_location(args, coords_id)
    require(refreshed_at != "NULL" and refreshed_at != coords_at,
            "dedicated location report did not refresh same-coordinate last_location_at")
    profile_rider = admin.request("GET", profile_url).json("profile before coordinate change")
    stage1.expect_status(admin.request("PUT", profile_url, {
        "name": profile_rider["name"], "phone": profile_rider["phone"],
        "longitude": FAR[0], "latitude": FAR[1],
    }), 200, "profile changed coordinates")
    require(db_location(args, coords_id) != refreshed_at,
            "profile coordinate change did not refresh database location age")
    refresh_location(admin, coords_id, NEAR)
    passed("location_freshness_server_owned_profile_state_and_same_coordinate_report")

    expired_id = create_rider(admin, report, suffix, "expired", NEAR)
    null_id = create_rider(admin, report, suffix, "nullTimestamp", NEAR)
    future_id = create_rider(admin, report, suffix, "futureTimestamp", NEAR)
    full_id = create_rider(admin, report, suffix, "full", NEAR)
    disabled_id = create_rider(admin, report, suffix, "disabled", NEAR)
    mysql(args, f"UPDATE rider SET last_location_at=UTC_TIMESTAMP(6)-INTERVAL {args.ttl_seconds + 5} SECOND "
                f"WHERE id={expired_id}")
    mysql(args, f"UPDATE rider SET last_location_at=NULL WHERE id={null_id}")
    mysql(args, f"UPDATE rider SET last_location_at=UTC_TIMESTAMP(6)+INTERVAL 1 DAY WHERE id={future_id}")
    mysql(args, f"UPDATE rider SET current_order_count=3 WHERE id={full_id}")
    stage1.expect_status(admin.request("PATCH", f"/api/v1/riders/{disabled_id}/disable"), 200,
                         "disable freshness filter rider")
    for rider_id in (expired_id, null_id, future_id, full_id, disabled_id):
        redis(args, "GEOADD", GEO_KEY, str(NEAR[0]), str(NEAR[1]), f"rider:{rider_id}")
    nearby_ids = {row.get("riderId") for row in nearby(admin)}
    require(not nearby_ids.intersection({expired_id, null_id, future_id, full_id, disabled_id}),
            "nearby returned an expired/null/future/full/disabled rider injected into GEO")
    passed("nearby_rechecks_database_freshness_status_and_capacity")

    expired_order = create_order(admin, report, "expiredManual")
    refused = admin.request("PATCH", f"/api/v1/orders/{expired_order}/assign/{expired_id}")
    require(refused.status in {400, 409}, "manual assignment unexpectedly accepted an expired rider")
    require(scalar(args, f"SELECT current_order_count FROM rider WHERE id={expired_id}",
                   "expired rider capacity") == "0",
            "rejected expired assignment consumed capacity")
    far_id = create_rider(admin, report, suffix, "freshFar", FAR)
    far_order = create_order(admin, report, "freshFarManual")
    far_assignment = admin.request("PATCH", f"/api/v1/orders/{far_order}/assign/{far_id}")
    stage1.expect_status(far_assignment, 200, "manual assignment without radius restriction")
    require(far_assignment.json("manual far assignment").get("riderId") == far_id,
            "manual assignment did not accept a fresh rider outside automatic dispatch radius")
    complete_order(admin, far_order)
    passed("expired_manual_assignment_refused_and_fresh_far_manual_assignment_allowed")

    # Only the fresh near rider should be eligible here. A stale GEO point at the old
    # location must not override a DB coordinate that has moved beyond dispatch radius.
    mysql(args, f"UPDATE rider SET last_location_at=UTC_TIMESTAMP(6)-INTERVAL {args.ttl_seconds + 5} SECOND "
                f"WHERE id={coords_id}")
    moved_id = create_rider(admin, report, suffix, "movedFar", NEAR)
    refresh_location(admin, moved_id, FAR)
    redis(args, "GEOADD", GEO_KEY, str(NEAR[0]), str(NEAR[1]), f"rider:{moved_id}")
    auto_id = create_rider(admin, report, suffix, "autoNear", NEAR)
    auto_order = create_order(admin, report, "automaticNear")
    auto_result = admin.request("PATCH", f"/api/v1/orders/{auto_order}/dispatch")
    stage1.expect_status(auto_result, 200, "automatic dispatch")
    auto_rider_id = auto_result.json("automatic dispatch").get("riderId")
    require(auto_rider_id == auto_id and auto_rider_id != moved_id,
            "automatic dispatch selected a stale-geometry/out-of-radius rider")
    complete_order(admin, auto_order)
    passed("automatic_dispatch_uses_valid_near_rider_and_database_coordinates")

    capacity_id = create_rider(admin, report, suffix, "capacity", NEAR)
    capacity_orders: list[int] = []
    for index in range(3):
        order_id = create_order(admin, report, f"capacity{index + 1}")
        assignment = admin.request("PATCH", f"/api/v1/orders/{order_id}/assign/{capacity_id}")
        stage1.expect_status(assignment, 200, "capacity boundary assignment")
        capacity_orders.append(order_id)
    redis(args, "GEOADD", GEO_KEY, str(NEAR[0]), str(NEAR[1]), f"rider:{capacity_id}")
    require(capacity_id not in {row.get("riderId") for row in nearby(admin)},
            "full rider remained eligible for nearby query")
    require(scalar(args, f"SELECT current_order_count FROM rider WHERE id={capacity_id}",
                   "full rider capacity") == "3", "three-order capacity boundary was not retained")
    complete_order(admin, capacity_orders[0])
    refresh_location(admin, capacity_id, NEAR)
    require(capacity_id in {row.get("riderId") for row in nearby(admin)},
            "fresh rider did not become nearby-eligible after capacity release")
    for order_id in capacity_orders[1:]:
        complete_order(admin, order_id)
    passed("three_order_capacity_exclusion_and_release")

    rider_lock_id = create_rider(admin, report, suffix, "riderLockWait", NEAR)
    rider_lock_order = create_order(admin, report, "riderLockWait")
    rider_lock_status = assignment_waiting_on_lock(
        admin, args, "rider", rider_lock_id, rider_lock_order, rider_lock_id)
    report["fixtures"]["riderLockWaitHttpStatus"] = rider_lock_status
    passed("rider_row_lock_wait_expiry_rejected_without_capacity_claim")

    order_lock_id = create_rider(admin, report, suffix, "orderLockWait", NEAR)
    order_lock_order = create_order(admin, report, "orderLockWait")
    order_lock_status = assignment_waiting_on_lock(
        admin, args, "delivery_order", order_lock_order, order_lock_order, order_lock_id)
    report["fixtures"]["orderLockWaitHttpStatus"] = order_lock_status
    passed("order_row_lock_wait_expiry_rolls_back_assignment_and_capacity")

    # Test recovery using a clean GEO key, two fresh valid riders (both below capacity),
    # stale candidates, and one syntactically valid but nonexistent member.
    recovery_fresh_id = create_rider(admin, report, suffix, "recoveryFresh", NEAR)
    refresh_location(admin, recovery_fresh_id)
    recovery_stale_id = create_rider(admin, report, suffix, "recoveryStale", NEAR)
    mysql(args, f"UPDATE rider SET last_location_at=UTC_TIMESTAMP(6)-INTERVAL {args.ttl_seconds + 5} SECOND "
                f"WHERE id={recovery_stale_id}")
    redis(args, "DEL", GEO_KEY)
    redis(args, "GEOADD", GEO_KEY, str(NEAR[0]), str(NEAR[1]), f"rider:{recovery_stale_id}")
    redis(args, "GEOADD", GEO_KEY, str(NEAR[0]), str(NEAR[1]), "rider:999999")
    before_fresh_at = db_location(args, recovery_fresh_id)
    recovered = wait_for_geo(args, {recovery_fresh_id},
                             {recovery_stale_id, 999999, expired_id, null_id, future_id, full_id, disabled_id},
                             args.recovery_wait_seconds)
    require(recovered, "GEO scheduled recovery did not restore only eligible riders within the bounded wait")
    require(db_location(args, recovery_fresh_id) == before_fresh_at,
            "GEO recovery unexpectedly refreshed the rider's database location time")
    report["fixtures"]["recoveryLastLocationAt"] = before_fresh_at
    passed("geo_recovery_restores_fresh_members_cleans_invalid_and_orphan_without_refresh")

    # Redis failover: stopping is limited to the explicitly named temporary verification
    # container. API location persistence must succeed while it is down; the scheduled
    # scan must reconnect and rebuild after it is restarted.
    down_id = create_rider(admin, report, suffix, "redisDown", NEAR)
    before_stop_info = redis(args, "INFO", "server")
    old_run_id = redis_info_value(before_stop_info, "run_id")
    stopped = False
    try:
        docker_control(args, "stop")
        stopped = True
        location_during_outage = admin.request("PUT", f"/api/v1/riders/{down_id}/location",
                                               {"longitude": NEAR[0], "latitude": NEAR[1]})
        stage1.expect_status(location_during_outage, 200, "location report during Redis outage")
        outage_timestamp = db_location(args, down_id)
        require(outage_timestamp != "NULL", "location report during Redis outage was not committed to MySQL")
    finally:
        if stopped:
            docker_control(args, "start")
    redis_after_start = wait_redis_info(args, args.recovery_wait_seconds)
    new_run_id = redis_info_value(redis_after_start, "run_id")
    require(old_run_id is not None and new_run_id is not None and old_run_id != new_run_id,
            "temporary Redis container did not report a new run_id after restart")
    # Remove the restarted instance's index so this assertion proves scheduled recovery,
    # rather than relying on Redis persistence or the after-commit callback.
    redis(args, "DEL", GEO_KEY)
    require(wait_for_geo(args, {down_id}, set(), args.recovery_wait_seconds),
            "GEO recovery did not reconnect and restore the location committed during Redis outage")
    require(db_location(args, down_id) == outage_timestamp,
            "Redis restart recovery unexpectedly changed last_location_at")
    report["fixtures"]["redisRunIdChanged"] = True
    passed("redis_outage_keeps_location_mysql_commit_and_geo_recovery_reconnects")

    report["elapsedSeconds"] = round(time.monotonic() - started, 3)
    report["finishedAt"] = datetime.now(timezone.utc).isoformat()
    return report


def docker_control(args: argparse.Namespace, action: str) -> None:
    if action not in {"stop", "start"}:
        raise LocationSmokeFailure("invalid Docker action")
    try:
        completed = subprocess.run(["docker", action, args.redis_container], check=False,
                                   capture_output=True, text=True, timeout=20)
    except (OSError, subprocess.TimeoutExpired):
        raise LocationSmokeFailure("Docker Redis control failed for the temporary verification container") from None
    if completed.returncode != 0:
        raise LocationSmokeFailure("Docker Redis control failed for the temporary verification container")


def wait_redis_info(args: argparse.Namespace, timeout_seconds: int) -> str:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        try:
            info = redis(args, "INFO", "server")
            if redis_info_value(info, "run_id"):
                return info
        except LocationSmokeFailure:
            pass
        time.sleep(min(args.poll_interval_seconds, max(0.05, deadline - time.monotonic())))
    raise LocationSmokeFailure("Redis did not become reachable within the bounded recovery wait")


def wait_for_geo(args: argparse.Namespace, must_exist: set[int], must_not_exist: set[int],
                 timeout_seconds: int) -> bool:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        try:
            members = set(redis(args, "ZRANGE", GEO_KEY, "0", "-1").splitlines())
            if (all(f"rider:{rider_id}" in members for rider_id in must_exist)
                    and all(f"rider:{rider_id}" not in members for rider_id in must_not_exist)):
                return True
        except LocationSmokeFailure:
            pass
        time.sleep(min(args.poll_interval_seconds, max(0.05, deadline - time.monotonic())))
    return False


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://localhost:18082")
    parser.add_argument("--mysql-container", default="delivery-mysql")
    parser.add_argument("--redis-container", default="delivery-redis-location-verify",
                        help="explicit temporary Redis container; name must contain verify")
    parser.add_argument("--database", required=True,
                        help="isolated database name: delivery_dispatch_verify_<suffix>")
    parser.add_argument("--ttl-seconds", type=int, default=20,
                        help="must match application dispatch.rider-location.ttl-seconds")
    parser.add_argument("--recovery-wait-seconds", type=int, default=10,
                        help="bounded wait for the scheduled GEO recovery scan")
    parser.add_argument("--poll-interval-seconds", type=float, default=0.25)
    parser.add_argument("--output", help="optional JSON report path; contains no credentials or tokens")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv or sys.argv[1:])
    started = time.monotonic()
    parsed = urlparse(args.base_url)
    report: dict[str, Any] = {
        "database": args.database,
        "baseUrl": parsed.scheme + "://" + parsed.netloc,
        "ttlSeconds": args.ttl_seconds,
        "startedAt": datetime.now(timezone.utc).isoformat(),
        "passed": [],
        "fixtures": {},
    }
    try:
        report = run_smoke(args)
        report["result"] = "passed"
    except stage1.SmokeFailure as error:
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
