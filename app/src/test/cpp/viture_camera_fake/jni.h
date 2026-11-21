#pragma once
#include <cstdint>
#include <vector>

#define JNIEXPORT
#define JNICALL
using jlong = int64_t;
using jint = int32_t;
using jsize = int32_t;
using jbyte = int8_t;
using jobject = void*;
using jbyteArray = std::vector<jbyte>*;

struct JNIEnv {
    jbyteArray NewByteArray(jsize size) { return new std::vector<jbyte>(size); }
    void SetByteArrayRegion(jbyteArray target, jsize offset, jsize size, const jbyte* bytes) {
        for (jsize index = 0; index < size; ++index) (*target)[offset + index] = bytes[index];
    }
};
