@echo off
cd iso2god-rs
echo Building Rust library for Android...
cargo ndk -t arm64-v8a -o ../app/src/main/jniLibs build --release
echo Done.