use jni::objects::JClass;
use jni::sys::jstring;
use jni::JNIEnv;

#[no_mangle]
pub extern "system" fn Java_com_ukmm_android_Native_version(mut env: JNIEnv, _class: JClass) -> jstring {
    env.new_string("UKMM Android core 0.1").unwrap().into_raw()
}
use jni::objects::JString;

#[no_mangle]
pub extern "system" fn Java_com_ukmm_android_Native_init(
    mut env: JNIEnv, _c: JClass, data_dir: JString,
) {
    let d: String = env.get_string(&data_dir).unwrap().into();
    std::env::set_var("HOME", &d);
    std::env::set_var("XDG_CONFIG_HOME", format!("{d}/config"));
    std::env::set_var("XDG_DATA_HOME", format!("{d}/data"));
    std::env::set_var("TMPDIR", format!("{d}/tmp"));
    let _ = std::fs::create_dir_all(format!("{d}/tmp"));
}
