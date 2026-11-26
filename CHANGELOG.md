# 更新日志

## v1.4.1 — 2026-11-26

**电脑端三个页面重排成「一屏装下、不滚动」，全场修掉「鼠标没动页面也抖一下」，所有弹窗点外面就能关；手机 OKOK 首页再清掉一批推广位。**

- **总览：五个项目不再单占一块**。原来独立的「五个项目」面板整块去掉，项目名、每组多少、「今天 N 组 · 昨天 X」、「累计 / 目标」、近 14 天迷你折线全部并进顶部那 5 张指标卡；环形完成度挪到每张卡右上角，大数字是今天的值。顶部只留一行「五个项目 · 今天」小标题，右侧一个「打开项目页」入口。整页一屏，没有右侧滚动条
- **总览/趋势默认选中「肌肉重量」**：打开就是肌肉，和第一张卡、第一个页签对齐，不用再手动点一下
- **今天页去掉两块，腾出的空间重新分**。删掉顶部 5 张统计卡（含「数据来源 · 树莓派」那张）和整块「今日趣事」；左边改成「今天，五个刻度」，五行按可用高度均分、正好铺满；右边上半是「今天的数据」2×2 四格（今日已记录 / 连续记录 / 今日组数 / 今天日期），下半是「今天的小结」3 条，同样均分到卡片底部。整页一屏，没有右侧滚动条
- **趋势页底部不再「被刀切」**：页面给了底部留白，图表和统计卡的高度改成弹性（此前写死的 520 + 224 上限在真实窗口里加起来超了可用高度，正是下沿被切的原因）。「这30天的节奏」现在和上面的图表之间是正常 24px 间距，下面和窗口底边留出空白
- **右侧「最近 14 天，逐日看」**：表格高度撑满整个方框，14 行始终铺到底；方框内部可以用滚轮上下翻更早的记录，滚动条仍然隐藏，滚到底不会带着整页一起动
- **切指标不再整页闪**：从「肌肉重量」切到「引体向上」这类操作，只重画图表/统计/逐日三块，页面骨架和滚动位置不动
- **「鼠标没动也抖一下」的原因**：页面每 20 秒拉一次数据，拉完不管有没有新数据都整块重画并用入场动画；停留久了就会看见毫无理由地抖一下。现在先比对数据指纹（只认版本号、修订号、各指标数值、体脂记录、备注和页面状态，刻意排除服务器时间戳），指纹没变就什么都不做；真变了才重画，而且不播入场动画。总览、今天、趋势、体脂率、项目、趣事、数据管理七个页面同一套逻辑
- **所有弹窗点外面就能关**：设置、连接树莓派、项目详情/编辑，以及以后新加的弹窗（都走同一个弹窗组件）——在卡片外的灰色遮罩上单击即可关闭；按下和抬起都在遮罩上才算，避免在卡片里按住拖到外面松手误关；`Esc` 也能关
- **手机 OKOK 首页清理（并进日刻的模块里改，不用再装第二个模块）**：热量记录卡、卡片上方的喇叭动画和右侧滚动文字、卡片右上角的书本图标、「每日签到送好礼」文字入口、「优惠购」底部页签（整格摘掉，不留空槽）、「卡片管理」及其以下的全部内容（含「查看更多」和下面那排推荐卡片、「大家都在看」）。清理规则先按文案判断再按资源名判断；隐藏后把空的父容器一并收起，不留白块；「发现」页单独核对过，没有被误伤
- **发布包不再内置地址和口令**：桌面端源码里原来写死了这台电脑用的树莓派地址和访问口令（为了免填），仓库是公开的，这样等于把口令发出去。1.4.1 改成空默认——第一次打开在「连接设置」里填一次，只存在本机 `%APPDATA%\DayTallyDesktop\config.json`，不进网页、不进仓库；本机已有配置的照旧自动连上。构建产物和交付包都核对过，已不含真实地址与口令
- **没动的部分**：手机桌面「肌肉重量」大卡的位置按你的要求留给你自己摆；树莓派服务端本轮不需要改，仍是 1.4.0；计数方块、体脂大卡、OKOK 称重回传链路都没碰
- **怎么验的**：电脑端用打包后的 EXE 内资源在 1456×802 视口实测三页——外层滚动为 0、总览不再有独立五项目块、默认页签是肌肉、趋势底部留 14px 页内空白 + 主区 46px 边距；滚轮实测方框内滚动 213px 而整页 `scrollY` 保持 0；切指标后页面节点还在（说明没整页重绘）、停留 24 秒跨过一次轮询后节点仍是同一个；弹窗点外部关、点卡片内不关、`Esc` 关都实测通过。手机端装新包后 `uiautomator dump` 逐项确认上述 id 与文案全部消失，截图核对首页只剩体重卡 + 目标 / 体重趋势 / 喝水 / 四宫格，底栏变成首页 / 发现 / ＋ / 我的
- **版本**：Android `versionCode 21` / `versionName "1.4.1"`；Windows 数据中台 `1.4.1`（此前一直沿用 1.2.0 的号，这次跟上）；服务端 `1.4.0` 不变

## v1.4.0 — 2026-11-26

**体脂/肌肉都能从 OKOK 真实测量取数；旧“身高”语义迁移为“肌肉重量（公斤）”；OKOKDeclutter 并进日刻本体。**

- **体脂与肌肉都进 OKOK 官方称重入口**：桌面“体脂率”大卡和“肌肉重量”大卡点“去记录”，都打开 OKOK 的 `AppWidget://WeightUpScale?params=0` 官方深链；修复了原实现只设显式 `MainActivity`、没有 `ACTION_VIEW` 的问题，OKOK 现在会路由到非导出的 `BluetoothUpScaleActivity`，不再只开首页
- **真实结果回传**：日刻本体在 OKOK `MainActivity` 捕获 session PendingIntent，在 `BluetoothUpScaleActivity.startReportActivity(WeightEntity)` 读取 `getAxunge()` 与 `weight × musclePercent / 100`，换算后回传；体脂写独立记录，肌肉写当天 `muscle` 记录
- **防止 OKOK 回放旧结果**：只有从日刻“去记录”进入 OKOK 的那次会话才回传；进入称重页 3 秒内冒出的结果不写（那是 OKOK 把上一次留在页面里的结果又报了一遍），也不丢掉本次会话；是否重复只看 OKOK 自己的 `weight_time`，与上一条已回传记录的时间完全相同才算重复，数值相同但时间不同是真人又称了一次，各记各的。原先的“10 秒门限 + 同体成分 30 分钟不重复写”已去掉——实测它会把真人连称两次误判成重复
- **失败才兜底**：没装 OKOK、模块未启用、版本不匹配或用户中途退出时，才显示“再试 OKOK / 保存手动记录”；手动记录明确标 `manual`，不冒充 OKOK
- **旧身高不冒充公斤**：新增 `muscle` 指标（20~120 公斤，1 位小数）；旧 `height` 只在本机 JSON 和服务端保留做兼容，不进入新界面、不写入新值；旧厘米历史不会变成“175 公斤”
- **桌面与 App**：保留 `HeightWidget` 类名和 `widget_height` 资源名，避免已添加组件丢失；桌面 4×2“肌肉重量”大卡已固定并显示 50.7 公斤；今日页第一张卡“肌肉重量/公斤”，趋势页页签“肌肉”并默认选中“肌肉”，肌肉纵轴固定 47~72；项目页第一张卡“肌肉”，范围“47 ~ 72 公斤”
- **OKOKDeclutter 整合**：`okok/OkokXposed.kt`、`OkokRules.kt`、`OkokMeasurementPolicy.kt`、`OkokBridgeContract.kt`、`MeasurementBridge.kt` 已并入日刻；`assets/xposed_init` 加入日刻入口；Vector 作用域含 `com.miui.home` 与 `com.chipsea.btcontrol`；旧独立 `io.github.seashell666.okokdeclutter` 已卸载，以后不再装第二个模块
- **广告清理实测**：OKOK 首页活动弹窗、首页活动横幅、饮食横幅、AI 浮窗、聊天悬浮窗、报告页提示、报告/体测/称重页底部广告、`HomePopDilog.show()`、开屏广告均已处理；旧规则未覆盖的“每日签到送好礼”文字入口和“优惠购”页签仍保留，未擅自扩大范围
- **服务端 1.4.0（已部署到树莓派）**：`METRICS` 增加 `muscle`，`SET_METRICS` 为 height/muscle；旧 height 0~227、muscle 20~120；体脂独立表/接口/Token 不变；`/health` 增加 `muscleSupported`
- **Windows PC 1.2.0 界面重做**：侧栏顺序改为总览/体脂率/今天/趋势/项目/趣事/数据管理；总览右侧“今日趣事”换成“今天的体脂率 + 近 14 天迷你折线”；点指标卡不再跳趋势，改为留在总览并打开项目详情弹窗；图表页签在总览内原地切换；项目页单击打开详情、双击编辑；趋势页图表缩小到约 434px（1478×838），右侧“最近 14 天”独立方框内部滚动且隐藏滚动条，图表下方独立 5 个统计块，整页无外层滚动条；体脂页删除大摘要卡，改 5 张渐变统计卡 + 左图表右历史明细；项目页/今天页统计卡与下一张卡间距统一 24px
- **构建与测试**：Android 23 条 JVM 单测通过（体脂合并 5、体脂策略 5、OKOK 结果策略 4、肌肉策略 3、OKOK 测量时间 6）；服务端 11 条单测通过；Android `assembleRelease` 成功；PC PyInstaller 打包成功并用最终 CSS 复测；真机安装、肌肉/体脂大卡点击、OKOK 深链、广告清理实机复测均已完成；2026-11-26 14:24 本人站秤成功回传 13.7% / 50.7 公斤 / 62.0 公斤；15:14:56 在最终版模块（OKOK 已重启加载）+ 树莓派 1.4.0 下再测，回传 13.5% / 50.7 公斤 / 61.8 公斤，手机、树莓派、PC 三边一致
- **状态**：树莓派 1.4.0 已于 2026-11-26 14:55 部署（`/health` 返回 1.4.0、`muscleSupported:true`），手机同步恢复、PC 已连真实服务；只剩 4 个待用户拍板的小选择，详见 `docs/1.4.0-交付与验收报告.md`
- **版本**：Android `versionCode 20` / `versionName "1.4.0"`；模块并进日刻本体；PC `1.2.0`；服务端 `1.4.0`（已部署）

## v1.2.12 — 2026-11-21

**桌面点一下，另一台手机的组件约 1 秒内跟着变；不用打开 App，也不用等 Wi‑Fi 重连。**

- **先定位旧链路缺口**：1.2.11 及以前，`MainActivity.onStart` 和 Wi‑Fi 重连才会拉快照；`SyncManager` 成功回写后没有刷新桌面组件；`WidgetProvider` 的点击广播没有 `goAsync()`，HTTP 没回包时进程可能已被回收。所以 A 手机加到 34，B 手机仍停在 33，不是数据没上传，而是 B 没有“数据变了”的通道
- **服务端新增 SSE**：`GET /api/v1/stream`，Bearer 鉴权，`text/event-stream`；写入成功后 `notify_change()` 递增 `STATE_VERSION`，向所有长连接广播 `event: change`；空闲每 25 秒发 `: ping`；健康检查和 `server_version` 升到 `1.2.12`
- **App 新增 `SyncPushService`**：`dataSync` 前台服务，低重要性常驻通知「日刻 · 实时同步」；SSE 收到 `change` 就调用一次 `SyncManager.request("push")`；断线按 1.2s → 30s 指数退避重连；`START_STICKY`；换服务器/Token 时 `restart()` 先断开旧连接
- **桌面点击不再依赖 App 主界面**：`WidgetProvider` 的 `ACTION_ADD` / `ACTION_REMOVE` 先 `goAsync()`，把 `PendingResult` 挂到 `SyncManager.request(..., onDone)` 上；加/减走 `urgent=true`，不被平时 1.8 秒合并窗口拖住。App 没打开、进程没在后台，点击仍会把变化发到服务端
- **B 手机只拉一次并刷新组件**：`SyncManager` 应用服务端快照后新增 `WidgetRefresh.refreshAll(ctx)`；即使 App 界面没打开，后台拉取成功也会重绘 4 个方块、2 张大卡
- **开机/覆盖安装自启**：新增 `BootReceiver` 监听 `BOOT_COMPLETED` 与 `MY_PACKAGE_REPLACED`；Manifest 增加 `RECEIVE_BOOT_COMPLETED`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_DATA_SYNC`、`POST_NOTIFICATIONS`；Android 13+ 已配置同步时请求通知权限
- **真机实测（两台小米 11 / MIUI 14，同一 Wi‑Fi，树莓派 `192.168.1.50:8787`，示意地址）**：
  - 旧手机桌面点「自定义」34 → 35，服务端 `revision` 立即变化；新手机没打开 App，组件在 1.2 秒内从 34 变 35
  - 旧手机 App 进程不在时点方块 34 → 35，`pidof` 确认广播拉起进程，HTTP 完成后组件和服务端都到 35
  - 新手机重启后不打开 App，开机广播拉起 `SyncPushService`，`isForeground=true`；旧手机再点 36 → 37，新手机组件在 1.2 秒内跟着变
  - 旧手机重启后同样由 `BOOT_COMPLETED` 拉起服务并建立 SSE 连接
- **机型限制**：MIUI 覆盖安装时可能拒绝后台启动前台服务（`Service.startForeground() not allowed due to bg restriction`）；打开一次 App 后恢复。长时间待机仍可能被杀，需要在手机管家允许自启动、省电无限制。README 已如实写清，不宣称零耗电
- **文档**：README 重排为「UI + 一句话价值 → 细分价值 → 使用方法/隐藏交互 → 实时同步方式 → 进阶模块 → 构建/后端 → 项目结构/原型」；设置页截图换成 1.2.12
- **原型升到 v12**：`每日刻度APP原型版本1.html` 补上 1.2.12 的「同步服务」卡与同步弹窗（IP / 端口 / 访问令牌 / 保存并连接），设置页 hook 卡补「已接上」徽标；演示数据改为与真机同一天：身高 175.0、俯卧撑 72、蹲马步 33、引体向上 12、自定义 20；趋势默认选中俯卧撑，桌面大卡默认蹲马步；真实内网 IP 换成 192.168.1.50 示意地址
- **原型备份**：v11 旧版存 `小刻度-App原型-v11备份20261122.html`，v12 定稿存 `小刻度-App原型-v12备份20261122.html`；备份目录按 v1～v12 连续，根目录只留工作 HTML，重复副本已清
- **宣发素材包**：新增 `宣发/`，含 9 份说明文档、`manifest.json`、`code/design-tokens.json`、`screen-map.json`、`SOURCE-MAP.md`、28 个 Kotlin 源码快照、5 张页面图、8 张卡片切图、3 张图标、16:9/9:16 主视觉、六帧故事板、两机同步示意、片尾品牌卡；根目录打包 `日刻1.2.12-宣发素材包-20261122.zip`
- **构建**：`versionCode 15` / `versionName "1.2.12"`

## v1.2.11 — 2026-11-21

**同步地址从“打包时写死”改成“装好后自己填”，公开包也能连自己家里的后端。**

- **设置页新增「同步服务」卡**：位置在「桌面长按接管」下面；紫渐变 `bg_week`、内边距 `16/15/12/15`、标题 `15sp` 加粗、右侧箭头，和上面那张卡同款同尺寸。状态分「未设置（红）/ 待连接（金）/ 已连接（绿）/ 连不上（红）」，副标题显示当前 `host:port` 或「点一下填 IP、端口和访问令牌」
- **弹窗复用项目弹窗**：`bg_sheet`、28dp 外边距、`Gravity.CENTER`、`ImePad` 键盘避让；字段为 IP / 主机、端口、访问令牌（密码态）；打开走 `Sound.sheetOpen()`，保存成功 `Sound.save()`，校验失败 `Sound.error()`；点遮罩关闭
- **运行时配置落 `ds_sync`**：`SyncConfig` 从 `BuildConfig` 改为 `SharedPreferences` 的 `base_url` / `token`，公开包不再需要编译时注入；`SyncManager` 每次请求读当前配置，换服务器时丢弃旧配置返回的响应，避免旧快照污染新服务器
- **换服务器清游标**：`SyncStore.resetForServerChange()` 重置 `initialized` / `seq` / 上次结果，新服务器重新走“空库才播种”的判断；`MainActivity` 同时监听 `ACTION_SYNCED` 和 `ACTION_SYNC_STATE`，失败也会刷新设置卡状态
- **公开包唯一**：`build.gradle` 删除 `withSync` / `sync.properties` / `SYNC_BASE_URL` / `SYNC_TOKEN` 整套构建分支；`versionCode 14` / `versionName "1.2.11"`。以后从 GitHub Release 下载的就是能自己填地址的公开包，不再有“本地同步版”
- **真机实测**：旧手机（1080×2400）设置页出现「同步服务」卡，状态「未设置」，空弹窗居中且在键盘上方；新手机（1440×3063）填 `192.168.0.27` / `8787` / 32 位 Token，保存后显示「已连接」，`ds_sync.xml` 写入 `base_url` 和 `initialized=true`；自定义加一组，服务端与本地同步到 34，长按减回 33，两端一致
- **文档**：README 当前版本、构建、App 端配置、常见问题改到运行时填写；删除 `-PwithSync=true` 和“带 Token 的包只留本地”的旧说明

## v1.2.10 — 2026-11-21

**桌面方块的颜色变了，App 里的今天页也立刻跟着变，不用再切页面。**

- **根因**：`WidgetProvider` 的加组/减组长按只调用 `refreshAll()` 刷新桌面组件，没有通知 App；`MainActivity.onStart()` 只发同步请求，没有重绘当前页，同步没回来时页面就停在旧数据。`HomeView.render()` 本身每次都会重建卡片，所以之前只有切到「项目」再切回「今天」才会恢复
- **修复**：`WidgetRefresh` 新增 `ACTION_DATA_CHANGED` 和 `notifyApp()`；`WidgetProvider` 在 `ACTION_ADD` / `ACTION_REMOVE` 刷新桌面后发广播；`MainActivity` 动态注册 `dataReceiver`，收到广播当场 `refreshCurrent()`，`onStart()` 先 `repo.ensureToday()` + 重绘再同步，`onDestroy()` 注销
- **真机实测（旧手机 Xiaomi 11 / Android 13）**：自定义 57 组（30 天累计 9964，未达标）先显示白色 2679；点方块加到 58 组（累计 10011，达标），返回 App「今天」页立刻变成金色 2726；长按减回 57 组，返回 App 立刻变回白色 2679。两次都没有切 Tab
- **文档与截图**：设置页截图更新为「日刻 1.2.10 · 预览版」；README 当前版本与安装链接改到 1.2.10
- **构建**：`versionCode 13` / `versionName "1.2.10"`；公开包不读 `sync.properties`，带 Token 的本地同步包仍单独构建、只留本机

## v1.2.9 — 2026-11-21

**桌面手感补强，方块首点不再“看运气”，数据开始在家里同步。**

- **单击震动改成 36ms + 最大振幅**：`WidgetProvider` 单击从 12ms 提到 36ms、振幅 255；长按保持 20ms / 200。真机 `dumpsys vibrator_manager` 确认 `duration=36ms`、`amplitude=1.0`、`status=finished`
- **修 Android 13 后台丢震动**：原来用 `USAGE_TOUCH`，桌面点击时 App 在后台，系统记成 `ignored_background` 直接不震；改用后台允许的 `USAGE_ALARM` 后，真机户外/口袋场景实际能震。四个方块共用一个 Provider，不会双震
- **修“点俯卧撑文字上半部第一次没反应”**：`keyAndHost()` 沿父链最多 10 层找真正的 `AppWidgetHostView`；`hostViews` 登记表没建好时用当前叶子/宿主矩形兜底；新增 `AppWidgetHostView.dispatchTouchEvent` 直挂；抬手只认 `downTime`，不再要求按下层和抬层层相同；命中矩形边缘放宽 4px
- **文字/数字/圆环都能加组**：`widget_lbl`、`widget_cnt`、`widget_ring` 都挂同一个加组 `PendingIntent`；点卡片边缘留白仍然进 App，不会再加一组
- **设置页精简**：删掉独立的“桌面长按体检”卡片（含桌面包名、心跳时间、复制按钮、Toast），状态徽标直接并进「桌面长按接管」标题行；绿色「已接上」、黄色「该复测了」、红色「未接上」保留。版本文案改为「日刻 1.2.9 · 预览版」
- **局域网同步后端**：新增 `server/`（Python 标准库 + SQLite + Docker），`docker-compose.yml` 映射 `8787:8787`、`./data:/data`、`restart: unless-stopped`
- **同步合并按事件，不按整值覆盖**：加/减记 `add`/`remove` 事件，服务端按事件 id 去重后求和；A 加 3、B 加 1 最终 4；身高/配置按 `occurredAt` 取最新；清空记截止时间；空库才用首台手机快照播种
- **客户端触发**：首次打开 App、Wi‑Fi 重连、每次本地改动后 1.8 秒合并请求；只走 Wi‑Fi。`SyncStore` 把事件队列持久化到 `filesDir/sync_events.json`，`SyncManager` 推事件并拉权威快照，`Repo.applyServerSnapshot()` 整段替换本地配置和逐日数据
- **真机实测**：重启桌面后第一次点俯卧撑文字上半部 → +1；四个方块分别点文字/数字/圆环 → 各自 +1；`dumpsys` 记录 36ms/255/finished；模拟第二台设备加 2 组 → 服务端到 25，本机重新打开 App 从 23 拉到 25；断 Wi‑Fi 加 1 组 → 重连后 `sync wifi_reconnect ok`，服务端到 26
- **文档与截图**：README 补设置页截图（`docs/screenshots/settings.png`）、真机桌面组件图（`docs/screenshots/widgets-real.jpg`）、后端部署/维护章节；公开包默认不读 `sync.properties`，只有显式加 `-PwithSync=true` 才把家里服务器的地址/Token 写进 `BuildConfig`，避免公开泄露
- **构建**：`versionCode 12` / `versionName "1.2.9"`

## v1.2.8 — 2026-11-21

**弹窗跟着键盘让位，Vector 首次启用能在 App 里自检。**

- **身高弹窗 / 编辑项目不再被输入法盖住，也不悬在上半屏**：新增 `ImePad`，默认保持整屏居中，只有弹窗底边会被键盘压到时才最小幅度上移，并与键盘留 16dp；`MainActivity` 改成 edge-to-edge + `ADJUST_NOTHING`，键盘弹起时首页不再按变矮的窗口重排，「记录身高」背后的身高卡不会被压扁/裁掉。真机实测身高弹窗几乎在整屏正中，编辑项目弹窗也在尽量靠中间的位置并完整露出
- **底栏不再压到弹窗上面**：项目页弹窗统一改挂到 App 最顶层的 overlay，并抬高到 24dp，盖住 8dp 的底栏和页面内容；点遮罩关闭时不会误触「今天 / 趋势 / 项目」
- **桌面长按体检**：桌面模块加载 / 长按成功时给 App 发心跳，设置页显示「已接上 / 该复测了 / 未接上」、来源桌面包名和最近心跳时间；没接上时直接给出 Vector 三步（筛选「系统应用」→ 搜 `com.miui.home` → 勾「系统桌面」并应用），按钮可复制当前桌面包名
- **推荐作用域**：Manifest 增加 `xposedscope`，Vector 会优先把系统桌面列为推荐应用
- **新手机实测**：小米 11 / Android 13 / Vector 2.2，勾选 `com.miui.home` 并重启桌面后，长按「蹲马步」从 5 组掉到 4 组；App 心跳台账收到 `com.miui.home` 与 `hook_hit=true`
- **构建**：`versionCode 11` / `versionName "1.2.8"`

## v1.2.7 — 2026-11-20

**设置入口两边都收在 0.7 秒。**

- **项目页长按改 0.7 秒**：`HoldToSettings.HOLD_MS` 2000 → **700**，副标题「长按项目进入设置」按住变红同步
- **今日页按住也统一 0.7 秒**：`HOLD_MS_HOME` 700 → **700**（中间试过 0.27 秒，手一碰就进太敏感，按要求改回 0.7 秒，与项目页一致），日期行「今天是 X 月 X 日」按住变红同步
- **注释与原型同步**：`MainActivity` / `ProjectsView` / `SettingsView` 里的「2 秒 / 0.27 秒」注释全部改为 0.7 秒；HTML 原型 `const ms = 700` 两边统一
- **构建**：`versionCode 10` / `versionName "1.2.7"`，设置页版本行「日刻 1.2.7 · 预览版」

## v1.2.6 — 2026-11-20

**方块命中只认红色本体，设置入口全面提速。**

- **方块误触再收紧（本轮核心）**：之前只按「外壳 View」判定，MIUI 的外壳（`LauncherWidgetView` / `AppWidgetHostView`）比红色方块大一圈——上下各多十几到二十几像素、含标题条与内边距，点在红块外面一点点、上下排缝隙里、甚至左边 App 图标边缘都会加组。现在三条触摸路径（外层容器的 `View.dispatchTouchEvent`、`LauncherAppWidgetHostView.dispatchTouchEvent`、`OnLongClickAgent`）统一改成「先问 `hostViews` 登记的真实方块矩形」：手指不在这个 key 的矩形里就直接放行，不接管、不加组；`OnLongClickAgent` 的旧 `mClientView` 也过同一道坐标校验，不会再拿上一次触摸的方块顶包
- **红块内操作不变**：单击 +1 组、长按 0.15 秒 −1 组、缩放与飘字照旧；实测红块内四个角与中心都能响应，红块上下左右外侧 10～30px 均无反应
- **项目页长按改 2 秒**：`HoldToSettings.HOLD_MS` 5000 → **2000**，副标题「长按项目进入设置」按住变红的时间同步
- **今日页按住底栏改 0.7 秒**：`HOLD_MS_HOME` 1000 → **700**，日期行「今天是 11 月 20 日」按住变红同步
- **注释与文案同步**：Kotlin 里所有「5 秒 / 1 秒」注释、HTML 原型的 `const ms = homeTab ? 700 : 2000`、设置页版本号「日刻 1.2.6 · 预览版」全部对齐
- **构建**：`versionCode 9` / `versionName "1.2.6"`

## v1.2.5 — 2026-11-20

**方块错位根治 + 长按进设置的手势收口。**

- **方块上下排错位（严重回归）**：命中区 y 一直用 `loc[1] − height/2` 算，整体被抬高一整格（267px），点上排「俯卧撑」实际落到下排「引体向上」。真机复测 `getLocationOnScreen` 的 y 就是左上角，命中改为「真实矩形包含优先 + 最近邻 210px 兜底」，`SCAN` 日志会打印 `in` 标记
- **长按进设置松手退回项目页**：原来靠 900ms 时间窗判断「刚长按过、别再触发单击」，用户在设置页停留超过 900ms 再松手就失效。改为手势级标记：长按触发即置位，下次 `ACTION_DOWN` 才清零
- **今日页长按 1 秒 / 项目页 5 秒**：底栏「项目」Tab 按当前页面动态取时长；今日页按住时左上角「今天是 X 月 X 日」变红，项目页按住时副标题「长按项目进入设置」变红（`HomeView.paintDate` / `ProjectsView.paintHint` 对外暴露）
- **设置页恢复上一版**：补回「关掉后桌面方块恢复原生长按，可拖动、可移除」说明行，卡片样式保持 1.2.3 的紫渐变，不再擅自删字
- **身高弹窗**：提示由「100 ~ 250 厘米，可带一位小数」改为「0 ~ 227 厘米」，校验范围同步 0～227（`MainActivity` 与桌面 `HeightInputActivity` 两处）
- **项目卡音效时机**：784Hz 短音从「弹窗铺好后」提前到「按下瞬间」，弹层内部不再补响；长按进清空弹窗时震动与音效同时触发
- **原型备份命名**：第 10 版统一为 `小刻度-App原型-v10备份20261120.html`，仓库根目录里重复的同名副本删除，只保留一份

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
