# Power Manager

LSPosed 电源管理模块。目标是 LSPosed 框架：完全基于 LSPosed 注入 + Xposed Hook 能力实现，**不依赖 root、不做系统层强制手段**。策略执行下沉到每个用户应用进程内，迫使应用符合当前模板（亮度/帧率/动画/GPS/蓝牙/后台清理与冻结）；`system_server` 仅保留最小系统层能力（蓝牙强制关闭）。

## 核心特性

- 模板化策略管理：内置「正常(-3)」「省电(-2)」「极限(-1)」三档只读模板，支持创建/编辑/删除自定义模板（ID >= 0）。
- 应用进程内策略执行（AppPolicyHook，注入每个用户应用）：
  - 后台自杀清理：killDelay 倒计时到点后应用进程自我退出，下次启动为系统冷启动，效果等同删除后台（0 立即杀，回前台取消）。
  - 后台冻结：cpuThrottle 启用时后台拒绝 WakeLock.acquire（不持锁）。
  - 亮度钳制：brightnessCap 启用时钳制窗口亮度（screenBrightness <= cap/255）。
  - 帧率锁：targetFps 优先，未设时 cpuThrottle>=2 默认 30（拉大 Choreographer 帧间隔）。
  - 动画禁用：animOff 或 cpuThrottle>0 时动画时长置 0（瞬间完成）。
  - GPS 限制：gpsPolicy=0 全拦、=1 后台拦（定位返回 null / 不注册更新）。
  - 蓝牙锁定：btPolicy=0 时应用进程内拦截 BluetoothAdapter.enable，无法重新开启。
- 系统层蓝牙（BluetoothHook，仅 system_server）：激活 btPolicy=0 时强制关闭蓝牙，30s 周期检查兜底；非 root 下应用进程无法主动关闭蓝牙，故保留此最小系统层能力。
- 两层裁决链（优先级从高到低）：单独应用规则（前台/后台启用 + killDelay）> 全局激活模板。
- 配置/日志互通：各进程经 ContentProvider（content://com.power.manager/config）读取模块 App 配置（3s TTL）；AppLog 写 logcat + XposedBridge 并经 /log 推送 App 统一落盘，日志页可滚动查看与复制。
- 首次启动警告覆盖层：全屏「严重警告」（测试软件声明），5 秒倒计时后一次性确认。
- 保护白名单：系统 UI、电话、输入法、设置、launcher 等受保护进程仅做蓝牙拦截，跳过破坏性策略。

## 架构

| 层级 | 职责 |
| --- | --- |
| UI 表现层（App） | 模板管理、应用单独设置、首次警告覆盖层、日志页（Compose + Material 3，纯中文） |
| 数据持久层 | JSON 字符串存 SharedPreferences（键 config）；经 ContentProvider（/config、/log）与各进程互通 |
| 应用策略层 | AppPolicyHook：注入每个用户应用进程，执行模板策略（后台自杀/冻结/亮度/帧率/动画/GPS/蓝牙） |
| 系统层（最小） | BluetoothHook（system_server 强制关闭蓝牙）+ SystemScheduler（30s 周期检查） |
| 配置/日志通道 | ConfigChannel（3s TTL）+ AppLog/AppLogStore（统一落盘） |

### 配置/日志互通

1. 各进程经 content://com.power.manager/config query 读取模块 App 配置 JSON（ConfigChannel 带 3s TTL）。
2. 各进程 AppLog 写 logcat（tag PowerManager）+ XposedBridge，并经 content://com.power.manager/log insert 推送到 App，由 AppLogStore 单线程顺序落盘（5000 行截断）。
3. App 内日志页 3 秒轮询读取日志文件，可一键复制。

### 数据模型

- AppConfig：templates（Map<Int,Template>）+ currentTemplateId（默认 -3）+ rules（Map<包名, AppRule>）。规则不存在即默认受管。
- AppRule（单独应用设置，优先级最高）：enabledFg / enabledBg / killDelay（-1 跟随模板）。
- Template：killDelay/targetFps/cpuThrottle/brightnessCap/animOff/gpsPolicy/netPolicy/btPolicy/batterySaver。内置只读预设 ID < 0，用户模板 ID >= 0；fromJson 缺内置模板时自动补齐。

## 使用

1. 通过 LSPosed 管理器激活模块，为模块勾选作用域：**全部应用**（android 系统服务包含其中，用于蓝牙锁定；assets/scopes.txt 仅为建议参考）。
2. 打开 App → 阅读首次启动的「严重警告」覆盖层，等待 5 秒后点击「允许模块运行」完成一次性确认。
3. 在首页选择模板点击「应用」；可新建/编辑自定义模板（清理倒计时、帧率、CPU 节流三档、亮度上限、动画、GPS、蓝牙等）；设置页可进入「应用单独设置」为指定应用配置前台/后台启用与 killDelay。

模块对作用域内所有 Hook 均包裹异常防护，防止错误扩散影响应用与系统稳定性；受保护应用仅做蓝牙拦截。

## 策略项（模板字段 -> 应用进程内执行）

| 策略项 | 配置字段 | 执行方式（AppPolicyHook） |
| --- | --- | --- |
| 后台清理 | killDelay | 倒计时到点应用自杀（0 立即杀，<0 不杀），下次冷启动 |
| 后台冻结 | cpuThrottle>0 | 后台拒绝 WakeLock.acquire |
| 帧率锁 | targetFps（未设时 cpuThrottle>=2 -> 30） | hook Choreographer 帧间隔 |
| 禁用动画 | animOff / cpuThrottle>0 | ValueAnimator/Animation 时长置 0 |
| 亮度钳制 | brightnessCap（1..255） | 窗口亮度 screenBrightness <= cap/255 |
| GPS 策略 | gpsPolicy（0 全拦 / 1 后台拦 / 2 放行） | 定位返回 null / 不注册更新 |
| 蓝牙锁定 | btPolicy=0 | 系统层强制关闭 + 各进程拦截开启 |
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

- 日志分级：DEBUG / INFO / WARN / ERROR，覆盖状态变更、后台判定、定时器触发、豁免检查、Hook 注册等节点。
- App 内日志页可滚动查看并可一键复制；也可 adb logcat -s PowerManager 查看。
- 内部日志文件：/data/user/0/com.power.manager/files/power_manager.log。

## 许可证

GPL v3

