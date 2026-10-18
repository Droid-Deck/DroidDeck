project(
'adrenotools', 
['cpp', 'c'], 
default_options : ['default_library=static']
)

cpp = meson.get_compiler('cpp')
c = meson.get_compiler('c')

adrenotools_inc = include_directories('include')

libandroid_dep = cpp.find_library('android')
liblog_dep = c.find_library('log')

subdir('lib/linkernsbypass')

linkernsbypass_dep = declare_dependency(
    include_directories: linkernsbypass_inc, 
    link_with: linkernsbypass_lib,
    link_args: '-Wl,-s,--exclude-libs,liblinkernsbypass.a'
)

adrenotools_sources = ['src/bcenabler.cpp', 'src/driver.cpp', 'include/adrenotools/bcenabler.h', 'include/adrenotools/driver.h', 'include/adrenotools/priv.h']

adrenotools_lib = shared_library(
    'adrenotools', 
    adrenotools_sources,
    c_args: (['-Wall', '-Wextra']),
    cpp_args: (['-Wall', '-Wextra']),
    include_directories: adrenotools_inc,
    dependencies: ([linkernsbypass_dep, libandroid_dep, liblog_dep]),
    install: true
)

subdir('src/hook')
