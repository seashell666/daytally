#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
日刻局域网同步服务（Python 标准库 + SQLite，无第三方依赖）。

设计要点：
- 计数类项目按“动作”合并：add/remove 各是一条带事件 id 和时间戳的事件，服务端求和。
  因此 A 手机加 3 组、B 手机加 1 组，最终是 4 组，不会因为“最后同步的整值覆盖”丢增量。
- 身高是设置值，按 occurred_at 取最新；配置（名称/单位/每组/目标/纵轴上上限）同样取最新。
- reset 是一条截止时间：该时间之前的事件不再计入。
- 第一次连接时，如果服务端空库，客户端整库快照作为基线播种；服务端已有数据则只拉取，
  不拿旧手机的历史数据再播一次，避免两台手机历史被重复相加。
"""
import hmac
import json
import os
import re
import sqlite3
import sys
import threading
import time
import traceback
import uuid
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(os.environ.get("PORT", "8787"))
TOKEN = os.environ.get("DAYTALLY_TOKEN", "").strip()
DB_PATH = os.environ.get("DAYTALLY_DB", "/data/daytally.db")
MAX_BODY = 2 * 1024 * 1024

METRICS = ("height", "push", "horse", "pullup", "custom")
DAY_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")

# 所有已连接的手机共享一个版本号：任何一次同步写入成功后 +1，
# 正在长连接（SSE）上的其它手机立刻收到 change，马上去拉最新快照。
STATE_CONDITION = threading.Condition()
STATE_VERSION = 0


def notify_change() -> None:
    global STATE_VERSION
    with STATE_CONDITION:
        STATE_VERSION += 1
        STATE_CONDITION.notify_all()

DEFAULTS = {
    "height": {"name": "身高", "unit": "厘米", "per": 0, "goal": 0, "maxGroups": 0},
    "push": {"name": "俯卧撑", "unit": "个", "per": 12, "goal": 10000, "maxGroups": 32},
    "horse": {"name": "蹲马步", "unit": "分钟", "per": 11, "goal": 10000, "maxGroups": 32},
    "pullup": {"name": "引体向上", "unit": "个", "per": 12, "goal": 10000, "maxGroups": 32},
    "custom": {"name": "自定义", "unit": "个", "per": 10, "goal": 10000, "maxGroups": 32},
}

SCHEMA = """
CREATE TABLE IF NOT EXISTS meta (
  k TEXT PRIMARY KEY,
  v TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS events (
  id TEXT PRIMARY KEY,
  client_id TEXT NOT NULL,
  metric TEXT NOT NULL,
  day TEXT,
  kind TEXT NOT NULL,
  delta REAL NOT NULL DEFAULT 0,
  value REAL,
  occurred_at INTEGER NOT NULL,
  seq INTEGER NOT NULL DEFAULT 0,
  received_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_events_metric_day ON events(metric, day);
CREATE INDEX IF NOT EXISTS idx_events_metric_time ON events(metric, occurred_at);
CREATE TABLE IF NOT EXISTS configs (
  metric TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  unit TEXT NOT NULL,
  per INTEGER NOT NULL,
  goal INTEGER NOT NULL,
  max_groups INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS resets (
  metric TEXT PRIMARY KEY,
  reset_at INTEGER NOT NULL
);
"""


def now_ms() -> int:
    return int(time.time() * 1000)


def connect() -> sqlite3.Connection:
    conn = sqlite3.connect(DB_PATH, timeout=20)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA synchronous=NORMAL")
    return conn


def init_db() -> None:
    os.makedirs(os.path.dirname(DB_PATH) or ".", exist_ok=True)
    conn = connect()
    try:
        conn.executescript(SCHEMA)
        conn.execute("INSERT OR IGNORE INTO meta(k, v) VALUES('seeded', '0')")
        conn.commit()
    finally:
        conn.close()


def is_seeded(conn: sqlite3.Connection) -> bool:
    row = conn.execute("SELECT v FROM meta WHERE k='seeded'").fetchone()
    return bool(row and str(row["v"]) == "1")


def set_seeded(conn: sqlite3.Connection) -> None:
    conn.execute("UPDATE meta SET v='1' WHERE k='seeded'")


def default_config(metric: str) -> dict:
    return dict(DEFAULTS[metric])


def valid_day(value) -> bool:
    if not isinstance(value, str) or not DAY_RE.match(value):
        return False
    try:
        datetime.strptime(value, "%Y-%m-%d")
        return True
    except ValueError:
        return False


def as_int(value, fallback: int) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return fallback


def validate_event(raw, client_id: str) -> dict:
    if not isinstance(raw, dict):
        raise ValueError("event must be object")
    eid = str(raw.get("id") or "").strip()
    if not eid or len(eid) > 128:
        raise ValueError("event id missing or too long")
    metric = str(raw.get("metric") or "")
    if metric not in METRICS:
        raise ValueError("unknown metric")
    kind = str(raw.get("kind") or "")
    if kind not in ("add", "remove", "set", "config", "reset"):
        raise ValueError("unknown event kind")

    occurred = as_int(raw.get("occurredAt"), now_ms())
    if occurred <= 0:
        occurred = now_ms()
    seq = as_int(raw.get("seq"), 0)

    day = raw.get("day")
    if kind in ("add", "remove", "set"):
        if not valid_day(day):
            raise ValueError("invalid day")

    delta = 0.0
    value = None
    config = None

    if kind == "add":
        delta = 1.0
    elif kind == "remove":
        delta = -1.0
    elif kind == "set":
        try:
            value = float(raw.get("value"))
        except (TypeError, ValueError):
            raise ValueError("invalid value")
        if metric == "height" and not (0 <= value <= 227):
            raise ValueError("height out of range")
    elif kind == "config":
        cfg = raw.get("config")
        if not isinstance(cfg, dict):
            raise ValueError("config missing")
        name = str(cfg.get("name") or "").strip()[:8]
        unit = str(cfg.get("unit") or "").strip()[:8]
        if not name or not unit:
            raise ValueError("config name/unit empty")
        per = as_int(cfg.get("per"), DEFAULTS[metric]["per"])
        goal = as_int(cfg.get("goal"), DEFAULTS[metric]["goal"])
        max_groups = as_int(cfg.get("maxGroups"), DEFAULTS[metric]["maxGroups"])
        if per < 1 or per > 999 or goal < 0 or max_groups < 1 or max_groups > 999:
            raise ValueError("config out of range")
        config = {"name": name, "unit": unit, "per": per, "goal": goal, "maxGroups": max_groups}
    # reset 只需要 metric + occurredAt

    return {
        "id": eid,
        "client_id": client_id[:128],
        "metric": metric,
        "day": day if kind in ("add", "remove", "set") else None,
        "kind": kind,
        "delta": delta,
        "value": value,
        "config": config,
        "occurred_at": occurred,
        "seq": seq,
    }


def upsert_config(conn: sqlite3.Connection, metric: str, cfg: dict, updated_at: int) -> None:
    conn.execute(
        """
        INSERT INTO configs(metric, name, unit, per, goal, max_groups, updated_at)
        VALUES(?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(metric) DO UPDATE SET
          name=excluded.name, unit=excluded.unit, per=excluded.per,
          goal=excluded.goal, max_groups=excluded.max_groups, updated_at=excluded.updated_at
        WHERE excluded.updated_at >= configs.updated_at
        """,
        (metric, cfg["name"], cfg["unit"], cfg["per"], cfg["goal"], cfg["maxGroups"], updated_at),
    )


def insert_event(conn: sqlite3.Connection, e: dict, received_at: int) -> None:
    conn.execute(
        """
        INSERT OR IGNORE INTO events
          (id, client_id, metric, day, kind, delta, value, occurred_at, seq, received_at)
        VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        (
            e["id"], e["client_id"], e["metric"], e.get("day"), e["kind"],
            e.get("delta", 0.0), e.get("value"), e["occurred_at"], e.get("seq", 0), received_at,
        ),
    )


def apply_event(conn: sqlite3.Connection, e: dict, received_at: int) -> None:
    before = conn.total_changes
    insert_event(conn, e, received_at)
    if conn.total_changes == before:
        return  # duplicate id: 已经处理过，直接算 ack
    if e["kind"] == "config" and e.get("config"):
        upsert_config(conn, e["metric"], e["config"], e["occurred_at"])
    elif e["kind"] == "reset":
        conn.execute(
            """
            INSERT INTO resets(metric, reset_at) VALUES(?, ?)
            ON CONFLICT(metric) DO UPDATE SET reset_at=MAX(resets.reset_at, excluded.reset_at)
            """,
            (e["metric"], e["occurred_at"]),
        )


def seed_from_snapshot(conn: sqlite3.Connection, payload: dict, received_at: int) -> bool:
    snapshot = payload.get("snapshot")
    if not isinstance(snapshot, dict):
        return False
    metrics = snapshot.get("metrics")
    if not isinstance(metrics, dict):
        return False

    for metric in METRICS:
        raw = metrics.get(metric) or {}
        if not isinstance(raw, dict):
            raw = {}
        cfg = {
            "name": str(raw.get("name") or DEFAULTS[metric]["name"])[:8],
            "unit": str(raw.get("unit") or DEFAULTS[metric]["unit"])[:8],
            "per": as_int(raw.get("per"), DEFAULTS[metric]["per"]),
            "goal": as_int(raw.get("goal"), DEFAULTS[metric]["goal"]),
            "maxGroups": as_int(raw.get("maxGroups"), DEFAULTS[metric]["maxGroups"]),
        }
        upsert_config(conn, metric, cfg, received_at)
        data = raw.get("data")
        if not isinstance(data, dict):
            continue
        for day, value in data.items():
            if not valid_day(day):
                continue
            try:
                v = float(value)
            except (TypeError, ValueError):
                continue
            if metric == "height" and not (0 <= v <= 227):
                continue
            eid = "seed:" + metric + ":" + str(day) + ":" + uuid.uuid4().hex[:8]
            e = {
                "id": eid, "client_id": "server-seed", "metric": metric, "day": day,
                "kind": "seed", "delta": 0.0, "value": v, "occurred_at": received_at,
                "seq": 0,
            }
            insert_event(conn, e, received_at)

    # 快照已经包含这批事件的效果，把它们按 id 记为 ignored，下次重传不会再加一遍
    for raw in payload.get("events") or []:
        if not isinstance(raw, dict):
            continue
        eid = str(raw.get("id") or "").strip()
        if not eid:
            continue
        e = {
            "id": eid, "client_id": "server-seed", "metric": str(raw.get("metric") or "")[:32],
            "day": None, "kind": "ignored", "delta": 0.0, "value": None,
            "occurred_at": received_at, "seq": 0,
        }
        insert_event(conn, e, received_at)

    set_seeded(conn)
    return True


def reset_at(conn: sqlite3.Connection, metric: str) -> int:
    row = conn.execute("SELECT reset_at FROM resets WHERE metric=?", (metric,)).fetchone()
    return int(row["reset_at"]) if row else 0


def build_snapshot(conn: sqlite3.Connection) -> dict:
    metrics = {}
    for metric in METRICS:
        row = conn.execute("SELECT * FROM configs WHERE metric=?", (metric,)).fetchone()
        cfg = default_config(metric)
        if row:
            cfg = {
                "name": row["name"], "unit": row["unit"], "per": row["per"],
                "goal": row["goal"], "maxGroups": row["max_groups"],
            }
        cutoff = reset_at(conn, metric)
        data = {}

        if metric == "height":
            rows = conn.execute(
                """
                SELECT day, value FROM events
                WHERE metric=? AND kind IN ('seed','set') AND occurred_at >= ?
                ORDER BY day, occurred_at, id
                """,
                (metric, cutoff),
            ).fetchall()
            for r in rows:
                if r["day"] and r["value"] is not None:
                    data[str(r["day"])] = float(r["value"])
        else:
            rows = conn.execute(
                """
                SELECT day, SUM(CASE WHEN kind='seed' THEN value ELSE delta END) AS v
                FROM events
                WHERE metric=? AND kind IN ('seed','add','remove') AND occurred_at >= ?
                GROUP BY day
                """,
                (metric, cutoff),
            ).fetchall()
            for r in rows:
                if not r["day"]:
                    continue
                v = int(round(float(r["v"] or 0)))
                data[str(r["day"])] = max(0, min(99, v))

        metrics[metric] = {**cfg, "data": data}

    rev_row = conn.execute("SELECT COALESCE(MAX(received_at), 0) AS r FROM events").fetchone()
    return {
        "ok": True,
        "version": "1.2.12",
        "revision": int(rev_row["r"] or 0),
        "serverTime": now_ms(),
        "seeded": is_seeded(conn),
        "metrics": metrics,
    }


def process_sync(payload: dict) -> dict:
    if not isinstance(payload, dict):
        raise ValueError("body must be object")
    client_id = str(payload.get("clientId") or "unknown")[:128]
    received = now_ms()
    conn = connect()
    try:
        conn.execute("BEGIN IMMEDIATE")
        seeded_now = False
        if not is_seeded(conn) and payload.get("seed") is True:
            seeded_now = seed_from_snapshot(conn, payload, received)

        events = payload.get("events") or []
        if not isinstance(events, list):
            raise ValueError("events must be array")

        acked = []
        if not seeded_now:
            for raw in events:
                e = validate_event(raw, client_id)
                apply_event(conn, e, received)
                acked.append(e["id"])
            if events and not is_seeded(conn):
                set_seeded(conn)

        if seeded_now:
            acked = []
            for raw in events:
                if isinstance(raw, dict) and raw.get("id"):
                    acked.append(str(raw["id"]))

        conn.commit()
        snap = build_snapshot(conn)
        snap["seeded"] = bool(seeded_now)
        snap["acked"] = acked
        # 只有真的有新事件（或第一次播种）才唤醒别的手机；重复上报不会造成风暴
        if seeded_now or events:
            notify_change()
        return snap
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()


class Handler(BaseHTTPRequestHandler):
    server_version = "DayTallySync/1.2.12"

    def _auth(self) -> bool:
        if not TOKEN:
            return True
        got = self.headers.get("Authorization", "")
        return hmac.compare_digest(got, "Bearer " + TOKEN)

    def _json(self, code: int, obj: dict) -> None:
        body = json.dumps(obj, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _read_body(self) -> dict:
        length = int(self.headers.get("Content-Length", "0") or "0")
        if length <= 0:
            raise ValueError("empty body")
        if length > MAX_BODY:
            raise ValueError("body too large")
        raw = self.rfile.read(length)
        return json.loads(raw.decode("utf-8"))

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Headers", "Content-Type, Authorization")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.end_headers()

    def do_GET(self):
        try:
            if self.path == "/health":
                conn = connect()
                try:
                    seeded = is_seeded(conn)
                finally:
                    conn.close()
                self._json(200, {"ok": True, "service": "daytally-sync", "version": "1.2.12", "seeded": seeded})
                return
            if self.path == "/api/v1/stream":
                if not self._auth():
                    self._json(401, {"ok": False, "error": "unauthorized"})
                    return
                # SSE：手机端保持一条极轻的长连接；有写入就推 change，平时只发心跳。
                self.send_response(200)
                self.send_header("Content-Type", "text/event-stream; charset=utf-8")
                self.send_header("Cache-Control", "no-cache")
                self.send_header("Connection", "keep-alive")
                self.end_headers()
                self.wfile.write(b"retry: 3000\n\n")
                self.wfile.flush()
                last = -1
                try:
                    while True:
                        with STATE_CONDITION:
                            version = STATE_VERSION
                            if version == last:
                                STATE_CONDITION.wait(timeout=25)
                                version = STATE_VERSION
                        if version != last:
                            last = version
                            data = json.dumps(
                                {"version": version, "serverTime": now_ms()},
                                ensure_ascii=False, separators=(",", ":"),
                            )
                            self.wfile.write(
                                ("event: change\ndata: " + data + "\n\n").encode("utf-8")
                            )
                        else:
                            self.wfile.write(b": ping\n\n")
                        self.wfile.flush()
                except (BrokenPipeError, ConnectionResetError, OSError):
                    return
            if self.path == "/api/v1/snapshot":
                if not self._auth():
                    self._json(401, {"ok": False, "error": "unauthorized"})
                    return
                conn = connect()
                try:
                    self._json(200, build_snapshot(conn))
                finally:
                    conn.close()
                return
            self._json(404, {"ok": False, "error": "not found"})
        except Exception as exc:
            print("GET error:", exc, file=sys.stderr)
            traceback.print_exc()
            self._json(500, {"ok": False, "error": "server error"})

    def do_POST(self):
        if self.path != "/api/v1/sync":
            self._json(404, {"ok": False, "error": "not found"})
            return
        if not self._auth():
            self._json(401, {"ok": False, "error": "unauthorized"})
            return
        try:
            payload = self._read_body()
            result = process_sync(payload)
            self._json(200, result)
        except ValueError as exc:
            self._json(400, {"ok": False, "error": str(exc)})
        except Exception as exc:
            print("POST error:", exc, file=sys.stderr)
            traceback.print_exc()
            self._json(500, {"ok": False, "error": "server error"})

    def log_message(self, fmt, *args):
        print("[%s] %s" % (self.log_date_time_string(), fmt % args), flush=True)


class Server(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True


def main():
    init_db()
    print("DayTally sync listening on 0.0.0.0:%d, db=%s" % (PORT, DB_PATH), flush=True)
    Server(("0.0.0.0", PORT), Handler).serve_forever()


if __name__ == "__main__":
    main()
