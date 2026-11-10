use jni::objects::JClass;
use jni::sys::jstring;
use jni::JNIEnv;

#[no_mangle]
pub extern "system" fn Java_com_ukmm_android_Native_version(mut env: JNIEnv, _class: JClass) -> jstring {
    env.new_string("UKMM Android core 0.1").unwrap().into_raw()
}
