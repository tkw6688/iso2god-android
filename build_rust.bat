@echo off
rem First build downloads the iso2god kernel from GitHub (git dependency pinned
rem in android-bridge/Cargo.toml), so the initial build needs network access.
cd /d "%~dp0android-bridge"
echo Building Rust library for Android...
cargo ndk -t arm64-v8a -o ../app/src/main/jniLibs build --release
if errorlevel 1 goto fail
echo Done.
exit /b 0

:fail
echo.
echo Build FAILED - see the cargo output above.
exit /b 1
