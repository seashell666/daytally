#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""日刻 · 数据中台（Windows 桌面客户端）

本地只做两件事：
1. 托管一个本地页面（pywebview 打开它）；
2. 在本机把页面请求转给树莓派的同步服务，避免把 Token 写进页面、也避免跨域。
"""
from __future__ import annotations

import argparse
import json
import logging
import mimetypes
import os
import re
import sys
import threading
import time
import uuid
import webbrowser
from datetime import date, datetime, timedelta
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import unquote, urlparse
from urllib.request import Request, urlopen

APP_NAME = "日刻 · 数据中台"
APP_VERSION = "1.4.1"
BASE_DIR = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent))
WEB_DIR = BASE_DIR / "web"
CONFIG_DIR = Path(os.environ.get("APPDATA", Path.home())) / "DayTallyDesktop"
CONFIG_PATH = CONFIG_DIR / "config.json"
CACHE_PATH = CONFIG_DIR / "snapshot-cache.json"
LOG_PATH = CONFIG_DIR / "desktop.log"
DEFAULT_BASE = os.environ.get("DAYTALLY_BASE_URL", "").strip()
# 公开仓库与发布包不带任何真实地址/口令：只在第一次上手时由用户在「连接设置」里填。
# 需要免配置启动时，用环境变量 DAYTALLY_BASE_URL / DAYTALLY_TOKEN 或 --base-url / --token 传入。
DEFAULT_TOKEN = os.environ.get("DAYTALLY_TOKEN", "").strip()
# height 是旧版身高历史的兼容键，只用于 reset/清空，不在 PC 界面展示；
# muscle 是新版肌肉重量（公斤），界面 ORDER 的第一位已经切换成它。
METRICS = ("height", "muscle", "push", "horse", "pullup", "custom")

CONFIG_DIR.mkdir(parents=True, exist_ok=True)
logging.basicConfig(
    filename=str(LOG_PATH),
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(message)s",
    encoding="utf-8",
)


class RemoteError(Exception):
    def __init__(self, status: int, message: str):
        super().__init__(message)
        self.status = status
        self.message = message


def now_ms() -> int:
    return int(time.time() * 1000)


def normalize_base(value: str) -> str:
    value = (value or "").strip()
    if not value:
        return ""
    if not re.match(r"^https?://", value, re.I):
        value = "http://" + value
    return value.rstrip("/")


def split_base(base: str) -> tuple[str, int]:
    base = normalize_base(base)
    if not base:
        return "", 8787
    parsed = urlparse(base)
    host = parsed.hostname or ""
    port = parsed.port or 8787
    return host, port


def make_base(host: str, port: Any) -> str:
    host = (host or "").strip().rstrip("/")
    if host.startswith(("http://", "https://")):
        parsed = urlparse(host)
        return normalize_base(f"{parsed.scheme}://{parsed.hostname}:{int(port or parsed.port or 8787)}")
    if ":" in host and not host.startswith("["):
        # 用户可能直接粘了 host:port
        host = host.split(":")[0]
    return normalize_base(f"http://{host}:{int(port or 8787)}")


class ConfigStore:
    def __init__(self) -> None:
        self.lock = threading.RLock()
        self.data = self._load()

    def _load(self) -> dict:
        default = {
            "baseUrl": DEFAULT_BASE,
            "token": DEFAULT_TOKEN,
            "clientId": "daytally-windows-" + uuid.uuid4().hex[:10],
            "notes": [],
        }
        try:
            if CONFIG_PATH.exists():
                raw = json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
                if isinstance(raw, dict):
                    for key, value in default.items():
                        raw.setdefault(key, value)
                    raw["baseUrl"] = normalize_base(str(raw.get("baseUrl") or DEFAULT_BASE))
                    raw["notes"] = raw.get("notes") if isinstance(raw.get("notes"), list) else []
                    return raw
        except Exception:
            logging.exception("load config failed")
        return default

    def save(self) -> None:
        with self.lock:
            CONFIG_PATH.write_text(json.dumps(self.data, ensure_ascii=False, indent=2), encoding="utf-8")

    def get(self, key: str, default=None):
        with self.lock:
            return self.data.get(key, default)

    def set(self, key: str, value) -> None:
        with self.lock:
            self.data[key] = value
            self.save()

    def notes(self) -> list[dict]:
        with self.lock:
            return list(self.data.get("notes") or [])

    def add_note(self, text: str) -> list[dict]:
        text = (text or "").strip()[:200]
        if not text:
            raise ValueError("趣事不能为空")
        with self.lock:
            notes = list(self.data.get("notes") or [])
            notes.insert(0, {
                "id": uuid.uuid4().hex[:12],
                "day": date.today().isoformat(),
                "text": text,
                "createdAt": now_ms(),
            })
            self.data["notes"] = notes[:200]
            self.save()
            return list(self.data["notes"])

    def delete_note(self, note_id: str) -> list[dict]:
        with self.lock:
            notes = [n for n in (self.data.get("notes") or []) if str(n.get("id")) != str(note_id)]
            self.data["notes"] = notes
            self.save()
            return notes

    def clear_notes(self) -> None:
        with self.lock:
            self.data["notes"] = []
            self.save()


class RemoteClient:
    def __init__(self, store: ConfigStore):
        self.store = store

    @property
    def base_url(self) -> str:
        return normalize_base(str(self.store.get("baseUrl") or ""))

    @property
    def token(self) -> str:
        return str(self.store.get("token") or "").strip()

    def _request(self, method: str, path: str, payload: dict | None = None, timeout: int = 8) -> dict:
        if not self.base_url:
            raise RemoteError(0, "还没有设置树莓派地址")
        url = self.base_url + path
        body = None
        if payload is not None:
            body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        req = Request(url, data=body, method=method)
        req.add_header("Accept", "application/json")
        if body is not None:
            req.add_header("Content-Type", "application/json; charset=utf-8")
        if self.token:
            req.add_header("Authorization", "Bearer " + self.token)
        try:
            with urlopen(req, timeout=timeout) as resp:
                raw = resp.read().decode("utf-8", "replace")
                data = json.loads(raw) if raw else {}
                if not isinstance(data, dict):
                    raise RemoteError(0, "树莓派返回的不是 JSON")
                return data
        except HTTPError as exc:
            detail = ""
            try:
                detail = exc.read().decode("utf-8", "replace")
                parsed = json.loads(detail)
                detail = str(parsed.get("error") or detail)
            except Exception:
                pass
            if exc.code == 401:
                raise RemoteError(401, "Token 不对，或树莓派没有设置 Token")
            if exc.code == 404:
                raise RemoteError(404, detail or "树莓派接口不存在")
            raise RemoteError(exc.code, detail or f"树莓派返回 {exc.code}")
        except URLError as exc:
            reason = getattr(exc, "reason", exc)
            raise RemoteError(0, f"连不上树莓派：{reason}")
        except TimeoutError:
            raise RemoteError(0, "树莓派响应超时")
        except json.JSONDecodeError:
            raise RemoteError(0, "树莓派返回的不是有效 JSON")

    def health(self) -> dict:
        return self._request("GET", "/health", timeout=5)

    def snapshot(self) -> dict:
        return self._request("GET", "/api/v1/snapshot", timeout=8)

    def sync(self, events: list[dict]) -> dict:
        payload = {
            "clientId": self.store.get("clientId") or "daytally-windows",
            "deviceName": "日刻 Windows 数据中台",
            "appVersion": APP_VERSION,
            "events": events,
        }
        return self._request("POST", "/api/v1/sync", payload, timeout=12)

    def save_project(self, metric: str, config: dict) -> dict:
        if metric not in METRICS:
            raise ValueError("未知项目")
        if metric in ("height", "muscle"):
            raise ValueError("肌肉重量是记录值，不能按计数项目编辑" if metric == "muscle"
                             else "身高是旧版记录值，不能按计数项目编辑")
        name = str(config.get("name") or "").strip()[:8]
        unit = str(config.get("unit") or "").strip()[:8]
        per = int(config.get("per") or 0)
        goal = int(config.get("goal") or 0)
        max_groups = int(config.get("maxGroups") or 0)
        if not name or not unit:
            raise ValueError("项目名和单位不能为空")
        if not (1 <= per <= 999):
            raise ValueError("每组数量要在 1～999")
        if not (0 <= goal <= 100000000):
            raise ValueError("目标值要在 0～100000000")
        if not (1 <= max_groups <= 999):
            raise ValueError("纵轴上限要在 1～999")
        event = {
            "id": "win:" + uuid.uuid4().hex,
            "metric": metric,
            "kind": "config",
            "config": {"name": name, "unit": unit, "per": per, "goal": goal, "maxGroups": max_groups},
            "occurredAt": now_ms(),
        }
        return self.sync([event])

    def clear_all(self) -> tuple[dict, str]:
        # 新版服务端有真正的 DELETE 接口，旧版没有；旧版用 reset 事件把记录归零。
        try:
            result = self._request("POST", "/api/v1/admin/clear-data", {"confirm": "CLEAR_DATA"}, timeout=12)
            if result.get("ok"):
                return result, "purge"
        except RemoteError as exc:
            if exc.status != 404:
                raise
        events = []
        stamp = now_ms()
        for metric in METRICS:
            events.append({
                "id": f"win:clear:{stamp}:{metric}",
                "metric": metric,
                "kind": "reset",
                "occurredAt": stamp,
            })
        result = self.sync(events)
        return result, "reset"


class AppState:
    def __init__(self):
        self.store = ConfigStore()
        self.remote = RemoteClient(self.store)
        self.snapshot_cache: dict | None = self._load_cache()

    def _load_cache(self) -> dict | None:
        try:
            if CACHE_PATH.exists():
                raw = json.loads(CACHE_PATH.read_text(encoding="utf-8"))
                return raw if isinstance(raw, dict) else None
        except Exception:
            logging.exception("load cache failed")
        return None

    def save_cache(self, snapshot: dict) -> None:
        try:
            CACHE_PATH.write_text(json.dumps(snapshot, ensure_ascii=False), encoding="utf-8")
        except Exception:
            logging.exception("save cache failed")

    def config_for_ui(self) -> dict:
        host, port = split_base(self.remote.base_url)
        return {
            "ok": True,
            "host": host,
            "port": port,
            "baseUrl": self.remote.base_url,
            "token": self.remote.token,
            "hasToken": bool(self.remote.token),
            "clientId": self.store.get("clientId"),
            "notes": self.store.notes(),
            "cached": self.snapshot_cache is not None,
        }

    def snapshot(self) -> dict:
        snap = self.remote.snapshot()
        if not isinstance(snap.get("metrics"), dict):
            raise RemoteError(0, "树莓派没有返回项目数据")
        self.snapshot_cache = snap
        self.save_cache(snap)
        return snap

    def test(self) -> dict:
        health = self.remote.health()
        snap = self.remote.snapshot()
        return {
            "ok": True,
            "health": health,
            "version": snap.get("version"),
            "seeded": snap.get("seeded"),
            "metrics": len(snap.get("metrics") or {}),
        }

    def save_config(self, host: str, port: Any, token: str) -> dict:
        base = make_base(host, port)
        if not base:
            raise ValueError("服务地址不能为空")
        self.store.set("baseUrl", base)
        self.store.set("token", (token or "").strip())
        return self.config_for_ui()

    def save_project(self, metric: str, config: dict) -> dict:
        result = self.remote.save_project(metric, config)
        self.snapshot_cache = result
        self.save_cache(result)
        return result

    def clear_all(self) -> dict:
        snap, mode = self.remote.clear_all()
        self.snapshot_cache = snap
        self.save_cache(snap)
        self.store.clear_notes()
        return {"ok": True, "mode": mode, "snapshot": snap, "notes": []}


STATE = AppState()


class Handler(BaseHTTPRequestHandler):
    server_version = "DayTallyDesktop/" + APP_VERSION

    def log_message(self, fmt, *args):
        logging.info("%s - %s", self.address_string(), fmt % args)

    def _json(self, code: int, obj: dict) -> None:
        data = json.dumps(obj, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def _error(self, code: int, message: str) -> None:
        self._json(code, {"ok": False, "error": message})

    def _body(self) -> dict:
        length = int(self.headers.get("Content-Length", "0") or "0")
        if length <= 0:
            return {}
        if length > 2 * 1024 * 1024:
            raise ValueError("请求太大")
        raw = self.rfile.read(length)
        data = json.loads(raw.decode("utf-8"))
        if not isinstance(data, dict):
            raise ValueError("请求格式不对")
        return data

    def _serve_file(self, path: str) -> None:
        if path == "/":
            path = "/index.html"
        rel = unquote(path).lstrip("/")
        target = (WEB_DIR / rel).resolve()
        if target != WEB_DIR and WEB_DIR.resolve() not in target.parents:
            self._error(403, "forbidden")
            return
        if not target.is_file():
            self._error(404, "not found")
            return
        mime, _ = mimetypes.guess_type(str(target))
        data = target.read_bytes()
        self.send_response(200)
        self.send_header("Content-Type", (mime or "application/octet-stream") + ("; charset=utf-8" if (mime or "").startswith("text/") else ""))
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        path = urlparse(self.path).path
        try:
            if not path.startswith("/api/"):
                self._serve_file(path)
                return
            if path == "/api/config":
                self._json(200, STATE.config_for_ui())
                return
            if path == "/api/snapshot":
                self._json(200, STATE.snapshot())
                return
            if path == "/api/health":
                self._json(200, {"ok": True, "service": "daytally-desktop", "version": APP_VERSION, "baseUrl": STATE.remote.base_url})
                return
            if path == "/api/notes":
                self._json(200, {"ok": True, "notes": STATE.store.notes()})
                return
            self._error(404, "not found")
        except RemoteError as exc:
            self._json(502 if exc.status == 0 else exc.status, {"ok": False, "error": exc.message, "status": exc.status})
        except Exception as exc:
            logging.exception("GET error")
            self._error(500, "本机服务出错")

    def do_POST(self):
        path = urlparse(self.path).path
        try:
            if path == "/api/config":
                body = self._body()
                self._json(200, STATE.save_config(str(body.get("host") or ""), body.get("port"), str(body.get("token") or "")))
                return
            if path == "/api/test":
                self._json(200, STATE.test())
                return
            if path == "/api/project":
                body = self._body()
                self._json(200, {"ok": True, "snapshot": STATE.save_project(str(body.get("metric") or ""), body.get("config") or {})})
                return
            if path == "/api/clear":
                body = self._body()
                if str(body.get("confirm") or "") != "CLEAR_DATA":
                    self._error(400, "请先确认清空操作")
                    return
                self._json(200, STATE.clear_all())
                return
            if path == "/api/notes":
                body = self._body()
                notes = STATE.store.add_note(str(body.get("text") or ""))
                self._json(200, {"ok": True, "notes": notes})
                return
            if path == "/api/notes/delete":
                body = self._body()
                notes = STATE.store.delete_note(str(body.get("id") or ""))
                self._json(200, {"ok": True, "notes": notes})
                return
            self._error(404, "not found")
        except RemoteError as exc:
            self._json(502 if exc.status == 0 else exc.status, {"ok": False, "error": exc.message, "status": exc.status})
        except ValueError as exc:
            self._error(400, str(exc))
        except Exception as exc:
            logging.exception("POST error")
            self._error(500, "本机服务出错")


def start_local_server(port: int = 0) -> ThreadingHTTPServer:
    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    server.daemon_threads = True
    thread = threading.Thread(target=server.serve_forever, name="daytally-local", daemon=True)
    thread.start()
    return server


def find_webview2_runtime() -> str:
    """WebView2 can be present without the EdgeUpdate registry entry; point pywebview at it."""
    candidates = [
        os.environ.get("WEBVIEW2_RUNTIME_PATH", ""),
        r"C:\Program Files (x86)\Microsoft\EdgeWebView\Application",
        r"C:\Program Files\Microsoft\EdgeWebView\Application",
    ]
    for base in candidates:
        if not base:
            continue
        path = Path(base)
        if path.is_file() and path.name.lower() == "msedgewebview2.exe":
            return str(path.parent)
        if not path.is_dir():
            continue
        for child in sorted(path.iterdir(), key=lambda p: p.name, reverse=True):
            if child.is_dir() and (child / "msedgewebview2.exe").is_file():
                return str(child)
    return ""


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--serve-only", action="store_true", help="只启动本地 HTTP 服务，方便截图/调试")
    parser.add_argument("--port", type=int, default=0, help="本地端口，默认随机")
    parser.add_argument("--base-url", default=os.environ.get("DAYTALLY_BASE_URL", ""))
    parser.add_argument("--token", default=os.environ.get("DAYTALLY_TOKEN", ""))
    parser.add_argument("--debug", action="store_true")
    args = parser.parse_args()

    if args.base_url:
        STATE.store.set("baseUrl", normalize_base(args.base_url))
    if args.token:
        STATE.store.set("token", args.token)

    server = start_local_server(0 if not args.serve_only else (args.port or 8765))
    port = server.server_address[1]
    url = f"http://127.0.0.1:{port}"
    logging.info("local server %s", url)

    if args.serve_only:
        print(f"DAYTALLY_DESKTOP_READY {url}", flush=True)
        try:
            while True:
                time.sleep(1)
        except KeyboardInterrupt:
            pass
        finally:
            server.shutdown()
        return 0

    try:
        import webview  # type: ignore
    except Exception:
        logging.exception("pywebview import failed")
        webbrowser.open(url)
        try:
            while True:
                time.sleep(1)
        except KeyboardInterrupt:
            pass
        finally:
            server.shutdown()
        return 0

    runtime = find_webview2_runtime()
    if runtime:
        webview.settings["WEBVIEW2_RUNTIME_PATH"] = runtime
        logging.info("WebView2 runtime: %s", runtime)
    else:
        logging.warning("WebView2 runtime folder not found; Edge renderer may fall back to MSHTML")

    window = webview.create_window(
        APP_NAME,
        url,
        width=1500,
        height=980,
        min_size=(1000, 680),
        background_color="#FBF8FF",
        text_select=True,
        maximized=True,
    )

    def maximize_after_shown():
        time.sleep(0.35)
        try:
            window.maximize()
            logging.info("window maximized")
        except Exception:
            logging.exception("maximize failed")

    window.events.shown += lambda: threading.Thread(target=maximize_after_shown, daemon=True).start()
    window.events.closed += lambda: server.shutdown()
    webview.start(gui="edgechromium", debug=args.debug)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
