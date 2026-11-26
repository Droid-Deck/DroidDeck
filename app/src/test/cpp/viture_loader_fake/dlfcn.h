#pragma once

constexpr int RTLD_NOW = 2;
constexpr int RTLD_LOCAL = 0;

extern "C" {
void* dlopen(const char* path, int flags);
void* dlsym(void* handle, const char* name);
int dlclose(void* handle);
char* dlerror();
}
