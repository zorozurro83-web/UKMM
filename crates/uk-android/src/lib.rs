use std::{
    panic::{catch_unwind, AssertUnwindSafe},
    path::{Path, PathBuf},
    sync::Arc,
};

use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use uk_content::{constants::Language, prelude::Endian};
use uk_manager::{
    core::Manager,
    settings::{
        DeployConfig, DeployLayout, DeployMethod, Platform, PlatformSettings, Settings,
        UpdatePreference,
    },
};
use uk_reader::ResourceReader;

// ---------------------------------------------------------------------------
// Fungsi yang sudah ada
// ---------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_com_ukmm_android_Native_version(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    env.new_string("UKMM Android core 0.1").unwrap().into_raw()
}

// Panggil sekali saat app mulai, sebelum fungsi lain.
#[no_mangle]
pub extern "system" fn Java_com_ukmm_android_Native_init(
    mut env: JNIEnv,
    _c: JClass,
    data_dir: JString,
) {
    let d: String = env.get_string(&data_dir).unwrap().into();
    std::env::set_var("HOME", &d);
    std::env::set_var("XDG_CONFIG_HOME", format!("{d}/config"));
    std::env::set_var("XDG_DATA_HOME", format!("{d}/data"));
    std::env::set_var("TMPDIR", format!("{d}/tmp"));
    let _ = std::fs::create_dir_all(format!("{d}/tmp"));
}

// ---------------------------------------------------------------------------
// Pembantu
// ---------------------------------------------------------------------------

fn to_string(env: &mut JNIEnv, v: &JString) -> String {
    env.get_string(v).map(String::from).unwrap_or_default()
}

// Hasil selalu berupa teks: "OK" kalau berhasil, "ERROR: ..." kalau gagal.
// Panic dari Rust ditangkap supaya app tidak crash.
fn finish(env: &mut JNIEnv, r: std::thread::Result<anyhow::Result<()>>) -> jstring {
    let msg = match r {
        Ok(Ok(())) => "OK".to_string(),
        Ok(Err(e)) => format!("ERROR: {e:#}"),
        Err(_) => "ERROR: panic di library native".to_string(),
    };
    env.new_string(msg).unwrap().into_raw()
}

fn opt_path(p: &str) -> Option<PathBuf> {
    if p.is_empty() {
        None
    } else {
        Some(PathBuf::from(p))
    }
}

// ---------------------------------------------------------------------------
// 1. Konfigurasi: path dump game (romfs hasil ekstrak) + folder output
// ---------------------------------------------------------------------------

fn do_configure(data: &str, base: &str, update: &str, dlc: &str) -> anyhow::Result<()> {
    // DLC bersifat opsional (string kosong = tanpa DLC). Kalau diisi, harus
    // folder romfs DLC (…/01007EF00011F001/romfs) yang berisi Pack/AocMainField.pack.
    // Reader tidak memeriksa ini saat base game sudah ada, jadi path yang salah
    // baru ketahuan di tengah merge dengan pesan yang membingungkan.
    if !dlc.is_empty() && !Path::new(dlc).join("Pack/AocMainField.pack").is_file() {
        anyhow::bail!("Folder DLC tidak valid (Pack/AocMainField.pack tidak ditemukan): {dlc}");
    }
    let dump = ResourceReader::from_unpacked_dirs(
        opt_path(base),
        opt_path(update),
        opt_path(dlc),
        Endian::Little,
    )?;

    let mut s = Settings::default();
    s.current_mode = Platform::Switch;
    s.system_7z = false;
    s.storage_dir = PathBuf::from(format!("{data}/storage"));
    s.show_changelog = false;
    s.check_updates = UpdatePreference::None;
    s.switch_config = Some(PlatformSettings {
        language: Language::USen,
        profile: "Default".into(),
        dump: Arc::new(dump),
        deploy_config: Some(DeployConfig {
            output: PathBuf::from(format!("{data}/deploy")),
            method: DeployMethod::Copy,
            auto: false,
            cemu_rules: false,
            executable: None,
            // Emulator: <output>/<TitleID>/BreathOfTheWild_UKMM/romfs
            layout: DeployLayout::WithName,
        }),
    });
    s.save()?;
    Ok(())
}

#[no_mangle]
pub extern "system" fn Java_com_ukmm_android_Native_configure(
    mut env: JNIEnv,
    _c: JClass,
    data_dir: JString,
    base: JString,
    update: JString,
    dlc: JString,
) -> jstring {
    let data = to_string(&mut env, &data_dir);
    let base = to_string(&mut env, &base);
    let update = to_string(&mut env, &update);
    let dlc = to_string(&mut env, &dlc);
    let r = catch_unwind(AssertUnwindSafe(|| do_configure(&data, &base, &update, &dlc)));
    finish(&mut env, r)
}

// ---------------------------------------------------------------------------
// 2. Instal .bnp: konversi ke mod UKMM, lalu daftarkan di profil
// ---------------------------------------------------------------------------

fn do_install(bnp: &str) -> anyhow::Result<()> {
    let core = Manager::init()?;
    let zip = uk_manager::bnp::convert_bnp(&core, Path::new(bnp))?;
    core.mod_manager().add(&zip, None)?;
    core.mod_manager().save()?;
    Ok(())
}

#[no_mangle]
pub extern "system" fn Java_com_ukmm_android_Native_installBnp(
    mut env: JNIEnv,
    _c: JClass,
    bnp: JString,
    _data_dir: JString,
) -> jstring {
    let bnp = to_string(&mut env, &bnp);
    let r = catch_unwind(AssertUnwindSafe(|| do_install(&bnp)));
    finish(&mut env, r)
}

// ---------------------------------------------------------------------------
// 3. Merge: gabungkan semua mod aktif, lalu deploy ke <data>/deploy
// ---------------------------------------------------------------------------

fn do_merge() -> anyhow::Result<()> {
    let core = Manager::init()?;
    core.settings().wipe_output(Endian::Little)?;
    core.deploy_manager().apply(None)?;
    core.deploy_manager().deploy()?;
    Ok(())
}

#[no_mangle]
pub extern "system" fn Java_com_ukmm_android_Native_merge(
    mut env: JNIEnv,
    _c: JClass,
    _data_dir: JString,
) -> jstring {
    let r = catch_unwind(AssertUnwindSafe(|| do_merge()));
    finish(&mut env, r)
}
