# Power Manager

LSPosed 系统框架电源管理模块，仅作用于 `system_server` 与系统框架进程，不侵入第三方应用进程。通过「System API 主管线 + Root Shell 备分管线」双模执行器实现精细化功耗压制，提供物理级熔断自救机制。

## 核心特性

- 模板化策略管理：内置「正常(-3)」「省电(-2)」「极限(-1)」三档只读模板，支持无限创建/编辑/删除自定义模板（ID >= 0）。
- 三层裁决链（优先级从高到低）：
  1. 物理熔断（pmoff + pmon）
  2. 单独应用覆盖规则
  3. 黑白名单 + 全局激活模板
- 双模执行器（API 主 / Root 备）：
  - 主管线：利用 `system_server` 的 `system` UID 调用 Android 原生 API（杀进程、锁帧、禁网、亮度等）。
  - 备分管线：API 失败或 CPU 调频等无 API 场景，自动降级为 `su -c` Root Shell 保底执行。
- 物理级熔断自救（多目录冗余）：支持 8 个目录的 `pmoff` 文件探测 + `/sdcard/pmon` 授权信标机制，无需卸载模块即可彻底断电。
- CPU 频率智能换算：支持直接输入 KHz 整数或小数（如 `0.6` 代表最高频的 60%），自动钳制上限，低于最高频 20% 自动转 `-1`（不限），防止死机。
- 异常关机自动回退「正常模式(-3)」：Hook 关机流程写入优雅退出标记，缺失即回退并强制恢复 CPU。

## 架构

| 层级 | 职责 |
| --- | --- |
| UI 表现层（App） | 模板管理、黑白名单、激活引导与运行模式指示 |
| 数据持久层 | JSON 字符串存 SharedPreferences（键 `config`） |
| 策略引擎层 | 配置组装、CPU 标准化换算、Root 可用性预检 |
| Hook 注入层（LSPosed） | 注入 `system_server`，执行裁决链与双模执行器 |

### 双模执行器（StrategyExecutor）

1. 接收指令（杀后台 / 锁帧率 / 禁动画 / 钳亮度 / 关蓝牙 / 禁网 / 限 CPU）。
2. 尝试主管线（System API），捕获返回值与异常。
3. 成功则记录日志「API 模式」并结束。
4. 失败（SecurityException / IllegalStateException / RemoteException / 返回 false）则降级备分管线。
5. 备分管线执行 `su -c` 命令（300ms 超时 + 强制回收进程）。
6. 策略项连续 5 次 API 失败，熔断标记「不稳定」，下次直走备分。

CPU 频率无 API 主管线，强制走备分管线，60 秒定时重刷，熔断触发时立即还原 `cpuinfo_max_freq`。

### 物理熔断

模块启动（注入前）按下表顺序轮询，任一 `pmoff` 存在即静默退出；`/sdcard/pmon`（兼容旧 `/pmon`）不存在或不可读同样熔断（未授权）。

```
/data/local/tmp/pmoff
/data/local/tmp/pmoff.txt
/sdcard/pmoff
/sdcard/pmoff.txt
/storage/emulated/0/pmoff
/cache/pmoff
/system/pmoff
/pmoff
/sdcard/pmon  # 授权信标（主）
/pmon        # 授权信标（兼容旧版）
```

首次授权：App 内点击「允许模块运行」→ 3 秒倒计时确认 → 创建 `/sdcard/pmon`（兼容旧 `/pmon`）+ 删除所有 `pmoff` → 重启生效。

## 使用

1. 通过 LSPosed 管理器激活模块，作用域仅勾选：
   ```
   system
   android
   com.android.providers.settings
   com.android.phone
   ```
2. 打开 App → 点击「允许模块运行」完成授权（需要 Root），重启手机。
3. 在首页选择模板点击「应用」，可新建/编辑自定义模板，在设置页维护黑白名单。

模块绝不在作用域之外 Hook 任何应用；对作用域内所有 Hook 均包裹异常防护，防止错误扩散影响系统稳定性。

## 策略项

| 策略项 | 主管线（System API） | 备分管线（Root Shell） |
| --- | --- | --- |
| 杀后台进程 | ActivityManager force-stop | `am force-stop` |
| 锁定帧率 | Settings 写 refresh rate | `settings put system peak_refresh_rate` |
| 禁用动画 | Settings.Global 三档 scale | `settings put global animator_duration_scale 0` 等 |
| 亮度上限 | 拦截 setBrightness 钳制 | 写亮度节点 |
| 关闭蓝牙 | BluetoothAdapter.disable | `svc bluetooth disable` |
| 后台网络 | 单应用 UID 限制 | 不降级（API 失败即放弃，防误伤） |
| GPS 禁用 | 拦截定位请求，返回缓存坐标 | 无 |
| CPU 频率 | 无 API | 写 `scaling_max_freq`（强制走 Root） |

## 构建

模块仅通过 GitHub Actions 构建（本地绝不构建），工作流带 SDK 下载缓存与 Gradle 依赖/build 缓存以加速二次构建。

```bash
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
# 直接作为 LSPosed 模块安装即可
```

依赖要求：`de.robv.android.xposed:api:82`（compileOnly）；目标 SDK 35，最低 Android 8.0（API 26）。

## 日志

- 日志分级：API 成功=DEBUG，API 失败转 Root=INFO，Root 失败=WARN。
- App 内日志页可滚动查看并可一键复制；也可 `adb logcat -s PowerManager` 查看。
- 内部日志文件：`/data/user/0/com.power.manager/files/power_manager.log`。

## 许可证

GPL v3