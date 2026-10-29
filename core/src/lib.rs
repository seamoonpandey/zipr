//! zipr core: streaming zip writer over Android file descriptors.
//! Pure zip logic first (host-testable), JNI glue at the bottom.

use std::fs::File;
use std::io::{self, BufReader, BufWriter, Seek, SeekFrom, Write};
use std::os::fd::FromRawFd;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::time::UNIX_EPOCH;

use jni::objects::{JClass, JString};
use jni::sys::{jint, jlong, jstring};
use jni::JNIEnv;
use zip::write::SimpleFileOptions;
use zip::{CompressionMethod, DateTime, ZipWriter};

pub type Writer = ZipWriter<BufWriter<File>>;

/// Already-compressed formats: deflating them burns CPU for ~0% gain.
const STORED_EXTS: &[&str] = &[
    "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "avif", "dng", "mp4", "m4v", "mov", "mkv",
    "webm", "3gp", "3g2", "avi", "mp3", "m4a", "aac", "ogg", "opus", "flac", "zip", "7z", "rar",
    "gz", "apk",
];

fn is_precompressed(name: &str) -> bool {
    name.rsplit_once('.')
        .map(|(_, ext)| STORED_EXTS.iter().any(|e| e.eq_ignore_ascii_case(ext)))
        .unwrap_or(false)
}

/// Unix seconds -> zip DateTime (UTC; zip has no timezone). None if outside 1980..=2107.
fn dos_time(secs: i64) -> Option<DateTime> {
    // civil_from_days, Howard Hinnant
    let (days, rem) = (secs.div_euclid(86_400), secs.rem_euclid(86_400));
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let y = yoe + era * 400 + i64::from(m <= 2);
    DateTime::from_date_and_time(
        u16::try_from(y).ok()?,
        m as u8,
        d as u8,
        (rem / 3600) as u8,
        (rem % 3600 / 60) as u8,
        (rem % 60) as u8,
    )
    .ok()
}

pub fn open_writer(mut out: File) -> io::Result<Writer> {
    let _ = out.set_len(0); // fails on pipes/some SAF providers; harmless
    out.seek(SeekFrom::Start(0))?;
    Ok(ZipWriter::new(BufWriter::with_capacity(1 << 20, out)))
}

pub fn add_file<W: Write + Seek>(zip: &mut ZipWriter<W>, input: File, name: &str) -> Result<(), String> {
    let meta = input.metadata().map_err(|e| format!("stat {name}: {e}"))?;
    let size = meta.len();
    let method = if is_precompressed(name) { CompressionMethod::Stored } else { CompressionMethod::Deflated };
    let mut opts = SimpleFileOptions::default()
        .compression_method(method)
        .compression_level(if method == CompressionMethod::Deflated { Some(1) } else { None })
        .large_file(size >= 0xFFFF_FFFF)
        .unix_permissions(0o644);
    if let Some(t) = meta
        .modified()
        .ok()
        .and_then(|m| m.duration_since(UNIX_EPOCH).ok())
        .and_then(|d| dos_time(d.as_secs() as i64))
    {
        opts = opts.last_modified_time(t);
    }
    zip.start_file(name, opts).map_err(|e| format!("start {name}: {e}"))?;
    let mut reader = BufReader::with_capacity(256 * 1024, input);
    io::copy(&mut reader, zip).map_err(|e| format!("write {name}: {e}"))?;
    Ok(())
}

pub fn finish_writer(zip: Writer) -> Result<(), String> {
    let mut buf = zip.finish().map_err(|e| format!("finish: {e}"))?;
    buf.flush().map_err(|e| format!("flush: {e}"))?;
    Ok(())
}

// ---------------- JNI glue (app.zipr.NativeZip) ----------------

fn panic_msg(p: Box<dyn std::any::Any + Send>) -> String {
    let s = p
        .downcast_ref::<&str>()
        .map(|s| s.to_string())
        .or_else(|| p.downcast_ref::<String>().cloned())
        .unwrap_or_else(|| "unknown".into());
    format!("native panic: {s}")
}

/// Ok -> null, Err -> Java String.
fn to_jstring(env: &mut JNIEnv, r: Result<(), String>) -> jstring {
    match r {
        Ok(()) => std::ptr::null_mut(),
        Err(msg) => env.new_string(msg).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut()),
    }
}

#[no_mangle]
pub extern "system" fn Java_app_zipr_NativeZip_open(_env: JNIEnv, _class: JClass, out_fd: jint) -> jlong {
    catch_unwind(|| {
        let out = unsafe { File::from_raw_fd(out_fd) };
        match open_writer(out) {
            Ok(w) => Box::into_raw(Box::new(w)) as jlong,
            Err(_) => 0,
        }
    })
    .unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_app_zipr_NativeZip_add(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    in_fd: jint,
    name: JString,
) -> jstring {
    let r = catch_unwind(AssertUnwindSafe(|| {
        let input = unsafe { File::from_raw_fd(in_fd) }; // owned first so it closes on every path
        if handle == 0 {
            return Err("null handle".to_string());
        }
        let name: String = env.get_string(&name).map_err(|e| format!("bad name: {e}"))?.into();
        let zip = unsafe { &mut *(handle as *mut Writer) };
        add_file(zip, input, &name)
    }))
    .unwrap_or_else(|p| Err(panic_msg(p)));
    to_jstring(&mut env, r)
}

#[no_mangle]
pub extern "system" fn Java_app_zipr_NativeZip_finish(mut env: JNIEnv, _class: JClass, handle: jlong) -> jstring {
    let r = catch_unwind(|| {
        if handle == 0 {
            return Err("null handle".to_string());
        }
        let zip = unsafe { Box::from_raw(handle as *mut Writer) };
        finish_writer(*zip)
    })
    .unwrap_or_else(|p| Err(panic_msg(p)));
    to_jstring(&mut env, r)
}

#[no_mangle]
pub extern "system" fn Java_app_zipr_NativeZip_abort(_env: JNIEnv, _class: JClass, handle: jlong) {
    let _ = catch_unwind(|| {
        if handle != 0 {
            // Drop closes the fd. Note: ZipWriter's Drop best-effort writes a central directory;
            // the caller is expected to delete the partial output anyway.
            drop(unsafe { Box::from_raw(handle as *mut Writer) });
        }
    });
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Read;

    #[test]
    fn dos_time_conversion() {
        assert!(dos_time(0).is_none()); // 1970: before DOS epoch
        let t = dos_time(951_825_845).unwrap(); // 2000-02-29 12:04:05 UTC
        assert_eq!((t.year(), t.month(), t.day(), t.hour(), t.minute()), (2000, 2, 29, 12, 4));
    }

    #[test]
    fn roundtrip() {
        let dir = std::env::temp_dir().join(format!("zipr-test-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let files: &[(&str, Vec<u8>)] = &[
            ("photo.JPG", vec![0xFF, 0xD8, 0xFF, 1, 2, 3, 4, 5]),
            ("notes.txt", b"hello hello hello hello hello".to_vec()),
            ("sub/dir/a.txt", b"nested".repeat(100)),
        ];
        let out_path = dir.join("out.zip");
        let mut zip = open_writer(File::create(&out_path).unwrap()).unwrap();
        for (i, (name, data)) in files.iter().enumerate() {
            let p = dir.join(format!("in{i}"));
            std::fs::write(&p, data).unwrap();
            add_file(&mut zip, File::open(&p).unwrap(), name).unwrap();
        }
        finish_writer(zip).unwrap();

        let mut ar = zip::ZipArchive::new(File::open(&out_path).unwrap()).unwrap();
        assert_eq!(ar.len(), files.len());
        for (name, data) in files {
            let mut f = ar.by_name(name).unwrap();
            let want = if name.ends_with(".JPG") { CompressionMethod::Stored } else { CompressionMethod::Deflated };
            assert_eq!(f.compression(), want, "{name}");
            let mut got = Vec::new();
            f.read_to_end(&mut got).unwrap();
            assert_eq!(&got, data, "{name}");
        }
        std::fs::remove_dir_all(&dir).unwrap();
    }
}
