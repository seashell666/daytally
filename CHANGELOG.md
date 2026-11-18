# 更新日志

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
