# TimeFloat 悬浮时钟

Android 悬浮窗时钟：持续置顶显示当前日期（年-月-日 + 星期）与时间（时:分:秒），
支持自定义字幕标记，点击字幕高亮并记录 SRT 日志（适合路测/录屏标注）。

## 功能

- 悬浮窗始终显示在其他应用上层（前台服务持有，进程存活可靠）
- 默认深灰背景；HSV 圆盘取色（色相+饱和度），透明度（20%–100%）与字号（12–48sp）可调，改动即时生效
- 自定义字幕标记（最多 10 条），竖排显示在时钟右侧
- 点击字幕：高亮数秒（淡亮绿底白字），同时向固定的 SRT 文件追加一条记录
  （`2026-11-07 14:25:30：停车前`，逐行追加，文件不存在自动创建）
- SRT 统一存 `Download/TimeFloat/road_test_srt.srt`（API<29 存应用外部私有目录）
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
4. 设置页"字幕标记"添加字幕（如 停车前），点击悬浮窗上的字幕即高亮并记录一条 SRT
   （Download/TimeFloat/road_test_srt.srt，逐行追加）

## 技术要点

- Kotlin + 经典 View，无 Compose，唯一依赖 `androidx.core:core-ktx`
- minSdk 26 / targetSdk 34；悬浮窗 `TYPE_APPLICATION_OVERLAY`
- 前台服务类型 `specialUse`；设置通过 SharedPreferences 共享，
  服务侧 `OnSharedPreferenceChangeListener` 监听即时刷新外观与字幕列表
- SRT 落盘走 MediaStore（API 29+，无存储权限），读-改-写追加；写盘在专属 IO 线程
- 文字颜色按背景亮度自动黑/白，保证可读性
