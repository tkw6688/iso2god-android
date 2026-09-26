# ISO2GOD Android

把 Xbox 360 的 ISO 镜像转换为 Games On Demand (GOD) 格式的 Android 应用。

- Android 端：Jetpack Compose，minSdk 26 / targetSdk 35，Java 11
- 转换内核：[iso2god-rs](https://github.com/iliazeus/iso2god-rs)，以 `libiso2god.so` 形式通过 JNI 调用
- 输出目录通过 SAF（系统文件选择器）选取，ISO 与输出文件都以文件描述符（fd）传给原生层，不需要存储权限
- 仅打包 `arm64-v8a`

## 构建

**先构建 Rust 库**（需要 Android NDK 和 `cargo-ndk`）：

```bat
build_rust.bat
```

它执行 `cargo ndk -t arm64-v8a -o ../app/src/main/jniLibs build --release`，产物落在 `app/src/main/jniLibs/arm64-v8a/libiso2god.so`。

**再构建 APK**：

```bash
./gradlew assembleDebug     # 调试版
./gradlew assembleRelease   # 发布版，启用 R8 混淆与资源收缩，需自行签名
```

## 目录结构

```
app/                   Android 应用（Compose UI + SAF 文件读写）
iso2god-rs/            vendored 的 Rust 转换内核，含 JNI 层 src/android.rs
app/src/main/jniLibs/  构建出的 libiso2god.so
```

## 许可与致谢

本项目以 **MIT 许可**发布，见 [LICENSE](LICENSE)。

转换内核来自 [iliazeus/iso2god-rs](https://github.com/iliazeus/iso2god-rs)（MIT，Copyright (c) 2023 Ilia Pozdnyakov），
在此基础上增加了 Android JNI 绑定。该部分的许可原文保留在 [iso2god-rs/LICENSE](iso2god-rs/LICENSE)，
并随 APK 一起分发到 `assets/licenses/`，在应用内「开源许可」中可查看。

> 尚未覆盖：AndroidX / Compose / Material（Apache-2.0）以及 Rust 依赖（多为 MIT OR Apache-2.0）的完整许可清单。
> 后续可用 `aboutlibraries` Gradle 插件或 `cargo-about` 生成后放入 `app/src/main/assets/licenses/` —— 该目录会被应用内许可页自动列出，无需改代码。
