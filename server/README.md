# 日刻同步服务

这是 `日刻` 的局域网同步后端：Python 标准库 + SQLite，镜像很小，不需要 pip 装任何包。

## 它解决什么

两台手机各自记录时，不能只同步“今天总数”：
- A 手机离线加了 3 组
- B 手机离线加了 1 组
- 回家后两台都连上 Wi‑Fi

如果拿最后同步的总数覆盖，就会丢掉其中一台的增量。这里把每次加/减都记成一条带时间戳和唯一 id 的动作，服务端求和；所以最终是 4 组，不是 3 组或 1 组。

## 目录

```
server/
├── app.py              # HTTP + SQLite，全部标准库
├── Dockerfile
├── docker-compose.yml
├── .env.example
└── data/               # 运行后自动生成，存 daytally.db
```

## 本机试跑

```bash
cd server
cp .env.example .env
# 编辑 .env，把 Token 改成随机字符串（可用 openssl rand -hex 16 生成）
docker compose up -d --build
curl http://127.0.0.1:8787/health
```

## API

- `GET /health`：健康检查，不需要 Token。
- `GET /api/v1/snapshot`：读取当前权威快照，需要 Bearer Token。
- `POST /api/v1/sync`：客户端推事件 + 拉快照，需要 Bearer Token。

同步请求示例：

```json
{
  "clientId": "phone-a-uuid",
  "deviceName": "Xiaomi 11",
  "appVersion": "1.2.9",
  "seed": true,
  "snapshot": {
    "metrics": {
      "push": {
        "name": "俯卧撑",
        "unit": "个",
        "per": 12,
        "goal": 10000,
        "maxGroups": 32,
        "data": {"2026-11-21": 18}
      }
    }
  },
  "events": [
    {
      "id": "event-uuid",
      "metric": "push",
      "day": "2026-11-21",
      "kind": "add",
      "occurredAt": 1763700000000
    }
  ]
}
```
（实际请求包含全部 5 个项目；上面只截了 `push` 方便看结构。）

返回：

```json
{
  "ok": true,
  "seeded": false,
  "revision": 123,
  "serverTime": 1763700000000,
  "acked": ["event-uuid"],
  "metrics": {
    "push": {"name": "俯卧撑", "unit": "个", "per": 12, "goal": 10000, "maxGroups": 32,
             "data": {"2026-11-21": 19}}
  }
}
```

## 合并规则

- `add` / `remove`：按事件求和；同一个 `id` 只算一次。
- `set`（身高）：按 `occurredAt` 取最新。
- `config`：按 `occurredAt` 取最新配置。
- `reset`：记一个截止时间，早于它的事件不再计入。
- 第一次连接：服务端空库时，客户端快照作为基线播种；服务端已有数据时，只拉取，不重复播种。这样第二台旧手机不会把自己的旧历史再加一遍。

## 运维

```bash
cd /opt/daytally-sync

docker compose ps                 # 看状态
docker compose logs -f --tail=50  # 看日志
docker compose restart            # 重启
docker compose up -d --build      # 改了 app.py / compose 后重建
```

数据库在 `data/daytally.db`。备份先停容器再复制，避免 SQLite 正在写：

```bash
cd /opt/daytally-sync
docker compose stop
cp data/daytally.db data/daytally.db.$(date +%F).bak
docker compose start
```

恢复时先 `docker compose stop`，把备份覆盖回 `data/daytally.db`，再 `docker compose start`。不要把 `data/` 里的库直接删掉；要重来就改名留档。
