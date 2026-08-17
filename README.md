# Power Manager

LSPosed 系统框架电源管理模块，仅作用于 `system_server` 与系统框架进程，不侵入第三方应用进程。通过模板化策略对系统进行精细化功耗压制。

## 核心特性

- 模板化策略管理：内置「正常(-3)」「省电(-2)」「极限(-1)」三档只读模板，支持无限创建/编辑/删除自定义模板（ID >= 0）。模板可配置清理倒计时、锁定帧率、CPU 节流三档、亮度上限、禁用动画、GPS/网络/蓝牙策略与系统省电模式。
- 两层裁决链（优先级从高到低）：
  1. 单独应用设置规则（前台/后台启用 + 后台杀死时间）
  2. 全局激活模板
- 配置/日志互通：system_server 与各进程经 ContentProvider（`content://com.power.manager/config`）读取模块 App 配置（带 3 秒 TTL 缓存），取代旧的 XSharedPreferences + chmod 方案。
- 统一日志：各进程日志同时写 logcat（tag `PowerManager`）与 XposedBridge，并经 `content://com.power.manager/log` 推送到 App 统一落盘，App 日志页可滚动查看与复制。
- 首次启动警告覆盖层：App 首次启动显示全屏「严重警告」（本应用为测试软件，深度系统干预可能导致卡顿/异常/数据丢失甚至无法开机），5 秒倒计时后点击「允许模块运行」，确认一次后不再弹出。
- 作用域白名单防护：模块仅注入 `android`（system_server）等声明作用域，绝不 Hook 其他应用；内置硬豁免集（系统 UI、电话、输入法、设置等）保护关键进程。

## 架构

| 层级 | 职责 |
| --- | --- |
| UI 表现层（App） | 模板管理、应用单独设置、首次警告覆盖层、日志页（Compose + Material 3，纯中文） |
| 数据持久层 | JSON 字符串存 SharedPreferences（键 `config`）；经 ContentProvider（`/config`、`/log`）与各进程互通 |
| 配置通道 | `ConfigChannel`（3s TTL 缓存）+ `SysContext`（任意进程取 Context） |
| 日志通道 | `AppLog`（logcat + XposedBridge + provider 推送）+ `AppLogStore`（App 侧统一落盘） |
| 模块入口 | `PowerManagerModule`：作用域白名单检查 + system_server 注入日志 |

### 配置/日志互通

1. system_server 与各进程经 `content://com.power.manager/config` query 读取模块 App 配置 JSON（`ConfigChannel` 带 3s TTL，避免高频 Binder 往返）。
2. 各进程 `AppLog` 写 logcat + XposedBridge，并经 `content://com.power.manager/log` insert 推送到 App，由 `AppLogStore` 单线程顺序落盘（2000 行截断防膨胀）。
3. App 内日志页 3 秒轮询读取日志文件，可一键复制。

### 数据模型

- `AppConfig`：`templates`（Map<Int,Template>）+ `currentTemplateId`（默认 -3）+ `rules`（Map<包名, AppRule>）。规则不存在即默认受管。
- `AppRule`（单独应用设置，优先级最高）：`enabledFg` / `enabledBg` / `killDelay`（-1 跟随模板）。
- `Template`：`id`/`name`/`killDelay`/`targetFps`/`cpuThrottle`/`brightnessCap`/`animOff`/`gpsPolicy`/`netPolicy`/`btPolicy`/`batterySaver`。内置只读预设 ID < 0，用户模板 ID >= 0；`fromJson` 缺内置模板时自动补齐。

## 使用

1. 通过 LSPosed 管理器激活模块，作用域仅勾选：
   ```
   android
   com.android.providers.settings
   com.android.phone
   ```
2. 打开 App → 阅读首次启动的「严重警告」覆盖层，等待 5 秒后点击「允许模块运行」完成一次性确认，随后正常使用。
3. 在首页选择模板点击「应用」，可新建/编辑自定义模板；在设置页进入「应用单独设置」为指定应用配置前台/后台启用状态与后台杀死时间。

模块绝不在作用域之外 Hook 任何应用；对作用域内所有 Hook 均包裹异常防护，防止错误扩散影响系统稳定性。

## 策略项（配置模型，执行器重构中）

| 策略项 | 配置字段 | 说明 |
| --- | --- | --- |
| 后台清理 | `killDelay` | 切后台后秒数（0 即杀）；应用规则优先于模板 |
| 帧率锁 | `targetFps` | 锁定目标刷新率 |
| CPU 节流 | `cpuThrottle` | 三档：0 不限 / 1 省电 / 2 极限 |
| 禁用动画 | `animOff` | 系统动画三档置 0 |
| 亮度钳制 | `brightnessCap` | 亮度上限 0-255 |
| GPS 策略 | `gpsPolicy` | 0 禁用 / 1 后台禁用 / 2 放行 |
| 后台网络 | `netPolicy` | 0 禁止 / 1 放行 |
| 蓝牙策略 | `btPolicy` | 0 关闭 / 1 保持 |
| 系统省电模式 | `batterySaver` | 开关 |

## 构建

模块仅通过 GitHub Actions 构建（本地绝不构建），工作流带 SDK 下载缓存与 Gradle 依赖/build 缓存以加速二次构建。

```bash
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
# 直接作为 LSPosed 模块安装即可
```

版本号格式：`1.0.0build{YYMMDDHHMM}`（如 `1.0.0build2608170442`），构件号按构建时间自动生成，可用 `-PbuildNumber=xxxx` 覆盖。

依赖要求：`de.robv.android.xposed:api:82`（compileOnly）；目标 SDK 35，最低 Android 8.0（API 26）。

## 日志

- 日志分级：DEBUG / INFO / WARN / ERROR。
- App 内日志页可滚动查看并可一键复制；也可 `adb logcat -s PowerManager` 查看。
- 内部日志文件：`/data/user/0/com.power.manager/files/power_manager.log`。

## 许可证

GPL v3
