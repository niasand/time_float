# TimeFloat 悬浮时钟

Android 悬浮窗时钟：持续置顶显示当前日期（年-月-日 + 星期）与时间（时:分:秒），
支持自定义字幕标记，点击字幕自动整屏截屏并记录 SRT 日志（适合路测/录屏标注）。

## 功能

- 悬浮窗始终显示在其他应用上层（前台服务持有，进程存活可靠）
- 默认深灰背景；HSV 圆盘取色（色相+饱和度），透明度（20%–100%）与字号（12–48sp）可调，改动即时生效
- 自定义字幕标记（最多 10 条），竖排显示在时钟右侧
- 点击字幕：高亮数秒（淡亮绿底白字），同时整屏截屏存相册 + 追加一条 SRT 记录
  （`2026-11-07 14:25:30：停车前`，按天一个文件）；未授权截屏时仅记录文字
- 悬浮窗可拖动（时钟区域），位置自动记忆；单击时钟回到设置页
- 开机自启（需已授权悬浮窗权限且开关为开）
- 通知栏常驻通知，附"关闭悬浮窗"按钮

## 构建

要求 JDK 17 + Android SDK（`ANDROID_HOME` 已设置即可，无需 local.properties）。

```bash
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

若本机 `JAVA_HOME` 指向 JDK 11，构建时显式覆盖：

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew assembleDebug
```

## 使用

1. 安装 APK 后启动，点击"去授权"授予"显示在其他应用上层"权限
2. 同意通知权限（Android 13+）
3. 打开"显示悬浮窗"开关
4. 想截屏记录：设置页"整屏截屏授权"点一次授权（进程级，重启应用后需重新授权）
5. 设置页"字幕标记"添加字幕（如 停车前），点击悬浮窗上的字幕即完成一次
   截屏（相册 Pictures/TimeFloat）+ SRT 记录（Download/TimeFloat/timefloat_当天日期.srt）

## 技术要点

- Kotlin + 经典 View，无 Compose，唯一依赖 `androidx.core:core-ktx`
- minSdk 26 / targetSdk 34；悬浮窗 `TYPE_APPLICATION_OVERLAY`
- 前台服务类型 `specialUse|mediaProjection`；设置通过 SharedPreferences 共享，
  服务侧 `OnSharedPreferenceChangeListener` 监听即时刷新外观与字幕列表
- 整屏截屏走 MediaProjection：consent 单次有效（Android 14），常驻单个
  VirtualDisplay + ImageReader 按需取帧，rowStride 逐行对齐拷贝
- 文字颜色按背景亮度自动黑/白，保证可读性
