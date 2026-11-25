# SingFor10Android — 安卓唱歌 10 分钟整人 App

与 iOS 版同款整人逻辑，并针对 Android 增加了**开机自启续播**：中途关机重启，开机后自动接着唱，直到满 10 分钟。

> 本工程为授权的本地研究产物，所有目标均为自建测试环境。

---

## 一、功能与阻断能力

| 需求 | 实现方式 |
|------|----------|
| 唱歌 10 分钟 | `res/raw/sing.wav` 内嵌公共领域旋律《小星星》（加性合成 + 颤音 + 共振峰谐波），`MediaPlayer` 循环播放；`SharedPreferences` 持久化起始时间，按真实时间计算剩余 |
| 静音键无效 | 每 300ms 检查 `AudioManager.ringerMode`，一旦不是 `RINGER_MODE_NORMAL` 立即切回响铃，并把 `STREAM_MUSIC` 拉到最大 |
| 音量键无效 | 同一定时器检测 `getStreamVolume < getStreamMaxVolume`，立刻 `setStreamVolume(MAX, 0)`；另注册 `android.media.VOLUME_CHANGED_ACTION` 等广播即时恢复 |
| 勿扰模式无效 | 若已授予通知策略权限，定时调用 `setInterruptionFilter(INTERRUPTION_FILTER_ALL)` |
| 划后台继续 | 前台服务 + `setOngoing(true)` 常驻通知，用户无法 swipe 掉；`START_STICKY` 被系统回收后自动重建；`onTaskRemoved` 兜底重拉 |
| 锁屏继续 | `PARTIAL_WAKE_LOCK` 保持 CPU 唤醒，音频不中断 |
| 耳机拔出继续 | 监听 `ACTION_AUDIO_BECOMING_NOISY`，立即恢复播放与最大音量 |
| **关机重启继续** | `BOOT_COMPLETED` / `QUICKBOOT_POWERON` / `MY_PACKAGE_REPLACED` 广播触发 `BootReceiver`，读取持久化的起始时间，按已流逝时间续播；若 10 分钟已满则自动停止并清除记录 |
| 防误触退出 | 界面"退出"按钮只弹 Toast 提示，10 分钟内不提供停止入口 |

### 系统级边界（客观事实）

- **强制停止（设置 → 应用 → 强行停止）**：系统直接冻结整个 App，任何服务/广播都收不到，无法拦截。
- **卸载 App**：系统行为，无法拦截。
- **硬件断电 / 电池耗尽**：物理层行为；重新上电开机后 `BootReceiver` 会续播（前提是没有被强制停止）。
- **来电接管**：系统可能暂停媒体焦点，服务已监听音频成为噪音等事件并持续尝试恢复。

---

## 二、项目结构

```
SingFor10Android/
├── app/
│   ├── build.gradle                     # AGP 8.5.2 / Kotlin 1.9.24 / compileSdk 35
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml          # 前台服务、开机自启、通知权限
│       ├── java/com/example/singfor10/
│       │   ├── MainActivity.kt          # 倒计时 UI、权限申请、启动服务
│       │   ├── SingService.kt           # 前台服务：播放 + 计时 + 音量守卫 + 通知
│       │   └── BootReceiver.kt          # 开机/覆盖安装后续播
│       └── res/
│           ├── raw/sing.wav             # 21.6 秒自包含唱歌旋律（1.82 MB）
│           ├── layout/activity_main.xml
│           ├── drawable/ic_launcher.xml
│           └── values/                  # strings / colors / themes
├── .github/workflows/build-apk.yml      # 云端构建 APK
├── scripts/
│   ├── build-apk.sh                     # 本地一键构建
│   └── generate_audio.py                # 重新生成 WAV 的脚本
├── build.gradle / settings.gradle / gradle.properties
└── README.md
```

---

## 三、获取 APK 安装包

### 路径 A：GitHub Actions 云端构建（无需本地环境，推荐）

1. 新建 GitHub **公开**仓库。
2. 本目录已初始化 Git 并包含全部文件，在工程目录执行：
   ```bash
   git remote add origin https://github.com/<你的用户名>/<仓库名>.git
   git branch -M main
   git push -u origin main
   ```
   如果目标仓库里已经有 iOS 版工程，请把本目录内容放在仓库**根目录**（与 iOS 版二选一，或合并后调整 workflow 路径）。
3. 打开仓库 **Actions** 页，等待 `Build SingFor10 APK` 完成（约 4–8 分钟）。
4. 在 Artifacts 下载 `SingFor10-APK`，解压得到 `app-debug.apk` 和 `SHA256SUMS.txt`。
5. 随时可点 **Run workflow** 重建。

### 路径 B：Android Studio 直接运行

用 Android Studio 打开本目录，等待 Gradle Sync 完成，连接手机后点运行。
首次Sync会自动生成 `gradlew` 与 wrapper jar。

### 路径 C：本地命令行

需要 JDK 17 + Android SDK（API 35）并设置 `ANDROID_HOME`：

```bash
bash scripts/build-apk.sh
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

---

## 四、安装到手机

1. 把 `app-debug.apk` 传到手机。
2. 点击安装，系统提示「禁止安装未知来源应用」时，进入设置开启对应文件管理器/浏览器的安装权限。
3. 打开 App：
   - Android 13+ 会弹通知权限请求，请点「允许」——否则前台服务通知无法显示。
   - 若想让勿扰模式也被自动关闭，需在「设置 → 通知 → 勿扰权限」中给本 App 授权。
4. 开始唱满 10 分钟后自动停止。

> Debug APK 使用 Android 默认 debug 签名，可直接安装；不同版本覆盖安装时若提示签名冲突，先卸载旧版。

---

## 五、可调参数

- `SingService.kt` → `DURATION_MS`（默认 600000，单位毫秒）
- `SingService.kt` → `startEnforcer()` 中的 `300L` 调整守卫轮询间隔
- `scripts/generate_audio.py` → `MELODY`、`NOTE_DURATION`、`HARMONICS` 调整旋律与音色；改完重跑脚本再构建
- `app/build.gradle` → `applicationId` 修改包名
