//! iso2god 的 Android JNI 桥接层。
//!
//! 转换内核来自上游 crate `iso2god`（git 依赖、按 rev 锁定），本 crate 只负责两件事：
//! 1. 把 Java 侧传来的文件描述符包装成 `File`，调用上游 API；
//! 2. 把结果编码成结构化 JSON、把进度编码成「阶段码 + 计数」，交给 Kotlin 侧。
//!
//! 本层不产生任何面向用户的文案：所有提示语都由 Kotlin 按 `code` / `stage` 本地化。

use jni::JNIEnv;
use jni::objects::{JClass, JIntArray, JObject, JString, JValue};
use jni::sys::jint;
use serde::Serialize;
use std::fs::File;
use std::io::{Seek, SeekFrom, Write};
use std::os::fd::{FromRawFd, IntoRawFd};

use iso2god_src::executable::TitleInfo;
use iso2god_src::game_list;
use iso2god_src::god;
use iso2god_src::iso::IsoReader;

/// 进度阶段码，与 Kotlin 侧 `ProgressStage` 一一对应，改动必须两边同步。
#[derive(Clone, Copy)]
#[repr(i32)]
enum ProgressStage {
    /// `current` = 已完成的数据包数，`total` = 数据包总数
    WritingParts = 0,
    /// 正在串 MHT 哈希链
    WritingMht = 1,
    /// 正在写数据头
    WritingHeader = 2,
}

/// 失败原因码，与 Kotlin 侧 `conversionErrorMessage()` 一一对应。
#[derive(Clone, Copy)]
enum ErrorCode {
    Jni,
    IsoRead,
    PartCountMismatch,
    WritePart,
    Mht,
    Header,
    Unknown,
}

impl ErrorCode {
    fn as_str(self) -> &'static str {
        match self {
            ErrorCode::Jni => "JNI",
            ErrorCode::IsoRead => "ISO_READ",
            ErrorCode::PartCountMismatch => "PART_COUNT_MISMATCH",
            ErrorCode::WritePart => "WRITE_PART",
            ErrorCode::Mht => "MHT",
            ErrorCode::Header => "HEADER",
            ErrorCode::Unknown => "UNKNOWN",
        }
    }
}

/// 一次失败。界面上的主文案由 Kotlin 按 `code` 决定，`detail` 只是诊断细节。
struct Failure {
    code: ErrorCode,
    detail: String,
}

impl Failure {
    fn new(code: ErrorCode, detail: impl Into<String>) -> Self {
        Self {
            code,
            detail: detail.into(),
        }
    }

    /// `{:#}` 会把 anyhow 的错误链（含 context）串成一行。
    fn from_error(code: ErrorCode, error: anyhow::Error) -> Self {
        Self {
            code,
            detail: format!("{error:#}"),
        }
    }

    /// 交给 serde 构造 JSON：手工 format! 拼接时，错误信息里的引号会把 JSON 破坏掉。
    fn to_json(&self) -> String {
        #[derive(Serialize)]
        struct FailureJson<'a> {
            ok: bool,
            code: &'a str,
            detail: &'a str,
        }

        serde_json::to_string(&FailureJson {
            ok: false,
            code: self.code.as_str(),
            detail: &self.detail,
        })
        .unwrap_or_else(|_| r#"{"ok":false,"code":"UNKNOWN","detail":""}"#.to_string())
    }
}

/// 成功结果，字面量即可，无需经过 serde。
const OK_JSON: &str = r#"{"ok":true}"#;

/// 构造 Java 字符串。失败时返回 null，绝不 panic ——
/// panic 跨 FFI 边界会直接 abort 整个进程，而不是抛 Java 异常。
fn new_jstring<'local>(env: &mut JNIEnv<'local>, value: &str) -> JString<'local> {
    env.new_string(value).unwrap_or_default()
}

/// 返回给 Kotlin 的 ISO 信息。字段名即 JSON 键名，改动需同步 Kotlin 侧的解析。
#[derive(Serialize)]
struct IsoInfo {
    ok: bool,
    /// 未收录的 title id 返回空串，由 Kotlin 显示占位文案
    title: String,
    title_id: String,
    media_id: String,
    data_parts: u64,
    data_size: u64,
}

/// 把 Java 传来的 fd 包成 `File`，转换完成后必须用 `into_raw_fd()` 交还所有权，
/// 否则 `File` 析构会关掉 Java 侧 `ParcelFileDescriptor` 还要用的 fd。
///
/// 返回 `{"ok":true}` 或 `{"ok":false,"code":...,"detail":...}`。
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_thehbc_iso2god_MainActivity_convertIso<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    iso_fd: jint,
    header_fd: jint,
    part_fds: JIntArray<'local>,
    callback: JObject<'local>,
) -> JString<'local> {
    let mut input_file = unsafe { File::from_raw_fd(iso_fd) };
    let mut header_file = unsafe { File::from_raw_fd(header_fd) };

    // 取出数组元素（AutoArray 析构时自动归还；NoCopyBack 表示不回写）
    let part_fd_elements =
        match unsafe { env.get_array_elements(&part_fds, jni::objects::ReleaseMode::NoCopyBack) } {
            Ok(elements) => elements,
            Err(e) => {
                let _ = input_file.into_raw_fd();
                let _ = header_file.into_raw_fd();
                return new_jstring(
                    &mut env,
                    &Failure::new(ErrorCode::Jni, e.to_string()).to_json(),
                );
            }
        };

    let mut part_files: Vec<File> = part_fd_elements
        .iter()
        .map(|&fd| unsafe { File::from_raw_fd(fd) })
        .collect();

    // 进度只上报「阶段码 + 计数」，文案由 Kotlin 拼
    let mut report_progress = |current: u64, total: u64, stage: ProgressStage| {
        let _ = env.call_method(
            &callback,
            "onProgress",
            "(III)V",
            &[
                JValue::Int(current as i32),
                JValue::Int(total as i32),
                JValue::Int(stage as i32),
            ],
        );
    };

    let result = convert_iso_internal(
        &input_file,
        &mut header_file,
        &mut part_files,
        &mut report_progress,
    );

    // 交还所有权：这些 fd 归 Java 侧的 ParcelFileDescriptor 管
    let _ = input_file.into_raw_fd();
    let _ = header_file.into_raw_fd();
    for file in part_files {
        let _ = file.into_raw_fd();
    }

    let payload = match result {
        Ok(()) => OK_JSON.to_string(),
        Err(failure) => failure.to_json(),
    };
    new_jstring(&mut env, &payload)
}

fn convert_iso_internal<F>(
    iso_file: &File,
    header_file: &mut File,
    part_files: &mut [File],
    report_progress: &mut F,
) -> Result<(), Failure>
where
    F: FnMut(u64, u64, ProgressStage),
{
    // 取原始 ISO 的属性；try_clone 出的副本由 IsoReader 接管，关掉它不影响调用方的 fd
    let iso_file_clone = iso_file
        .try_clone()
        .map_err(|e| Failure::new(ErrorCode::IsoRead, e.to_string()))?;
    let mut source_iso =
        IsoReader::read(iso_file_clone).map_err(|e| Failure::from_error(ErrorCode::IsoRead, e))?;
    let title_info =
        TitleInfo::from_image(&mut source_iso).map_err(|e| Failure::from_error(ErrorCode::IsoRead, e))?;
    let exe_info = title_info.execution_info;
    let content_type = title_info.content_type;

    let data_size = source_iso.get_max_used_prefix_size();
    let block_count = data_size.div_ceil(god::BLOCK_SIZE);
    let part_count = block_count.div_ceil(god::BLOCKS_PER_PART);

    if part_files.len() as u64 != part_count {
        return Err(Failure::new(
            ErrorCode::PartCountMismatch,
            format!("expected {part_count}, got {}", part_files.len()),
        ));
    }

    // 逐包写入。每条进度都带阶段码，用来替换掉以前写死在 Rust 里的中英文提示语。
    for (part_index, part_file) in part_files.iter_mut().enumerate() {
        let part_index = part_index as u64;
        report_progress(part_index, part_count, ProgressStage::WritingParts);

        let mut iso_data_volume = iso_file
            .try_clone()
            .map_err(|e| Failure::new(ErrorCode::WritePart, e.to_string()))?;
        iso_data_volume
            .seek(SeekFrom::Start(source_iso.volume_descriptor.root_offset))
            .map_err(|e| Failure::new(ErrorCode::WritePart, e.to_string()))?;

        god::write_part(iso_data_volume, part_index, part_file)
            .map_err(|e| Failure::from_error(ErrorCode::WritePart, e))?;
    }

    report_progress(part_count, part_count, ProgressStage::WritingMht);

    if part_count > 0 {
        // 从最后一个数据包起向前逐级串 MHT 哈希链
        let mut mht = {
            let last_part_file = &mut part_files[(part_count - 1) as usize];
            last_part_file
                .seek(SeekFrom::Start(0))
                .map_err(|e| Failure::new(ErrorCode::Mht, e.to_string()))?;
            god::HashList::read(last_part_file).map_err(|e| Failure::from_error(ErrorCode::Mht, e))?
        };

        for prev_part_index in (0..part_count - 1).rev() {
            let mut prev_mht = {
                let prev_file = &mut part_files[prev_part_index as usize];
                prev_file
                    .seek(SeekFrom::Start(0))
                    .map_err(|e| Failure::new(ErrorCode::Mht, e.to_string()))?;
                god::HashList::read(prev_file).map_err(|e| Failure::from_error(ErrorCode::Mht, e))?
            };

            prev_mht.add_hash(&mht.digest());

            {
                let prev_file = &mut part_files[prev_part_index as usize];
                prev_file
                    .seek(SeekFrom::Start(0))
                    .map_err(|e| Failure::new(ErrorCode::Mht, e.to_string()))?;
                prev_mht
                    .write(prev_file)
                    .map_err(|e| Failure::from_error(ErrorCode::Mht, e))?;
            }

            mht = prev_mht;
        }

        report_progress(part_count, part_count, ProgressStage::WritingHeader);

        let last_part_size = part_files[(part_count - 1) as usize]
            .metadata()
            .map_err(|e| Failure::new(ErrorCode::Header, e.to_string()))?
            .len();

        let mut con_header_builder = god::ConHeaderBuilder::new()
            .with_execution_info(&exe_info)
            .with_block_counts(block_count as u32, 0)
            .with_data_parts_info(
                part_count as u32,
                last_part_size + (part_count - 1) * god::BLOCK_SIZE * 0xa290,
            )
            .with_content_type(content_type)
            .with_mht_hash(&mht.digest());

        if let Some(game_title) = game_list::find_title_by_id(exe_info.title_id) {
            con_header_builder = con_header_builder.with_game_title(&game_title);
        }

        let con_header = con_header_builder.finalize();
        header_file
            .write_all(&con_header)
            .map_err(|e| Failure::new(ErrorCode::Header, e.to_string()))?;
    }

    Ok(())
}

/// 解析 ISO 元信息。返回 `{"ok":true,...}` 或 `{"ok":false,"code":...,"detail":...}`。
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_thehbc_iso2god_MainActivity_getIsoInfo<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    fd: jint,
) -> JString<'local> {
    let input_file = unsafe { File::from_raw_fd(fd) };

    let info = get_iso_info_internal(&input_file);

    // 交还所有权，fd 归 Java 侧的 ParcelFileDescriptor 管
    let _ = input_file.into_raw_fd();

    let payload = match info {
        Ok(info) => serde_json::to_string(&info)
            .unwrap_or_else(|e| Failure::new(ErrorCode::Unknown, e.to_string()).to_json()),
        Err(failure) => failure.to_json(),
    };
    new_jstring(&mut env, &payload)
}

fn get_iso_info_internal(file: &File) -> Result<IsoInfo, Failure> {
    let file_clone = file
        .try_clone()
        .map_err(|e| Failure::new(ErrorCode::IsoRead, e.to_string()))?;
    let mut source_iso =
        IsoReader::read(file_clone).map_err(|e| Failure::from_error(ErrorCode::IsoRead, e))?;
    let title_info =
        TitleInfo::from_image(&mut source_iso).map_err(|e| Failure::from_error(ErrorCode::IsoRead, e))?;

    let exe_info = title_info.execution_info;
    let title_id = format!("{:08X}", exe_info.title_id);
    let media_id = format!("{:08X}", exe_info.media_id);
    let title = game_list::find_title_by_id(exe_info.title_id).unwrap_or_default();

    let data_size = source_iso.get_max_used_prefix_size();
    let block_count = data_size.div_ceil(god::BLOCK_SIZE);
    let data_parts = block_count.div_ceil(god::BLOCKS_PER_PART);

    Ok(IsoInfo {
        ok: true,
        title,
        title_id,
        media_id,
        data_parts,
        data_size,
    })
}
