fn main() {
    println!("cargo:rerun-if-env-changed=NEXA_UPDATER_PUBLIC_KEY");
    tauri_build::build()
}
