# 日刻 DayTally · 宣发素材包 v1.2.12

> 给 Claude / ChatGPT / Seedance 2.5 / 剪辑师看的“产品说明书 + 素材库”。
> 目标：任何 AI 打开这个包，先看懂产品是什么、有哪些功能，再决定怎么拍。

---

## 30 秒看懂

**日刻是一个把“每日记录”放回桌面的 Android 小工具。**

- 不打开 App：桌面 1×1 方块点一下，就加一组俯卧撑 / 蹲马步 / 引体向上 / 自定义。
- 抬眼就知道：桌面大卡直接显示今天数值、本周 7 天、目标进度。
- 两台手机同数：一台桌面点一下，另一台不打开 App，约 1 秒跟着变（1.2.12）。
- 数据是自己的：默认只存手机本地；要同步，就填自己家局域网的服务器地址和 Token，不走公网。
- 没有社区、课程、勋章、打卡地图，也没有广告。

一句话：

> **桌面点一下，数字就刻上去。**

---

## 先读哪几个文件

| 顺序 | 文件 | 你会得到什么 |
|---|---|---|
| 1 | `00-START-HERE.md` | 60 秒产品 brief，适合直接粘给 AI |
| 2 | `01-产品一页纸.md` | 定位、用户、痛点、差异、商业可能性 |
| 3 | `02-功能清单.md` | 全部功能、状态、限制、版本归属 |
| 4 | `03-页面与交互.md` | 今天 / 趋势 / 项目 / 设置 / 桌面组件逐页说明 |
| 5 | `04-视觉规范.md` | 颜色、字体、圆角、间距、动效、语气 |
| 6 | `05-导演手册-视频提示词.md` | 3 套视频方案 + Seedance 2.5 镜头提示词 + 工作流 |
| 7 | `06-AI使用说明.md` | 给 Claude / ChatGPT 的现成提示词模板 |
| 8 | `07-版本与发布.md` | 1.2.12 版本、APK、GitHub、校验信息 |

机器可读索引：`manifest.json`；屏幕结构：`code/screen-map.json`；设计变量：`code/design-tokens.json`。

---

## 素材目录

```
宣发/
├── README.md                   ← 你在这里
├── 00-START-HERE.md            60 秒 brief
├── 01-产品一页纸.md
├── 02-功能清单.md
├── 03-页面与交互.md
├── 04-视觉规范.md
├── 05-导演手册-视频提示词.md
├── 06-AI使用说明.md
├── 07-版本与发布.md
├── manifest.json               文件清单 + 尺寸 + 用途
├── screens/                    1.2.12 页面截图（今天/趋势/项目为真机；桌面/设置为原型渲染，已脱敏）
│   ├── 01-today.png
│   ├── 02-trend.png
│   ├── 03-projects.png
│   ├── 04-settings.png
│   └── 05-desktop-widgets.png
├── prototype/                  v12 HTML 原型与原型渲染图
│   ├── daytally-prototype-v12.html
│   ├── board-v12.png
│   ├── phone-desktop.png
│   ├── phone-app-home.png
│   ├── phone-trend.png
│   ├── phone-projects.png
│   └── phone-settings-connected.png
├── cards/                      从 HTML 原型单独抠出的卡片 PNG（透明外无背景依赖）
│   ├── card-height.png
│   ├── card-counter-pushup.png
│   ├── card-weekcard-squat.png
│   ├── card-project-height.png
│   ├── card-project-pushup.png
│   ├── card-settings-hook.png
│   ├── card-settings-sync.png
│   └── sheet-sync.png
├── brand/
│   ├── daytally-icon-1024.png
│   ├── daytally-icon-512.png
│   └── daytally-icon-alt-1024.png
├── promo/
│   ├── key-visual-16x9.png     1920×1080 主视觉
│   ├── key-visual-9x16.png     1080×1920 竖版主视觉 / 视频首帧
│   ├── storyboard-6frames.png  1920×1080 六帧故事板联系表
│   ├── sync-two-phones.png     两台手机同步示意
│   ├── brand-endcard.png       片尾品牌卡
│   └── widgets-stack.png       桌面组件堆叠裁图（原型渲染，避免个人壁纸）
└── code/
    ├── design-tokens.json
    ├── screen-map.json
    ├── SOURCE-MAP.md
    ├── android/AndroidManifest.xml
    ├── android/app-build.gradle
    └── app-kotlin/daytally/...  真机 Kotlin 源码快照（28 个文件）
```

---

## 这些图怎么用

| 用途 | 优先用 |
|---|---|
| 给投资人 / 用户看“桌面就是主界面” | `screens/05-desktop-widgets.png`、`prototype/phone-desktop.png` |
| 展示 App 三页 | `screens/01-today.png`、`02-trend.png`、`03-projects.png` |
| 展示 1.2.12 新能力 | `screens/04-settings.png`、`prototype/phone-settings-connected.png`、`cards/sheet-sync.png` |
| 做视频后期贴屏 / 合成 | `cards/*.png`、`prototype/phone-*.png` |
| 做封面 / 首帧 / 投放图 | `promo/key-visual-16x9.png`、`key-visual-9x16.png` |
| 给导演/剪辑看节奏 | `promo/storyboard-6frames.png` |
| 片尾品牌收束 | `promo/brand-endcard.png` |
| 演示两台手机同步 | `promo/sync-two-phones.png` |
| 让 AI 理解真实实现 | `code/app-kotlin/daytally/ui/*`、`widget/*`、`sync/*` |

---

## 给视频 AI 的一条硬规则

**不要让 Seedance / 视频模型直接生成可读的 UI 文字和数字。** 它很容易把“175.0”“72”“已连接”生成成乱码。

正确做法：

1. 用 Seedance 生成“手、手机、桌面、光线、运动”的实拍镜头；
2. 在剪辑里把 `cards/*.png` 或 `prototype/phone-*.png` 贴到屏幕区域；
3. 数字变化用 AE / 剪映关键帧做 0 → 目标值的滚动；
4. 需要两台手机同步时，用两个真实屏幕素材做时间差，不要靠模型画第二台。

---

## 版本

- App：`1.2.12`（versionCode 15）
- 原型：第 12 版，与 1.2.12 真机对齐
- 平台：Android，minSdk 26 / targetSdk 34
- 仓库：https://github.com/seashell666/daytally
- Release：https://github.com/seashell666/daytally/releases/tag/v1.2.12
- 截图数据：演示数据（身高 175.0、俯卧撑 72、蹲马步 33、引体向上 12、自定义 20）；不是真实用户数据。

---

## 一句话给 AI 的开场

把下面这段直接粘给 Claude / ChatGPT：

```text
你是一名产品广告导演。请先完整读取这个 ZIP 里的 README.md、00-START-HERE.md、01-产品一页纸.md、02-功能清单.md、03-页面与交互.md、05-导演手册-视频提示词.md。
产品叫「日刻 DayTally」，核心是“桌面点一下，数字就刻上去”的 Android 每日量化工具。
请基于真实素材设计一支 30 秒产品片：先给 3 个创意方向，再输出现成的分镜表和 Seedance 2.5 镜头提示词。不要虚构产品没有的功能。
```
