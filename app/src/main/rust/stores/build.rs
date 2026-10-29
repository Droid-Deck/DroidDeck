fn main() {
    cc::Build::new()
        .include("vendor/xdelta3")
        .file("vendor/xdelta3/xdelta3.c")
        .file("src/gog/delta.c")
        .define("SIZEOF_SIZE_T", "8")
        .define("SIZEOF_UNSIGNED_INT", "4")
        .define("SIZEOF_UNSIGNED_LONG", "8")
        .define("SIZEOF_UNSIGNED_LONG_LONG", "8")
        .define("XD3_USE_LARGEFILE64", "1")
        .define("XD3_ENCODER", "0")
        .define("SECONDARY_DJW", "0")
        .define("SECONDARY_FGK", "0")
        .define("SECONDARY_LZMA", "0")
        .define("SHELL_TESTS", "0")
        .define("XD3_MAIN", "0")
        .warnings(false)
        .compile("gogdelta");
    println!("cargo:rerun-if-changed=vendor/xdelta3");
    println!("cargo:rerun-if-changed=src/gog/delta.c");
}
