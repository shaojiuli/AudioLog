# AudioLog（音频播放统计）

类似「屏幕使用时间」的安卓**音频版**：记录每个 App 每天播放音频的时长，以及听过的书、歌。

## 编者简介

主要是用于统计听书时长自用（目前只有 QQ 浏览器的听书统计），也做了个听歌的统计页面（目前只有网易云），搞不懂为什么没有这种详细一些的音频播放时长统计应用（对应屏幕使用时长），遂编~

## 功能

**记录**
- 按 日 / 周（近 7 天）/ 月 / 年 统计播放时长，堆叠柱状图按应用分色，带平均线
- 日视图柱高为每小时的播放占比；点柱子可下钻到当天 / 当月
- 应用列表可选中，选中后图表和播放记录只看该应用；选中 QQ 浏览器时按书分色
- 播放记录：连续的同一标题合并成一组，连接方式（蓝牙 / 有线 / 外放）变化时才标注

**歌单**（网易云音乐）
- 听歌合计，每首歌显示封面、歌手、播放次数（听的总时长 ÷ 单曲时长）、时长条
- 以网易云歌曲 ID 归并；按最近 / 时长排序，每页 30 首

**书库**（QQ 浏览器听书）
- 同一播放标题为一本书，统计累计听书时长
- 收听记录按连续日期汇总（如「11月3日–11月9日 4小时」），可加备注
- 同一本书标题不同时可合并计时，随时可拆分

**设置**
- 隐藏歌单 / 书库页；记录范围（只记录选中的应用）；静音时是否计时
- 导出表格（CSV）、导出数据（完整备份 zip，含封面）、导入数据（合并不覆盖）
- 后台运行：电池优化白名单，以及华为 / 荣耀 / 小米 / OPPO / vivo / 三星的自启动设置入口

数据只保存在本机，不联网。只需要「通知使用权」（用来读取各 App 的播放状态，不读取通知内容）。

## 原理

| 来源 | 作用 |
|---|---|
| `NotificationListenerService` + `MediaSessionManager.getActiveSessions()` | 监听各 App 的媒体会话，播放时开始计时，暂停/停止时结束 |
| `AudioManager.isMusicActive` + `AudioPlaybackCallback` | 系统音乐流状态；用于推断不上报播放状态的应用（如 QQ 浏览器） |
| `AudioDeviceCallback` | 输出设备切换时拆分记录 |
| 媒体元数据中的 `MEDIA_ID` | 以「应用 + ID」保存歌名、歌手、专辑、单曲时长、封面 |

进行中的记录每 30 秒写盘一次。处理各播放器怪癖的思路参考了 [Pano Scrobbler](https://github.com/kawaiiDango/pano-scrobbler)（代码为独立编写）。

## 编译

需要 JDK 17 和 Android SDK（compileSdk 35）：

```bash
./gradlew assembleDebug
```

APK 会复制到 `apk/音频播放统计_v<版本>.apk`。也可以直接在 [Releases](https://github.com/shaojiuli/AudioLog/releases) 下载打包好的 APK。签名文件 `signing/debug.keystore` 不在仓库里，没有它时请删掉 `app/build.gradle.kts` 里的 `signingConfigs` 一段，使用本机默认的 debug 签名。

项目路径含中文时需要 `gradle.properties` 里的 `android.overridePathCheck=true`（已包含）。

## 使用

1. 安装后点「去开启」，授予通知使用权
2. 国产系统：设置 → 后台运行，允许后台运行并打开自启动，否则后台会被杀导致漏记
3. 正常听歌听书即可

更新内容见 [CHANGELOG.md](CHANGELOG.md)。`tools/audio_monitor.ps1` 可以通过 adb 实时查看手机上哪个应用在发声。

## 许可证

[MIT](LICENSE)
