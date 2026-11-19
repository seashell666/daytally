# 更新日志

## v1.2.4 — 2026-11-19

**桌面手感与误触这一轮。**

- **方块误触根治**：旧钩子在 `View.dispatchTouchEvent` 按坐标吃事件，文件夹/多任务里点到方块区域也加组。改为三层判定——① 遮挡判定（`Folder` / `Recents`/`MultiTask`/`TaskView` 全屏可见 View 直接放行，再用 `WindowManagerGlobal.mViews` 兜住所有窗口根视图）；② 命中判定（从 `hostViews` 登记表取 `LauncherAppWidgetHostView`，真实中心 `cy = loc[1] − height/2`、`cx = loc[0] + width/2`，最近邻 + 210px 阈值）；③ 三处 widget 层 hook 兜底
- **浮动数字四块统一**：四个方块走同一套绘制代码，颜色常量全局唯一，`2px` 白描边；`FloatNumView.progress()` 从全屏 `invalidate()` 改成只失效文字小矩形，长按/连点不再发虚
- **浮动数字锚点修正**：`parent` 强制挂 `DecorView`，锚点用纯屏幕坐标 `sy − 200`，不再受容器 `top` 与 `host.height` 影响
- **长按阈值 200ms → 150ms**，长按减组的顿挫感消掉
- **身高弹窗上下居中**：`SOFT_INPUT_ADJUST_NOTHING` + `Gravity.CENTER`，弹窗不再贴上半屏
- **项目页**：副标题定稿「所有日刻项目 · 长按项目进入设置」，按住时「长按项目进入设置」变红（`#D2505C`），热区从标题文字扩到整个左侧容器；进入设置页的长按 5 秒在副标题、底栏「项目」Tab、左上角大字三处都生效
- **音效对齐**：项目卡点击加 `sheetChime()`（与「去记录」同一条 784Hz）；清空重置复用「加一组」的双音（988Hz + 34ms 后 1319Hz）；自动保存的去抖不再叠震动
- **清空弹窗**：说明文字行距 `1.7 → 1.45`，两行说明的间距小于标题与正文的间距
- **五段间隙等距**：今日页 5 个 `weight` 弹簧（身高卡后 1 + 4 张计数卡之间 3 + 末尾 1），项目页同构；`buildHeader` 底部 10dp padding → 0
- **隐藏设置页**：从项目页角落入口进入，杀掉进程重启仍停留设置页，不会自动弹回今日页；单行紫渐变卡片 + 开关，不带说明文本
- **HTML 原型升到 v11**：新增 `#view-settings` 场景、五段间隙等距、副标题变红、长按 5 秒进设置

## v1.1.0 — 2026-11-18

**改名与开源首发。**

- 包名 `com.beike.dailyscale` → **`com.beike.daytally`**
- App 中文名「每日刻度」→ **「日刻」**
- 桌面大卡组件标题同步为「日刻 · 大卡」
- 主题名 `Theme.DailyScale` → `Theme.DayTally`
- 数据文件名 `daily_scale.json` → `daytally.json`
- 签名密钥外置：`keystore.properties` 读取，缺失时 release 退回 debug 签名；仓库不含任何私钥
- 版本号 `1.0.0` → `1.1.0`（versionCode 1 → 2）

> 包名变更属于 breaking change：本版与旧版是两个独立 App，无法覆盖升级。旧桌面组件会随旧包一起失效，需重新添加。旧数据可手工迁移（见下）。

## v1.0.1 — 2026-11-17

**UI 1:1 复刻定稿。**

- 定位到"对不上"的物理根因：原型屏幕逻辑宽 350px，真机 411dp，差 17.5%。此前按 1px = 1dp 直搬，导致卡片横向拉宽而高度不变
- 引入 `Ui.S = 1.175` 全局等比换算（dp / sp 一起缩放）
- `ChartView` 重写为按原型 SVG viewBox 320×200 等比绘制
- 修复趋势页三个容器未设 layoutParams 导致内容挤左
- 修复项目页卡片固定高度导致字号放大后被裁
- 修复趋势页统计单位错误（组数被显示成个数）
- 卡片补回原型中的极淡投影

## v1.0.0 — 2026-11-16

**首个可运行版本。**

- 今天页：5 类项目累计、加一组、本周 7 天条带、身高渐变卡
- 趋势页：项目切换、三栏统计、Canvas 折线图、逐日明细
- 项目页：每组数量 / 累计 / 目标 / 纵轴范围，点击改设置
- 桌面组件：4 个 1×1 方块 + 1 个 4×2 大卡
- 本地 JSON 存储，无网络权限

## 数据迁移（旧版 → v1.1.0）

旧包数据在 `/data/data/com.beike.dailyscale/files/daily_scale.json`，新版读的是 `/data/data/com.beike.daytally/files/daytally.json`。root 设备可直接复制：

```bash
su -c 'cp /data/data/com.beike.dailyscale/files/daily_scale.json \
           /data/data/com.beike.daytally/files/daytally.json'
su -c 'chown $(stat -c %u /data/data/com.beike.daytally/files):$(stat -c %g /data/data/com.beike.daytally/files) \
           /data/data/com.beike.daytally/files/daytally.json'
```

非 root 设备无法直接读应用私有目录，先用旧版导出一份（应用内"数据"入口），再在新版导入。
