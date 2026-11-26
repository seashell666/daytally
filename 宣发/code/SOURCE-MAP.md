# 源码地图｜日刻 1.2.12

> 这里放的是 **1.2.12 的源码快照**，目的是让 Claude/ChatGPT 理解实现方式，不是让它直接重打包。
> 源码路径相对于 `code/app-kotlin/daytally/`。

---

## 1. 入口与容器

| 文件 | 作用 |
|---|---|
| `MainActivity.kt` | 单 Activity 容器；三页切换、底栏、弹窗 overlay、长按进设置、数据广播 |
| `HeightInputActivity.kt` | 桌面身高输入弹窗（独立 Activity） |
| `WidgetPreviewActivity.kt` | 组件预览/配置辅助 |
| `Repo.kt` | 本地 JSON 读写、种子数据、按事件应用快照 |
| `model/Metric.kt` | 项目模型（key/name/unit/per/goal/maxGroups/data） |

> 整个 App **只有一个主 Activity**，页面是原生 View 不是 Fragment/Compose。

---

## 2. UI 页面

| 文件 | 对应页面 | 关键点 |
|---|---|---|
| `ui/HomeView.kt` | 今天 | 身高卡 + 4 张计数卡 + 7 天条带；五段弹簧等距 |
| `ui/TrendView.kt` | 趋势 | 分段选择、三栏统计、30 天折线、逐日明细 |
| `ui/ChartView.kt` | 趋势图表 | Canvas 折线，按 320×200 viewBox 等比 |
| `ui/ProjectsView.kt` | 项目 | 5 张项目卡、编辑弹窗、清空、长按进设置 |
| `ui/SettingsView.kt` | 隐藏设置 | 长按接管开关、同步服务卡、版本行 |
| `ui/ImePad.kt` | 弹窗 | 键盘避让：只上移必要距离，留 16dp |
| `util/Ui.kt` | 全局 | `Ui.S = 1.175` 等比换算 |
| `util/Colors.kt` | 全局 | 颜色常量 |
| `Sound.kt` | 全局 | 点击/弹窗/错误音效 |

---

## 3. 桌面组件

| 文件 | 作用 |
|---|---|
| `widget/WidgetProvider.kt` | 4 个 1×1 的 Provider；点击/长按、加组/减组、`goAsync()` 同步 |
| `widget/HeightWidget.kt` | 身高 4×2 大卡 |
| `widget/WeekWidget.kt` | 计数 4×2 大卡（项目胶囊 + 7 天矩阵） |
| `widget/RingBitmap.kt` | Canvas 画圆环位图 |
| `widget/WidgetRefresh.kt` | 刷新全部组件；发 `ACTION_DATA_CHANGED` 给 App |
| `xposed/DayTallyXposed.kt` | Vector/LSPosed 钩子：桌面长按减组、浮动数字、缩放、震动 |

### 关键机制

- 桌面点击用 `PendingIntent` 广播到 `WidgetProvider`。
- 1.2.12 在 `ACTION_ADD` / `ACTION_REMOVE` 里先 `goAsync()`，把 `PendingResult` 挂到 HTTP 回包后，避免进程被系统提前回收。
- 长按减组必须挂在 `com.miui.home` 进程，因为 MIUI 桌面触摸不进组件 View。

---

## 4. 同步

| 文件 | 作用 |
|---|---|
| `sync/SyncConfig.kt` | 运行时 `base_url` / `token`，存 SharedPreferences |
| `sync/SyncStore.kt` | 事件队列、游标、上次结果；换服务器清游标 |
| `sync/SyncManager.kt` | 合并请求、推事件、拉快照、应用快照、刷新组件 |
| `sync/SyncNetwork.kt` | HTTP 请求（snapshot / sync） |
| `sync/SyncPushService.kt` | 前台服务 + SSE 长连接；收到 change 拉一次 |
| `sync/BootReceiver.kt` | 开机 / 覆盖安装拉起服务 |

### 1.2.12 链路

```text
桌面点击
  → WidgetProvider ACTION_ADD
  → goAsync()
  → SyncManager.request(urgent=true)
  → POST /api/v1/sync
  → 服务端写入 + notify_change()
  → SSE 广播 event: change
  → 另一台 SyncPushService 收到
  → SyncManager.request("push")
  → GET /api/v1/snapshot
  → Repo.applyServerSnapshot()
  → WidgetRefresh.refreshAll()
  → 桌面组件更新（约 1.2 秒）
```

### 服务端（不在本包内）

- `server/app.py`：Python 标准库 + SQLite
- `GET /health`（无 Token）
- `GET /api/v1/snapshot`（Bearer）
- `POST /api/v1/sync`（Bearer）
- `GET /api/v1/stream`（Bearer，SSE）
- 端口 8787；Docker 部署；数据落 `./data/daytally.db`

---

## 5. 源码里不要误读的点

- `Ui.S = 1.175` 是 **原型 350px → 真机 411dp** 的全局比例；不是随便的缩放。
- 组件颜色不是固定红：按当天组数分 8 档；达标转金色。
- “清空”不是删除历史，而是记一个截止时间。
- 同步不是覆盖总数，是按事件 id 去重后求和。
- 长按 0.15 秒是 **桌面方块减组**；设置入口长按是 **0.7 秒**，不要混。
- 单击震动 36ms；长按震动 20ms。
- App 没有用 Compose；全部原生 View + Canvas。

---

## 6. 如果 AI 要改代码

先读：

1. `MainActivity.kt`（容器、导航、overlay）
2. 目标页的 `ui/*.kt`
3. `util/Ui.kt`（尺寸换算）
4. `widget/*`（如果涉及桌面）
5. `sync/*`（如果涉及同步）

不要：

- 不要为了“对齐 HTML”删掉 1.2.12 的同步服务。
- 不要把 `Ui.S` 改回 1。
- 不要把长按 0.15s / 0.7s 混用。
- 不要在公开包里写死服务器地址或 Token。
