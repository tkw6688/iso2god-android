use jni::JNIEnv;
use jni::objects::{JIntArray, JClass, JString, JObject, JValue};
use jni::sys::jint;
use std::fs::File;
use std::io::{Read, Seek, SeekFrom, Write};
use std::os::fd::{FromRawFd, IntoRawFd};
use std::path::Path;

use crate::iso::IsoReader;
use crate::executable::TitleInfo;
use crate::game_list;
use crate::god;
use serde::Serialize;

#[derive(Serialize)]
struct IsoInfo {
    title: String,
    title_id: String,
    media_id: String,
    platform: String,
    exe_type: String,
    data_parts: u64,
    data_size: u64,
}

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
    
    // Get array elements (auto released on drop of AutoArray)
    let part_fd_ints_auto = match unsafe { env.get_array_elements(&part_fds, jni::objects::ReleaseMode::NoCopyBack) } {
        Ok(arr) => arr,
        Err(e) => {
             let _ = input_file.into_raw_fd();
             let _ = header_file.into_raw_fd();
             return env.new_string(format!("Error getting part FDs: {}", e)).unwrap();
        }
    };
    
    let mut part_files: Vec<File> = part_fd_ints_auto.iter().map(|&fd| unsafe { File::from_raw_fd(fd) }).collect();

    let mut progress_closure = |current: u64, total: u64, msg: &str| {
        let msg_jstr = match env.new_string(msg) {
            Ok(s) => s,
            Err(_) => return,
        };
        
        let _ = env.call_method(
            &callback, 
            "onProgress", 
            "(IILjava/lang/String;)V", 
            &[
                JValue::Int(current as i32),
                JValue::Int(total as i32),
                JValue::Object(&msg_jstr)
            ]
        );
    };

    let res = convert_iso_internal(&input_file, &mut header_file, &mut part_files, &mut progress_closure);

    // Release ownership
    let _ = input_file.into_raw_fd();
    let _ = header_file.into_raw_fd();
    for f in part_files {
        let _ = f.into_raw_fd();
    }

    match res {
        Ok(_) => env.new_string("Success").unwrap(),
        Err(e) => env.new_string(format!("Error: {}", e)).unwrap(),
    }
}

fn convert_iso_internal<F>(
    iso_file: &File, 
    header_file: &mut File, 
    part_files: &mut [File],
    progress_cb: &mut F
) -> Result<(), anyhow::Error> 
where F: FnMut(u64, u64, &str)
{
    // 1. Setup IsoReader
    let iso_file_clone = iso_file.try_clone()?;
    
    let mut source_iso = IsoReader::read(iso_file_clone)?;
    let title_info = TitleInfo::from_image(&mut source_iso)?;
    let exe_info = title_info.execution_info;
    let content_type = title_info.content_type;
    
    let data_size = source_iso.get_max_used_prefix_size(); // TrimMode::FromEnd
    let block_count = data_size.div_ceil(god::BLOCK_SIZE);
    let part_count = block_count.div_ceil(god::BLOCKS_PER_PART);
    
    if part_files.len() as u64 != part_count {
        return Err(anyhow::anyhow!("Part count mismatch: expected {}, got {}", part_count, part_files.len()));
    }
    
    progress_cb(0, part_count, "Starting conversion...");
    
    // 2. Write Parts
    for (part_index, part_file) in part_files.iter_mut().enumerate() {
        let part_index = part_index as u64;
        
        progress_cb(part_index, part_count, &format!("写入数据包 {}/{}", part_index + 1, part_count));
        
        // We need a fresh handle (or seeked handle) for the ISO data
        let mut iso_data_volume = iso_file.try_clone()?;
        iso_data_volume.seek(SeekFrom::Start(source_iso.volume_descriptor.root_offset))?;
        
        god::write_part(iso_data_volume, part_index, part_file)?;
    }
    
    progress_cb(part_count, part_count, "Calculating MHT...");
    
    // 3. MHT Chain
    if part_count > 0 {
        // Read last part MHT
        let mut mht = {
            let last_part_file = &mut part_files[(part_count - 1) as usize];
            last_part_file.seek(SeekFrom::Start(0))?;
            god::HashList::read(last_part_file)?
        };
        
        // Loop backwards
        for prev_part_index in (0..part_count - 1).rev() {
            let mut prev_mht = {
                let prev_file = &mut part_files[prev_part_index as usize];
                prev_file.seek(SeekFrom::Start(0))?;
                god::HashList::read(prev_file)?
            };
            
            prev_mht.add_hash(&mht.digest());
            
            {
                let prev_file = &mut part_files[prev_part_index as usize];
                prev_file.seek(SeekFrom::Start(0))?;
                prev_mht.write(prev_file)?;
            }
            
            mht = prev_mht;
        }
        
        progress_cb(part_count, part_count, "写入数据头中...");
        
        // 4. Con Header
        let last_part_size = part_files[(part_count - 1) as usize].metadata()?.len();
        
        let mut con_header_builder = god::ConHeaderBuilder::new()
            .with_execution_info(&exe_info)
            .with_block_counts(block_count as u32, 0)
            .with_data_parts_info(
                part_count as u32,
                last_part_size + (part_count - 1) * god::BLOCK_SIZE * 0xa290,
            )
            .with_content_type(content_type)
            .with_mht_hash(&mht.digest());
            
        let game_title = game_list::find_title_by_id(exe_info.title_id);
        if let Some(gt) = game_title {
            con_header_builder = con_header_builder.with_game_title(&gt);
        }
        
        let con_header = con_header_builder.finalize();
        header_file.write_all(&con_header)?;
    }
    
    Ok(())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_thehbc_iso2god_MainActivity_getIsoInfo<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    fd: jint,
) -> JString<'local> {
    // 1. Wrap FD
    // We use ManuallyDrop to ensure we can control when/if it's dropped, 
    // but here we actually want to keep it alive until we dup it, then release it.
    let input_file = unsafe { File::from_raw_fd(fd) };

    let info_result = get_iso_info_internal(&input_file);
    
    // CRITICAL: Release ownership of the original FD so Rust doesn't close it
    let _ = input_file.into_raw_fd();

    match info_result {
        Ok(info) => {
            let json = serde_json::to_string(&info).unwrap_or_else(|e| format!("{{\"error\": \"Serialization error: {}\"}}", e));
            env.new_string(json).expect("Couldn't create java string!")
        },
        Err(e) => {
            env.new_string(format!("{{\"error\": \"{}\"}}", e)).expect("Couldn't create java string!")
        }
    }
}

fn get_iso_info_internal(file: &File) -> Result<IsoInfo, anyhow::Error> {
    // Clone the file to give ownership to IsoReader. 
    // This creates a new FD via dup(), so closing it is safe and won't affect the original FD.
    let file_clone = file.try_clone()?;
    
    let mut source_iso = IsoReader::read(file_clone)?;
    let title_info = TitleInfo::from_image(&mut source_iso)?;
    
    let exe_info = title_info.execution_info;
    let title_id = format!("{:08X}", exe_info.title_id);
    let media_id = format!("{:08X}", exe_info.media_id);
    let platform = format!("{:?}", exe_info.platform);
    let exe_type = format!("{:?}", exe_info.executable_type);
    
    let name = game_list::find_title_by_id(exe_info.title_id).unwrap_or("(unknown)".to_owned());

    // Calculate sizes (defaulting to TrimMode::FromEnd logic)
    let data_size = source_iso.get_max_used_prefix_size();
    let block_count = data_size.div_ceil(god::BLOCK_SIZE);
    let part_count = block_count.div_ceil(god::BLOCKS_PER_PART);

    Ok(IsoInfo {
        title: name,
        title_id,
        media_id,
        platform,
        exe_type,
        data_parts: part_count,
        data_size,
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_thehbc_iso2god_MainActivity_helloFromRust<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> JString<'local> {
    env.new_string("Hello from Rust!").expect("Couldn't create java string!")
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_thehbc_iso2god_MainActivity_testFileIo<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    fd: jint,
    out_fd: jint,
) -> JString<'local> {
    // 1. 将 Java 传来的文件描述符转换为 Rust File
    let mut input_file = unsafe { File::from_raw_fd(fd) };
    let mut output_file = unsafe { File::from_raw_fd(out_fd) };

    // 3. 读取前 64 字节
    let mut buffer = [0u8; 64];
    let read_result = input_file.read(&mut buffer);

    // CRITICAL: 放弃所有权，防止 Rust 关闭 FD
    let _ = input_file.into_raw_fd();

    if let Err(e) = read_result {
        // 如果读取失败，也要记得把 output_file 的所有权放弃，否则也会 close
        let _ = output_file.into_raw_fd();
        return env.new_string(format!("Error reading input: {}", e)).unwrap();
    }

    // 4. 写入到输出文件 FD
    let write_header_res = output_file.write_all(b"Header from input file:\n");
    let write_buffer_res = output_file.write_all(&buffer);

    // CRITICAL: 放弃所有权
    let _ = output_file.into_raw_fd();

    if let Err(e) = write_header_res {
         return env.new_string(format!("Error writing header: {}", e)).unwrap();
    }
    
    if let Err(e) = write_buffer_res {
        return env.new_string(format!("Error writing buffer: {}", e)).unwrap();
    }

    // 5. 返回成功信息
    let msg = format!("Success! Wrote 64 bytes to output FD {}", out_fd);
    env.new_string(msg).expect("Couldn't create java string!")
}
