#pragma once
#include <utility>
namespace droiddeck::xr {
template<class Function> class ScopeExit {
public:
    explicit ScopeExit(Function function) : function_(std::move(function)) {}
    ScopeExit(const ScopeExit &) = delete;
    ~ScopeExit() { if (active_) function_(); }
    void dismiss() noexcept { active_ = false; }
private:
    Function function_;
    bool active_ = true;
};
}
