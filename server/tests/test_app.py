import json
import os
import shutil
import sys
import tempfile
import threading
import unittest
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TMP = tempfile.mkdtemp(prefix="daytally-server-test-")
os.environ["DAYTALLY_DB"] = str(Path(TMP) / "daytally.db")
os.environ["DAYTALLY_TOKEN"] = ""
sys.path.insert(0, str(ROOT))

import app  # noqa: E402


def record(record_id: str, value: float, measured_at: int = 1763700000000) -> dict:
    return {
        "recordId": record_id,
        "metricType": "body_fat",
        "value": value,
        "unit": "%",
        "measuredAt": measured_at,
        "source": "okok",
        "deviceId": "phone-a",
        "createdAt": measured_at,
        "updatedAt": measured_at,
    }


class ServerBodyFatTest(unittest.TestCase):
    def setUp(self):
        app.init_db()
        conn = app.connect()
        try:
            conn.execute("DELETE FROM events")
            conn.execute("DELETE FROM resets")
            conn.execute("DELETE FROM body_fat_records")
            conn.execute("UPDATE meta SET v='0' WHERE k='seeded'")
            conn.commit()
        finally:
            conn.close()

    def tearDown(self):
        pass

    def snapshot(self) -> dict:
        conn = app.connect()
        try:
            return app.build_snapshot(conn)
        finally:
            conn.close()

    def test_valid_record_and_range(self):
        self.assertEqual(app.validate_body_fat_record(record("bf-1", 23.4), "phone-a")["value"], 23.4)
        for bad in (0.0, 0.5, 75.1, 100):
            with self.assertRaises(ValueError):
                app.validate_body_fat_record(record("bf-bad", bad), "phone-a")
        with self.assertRaises(ValueError):
            app.validate_body_fat_record(record("bf-bad", 23.4) | {"unit": "kg"}, "phone-a")
        with self.assertRaises(ValueError):
            app.validate_body_fat_record(record("bf-bad", 23.4) | {"unit": "percent"}, "phone-a")
        with self.assertRaises(ValueError):
            app.validate_body_fat_record(record("bf-bad", 23.4) | {"unit": None}, "phone-a")
        with self.assertRaises(ValueError):
            app.validate_body_fat_record(record("bf-bad", 23.4) | {"metricType": "weight"}, "phone-a")

    def test_records_are_independent_and_deduped(self):
        conn = app.connect()
        try:
            app.upsert_body_fat_record(conn, app.validate_body_fat_record(record("bf-1", 23.4), "a"), 100)
            app.upsert_body_fat_record(conn, app.validate_body_fat_record(record("bf-1", 23.9), "b"), 101)
            app.upsert_body_fat_record(conn, app.validate_body_fat_record(record("bf-2", 24.1), "a"), 102)
            conn.commit()
        finally:
            conn.close()
        snap = self.snapshot()
        self.assertEqual(len(snap["bodyFatRecords"]), 2)
        self.assertEqual(snap["bodyFatRecords"][0]["recordId"], "bf-1")
        self.assertEqual(snap["bodyFatRecords"][0]["value"], 23.4)

    def test_multiple_measurements_same_day_are_kept(self):
        payload = {
            "clientId": "phone-a",
            "seed": False,
            "events": [],
            "bodyFatRecords": [
                record("bf-a", 22.8, 1763700000000),
                record("bf-b", 23.0, 1763700100000),
                record("bf-c", 23.2, 1763700200000),
            ],
        }
        result = app.process_sync(payload)
        self.assertEqual(result["bodyFatAcked"], ["bf-a", "bf-b", "bf-c"])
        self.assertEqual(len(result["bodyFatRecords"]), 3)
        self.assertEqual([r["value"] for r in result["bodyFatRecords"]], [22.8, 23.0, 23.2])

    def test_seed_from_body_fat_snapshot(self):
        payload = {
            "clientId": "phone-a",
            "seed": True,
            "snapshot": {"metrics": {}},
            "bodyFatSnapshot": [record("bf-seed", 21.9, 1763600000000)],
            "events": [],
        }
        result = app.process_sync(payload)
        self.assertTrue(result["seeded"])
        self.assertEqual(len(result["bodyFatRecords"]), 1)
        self.assertEqual(result["bodyFatRecords"][0]["value"], 21.9)

    def test_existing_counter_still_works_with_body_fat(self):
        payload = {
            "clientId": "phone-a",
            "seed": False,
            "events": [
                {"id": "ev-1", "metric": "push", "day": "2026-11-25", "kind": "add", "occurredAt": 1763700000000}
            ],
            "bodyFatRecords": [record("bf-1", 23.4)],
        }
        result = app.process_sync(payload)
        self.assertIn("ev-1", result["acked"])
        self.assertEqual(result["metrics"]["push"]["data"]["2026-11-25"], 1)
        self.assertEqual(len(result["bodyFatRecords"]), 1)

    def test_clear_all_keeps_project_configs(self):
        conn = app.connect()
        try:
            conn.execute(
                "INSERT OR REPLACE INTO configs(metric,name,unit,per,goal,max_groups,updated_at) VALUES('push','俯卧撑','个',12,10000,32,1)"
            )
            app.upsert_body_fat_record(conn, app.validate_body_fat_record(record("bf-1", 23.4), "a"), 100)
            conn.commit()
        finally:
            conn.close()
        snap = app.clear_all_data()
        self.assertEqual(snap["bodyFatRecords"], [])
        self.assertEqual(snap["metrics"]["push"]["name"], "俯卧撑")
        self.assertEqual(snap["metrics"]["push"]["data"], {})

    def test_http_clear_endpoint(self):
        server = app.Server(("127.0.0.1", 0), app.Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            port = server.server_address[1]
            body = json.dumps({"confirm": "CLEAR_DATA"}).encode("utf-8")
            req = urllib.request.Request(
                f"http://127.0.0.1:{port}/api/v1/admin/clear-data",
                data=body, method="POST", headers={"Content-Type": "application/json"},
            )
            with urllib.request.urlopen(req, timeout=5) as resp:
                data = json.loads(resp.read().decode("utf-8"))
            self.assertEqual(data["mode"], "purge")
            self.assertEqual(data["snapshot"]["bodyFatRecords"], [])
        finally:
            server.shutdown()
            server.server_close()


class ServerMuscleTest(unittest.TestCase):
    def setUp(self):
        app.init_db()
        conn = app.connect()
        try:
            conn.execute("DELETE FROM events")
            conn.execute("DELETE FROM resets")
            conn.execute("DELETE FROM configs")
            conn.execute("DELETE FROM body_fat_records")
            conn.execute("UPDATE meta SET v='0' WHERE k='seeded'")
            conn.commit()
        finally:
            conn.close()

    def snapshot(self) -> dict:
        conn = app.connect()
        try:
            return app.build_snapshot(conn)
        finally:
            conn.close()

    def event(self, eid: str, metric: str, kind: str, day=None, value=None) -> dict:
        raw = {
            "id": eid,
            "metric": metric,
            "kind": kind,
            "occurredAt": 1763700000000,
        }
        if day is not None:
            raw["day"] = day
        if value is not None:
            raw["value"] = value
        return raw

    def test_muscle_and_legacy_height_both_sync(self):
        payload = {
            "clientId": "phone-a",
            "seed": False,
            "events": [
                self.event("m-1", "muscle", "set", "2026-11-26", 50.7),
                self.event("h-1", "height", "set", "2026-11-25", 175.0),
            ],
            "bodyFatRecords": [record("bf-1", 23.4)],
        }
        result = app.process_sync(payload)
        self.assertEqual(result["metrics"]["muscle"]["name"], "肌肉重量")
        self.assertEqual(result["metrics"]["muscle"]["unit"], "公斤")
        self.assertEqual(result["metrics"]["muscle"]["data"]["2026-11-26"], 50.7)
        self.assertEqual(result["metrics"]["height"]["unit"], "厘米")
        self.assertEqual(result["metrics"]["height"]["data"]["2026-11-25"], 175.0)
        self.assertEqual(len(result["bodyFatRecords"]), 1)

    def test_muscle_range_and_event_kind_validation(self):
        self.assertEqual(
            app.validate_event(self.event("m-ok", "muscle", "set", "2026-11-26", 50.7), "a")["value"],
            50.7,
        )
        for bad in (10.0, 119.9 + 10, 200.0, -1.0):
            with self.assertRaises(ValueError):
                app.validate_event(self.event("m-bad", "muscle", "set", "2026-11-26", bad), "a")
        with self.assertRaises(ValueError):
            app.validate_event(self.event("p-set", "push", "set", "2026-11-26", 1), "a")

    def test_muscle_config_accepts_set_metric_shape(self):
        event = {
            "id": "m-cfg",
            "metric": "muscle",
            "kind": "config",
            "occurredAt": 1763700000000,
            "config": {"name": "肌肉重量", "unit": "公斤", "per": 0, "goal": 0, "maxGroups": 0},
        }
        parsed = app.validate_event(event, "a")
        self.assertEqual(parsed["config"]["per"], 0)
        bad = dict(event)
        bad["id"] = "m-cfg-bad"
        bad["config"] = {"name": "肌肉重量", "unit": "公斤", "per": 12, "goal": 0, "maxGroups": 0}
        with self.assertRaises(ValueError):
            app.validate_event(bad, "a")

    def test_muscle_seed_snapshot_and_clear(self):
        payload = {
            "clientId": "phone-a",
            "seed": True,
            "snapshot": {
                "metrics": {
                    "muscle": {
                        "name": "肌肉重量", "unit": "公斤", "per": 0, "goal": 0,
                        "maxGroups": 0, "data": {"2026-11-26": 50.7},
                    },
                    "height": {
                        "name": "身高", "unit": "厘米", "per": 0, "goal": 0,
                        "maxGroups": 0, "data": {"2026-11-25": 175.0},
                    },
                }
            },
            "events": [],
        }
        result = app.process_sync(payload)
        self.assertEqual(result["metrics"]["muscle"]["data"]["2026-11-26"], 50.7)
        self.assertEqual(result["metrics"]["height"]["data"]["2026-11-25"], 175.0)

        cleared = app.clear_all_data()
        self.assertEqual(cleared["metrics"]["muscle"]["data"], {})
        self.assertEqual(cleared["metrics"]["muscle"]["unit"], "公斤")
        self.assertEqual(cleared["metrics"]["height"]["data"], {})


def tearDownModule():
    shutil.rmtree(TMP, ignore_errors=True)


if __name__ == "__main__":
    unittest.main()
