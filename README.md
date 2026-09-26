# ISO2GOD Android

把 Xbox 360 的 ISO 镜像转换为 Games On Demand (GOD) 格式的 Android 应用。

- Android 端：Jetpack Compose，minSdk 26 / targetSdk 35，Java 11
- 转换内核：[iso2god-rs](https://github.com/iliazeus/iso2god-rs)（按 git rev 锁定上游版本），由 `android-bridge/` 这层 JNI 桥接编译成 `libiso2god.so`
- 输出目录通过 SAF（系统文件选择器）选取，ISO 与输出文件都以文件描述符（fd）传给原生层，不需要存储权限
- 仅打包 `arm64-v8a`

## 构建

**先构建 Rust 库**，需要三样环境（首次配置）：

```bash
rustup target add aarch64-linux-android   # 交叉编译目标
cargo install cargo-ndk                   # 构建驱动
```

另外要安装 Android NDK，并让 `ANDROID_NDK_HOME` 指向它——`cargo-ndk` 只读环境变量，不读 Gradle 的 `local.properties`。

```bat
build_rust.bat
```

它进入 `android-bridge/` 执行 `cargo ndk -t arm64-v8a -o ../app/src/main/jniLibs build --release`，产物落在 `app/src/main/jniLibs/arm64-v8a/libiso2god.so`。
首次构建会从 GitHub 拉取转换内核（git 依赖），需要联网；生成的 `android-bridge/Cargo.lock` 建议一并提交，以保证依赖可复现。

> 建议用 NDK r28 或更新版本：它默认按 16 KB 页对齐，而 targetSdk 35 的应用上架要求如此。用 r27 及更早版本需要自行补 `-C link-arg=-Wl,-z,max-page-size=16384`。

**再构建 APK**：

```bash
./gradlew assembleDebug     # 调试版
./gradlew assembleRelease   # 发布版，启用 R8 混淆与资源收缩，需自行签名
```

## 目录结构

```
app/                   Android 应用（Compose UI + SAF 文件读写）
android-bridge/        JNI 桥接层：包装 fd、调用转换内核、把结果编码成结构化 JSON
app/src/main/jniLibs/  构建出的 libiso2god.so
```

## 许可与致谢

本项目以 **MIT 许可**发布，见 [LICENSE](LICENSE)。

转换内核来自 [iliazeus/iso2god-rs](https://github.com/iliazeus/iso2god-rs)（MIT，Copyright (c) 2023 Ilia Pozdnyakov），
由 `android-bridge/` 以 git 依赖、按 rev 锁定上游 v1.8.0 使用，本仓库不再存放其源码副本。
该内核的 MIT 原文随 APK 一起分发（由 `rust-dependencies.json` 携带，见下），在应用内「开源许可」中可查看。

### 第三方许可清单

应用内「开源许可」页按 `app/src/main/assets/licenses/*.json` 分级展示：分组 → 组件（可搜索）→ 许可全文。两份数据都由脚本从真实依赖图生成，依赖变化后重跑即可：

| 文件 | 内容 | 重新生成方式 |
|---|---|---|
| `android-dependencies.json` | 打进 APK 的全部 Maven 依赖（含传递依赖）+ Apache-2.0 全文 | `python tools/generate_android_licenses.py` |
| `rust-dependencies.json` | 静态链接进 `libiso2god.so` 的 Rust crate 及其许可原文 | `python tools/generate_rust_licenses.py` |

两侧数据同构，便于界面统一渲染：

```json
{ "title": "…", "licenses": [{ "id": "…", "name": "…", "text": "…" }],
  "entries": [{ "name": "…", "version": "…", "licenseIds": ["…"] }] }
```

许可正文按 `id` 去重存放，条目只引用 id——否则上百个组件各带一份 Apache-2.0 全文会让资源膨胀上百倍。

Rust 侧首次需安装生成工具（之后由脚本自动调用）：

```bash
cargo install --locked --features cli cargo-about
```

配置位于 `android-bridge/about.toml`（接受的许可白名单、上游 crate 的 license 澄清）与 `android-bridge/about.hbs`（机器可解析的中间格式模板）。

> 转换内核的 MIT 原文不再单独维护（原先的 `iso2god-rs.txt` 已移除）：它由 cargo-about 依据 `about.toml` 中对上游 LICENSE 文件的澄清自动带出，上游换版本时会随生成一起更新，不会与代码脱节。
