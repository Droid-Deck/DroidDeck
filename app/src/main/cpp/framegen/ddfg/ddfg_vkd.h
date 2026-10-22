#pragma once
#include "vk_dispatch.hpp"
struct VkTable;
bool ddfgVkdInit(const VkTable& table, ddfg::DeviceDispatch& dd, ddfg::InstanceDispatch& id);
