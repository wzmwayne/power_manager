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
| UI | Jetpack Compose + Material 3，完全默认原生主题（跟随系统深浅色，无自定义颜色/样式），纯中文 |
| 存储 | JSON 字符串存 SharedPreferences（键 `config`），system_server 经 `XSharedPreferences` 读取；状态/日志经文件互通 |
| Xposed API | `de.robv.android.xposed:api:82`（compileOnly） |
| CI 运行 JDK | temurin 17 |

## 架构

### 两层裁决链（优先级从高到低）
1. 单独应用设置规则（`AppRule`：前台/后台启用 + 后台杀死时间，覆盖名单与模板）。
2. 应用名单（单一名单 + 黑/白名单模式切换）+ 全局激活模板。

### 应用名单（设置页单一名单 + 模式切换）
- 黑名单模式：名单内应用按模板受限，其余不受影响。
- 白名单模式：名单内应用豁免，其余应用一律按模板受限（运行时由 system_server 枚举已装应用补全网络限制）。
- 名单字段：`list_mode`（0 黑名单 / 1 白名单）+ `list`（单一名单）。测试阶段不考虑旧版兼容，不做旧字段迁移。

### 单独应用设置（AppRule，优先级最高）
- 字段：`enabled_fg` / `enabled_bg`（前台/后台启用）+ `kill_delay`（后台杀死时间，-1 跟随模板）。
- 存储：`rules` Map（键=包名，值=AppRule）。裁决时 `isManaged(pkg, fg)` 优先查规则，其次名单；`killDelayFor(pkg, tpl)` 优先规则值。

### 首次使用授权（仅 App 端一次）
- App 首次启动进入全屏「严重警告」覆盖层：声明本应用为测试软件，警告深度系统干预可能导致卡顿/异常/数据丢失甚至无法开机，后果自负。
- 5 秒倒计时后「允许模块运行」按钮可用，点击即写入 `SharedPreferences`（键 `consent`）一次性持久确认，此后不再弹出，直接进入主界面。
- 无任何文件信标/物理熔断；是否生效完全取决于用户在 LSPosed 管理器中对模块的启用状态，App 端不做二次闸门。

### 双模执行器（StrategyExecutor）
- 主管线：System API（system UID）。
- 备分管线：su -c（300ms 超时 + 强制回收进程）。
- 降级触发：SecurityException / IllegalStateException / RemoteException / 返回 false。
- CPU 频率：无 API，强制走备分管线（Root Shell）；写入内核前强制 `CpuUtil.sanitize` 兜底审查，非法/低于 20% 安全线/超频一律恢复 max，杜绝危险频率导致 CPU 挂起。
- 熔断（CircuitBreaker）：策略项连续 5 次 API 失败 → 标记「不稳定」，下次直走备分。
- 日志分级：API 成功=DEBUG，API 失败转 Root=INFO，Root 失败=WARN。

### 模板管理
- 字段：name / max_bg / kill_delay / target_fps / cpu_freq / brightness_cap / anim_off / gps_policy / net_policy / bt_policy / battery_saver（-1 不限；battery_saver 启用模板时开启系统省电模式）。
- 内置只读预设：-3 正常（全放行）、-2 省电、-1 极限（均 battery_saver=true）。用户模板 ID≥0 递增，复制/空白（=复制 -3）新建。
- 编辑页快捷填充仅 setText 不自动保存；CPU 动态换算（小数≤1.0 × cpuinfo_max_freq，>1.0 视为 KHz，20% 安全阈值低于自动转 -1 + Toast，防超频钳制）。
- CPU 双审查：切换模板时 `sanitizeAllCpu` 强制审查所有模板，-2 哨兵（小数倍率）自动解析为真实 KHz 写回，非法值修复为安全值；写入内核前（`RootExecutor.writeCpuMaxFreq`）再次兜底审查。
- 硬件扫描与能力自动禁用：首次授权确认时 `HardwareProbe.scan` 扫描 CPU 基准（max 频率/核数）并逐项测试能力（CPU 调频/帧率锁/动画/蓝牙/网络/GPS），落盘 `files/caps.json`（666）；运行时各 Hook 与调度按能力自动跳过不支持项，UI 编辑页自动禁用并提示。
- max_bg 强制：`BackgroundKillHook` 15s 周期枚举后台受限进程（按 importance 排序），超限清理最不重要者。
- 配置共享读取：`MODE_PRIVATE` 落盘 600 权限 system_server 读不到，每次 `save` 必须 `commit()` 同步落盘后经 Root `chmod shared_prefs 777` + `config.xml 666`。
- 生命周期：应用即刷策略（CPU 即时，其余 Hook 实时读缓存）；删除激活模板回 -3；设置页重置所有模板。
- 异常关机自动回退 -3：Hook `PowerManagerService` shutdown 写优雅退出标记，缺失且激活非 -3 时回退。

### Hook 拦截点（system_server 作用域）
- 切后台杀进程（Hook `setResumedActivityUncheckLocked`，按模板 kill_delay，通话中豁免）。
- 帧率锁（自定义输入，仅受限应用生效）。
- 动画三档（animator/transition/window）全置 0。
- 亮度钳制（0-255，仅受限应用）。
- 蓝牙关闭/还原原状态。
- 后台网络：单应用 UID 限制（API 失败即放弃，不降级，防误伤）。
- GPS：按 gps_policy（0 禁/1 后台禁/2 放行），受限应用返回缓存坐标。
- CPU 频率：强制走 Root，60s 定时重刷。

### 作用域（assets/scopes.txt，4 行）
```
system
android
com.android.providers.settings
com.android.phone
```
- LSPosed 识别 legacy 模块：`AndroidManifest.xml` 必须带 `xposedminversion` metadata（LSPosed 以它判定模块，`assets/xposed_init` 仅作入口）。当前清单含 `xposedmodule/xposeddescription/xposedminversion=82/xposedscope`（scope 用 legacy 命名，管理器按旧版规则展示）。

### Root 检测（RootChecker）
- 检测结果带 30s TTL 自动重查，不再首次结果永久缓存；`forceRefresh()` 供授权成功后立即刷新，避免「已授予 root 仍显示缺失」。
- 注意：APatch/KernelSU 按应用白名单授权，system_server 进程内 `su` 可能被拒，状态如实反映该进程实际能力。

### UI（Compose + Material 3）
- 完全默认原生 Material 3：`darkColorScheme()`/`lightColorScheme()` 跟随系统深浅色，无任何自定义颜色/样式；组件只用标准 M3（Card/AssistChip/AlertDialog/FilterChip/Switch），图标只用 material-icons-core（Home/Settings/Info/Delete/Edit/ArrowBack/Refresh）。注意 material3 1.3.0 无 Banner 组件，覆盖层用 Card+Text+Button 渲染。
- 首次启动全屏「严重警告」覆盖层（`ConsentScreen`，5 秒倒计时后「允许模块运行」可用），确认后写 `consent` 标志永不再弹；确认同时后台 `HardwareProbe.scan` 落盘能力基准。
- 首页不显示任何运行模式指示器；缺 Root 时显示 Root 缺失横幅提示；首页整体可滚动（Column + verticalScroll，模板列表不再独占内部滚动）。
- 设置页：单一名单 + 黑/白名单模式切换（FilterChip）；「应用单独设置」按钮进入 AppRulesScreen 管理每应用 AppRule。
- 性能：root 轮询（3s）全部在 `Dispatchers.IO` 执行，绝不占主线程；Toast 用 `LaunchedEffect` 一次性显示并置空；模板列表用 `remember(cfg)` 缓存。
- 实时刷新：`AppStore.load()` 每次返回全新实例（弃用对象缓存），写操作一律「`copyOf` 深拷贝 → 改副本 → `save` → 整体替换 state」，杜绝就地改状态对象导致 Compose 不重组。

### 保护白名单（杀后台永不触碰，核心系统）
system_server、launcher、SystemUI、电话、输入法。另有硬豁免：电话、输入法、系统 UI。

## 构建工作流

- 文件：`.github/workflows/build.yml`
- 触发：push / workflow_dispatch
- 产物：`app/build/outputs/apk/debug/app-debug.apk`，artifact 命名 `power_manager_v{version}.apk`（版本号由 `:app:printVersionName` 任务输出）。versionName 格式 `1.0.0build{YYMMDDHHMM}`，构件号在 workflow 中由 `date +%y%m%d%H%M` 生成一次并 `-PbuildNumber=` 传给 Gradle（避免两次调用跨分钟不一致）
- 工作流全程仅 debug（assembleDebug + 上传 APK），**无任何发行版/Release 相关步骤**
- 依赖仓库：Xposed api 走 `https://api.xposed.info/`（jcenter 已死）
- `gradle.properties`：启用 `org.gradle.caching`，**禁用 `configuration-cache`**（与 AGP 8.7.3 冲突）
- 构建状态：2026-08-16 修复后 CI 全绿（8 步全通过，约 1m30s）；2026-08-17 综合修复后 CI 全绿；2026-08-17 原生化重构后 CI 全绿（修复 material3 1.3.0 无 Banner，改 Card 渲染）；2026-08-17 熔断移除后 CI 全绿；2026-08-17 名单/规则重构 + 省电模板后 CI 全绿（修复 setPowerSaveMode 隐藏 API 改反射）

## 决策日志

| 日期 | 决策 |
|---|---|
| 2026-08-16 | 双模执行器（API 主 / Root 备）；仅 LSPosed；仅 CI 构建；public 仓库；assembleDebug |
| 2026-08-16 | Compose M3 固定深色纯中文；minSdk 26；无 emoji |
| 2026-08-16 | 后台网络=单应用 UID 限制；CPU=还原+60s 定时重刷；熔断时 CPU 强制恢复 |
| 2026-08-16 | 模式指示器=ContentProvider 实时读；模板存储=JSON+SharedPreferences |
| 2026-08-16 | 应用显示名 Power Manager；README 对齐 assembleDebug |
| 2026-08-16 | 作用域白名单防护（仅注入 android/系统设置/电话，实际仅 system_server 注册 Hook） |
| 2026-08-16 | api:82 仅暴露 XposedBridge.hookAllMethods，统一用 XposedBridge；XSharedPreferences 存模板 |
| 2026-08-16 | NetworkPolicyManager 不在公开 SDK，后台网络改纯反射 |
| 2026-08-16 | 日志走内部文件 + logcat PowerManager + XposedBridge；App 日志页可复制 |
| 2026-08-16 | 工作流纯 debug 无发行版；版本号经 printVersionName 任务读取 |
| 2026-08-17 | CPU 双审查：切换模板全量审查并自动将 -2 哨兵解析为真实 KHz；写入内核前兜底 sanitize，防 CPU 挂起 |
| 2026-08-17 | 配置共享读取：save 后 commit+su chmod shared_prefs 777/config.xml 666，供 system_server 读取 |
| 2026-08-17 | 授权时硬件扫描（CPU 基准）+ 能力测试落盘 caps.json，运行时自动禁用不支持项；实现 maxBg 强制；熔断全局标志覆盖全部 Hook；authorized 状态同步 |
| 2026-08-17 | 综合修复：manifest 补 xposedminversion=82 metadata（LSPosed 以此识别 legacy 模块，解决模块不在列表）；pmon 信标改 /sdcard/pmon（兼容 /pmon，解决 APatch 根目录只读写失败）；RootChecker 30s TTL+forceRefresh（解决授权后仍报 root 缺失）；AppStore 弃缓存改 copyOf 深拷贝触发重组（解决模板实时刷新）；UI 重设计标准 M3（图标 NavigationBar/TopAppBar/Card/AssistChip）；轮询移 IO + Toast 一次性 + items key（解决滚动卡顿） |
| 2026-08-17 | 彻底原生化重构：熔断简化仅 /sdcard/pmon + /sdcard/pmoff 两个文件（删除 8 路径冗余与旧 /pmon 兼容）；删除运行模式指示器及 status.json/StatusProvider/ContentProvider 状态链（StatusReporter 仅保留 cpuFreqApplied/btDisabledByModule 供调度使用）；主题改系统默认 darkColorScheme/lightColorScheme 跟随深浅色、去全部自定义颜色；熔断时强制显示「允许模块运行」Banner，缺 Root 仅显示横幅提示 |
| 2026-08-17 | 彻底移除熔断与授权：删除 PhysicalFuse.kt 及 pmon/pmoff 全部信标逻辑（模块注入/调度/5 个 Hook 不再做熔断检查）；AppStore 删除 authorize/revoke，新增 consent 持久标志；App 首次启动全屏「严重警告」覆盖层（ConsentScreen，声明为测试软件、警告危险性，5 秒倒计时后允许，仅一次）；HomeScreen 删除熔断横幅/授权弹窗，保留 Root 缺失横幅与硬件能力卡片；Settings 删除「停用模块（物理熔断）」入口 |
| 2026-08-17 | 构件号自动生成：versionName 改 `1.0.0build{YYMMDDHHMM}`（如 1.0.0build2608170442），构件号由 workflow 用 `date +%y%m%d%H%M` 生成一次并经 `-PbuildNumber=` 传入（build.gradle.kts 支持属性覆盖，无属性时本地默认取当前时间） |
| 2026-08-17 | 名单/规则重构：黑白名单合并为单一名单 + 黑/白名单模式切换（`list_mode`+`list`，测试阶段不考虑旧版兼容，无迁移）；新增 AppRule 单独应用设置（enabled_fg/enabled_bg/kill_delay，优先级最高，`isManaged`/`killDelayFor` 裁决）；设置页新增 AppRulesScreen；首页整体可滚动（Column+verticalScroll）；模板新增 battery_saver（启用时开启系统省电模式，ApiExecutor 反射 `setPowerSaveMode` + Root 写 low_power 双模）；白名单模式下 system_server 枚举已装应用补全网络限制 |
