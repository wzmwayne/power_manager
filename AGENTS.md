# 项目记忆 · power_manager

> 本文件是项目的长期记忆。任何代码变更落地后，必须同步更新本文件（新增决策、改动的架构、构建状态等）。

## 项目定位

LSPosed 系统框架电源管理模块（仅作用于 `system_server` 与系统框架进程），通过「System API 主管线 + Root Shell 备分管线」双模执行器实现精细化功耗压制。

- 仓库：`https://github.com/wzmwayne/power_manager`
- 应用显示名：`Power Manager`
- 许可证：GPL v3

## 铁律（不可违反）

1. **本地绝不构建**：所有编译验证一律通过 GitHub Actions 工作流完成（唯一构建路径）。
2. **工作流必须带缓存**：SDK 下载缓存（`android-actions/setup-android`）+ Gradle 依赖与 build 缓存（`gradle/actions/setup-gradle`），用于加速二次构建。
3. **必须写项目记忆**：每次变更落地后同步更新本文件。
4. **应用与文档一律无 emoji**。
5. 构建产物：`assembleDebug`（debug key 自签，免密钥管理）。
6. 不访问本工作目录（`/home/wayne/date/power_manager`）之外的任何目录。

## 技术栈（锁定版本）

| 组件 | 版本 |
|---|---|
| Gradle | 8.11.1 |
| AGP | 8.7.3 |
| Kotlin | 2.0.21（含 compose 插件同版本） |
| compileSdk / targetSdk / minSdk | 35 / 34 / 26 |
| UI | Jetpack Compose + Material 3，完全默认原生主题（`darkColorScheme`/`lightColorScheme` 跟随系统深浅色，无自定义颜色/样式），纯中文 |
| 存储 | JSON 字符串存 SharedPreferences（键 `config`）；配置/日志经 ContentProvider 互通 |
| Xposed API | `de.robv.android.xposed:api:82`（compileOnly） |
| CI 运行 JDK | temurin 17 |

## 重要：配置互通重构已落地（CI 验证通过）

已删除整个 hook/执行器/Root 层；模块入口与 UI 已全部适配新架构，提交后 CI 验证通过（run 32035384091 success）。重构方向：以 ContentProvider 配置通道 + AppLog 统一日志取代旧的文件/XSharedPreferences 互通，精简 Hook 面（system_server 侧暂不注册 Hook）。

### 已删除的旧类（staged，勿再引用）
- `core/`：ApiExecutor、CircuitBreaker、ConfigProvider、EmergencyGuard、HardwareProbe、ModuleFiles、ModuleScheduler、RootChecker、RootExecutor、ScopeGuard、StatusReporter、StrategyExecutor
- `data/CpuUtil.kt`、`util/LogUtil.kt`、全部 `hook/*`（AnimationHook、BackgroundKillHook、BrightnessHook、CurrentApp、FpsHook、GpsHook、ShutdownHook）

### 新架构现状（已落盘的新文件）
- `core/Const.kt`：包名、`content://com.power.manager/config` 与 `/log` 两个 URI。
- `core/SysContext.kt`：任意进程经 `ActivityThread.getSystemContext` 取 Context/ContentResolver（缓存）。
- `core/ConfigChannel.kt`：system_server 等进程经 ContentProvider query 读配置，3s TTL 缓存。
- `core/AppLog.kt`：统一日志（logcat `PowerManager` + `XposedBridge.log` + 经 ContentProvider insert 推送到 App 落盘）。
- `ui/AppConfigProvider.kt`：ContentProvider——`query /config` 返回配置 JSON，`insert /log` 接收各进程日志行。
- `ui/AppLogStore.kt`：App 侧统一日志落盘（各进程经 /log insert 推送，单线程顺序写、2000 行截断）。
- 数据模型已改：`AppConfig` 删 `listMode`/`appList`（规则不存在即默认受管）；`Template` 删 `maxBg`/`cpuFreq`，新增 `cpuThrottle`（0/1/2 档）。

### 重构落地情况（2026-08-17 已完成，CI 验证通过）
- 新增 `ui/AppLogStore.kt`：App 侧统一日志落盘（单线程顺序写、2000 行截断、`read()`/`logFile()` 供日志页）。
- `AndroidManifest.xml` 已注册 `<provider android:name=".ui.AppConfigProvider" authorities="com.power.manager" exported="true">`。
- `PowerManagerModule.kt` 精简为作用域白名单 + system_server 注入日志（`AppLog`），不再注册任何 Hook。
- `ui/AppStore.kt` 删除 Root/chmod/CPU/名单旧逻辑；`copyOf` 只深拷贝 templates/rules；applyTemplate 仅写配置激活模板。
- `ui/screens/`：SettingsScreen 删除名单模式与「清除异常关机回退」（机制已删）；HomeScreen 删除 Root 横幅与硬件能力卡片，summary 改 cpuThrottle；EditScreen 按新 Template 签名（命名参数）+ cpuThrottle 三档；LogScreen 改读 AppLogStore。
- `MainActivity` 删除 `ensureShared`（chmod 方案废弃）；`AppRoot` 删除授权后 `HardwareProbe.scan`（能力扫描机制废弃）。
- `AppConfigProvider.onCreate` 补 `AppStore.init`（provider 可能先于 Activity 启动）。

## 架构（重构目标态）

### 配置/日志互通（新核心）
- 配置读取：system_server 与各进程经 `content://com.power.manager/config` 读模块 App 配置（`ConfigChannel` 带 3s TTL），取代旧 XSharedPreferences + chmod 方案。
- 日志推送：各进程 `AppLog` 写 logcat + XposedBridge，并经 `content://com.power.manager/log` insert 推给 App 统一落盘；App 日志页可滚动查看与复制。

### 数据模型
- `AppConfig`：`templates`（Map<Int,Template>）+ `currentTemplateId`（默认 -3）+ `rules`（Map<包名, AppRule>）。
  - `isManaged(pkg, fg)`：规则不存在 → 默认受管；存在 → 按 `enabledFg`/`enabledBg` 裁决。
  - `killDelayFor(pkg, fallback)`：规则 `killDelay >= 0` 优先，否则回退全局模板。
- `AppRule`（单独应用设置，优先级最高）：`enabledFg` / `enabledBg` / `killDelay`（-1 跟随模板）。
- `Template`：`id`/`name`/`killDelay`/`targetFps`/`cpuThrottle`/`brightnessCap`/`animOff`/`gpsPolicy`/`netPolicy`/`btPolicy`/`batterySaver`。
  - 内置只读预设（`id < 0`）：-3 正常（全放行）、-2 省电（killDelay=120/fps=60/cpuThrottle=1/亮度200/无动画/GPS后台禁/后台禁网/省电模式开）、-1 极限（killDelay=30/fps=30/cpuThrottle=2/亮度80/无动画/GPS禁/后台禁网/关蓝牙/省电模式开）。用户模板 ID ≥ 0。
  - `fromJson` 缺内置模板时自动补齐 -3/-2/-1。

### 保护白名单（`core/Protection.kt`）
`isProtected`：空包名、模块自身、硬豁免集（android、SystemUI、电话、输入法、settings、providers.settings、launcher、Google 搜索等）一律受保护。

### 首次使用授权（仅 App 端一次）
- 未确认时全屏 `ConsentScreen`：声明为测试软件、警告深度系统干预可能导致卡顿/异常/数据丢失甚至无法开机，5 秒倒计时后「允许模块运行」可用。
- 点击写 SharedPreferences `consent` 一次性持久确认，此后不再弹出（硬件能力扫描机制已随重构删除，不再落盘能力基准）。
- 无文件信标/物理熔断；是否生效取决于 LSPosed 管理器中的启用状态，App 端不做二次闸门。

### UI（Compose + Material 3）
- 完全默认原生 Material 3，无任何自定义颜色/样式；组件用标准 M3（Card/AssistChip/AlertDialog/FilterChip/Switch/ChoiceChip），图标只用 material-icons-core。
- 三 Tab：首页（模板列表+应用/新建/编辑/删除）、设置（应用单独设置入口+重置模板+查看日志）、日志。
- 性能约定：日志轮询（3s）在 `Dispatchers.IO`；Toast 用 `LaunchedEffect` 一次性显示并置空；模板列表用 `remember(cfg)` 缓存。
- 实时刷新：`AppStore.load()` 每次返回全新实例；写操作一律「`copyOf` 深拷贝 → 改副本 → `save` → 整体替换 state」，杜绝就地改状态导致 Compose 不重组。

### Hook 拦截点（system_server 作用域，重构前设计）
- 切后台杀进程（Hook `setResumedActivityUncheckLocked`，按模板 kill_delay，通话中豁免）。
- 帧率锁、动画三档全置 0、亮度钳制（仅受限应用）。
- 蓝牙关闭/还原、后台网络单应用 UID 限制（API 失败即放弃不降级，防误伤）、GPS 按策略返回缓存坐标。
- CPU 频率：强制走 Root 写 `scaling_max_freq`，60s 定时重刷；写入内核前兜底 sanitize 防危险频率导致 CPU 挂起。

### 作用域与模块识别
- `assets/scopes.txt` 4 行：system / android / com.android.providers.settings / com.android.phone。
- `AndroidManifest.xml` 必须带 `xposedminversion=82` metadata（LSPosed 以它判定 legacy 模块，`assets/xposed_init` 仅作入口）；当前清单含 `xposedmodule/xposeddescription/xposedminversion/xposedscope`（scope=android;com.android.providers.settings;com.android.phone）。
- 模块实际仅对 `android`（system_server）注册 Hook，其余作用域仅为兼容保留。

## 构建工作流

- 文件：`.github/workflows/build.yml`；触发：push main/master / workflow_dispatch。
- 流程：JDK 17（temurin）→ `android-actions/setup-android`（SDK 缓存）→ sdkmanager 装 platform 35 + build-tools 34.0.0 → `gradle/actions/setup-gradle`（依赖+build 缓存）→ 生成构件号 → `assembleDebug` → `printVersionName` 取版本号 → 上传 APK。
- 版本号：`1.0.0build{YYMMDDHHMM}`（如 1.0.0build2608170442），构件号由 workflow 用 `date +%y%m%d%H%M` 生成一次并经 `-PbuildNumber=` 传入（避免两次调用跨分钟不一致）；无属性时本地默认取当前时间。
- 工作流全程仅 debug（assembleDebug + 上传 APK），无任何发行版/Release 步骤。
- 依赖仓库：Xposed api 走 `https://api.xposed.info/`（jcenter 已死）。
- `gradle.properties`：启用 `org.gradle.caching` 与 `org.gradle.parallel`；**勿启用 `configuration-cache`**（与 AGP 8.7.3 冲突）。
- 构建状态：历史记录 CI 全绿；配置互通重构落地提交（dd31ea8 + 0105756 修复）后 CI 验证通过（run 32035384091 success，14 步全绿，含 assembleDebug 与 APK 上传）。

## 决策日志

| 日期 | 决策 |
|---|---|
| 2026-08-16 | 双模执行器（API 主 / Root 备）；仅 LSPosed；仅 CI 构建；public 仓库；assembleDebug |
| 2026-08-16 | Compose M3 固定深色纯中文；minSdk 26；无 emoji |
| 2026-08-16 | 后台网络=单应用 UID 限制；CPU=还原+60s 定时重刷 |
| 2026-08-16 | 模板存储=JSON+SharedPreferences；应用显示名 Power Manager；README 对齐 assembleDebug |
| 2026-08-16 | 作用域白名单防护（仅注入 android/系统设置/电话，实际仅 system_server 注册 Hook） |
| 2026-08-16 | api:82 仅暴露 XposedBridge.hookAllMethods，统一用 XposedBridge |
| 2026-08-16 | 日志走内部文件 + logcat PowerManager + XposedBridge；App 日志页可复制 |
| 2026-08-16 | 工作流纯 debug 无发行版；版本号经 printVersionName 任务读取 |
| 2026-08-17 | CPU 双审查（切换模板全量审查、写入内核前兜底 sanitize）；配置共享读取（save 后 chmod）；硬件扫描能力自动禁用落盘 caps.json；maxBg 强制 |
| 2026-08-17 | 综合修复：manifest 补 xposedminversion=82 metadata；pmon 信标改 /sdcard；RootChecker 30s TTL；AppStore 弃缓存改 copyOf 深拷贝；UI 重设计标准 M3；轮询移 IO |
| 2026-08-17 | 彻底原生化重构（简化熔断信标、删状态指示器与状态链、系统默认 M3 主题） |
| 2026-08-17 | 彻底移除熔断与授权（删 PhysicalFuse 信标逻辑），App 首次启动全屏「严重警告」ConsentScreen 一次确认 |
| 2026-08-17 | 构件号自动生成：versionName `1.0.0build{YYMMDDHHMM}`，workflow 生成一次并经 `-PbuildNumber=` 传入 |
| 2026-08-17 | 名单/规则重构：单一名单 + 黑/白名单模式切换；新增 AppRule 单独应用设置；模板新增 battery_saver（反射 setPowerSaveMode + Root 写 low_power 双模） |
| 2026-08-17 | **配置互通原生化重构（未提交，中间态）**：删整个 hook/执行器/Root 层与 CpuUtil/LogUtil/全部 hook；新增 ContentProvider 配置/日志通道（AppConfigProvider + ConfigChannel 3s TTL + AppLog 统一日志 + SysContext 任意进程取 Context）；数据模型删名单改「规则缺省即受管」、Template 删 maxBg/cpuFreq 改 cpuThrottle 三档 |
| 2026-08-17 | **配置互通重构落地（CI 验证通过）**：新增 AppLogStore 统一落盘；manifest 注册 AppConfigProvider；模块入口精简为作用域白名单+日志；AppStore 删除 Root/chmod/CPU/名单旧逻辑；Settings/Home/Edit/Log 全 UI 适配新模型；删除名单模式、异常关机回退、硬件能力扫描与 Root 横幅 UI；提交后 CI 全绿 |
