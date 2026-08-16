# 项目记忆 · power_manager

> 本文件是项目的长期记忆。任何代码变更落地后，必须同步更新本文件（新增决策、改动的架构、构建状态等）。

## 项目定位

LSPosed 系统框架电源管理模块（仅作用于 `system_server` 与系统框架进程），通过「System API 主管线 + Root Shell 备分管线」双模执行器实现精细化功耗压制，提供物理级熔断自救。

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
| UI | Jetpack Compose + Material 3，固定深色主题，纯中文 |
| 存储 | JSON 字符串存 SharedPreferences（键 `config`），system_server 经 `XSharedPreferences` 读取；状态/日志经文件互通 |
| Xposed API | `de.robv.android.xposed:api:82`（compileOnly） |
| CI 运行 JDK | temurin 17 |

## 架构

### 三层裁决链（优先级从高到低）
1. 物理熔断：轮询 8 个 `pmoff` 路径 + `/pmon` 授权信标（不存在即熔断）。
2. 单独应用覆盖规则。
3. 黑白名单 + 全局激活模板。

### 物理熔断（8 + 1 路径，注入前轮询，任一命中即 return）
```
/data/local/tmp/pmoff
/data/local/tmp/pmoff.txt
/sdcard/pmoff
/sdcard/pmoff.txt
/storage/emulated/0/pmoff
/cache/pmoff
/system/pmoff
/pmoff
/pmon   # 授权信标：不存在或不可读 → 熔断（未授权）
```
- 首次授权：App 内「允许模块运行」→ 3 秒倒计时确认 → 创建 `/pmon` + 删除所有目录 `pmoff` → 重启生效。
- **熔断触发时：立即 su 写回 `cpuinfo_max_freq` 强制恢复 CPU。**

### 双模执行器（StrategyExecutor）
- 主管线：System API（system UID）。
- 备分管线：su -c（300ms 超时 + 强制回收进程）。
- 降级触发：SecurityException / IllegalStateException / RemoteException / 返回 false。
- CPU 频率：无 API，强制走备分管线（Root Shell）。
- 熔断（CircuitBreaker）：策略项连续 5 次 API 失败 → 标记「不稳定」，下次直走备分。
- 模式上报：system_server 写状态文件，App 经 ContentProvider 实时读取。
- 日志分级：API 成功=DEBUG，API 失败转 Root=INFO，Root 失败=WARN。

### 模板管理
- 字段：name / max_bg / kill_delay / target_fps / cpu_freq / brightness_cap / anim_off / gps_policy / net_policy / bt_policy（-1 不限）。
- 内置只读预设：-3 正常（全放行）、-2 省电、-1 极限。用户模板 ID≥0 递增，复制/空白（=复制 -3）新建。
- 编辑页快捷填充仅 setText 不自动保存；CPU 动态换算（小数≤1.0 × cpuinfo_max_freq，>1.0 视为 KHz，20% 安全阈值低于自动转 -1 + Toast，防超频钳制）。
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

### 保护白名单（杀后台永不触碰，核心系统）
system_server、launcher、SystemUI、电话、输入法。另有硬豁免：电话、输入法、系统 UI。

## 构建工作流

- 文件：`.github/workflows/build.yml`
- 触发：push / workflow_dispatch
- 产物：`app/build/outputs/apk/debug/app-debug.apk`，artifact 命名 `power_manager_v{version}.apk`（版本号由 `:app:printVersionName` 任务输出）
- 工作流全程仅 debug（assembleDebug + 上传 APK），**无任何发行版/Release 相关步骤**
- 依赖仓库：Xposed api 走 `https://api.xposed.info/`（jcenter 已死）
- `gradle.properties`：启用 `org.gradle.caching`，**禁用 `configuration-cache`**（与 AGP 8.7.3 冲突）
- 构建状态：2026-08-16 修复后 CI 全绿（8 步全通过，约 1m30s）

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
