#pragma once
#include "vk_dispatch.hpp"
struct VkTable;
bool winfgVkdInit(const VkTable& table, winfg::DeviceDispatch& dd, winfg::InstanceDispatch& id);
