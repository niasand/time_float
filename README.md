# TimeFloat 悬浮时钟

Android 悬浮窗时钟：持续置顶显示当前日期（年-月-日 + 星期）与时间（时:分:秒）。

## 功能

- 悬浮窗始终显示在其他应用上层（前台服务持有，进程存活可靠）
- 默认深灰背景；支持 8 个预设色 + 任意 HEX 颜色自定义
- 不透明度（20%–100%）与时间字号（12–48sp）可调，改动即时生效
- 悬浮窗可拖动，位置自动记忆；单击悬浮窗回到设置页
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
4. 后续换色 / 调透明度 / 调字号在设置页操作，悬浮窗即时生效

## 技术要点

- Kotlin + 经典 View，无 Compose，唯一依赖 `androidx.core:core-ktx`
- minSdk 26 / targetSdk 34；悬浮窗 `TYPE_APPLICATION_OVERLAY`
- 前台服务类型 `specialUse`；设置通过 SharedPreferences 共享，
  服务侧 `OnSharedPreferenceChangeListener` 监听即时刷新外观
- 文字颜色按背景亮度自动黑/白，保证可读性
