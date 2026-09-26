@echo off
rem 首次构建会从 GitHub 拉取 iso2god 内核（android-bridge 里按 git rev 锁定的依赖），需要联网
cd android-bridge
echo Building Rust library for Android...
cargo ndk -t arm64-v8a -o ../app/src/main/jniLibs build --release
echo Done.
