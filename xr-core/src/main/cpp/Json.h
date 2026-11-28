#pragma once
#include <string>
namespace droiddeck::xr {
inline std::string jsonString(const std::string &value) {
    static const char hex[] = "0123456789abcdef";
    std::string result = "\"";
    for (unsigned char ch : value) {
        if (ch == '"' || ch == '\\') { result += '\\'; result += char(ch); }
        else if (ch < 32) { result += "\\u00"; result += hex[ch >> 4]; result += hex[ch & 15]; }
        else result += char(ch);
    }
    return result + '"';
}
}
