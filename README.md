# Power Manager

LSPosed 电源管理模块。目标是 LSPosed 框架：完全基于 LSPosed 注入 + Xposed Hook 能力实现，**不依赖 root、不做系统层强制手段**。策略执行下沉到每个用户应用进程内，迫使应用符合当前模板（亮度/帧率/动画/GPS/蓝牙/后台冻结与自杀）；system_server 承担后台清理全局协调与蓝牙触发式锁定（事件驱动，无轮询）。

## 核心特性

- 模板化策略管理：内置「正常(-3)」「省电(-2)」「极限(-1)」三档只读模板，支持创建/编辑/删除自定义模板（ID >= 0）。
- 后台清理（系统层协调 + 应用自杀，不依赖 root）：
  - 应用进入后台：system_server（BackgroundKeeper）Hook 前台切换事件，维护后台队列。
  - 后台进程数上限（maxBg）：超过上限立即处决最早进入后台的应用。
  - 超时清理：按 killDelay 计时（事件驱动，非轮询），到点处决。
  - 处决方式：经模块 App ContentProvider 下发命令，目标应用进程内观察者收到后自杀（Process.killProcess），下次启动为系统冷启动，效果等同删除后台。
  - 应用在倒计时内回到前台：自动取消计时，保留存活。
- 应用进程内策略执行（AppPolicyHook，注入每个用户应用）：
  - 后台冻结：cpuThrottle 启用时后台拒绝 WakeLock.acquire（不持锁）。
  - 亮度钳制：brightnessCap 钳制窗口亮度（screenBrightness <= cap/255）。
  - 帧率锁：targetFps 优先，未设时 cpuThrottle>=2 默认 30（拉大 Choreographer 帧间隔）。
  - 动画禁用：animOff 或 cpuThrottle>0 时动画时长置 0（瞬间完成）。
  - GPS 限制：gpsPolicy=0 全拦、=1 后台拦（定位返回 null / 不注册更新）。
  - 蓝牙锁定：btPolicy=0 时应用进程内拦截 BluetoothAdapter.enable，无法重新开启。
- 蓝牙触发式锁定（BluetoothHook，system_server）：切换模板到 btPolicy=0 时立即关闭蓝牙，3 秒后复查一次，之后靠开启请求拦截永久锁定，直到切回不禁用蓝牙的模板；无轮询、无反复尝试关闭。
- 两层裁决链（优先级从高到低）：单独应用规则（前台/后台启用 + killDelay）> 全局激活模板。
- 配置/日志互通：各进程经 ContentProvider（content://com.power.manager/config）读取模块 App 配置（3s TTL）；AppLog 写 logcat + XposedBridge 并经 /log 推送 App 统一落盘，日志行含时间戳与来源应用（包名/进程），日志页可滚动查看与复制。
- 首次启动警告覆盖层：全屏「严重警告」（测试软件声明），5 秒倒计时后一次性确认。
- 保护白名单：系统 UI、电话、输入法、设置、launcher 等受保护进程仅做蓝牙拦截，跳过破坏性策略。

## 架构

| 层级 | 职责 |
| --- | --- |
| UI 表现层（App） | 模板管理、应用单独设置、后台进程数上限、首次警告覆盖层、日志页（Compose + Material 3，纯中文） |
| 系统层（协调） | BackgroundKeeper（后台队列/超限处决/超时计时）+ BluetoothHook（触发式蓝牙锁定），事件驱动无轮询 |
| 应用策略层 | AppPolicyHook：注入每个用户应用进程，执行模板策略（冻结/亮度/帧率/动画/GPS/蓝牙）+ 接收处决指令自杀 |
| 模块 App（中转） | AppConfigProvider（/config /log /bg 通道）+ BackgroundManager（处决标记应答）+ AppLogStore（日志落盘，来源标注） |
| 数据持久层 | JSON 字符串存 SharedPreferences（键 config） |

### 后台清理流程

1. 应用 A 进入后台：system_server BackgroundKeeper 检测到前台切换，A 入队。
2. 若后台数超过 maxBg 上限：立即处决最早进入后台的应用 B（经 provider 下发命令 -> B 进程观察者自杀）。
3. 同时按 killDelay 为 A 启动计时；计时到点处决 A。
4. A 在计时内回到前台：出队并取消计时，A 保留存活。
5. 处决执行：目标应用进程收到 /bg 广播后自查仍在后台，确认被标记则 Process.killProcess 自杀。

### 配置/日志互通

1. 各进程经 content://com.power.manager/config query 读取配置 JSON（ConfigChannel 3s TTL）。
2. 各进程 AppLog 写 logcat（tag PowerManager）+ XposedBridge，并经 content://com.power.manager/log insert 推送到 App，由 AppLogStore 单线程顺序落盘（5000 行截断），落盘行含时间戳与来源（AppLog.setProcess 标注包名/system_server/provider）。
3. 后台指令通道 content://com.power.manager/bg：system_server insert（kill 命令）、应用进程 query（check 应答），Binder 调用方来源标注。

### 数据模型

- AppConfig：templates + currentTemplateId（默认 -3）+ rules（Map<包名, AppRule>）+ maxBg（最大后台进程数，-1 不限）。规则不存在即默认受管。
- AppRule（单独应用设置，优先级最高）：enabledFg / enabledBg / killDelay（-1 跟随模板）。
- Template：killDelay/targetFps/cpuThrottle/brightnessCap/animOff/gpsPolicy/netPolicy/btPolicy/batterySaver。内置只读预设 ID < 0，用户模板 ID >= 0。

## 使用

1. 通过 LSPosed 管理器激活模块，为模块勾选作用域：**全部应用**（android 系统服务包含其中，用于后台协调与蓝牙锁定；assets/scopes.txt 仅为建议参考）。
2. 打开 App → 阅读首次启动的「严重警告」覆盖层，等待 5 秒后点击「允许模块运行」完成一次性确认。
3. 首页选择模板点击「应用」；可新建/编辑自定义模板（清理倒计时、帧率、CPU 节流、亮度上限、动画、GPS、蓝牙等）。
4. 设置页可维护「后台进程数上限」（-1 不限，超过后处决最早后台应用）与「应用单独设置」（单应用 killDelay / 前台后台启用）。
5. 修改配置后在 LSPosed 重启系统生效（注入发生在进程启动时；模板切换实时生效）。

模块对作用域内所有 Hook 均包裹异常防护，防止错误扩散影响应用与系统稳定性；受保护应用仅做蓝牙拦截。

## 策略项（模板字段 -> 执行）

| 策略项 | 配置字段 | 执行方式 |
| --- | --- | --- |
| 后台清理 | killDelay | system_server 计时，到点下发处决 -> 应用自杀 |
| 后台进程数上限 | maxBg | 超限立即处决最早后台应用 |
| 后台冻结 | cpuThrottle>0 | 应用后台拒绝 WakeLock.acquire |
| 帧率锁 | targetFps（未设时 cpuThrottle>=2 -> 30） | hook Choreographer 帧间隔 |
| 禁用动画 | animOff / cpuThrottle>0 | ValueAnimator/Animation 时长置 0 |
| 亮度钳制 | brightnessCap（1..255） | 窗口亮度 screenBrightness <= cap/255 |
| GPS 策略 | gpsPolicy（0 全拦 / 1 后台拦 / 2 放行） | 定位返回 null / 不注册更新 |
| 蓝牙锁定 | btPolicy=0 | system_server 触发式关闭 + 各进程拦截开启 |
| 后台网络 | netPolicy | 暂未在应用层实现 |
| 系统省电模式 | batterySaver | 暂未在应用层实现 |

## 构建

模块仅通过 GitHub Actions 构建（本地绝不构建），工作流带 SDK 下载缓存与 Gradle 依赖/build 缓存以加速二次构建。

```bash
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
# 直接作为 LSPosed 模块安装即可
```

版本号格式：1.0.0build{YYMMDDHHMM}（如 1.0.0build2608170442），构件号按构建时间自动生成，可用 -PbuildNumber=xxxx 覆盖。

依赖要求：de.robv.android.xposed:api:82（compileOnly）；目标 SDK 35，最低 Android 8.0（API 26）。

## 日志

- 日志覆盖：应用启动/进入后台/返回前台、亮度调整、权限调用拦截（GPS/蓝牙/WakeLock）、后台计时开始/取消/超时处决、处决命令通讯（Binder 来源）、Hook 注册等关键节点。
- 日志行格式：时间戳 [级别/来源应用] 消息；来源经 AppLog.setProcess 标注（包名/system_server/provider）。
- App 内日志页可滚动查看并可一键复制；也可 adb logcat -s PowerManager 查看。
- 内部日志文件：/data/user/0/com.power.manager/files/power_manager.log。

## 许可证

GPL v3

