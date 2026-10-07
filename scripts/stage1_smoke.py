#!/usr/bin/env python3
"""Stage-one HTTP integration smoke test for an isolated verification database.

This script intentionally does not create or delete databases. It creates API fixtures
in the explicitly named verification database and removes no fixture data.
"""

from __future__ import annotations

import argparse
import http.cookiejar
import json
import os
import re
import secrets
import subprocess
import sys
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlparse
from urllib.request import HTTPCookieProcessor, Request, build_opener


DATABASE_PATTERN = re.compile(r"delivery_dispatch_verify_[a-zA-Z0-9_]+\Z")
VERIFY_USERNAME_PATTERN = re.compile(r"verify-[a-zA-Z0-9_-]{1,55}\Z")
ORDER_REQUEST = {
    "merchantName": "Smoke便利店",
    "merchantPhone": "01012345678",
    "merchantAddress": "Smoke商家地址",
    "merchantLongitude": 116.3971280,
    "merchantLatitude": 39.9165270,
    "receiverName": "Smoke收件人",
    "receiverPhone": "13800000002",
    "receiverAddress": "Smoke收货地址",
    "receiverLongitude": 116.4000000,
    "receiverLatitude": 39.9200000,
    "deliveryFee": 5.00,
}


class SmokeFailure(RuntimeError):
    """A safe-to-report smoke failure; never carries request credentials or bodies."""


@dataclass
class HttpResult:
    status: int
    body: bytes

    def json(self, label: str) -> Any:
        try:
            return json.loads(self.body.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            raise SmokeFailure(f"{label}: response was not valid JSON") from None


class ApiClient:
    def __init__(self, base_url: str):
        self.base_url = base_url.rstrip("/")
        self.cookies = http.cookiejar.CookieJar()
        self.opener = build_opener(HTTPCookieProcessor(self.cookies))
        self.csrf_header: str | None = None
        self.csrf_token: str | None = None

    def request(self, method: str, path: str, body: Any = None,
                csrf: str | bool = "auto") -> HttpResult:
        method = method.upper()
        headers = {"Accept": "application/json"}
        data: bytes | None
        if isinstance(body, str):
            data = body.encode("utf-8")
            headers["Content-Type"] = "application/json"
        elif body is not None:
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json"
        else:
            data = None

        if method in {"POST", "PUT", "PATCH", "DELETE"}:
            if csrf == "auto":
                if not self.csrf_token:
                    self.fetch_csrf()
                assert self.csrf_header and self.csrf_token
                headers[self.csrf_header] = self.csrf_token
            elif csrf is True:
                headers["X-CSRF-TOKEN"] = "intentionally-invalid-stage1-token"
            elif csrf is False:
                pass
            elif isinstance(csrf, str):
                headers["X-CSRF-TOKEN"] = csrf

        request = Request(self.base_url + path, data=data, headers=headers, method=method)
        try:
            with self.opener.open(request, timeout=20) as response:
                result = HttpResult(response.status, response.read())
        except HTTPError as error:
            result = HttpResult(error.code, error.read())
        except (URLError, TimeoutError, OSError):
            raise SmokeFailure(f"{method} {path}: HTTP transport failed") from None

        if path == "/api/v1/auth/csrf" and result.status == 200:
            payload = result.json("CSRF endpoint")
            self.csrf_header = payload.get("headerName")
            self.csrf_token = payload.get("token")
            if not self.csrf_header or not self.csrf_token:
                raise SmokeFailure("CSRF endpoint: missing headerName/token")
        if method == "POST" and path == "/api/v1/auth/login" and result.status == 200:
            # Authentication rotates the session and invalidates the pre-login token.
            self.csrf_header = None
            self.csrf_token = None
        return result

    def fetch_csrf(self) -> None:
        result = self.request("GET", "/api/v1/auth/csrf")
        expect_status(result, 200, "fetch CSRF token")

    def login(self, username: str, password: str, expected_role: str) -> dict[str, Any]:
        self.fetch_csrf()
        result = self.request("POST", "/api/v1/auth/login",
                              {"username": username, "password": password})
        expect_status(result, 200, "login")
        payload = result.json("login")
        if payload.get("username") != username or payload.get("role") != expected_role:
            raise SmokeFailure("login: returned identity/role did not match expected account")
        self.fetch_csrf()
        return payload


def expect_status(result: HttpResult, expected: int, label: str) -> None:
    if result.status != expected:
        raise SmokeFailure(f"{label}: expected HTTP {expected}, got HTTP {result.status}")


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SmokeFailure(message)


def mysql(args: argparse.Namespace, sql: str) -> str:
    mysql_user = os.environ.get("MYSQL_USER")
    mysql_password = os.environ.get("MYSQL_PASSWORD")
    if not mysql_user or not mysql_password:
        raise SmokeFailure("set MYSQL_USER and MYSQL_PASSWORD for the isolated MySQL container")
    command = [
        "docker", "exec", "--env", "MYSQL_USER", "--env", "MYSQL_PASSWORD", args.mysql_container,
        "sh", "-c",
        'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql -u"$MYSQL_USER" "$1" --batch --skip-column-names -e "$2"',
        "stage1-smoke", args.database, sql,
    ]
    try:
        completed = subprocess.run(command, check=False, capture_output=True, text=True, timeout=30)
    except (OSError, subprocess.TimeoutExpired):
        raise SmokeFailure("MySQL helper failed; verify Docker, container, and credentials") from None
    if completed.returncode != 0:
        # Deliberately do not include stderr: client diagnostics can expose connection details.
        raise SmokeFailure("MySQL query failed; verify the isolated database and schema")
    return completed.stdout.strip()


def sql_scalar(args: argparse.Namespace, sql: str, label: str) -> str:
    output = mysql(args, sql)
    if not output:
        raise SmokeFailure(f"{label}: query returned no value")
    return output.splitlines()[0]


def account_id_from_response(accounts: Any, username: str) -> int | None:
    if not isinstance(accounts, list):
        return None
    for account in accounts:
        if isinstance(account, dict) and account.get("username") == username:
            return account.get("id")
    return None


def create_order_payload(**overrides: Any) -> dict[str, Any]:
    payload = dict(ORDER_REQUEST)
    payload.update(overrides)
    return payload


def run_smoke(args: argparse.Namespace) -> dict[str, Any]:
    started = time.monotonic()
    password = os.environ.get("STAGE1_ADMIN_PASSWORD")
    username = os.environ.get("STAGE1_ADMIN_USERNAME", "verify-admin")
    if not password:
        raise SmokeFailure("set STAGE1_ADMIN_PASSWORD for the verification administrator")
    if not VERIFY_USERNAME_PATTERN.fullmatch(username):
        raise SmokeFailure("STAGE1_ADMIN_USERNAME must start with verify- and use safe identifier characters")
    if not DATABASE_PATTERN.fullmatch(args.database):
        raise SmokeFailure("--database must match delivery_dispatch_verify_[a-zA-Z0-9_]+")
    parsed_url = urlparse(args.base_url)
    if (parsed_url.scheme not in {"http", "https"} or not parsed_url.netloc
            or parsed_url.username or parsed_url.query or parsed_url.fragment):
        raise SmokeFailure("--base-url must be an HTTP(S) URL without embedded credentials")

    tables = set(mysql(args, "SHOW TABLES").splitlines())
    required_tables = {"app_account", "delivery_order", "dispatch_outbox", "rider", "operation_log"}
    require(required_tables.issubset(tables), "verification database is missing required stage-one tables")
    require("operation_log_blocked" not in tables,
            "operation_log_blocked already exists; refusing to change verification database tables")
    selected_database = sql_scalar(args, "SELECT DATABASE()", "selected database")
    require(selected_database == args.database, "MySQL helper did not select the requested verification database")
    db_admin_id = int(sql_scalar(args,
                                 "SELECT id FROM app_account WHERE username='" + username + "' AND enabled=1 LIMIT 1",
                                 "verification administrator"))

    report: dict[str, Any] = {
        "database": args.database,
        "baseUrl": parsed_url.scheme + "://" + parsed_url.netloc,
        "startedAt": datetime.now(timezone.utc).isoformat(),
        "passed": [],
        "fixtures": {},
    }
    args._smoke_report = report

    def passed(case: str) -> None:
        report["passed"].append(case)

    admin = ApiClient(args.base_url)
    unauthenticated = admin.request("GET", "/api/v1/orders")
    expect_status(unauthenticated, 401, "anonymous business GET")
    passed("anonymous_business_get_401")

    identity = admin.login(username, password, "ADMIN")
    accounts_result = admin.request("GET", "/api/v1/accounts")
    expect_status(accounts_result, 200, "admin account list")
    accounts_json = accounts_result.json("account list")
    require(isinstance(accounts_json, list)
            and all("password" not in item and "passwordHash" not in item for item in accounts_json),
            "admin account list exposed credential fields")
    admin_account_id = account_id_from_response(accounts_json, username)
    require(admin_account_id == db_admin_id,
            "HTTP application account does not match the selected verification database; refusing fixture writes")
    report["fixtures"]["adminAccountId"] = admin_account_id
    require(identity["role"] == "ADMIN", "verification account is not an administrator")
    passed("admin_session_and_database_identity")

    suffix = secrets.token_hex(4)
    dispatcher_name = f"verify-dispatcher-{suffix}"
    dispatcher_password = secrets.token_urlsafe(18)
    dispatcher_result = admin.request("POST", "/api/v1/accounts", {
        "username": dispatcher_name,
        "password": dispatcher_password,
        "role": "DISPATCHER",
    })
    expect_status(dispatcher_result, 200, "create dispatcher account")
    dispatcher_json = dispatcher_result.json("create dispatcher account")
    require(dispatcher_json.get("username") == dispatcher_name
            and dispatcher_json.get("role") == "DISPATCHER"
            and "password" not in dispatcher_json
            and "passwordHash" not in dispatcher_json,
            "dispatcher response did not use the safe account DTO")
    dispatcher_id = dispatcher_json.get("id")
    require(isinstance(dispatcher_id, int), "dispatcher response did not include an account id")
    report["fixtures"]["dispatcherAccountId"] = dispatcher_id
    passed("account_create_safe_dto")

    dispatcher = ApiClient(args.base_url)
    dispatcher.login(dispatcher_name, dispatcher_password, "DISPATCHER")
    dispatcher_page = dispatcher.request("GET", "/api/v1/orders/page?page=1&size=20")
    expect_status(dispatcher_page, 200, "dispatcher order page")
    require(isinstance(dispatcher_page.json("dispatcher order page").get("records"), list),
            "dispatcher order page did not return records")
    passed("dispatcher_read_page_allowed")

    missing_csrf = dispatcher.request("POST", "/api/v1/orders", create_order_payload(), csrf=False)
    expect_status(missing_csrf, 403, "missing CSRF token")
    wrong_csrf = dispatcher.request("POST", "/api/v1/orders", create_order_payload(), csrf=True)
    expect_status(wrong_csrf, 403, "invalid CSRF token")
    passed("missing_and_invalid_csrf_rejected")

    malformed = admin.request("POST", "/api/v1/orders", "{not-json")
    expect_status(malformed, 400, "malformed order JSON")
    illegal_coordinates = admin.request("POST", "/api/v1/orders",
                                        create_order_payload(merchantLongitude=181))
    expect_status(illegal_coordinates, 400, "out-of-range order coordinates")
    bad_fee = admin.request("POST", "/api/v1/orders", create_order_payload(deliveryFee=1.234))
    expect_status(bad_fee, 400, "fee precision validation")
    too_large_page = admin.request("GET", "/api/v1/orders/page?page=1&size=101")
    expect_status(too_large_page, 400, "maximum page size")
    passed("malformed_input_coordinate_fee_and_page_validation")

    # Use the dispatcher-authorized create route and try to inject server-owned fields.
    order_payload = create_order_payload(
        id=987654321,
        orderNo="SPOOFED-ORDER-NO",
        status=3,
        riderId=987654321,
        createdAt="2001-01-01T00:00:00",
        updatedAt="2001-01-01T00:00:00",
    )
    orders_before_spoof = int(sql_scalar(args, "SELECT COUNT(*) FROM delivery_order", "orders before spoof test"))
    created_order_result = dispatcher.request("POST", "/api/v1/orders", order_payload)
    if created_order_result.status == 400:
        orders_after_spoof = int(sql_scalar(args, "SELECT COUNT(*) FROM delivery_order", "orders after spoof test"))
        require(orders_after_spoof == orders_before_spoof,
                "rejected spoofed order request nevertheless inserted a row")
        passed("order_create_rejects_server_managed_fields")
        clean_order_result = dispatcher.request("POST", "/api/v1/orders", create_order_payload())
        expect_status(clean_order_result, 200, "dispatcher order create with whitelisted fields")
        order = clean_order_result.json("dispatcher order create")
    else:
        expect_status(created_order_result, 200, "dispatcher order create")
        order = created_order_result.json("dispatcher order create")
        order_id_candidate = order.get("id")
        order_no_candidate = order.get("orderNo")
        require(isinstance(order_id_candidate, int) and order_id_candidate != 987654321,
                "client controlled the order id")
        require(order.get("status") == 0 and order.get("riderId") is None,
                "client controlled server-owned order status or rider id")
        require(isinstance(order_no_candidate, str) and order_no_candidate != "SPOOFED-ORDER-NO",
                "client controlled the generated order number")
        require(re.fullmatch(r"ORD[0-9a-fA-F]{29}", order_no_candidate) is not None,
                "generated order number format was unexpected")

    order_id = order.get("id")
    order_no = order.get("orderNo")
    require(isinstance(order_id, int) and order_id > 0, "created order id was invalid")
    require(order.get("status") == 0 and order.get("riderId") is None,
            "created order did not start in PENDING_DISPATCH without a rider")
    require(isinstance(order_no, str) and re.fullmatch(r"ORD[0-9a-fA-F]{29}", order_no),
            "generated order number format was unexpected")
    db_order_row = sql_scalar(args,
                              f"SELECT CONCAT(status, '|', IFNULL(rider_id,'NULL'), '|', order_no, '|', "
                              f"IF(created_at > '2001-01-02 00:00:00',1,0)) "
                              f"FROM delivery_order WHERE id={order_id}",
                              "created order database verification")
    row_fields = db_order_row.split("|")
    require(len(row_fields) == 4 and row_fields[0] == "0" and row_fields[1] == "NULL"
            and row_fields[2] == order_no and row_fields[3] == "1",
            "database order row contains client-controlled server-owned fields")
    report["fixtures"]["orderId"] = order_id
    if created_order_result.status != 400:
        passed("order_create_ignores_server_managed_fields")

    invalid_fee_update = admin.request("PATCH", f"/api/v1/orders/{order_id}/fee",
                                       {"deliveryFee": 4.567})
    expect_status(invalid_fee_update, 400, "fee update precision validation")
    passed("fee_update_rejects_more_than_two_decimal_places")

    page_query = urlencode({"page": 1, "size": 20, "status": 0, "orderNo": order_no})
    filtered = dispatcher.request("GET", "/api/v1/orders/page?" + page_query)
    expect_status(filtered, 200, "state/order number order page")
    filtered_json = filtered.json("state/order number order page")
    require(filtered_json.get("total", 0) >= 1
            and any(record.get("id") == order_id for record in filtered_json.get("records", [])),
            "order pagination did not filter by state and order number")
    passed("order_pagination_filters_state_and_order_no")

    # Dispatcher may read/create/work orders, but all administration and audit routes are denied.
    denied_requests = [
        ("PATCH", f"/api/v1/orders/{order_id}/fee", {"deliveryFee": 4.00}),
        ("DELETE", f"/api/v1/orders/{order_id}?reason=dispatcher-check", None),
        ("POST", "/api/v1/riders", {"riderNo": "VERIFY-DENIED", "name": "Denied", "phone": "13900000001"}),
        ("PUT", "/api/v1/riders/1/location", {"longitude": 116.4, "latitude": 39.9}),
        ("PATCH", "/api/v1/riders/1/disable", None),
        ("PATCH", "/api/v1/riders/1/enable", None),
        ("GET", "/api/v1/operation-logs/page?page=1&size=10", None),
    ]
    for method, path, body in denied_requests:
        denied = dispatcher.request(method, path, body)
        expect_status(denied, 403, "dispatcher forbidden " + method + " " + path.split("?")[0])
    passed("dispatcher_admin_routes_and_logs_forbidden")

    rider_no = "VERIFY-" + suffix.upper()
    rider_phone = "139" + str(10000000 + int(suffix, 16) % 89999999)
    rider_result = admin.request("POST", "/api/v1/riders", {
        "riderNo": rider_no,
        "name": "Smoke骑手",
        "phone": rider_phone,
        "longitude": 116.3971280,
        "latitude": 39.9165270,
    })
    expect_status(rider_result, 200, "admin rider create")
    rider = rider_result.json("admin rider create")
    rider_id = rider.get("id")
    require(isinstance(rider_id, int), "rider response did not include an id")
    report["fixtures"]["riderId"] = rider_id

    location_result = admin.request("PUT", f"/api/v1/riders/{rider_id}/location",
                                    {"longitude": 116.3971280, "latitude": 39.9165270})
    expect_status(location_result, 200, "admin rider location update")
    disabled_rider = admin.request("PATCH", f"/api/v1/riders/{rider_id}/disable")
    expect_status(disabled_rider, 200, "admin disable rider")
    require(disabled_rider.json("disable rider").get("status") == 3, "rider did not enter disabled state")
    enabled_rider = admin.request("PATCH", f"/api/v1/riders/{rider_id}/enable")
    expect_status(enabled_rider, 200, "admin enable rider")
    require(enabled_rider.json("enable rider").get("status") == 1, "rider did not return to available state")
    passed("admin_rider_create_location_disable_enable")

    assigned = admin.request("PATCH", f"/api/v1/orders/{order_id}/assign/{rider_id}")
    expect_status(assigned, 200, "manual order assignment")
    require(assigned.json("manual order assignment").get("status") == 1,
            "manual assignment did not move order to ASSIGNED")
    capacity_one = sql_scalar(args, f"SELECT current_order_count FROM rider WHERE id={rider_id}",
                              "capacity after assignment")
    require(capacity_one == "1", "rider capacity did not increase from 0 to 1")
    started_order = admin.request("PATCH", f"/api/v1/orders/{order_id}/start-delivery")
    expect_status(started_order, 200, "start delivery")
    require(started_order.json("start delivery").get("status") == 2,
            "start delivery did not move order to DELIVERING")
    completed_order = admin.request("PATCH", f"/api/v1/orders/{order_id}/complete")
    expect_status(completed_order, 200, "complete delivery")
    require(completed_order.json("complete delivery").get("status") == 3,
            "complete did not move order to COMPLETED")
    capacity_zero = sql_scalar(args, f"SELECT current_order_count FROM rider WHERE id={rider_id}",
                               "capacity after completion")
    require(capacity_zero == "0", "rider capacity did not return from 1 to 0")
    passed("manual_assign_start_complete_capacity_0_1_0")

    delete_reason = "stage1-smoke-delete-" + suffix
    deleted = admin.request("DELETE", f"/api/v1/orders/{order_id}?" + urlencode({"reason": delete_reason}))
    expect_status(deleted, 204, "delete completed order")
    audit_row = sql_scalar(args,
                           "SELECT CONCAT(actor, '|', reason, '|', target_type, '|', target_id, '|', "
                           "IFNULL(JSON_UNQUOTE(JSON_EXTRACT(before_json,'$.status')),'NULL'), '|', "
                           "IF(after_json IS NULL,1,0)) FROM operation_log "
                           f"WHERE action='DELETE' AND target_id={order_id} ORDER BY id DESC LIMIT 1",
                           "delete audit verification")
    require(audit_row == f"{username}|{delete_reason}|ORDER|{order_id}|3|1",
            "DELETE audit row did not retain actor, reason, completed snapshot, and null after snapshot")
    report["fixtures"]["deletedOrderId"] = order_id
    passed("completed_order_delete_audit_actor_reason_before_snapshot")

    # Management actions stay available in every state, as required by this console.
    for state in (0, 1, 2, 4):
        fixture = admin.request("POST", "/api/v1/orders", create_order_payload())
        expect_status(fixture, 200, f"create state {state} management fixture")
        fixture_id = fixture.json("management fixture")["id"]
        if state in (1, 2):
            expect_status(admin.request("PATCH", f"/api/v1/orders/{fixture_id}/assign/{rider_id}"),
                          200, "assign management fixture")
        if state == 2:
            expect_status(admin.request("PATCH", f"/api/v1/orders/{fixture_id}/start-delivery"),
                          200, "start management fixture")
        if state == 4:
            expect_status(admin.request("PATCH", f"/api/v1/orders/{fixture_id}/cancel"),
                          200, "cancel management fixture")
        fee_updated = admin.request("PATCH", f"/api/v1/orders/{fixture_id}/fee", {"deliveryFee": 0})
        expect_status(fee_updated, 200, f"fee update state {state}")
        require(fee_updated.json("fee update")["deliveryFee"] == 0, "zero fee did not persist")
        expect_status(admin.request("DELETE", f"/api/v1/orders/{fixture_id}?reason=stage1-all-state-delete"),
                      204, f"delete state {state}")
        require(sql_scalar(args, f"SELECT current_order_count FROM rider WHERE id={rider_id}",
                           "capacity after management deletion") == "0",
                "active-order deletion did not release rider capacity")
        require(sql_scalar(args, f"SELECT COUNT(*) FROM operation_log WHERE target_type='ORDER' "
                           f"AND target_id={fixture_id} AND action='DELETE' "
                           f"AND JSON_EXTRACT(before_json,'$.status')={state}", "all-state delete audit") == "1",
                "all-state deletion did not preserve its status snapshot")
    passed("pending_assigned_delivering_canceled_fee_update_and_delete_capacity_audit")

    disabled_account = admin.request("PATCH", f"/api/v1/accounts/{dispatcher_id}/enabled", {"enabled": False})
    expect_status(disabled_account, 200, "disable dispatcher account")
    old_session = dispatcher.request("GET", "/api/v1/orders/page?page=1&size=1")
    expect_status(old_session, 401, "disabled account existing session")
    passed("disabled_account_revokes_existing_session")

    # Keep one order in the app database and confirm the same id exists in the selected
    # verification database immediately before temporarily renaming the audit table.
    rollback_probe_fixture = admin.request("POST", "/api/v1/orders", create_order_payload())
    expect_status(rollback_probe_fixture, 200, "create transaction rollback reference order")
    rollback_probe_id = rollback_probe_fixture.json("rollback reference order").get("id")
    require(isinstance(rollback_probe_id, int) and rollback_probe_id > 0,
            "rollback reference order did not include a valid id")
    report["fixtures"]["rollbackReferenceOrderId"] = rollback_probe_id
    exists_in_target_db = sql_scalar(args,
                                     f"SELECT COUNT(*) FROM delivery_order WHERE id={rollback_probe_id}",
                                     "isolated database identity check")
    require(exists_in_target_db == "1",
            "HTTP-created order is not present in the selected verification DB; refusing table rename")
    rider_db_match = sql_scalar(args, f"SELECT COUNT(*) FROM rider WHERE id={rider_id}",
                                "isolated database rider check")
    require(rider_db_match == "1", "HTTP-created rider is not present in the selected verification DB")

    order_before = int(sql_scalar(args, "SELECT COUNT(*) FROM delivery_order", "orders before rollback probe"))
    outbox_before = int(sql_scalar(args, "SELECT COUNT(*) FROM dispatch_outbox", "outbox before rollback probe"))
    renamed = False
    try:
        mysql(args, "RENAME TABLE operation_log TO operation_log_blocked")
        renamed = True
        rollback_probe = admin.request("POST", "/api/v1/orders", create_order_payload())
        expect_status(rollback_probe, 500, "audit failure rollback probe")
        passed("audit_failure_returns_500")
    finally:
        if renamed:
            mysql(args, "RENAME TABLE operation_log_blocked TO operation_log")
    order_after = int(sql_scalar(args, "SELECT COUNT(*) FROM delivery_order", "orders after rollback probe"))
    outbox_after = int(sql_scalar(args, "SELECT COUNT(*) FROM dispatch_outbox", "outbox after rollback probe"))
    require(order_after == order_before and outbox_after == outbox_before,
            "audit failure left an order or outbox row; transaction rollback failed")
    passed("audit_failure_rolls_back_order_and_outbox")

    extra = admin.request("POST", "/api/v1/orders", create_order_payload())
    expect_status(extra, 200, "second summary fixture")
    summary_result = admin.request("GET", "/api/v1/dashboard/summary")
    expect_status(summary_result, 200, "dashboard summary")
    summary = summary_result.json("dashboard summary")
    db_total = int(sql_scalar(args, "SELECT COUNT(*) FROM delivery_order", "global order count"))
    db_pending = int(sql_scalar(args, "SELECT COUNT(*) FROM delivery_order WHERE status=0", "pending order count"))
    db_available = int(sql_scalar(args, "SELECT COUNT(*) FROM rider WHERE status=1 AND current_order_count<3",
                                  "available rider count"))
    first_page = admin.request("GET", "/api/v1/orders/page?page=1&size=1")
    expect_status(first_page, 200, "single-record order page")
    require(summary.get("totalOrders") == db_total and db_total > len(first_page.json("single page")["records"]),
            "dashboard summary incorrectly reflects only the visible page")
    require(summary.get("pendingOrders") == db_pending and summary.get("availableRiders") == db_available,
            "dashboard state/capacity totals were incorrect")
    time_query = urlencode({"page": 1, "size": 20, "createdFrom": "2001-01-01T00:00:00",
                            "createdTo": "2099-01-01T00:00:00"})
    time_filtered = admin.request("GET", "/api/v1/orders/page?" + time_query)
    expect_status(time_filtered, 200, "ISO date-time filtering")
    require(time_filtered.json("date filtering")["total"] == db_total, "ISO date-time filtering lost fixture orders")
    passed("global_dashboard_summary_and_iso_datetime_filter")

    report["elapsedSeconds"] = round(time.monotonic() - started, 3)
    report["finishedAt"] = datetime.now(timezone.utc).isoformat()
    return report


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://localhost:18081")
    parser.add_argument("--mysql-container", default="delivery-mysql")
    parser.add_argument("--database", required=True,
                        help="isolated database name: delivery_dispatch_verify_<suffix>")
    parser.add_argument("--output", help="optional JSON report path; contains no credentials or tokens")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv or sys.argv[1:])
    started = time.monotonic()
    report: dict[str, Any] = {
        "database": args.database,
        "baseUrl": urlparse(args.base_url).scheme + "://" + urlparse(args.base_url).netloc,
        "startedAt": datetime.now(timezone.utc).isoformat(),
        "passed": [],
        "fixtures": {},
    }
    try:
        report = run_smoke(args)
        report["result"] = "passed"
    except SmokeFailure as error:
        report = getattr(args, "_smoke_report", report)
        report["result"] = "failed"
        report["failure"] = str(error)
    except Exception as error:
        # Keep reports useful but do not serialize request objects, environment, or traceback.
        report = getattr(args, "_smoke_report", report)
        report["result"] = "failed"
        report["failure"] = f"unexpected smoke harness error ({type(error).__name__}); inspect the script and rerun"

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
