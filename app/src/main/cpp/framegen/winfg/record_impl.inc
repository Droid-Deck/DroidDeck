
namespace {
struct ScratchState {
    VkBuffer synthUbo = VK_NULL_HANDLE; VkDeviceMemory synthUboMem = VK_NULL_HANDLE; void* synthUboPtr = nullptr;
    std::vector<VkBuffer> flowUbo; std::vector<VkDeviceMemory> flowUboMem; std::vector<void*> flowUboPtr;
    VkBuffer expandUbo = VK_NULL_HANDLE; VkDeviceMemory expandUboMem = VK_NULL_HANDLE; void* expandUboPtr = nullptr;
    VkBuffer flowRegUbo = VK_NULL_HANDLE; VkDeviceMemory flowRegUboMem = VK_NULL_HANDLE; void* flowRegUboPtr = nullptr;
    VkBuffer gmUbo[winfg::FrameGen::kGmSlots]{};
    VkDeviceMemory gmUboMem[winfg::FrameGen::kGmSlots]{};
    void* gmUboPtr[winfg::FrameGen::kGmSlots]{};
    VkDescriptorSet sLumaCurr = VK_NULL_HANDLE, sLumaPrev = VK_NULL_HANDLE;
    std::vector<VkDescriptorSet> sDownA, sDownB, sFlow;
    VkDescriptorSet sExpand[winfg::FrameGen::kGmSlots]{};



    VkDescriptorSet sFlowReg0 = VK_NULL_HANDLE, sFlowRegAB = VK_NULL_HANDLE, sFlowRegBA = VK_NULL_HANDLE;
    VkDescriptorSet sSynth = VK_NULL_HANDLE;
    VkDescriptorSet sGmReduce[winfg::FrameGen::kGmSlots]{};
    std::vector<VkDescriptorSet> sGmPrewarp[winfg::FrameGen::kGmSlots];
    VkExtent2D builtFor{0,0};
    bool built = false;
    bool flowInited = false;
};
ScratchState g_scratch;
}

static bool makeUbo(const winfg::DeviceDispatch* dd, VkDevice dev,
                    const VkPhysicalDeviceMemoryProperties& mp,
                    VkDeviceSize size, VkBuffer& buf, VkDeviceMemory& mem, void*& ptr) {
    VkBufferCreateInfo bi{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    bi.size = size; bi.usage = VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT; bi.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    if (dd->CreateBuffer(dev, &bi, nullptr, &buf) != VK_SUCCESS) return false;
    VkMemoryRequirements mr; dd->GetBufferMemoryRequirements(dev, buf, &mr);
    uint32_t idx = 0; VkMemoryPropertyFlags want = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
    for (uint32_t i = 0; i < mp.memoryTypeCount; ++i)
        if ((mr.memoryTypeBits & (1u<<i)) && (mp.memoryTypes[i].propertyFlags & want) == want) { idx = i; break; }
    VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO}; ai.allocationSize = mr.size; ai.memoryTypeIndex = idx;
    if (dd->AllocateMemory(dev, &ai, nullptr, &mem) != VK_SUCCESS) return false;
    if (dd->BindBufferMemory(dev, buf, mem, 0) != VK_SUCCESS) return false;
    return dd->MapMemory(dev, mem, 0, size, 0, &ptr) == VK_SUCCESS;
}

static VkDescriptorSet allocSet(const winfg::DeviceDispatch* dd, VkDevice dev,
                                VkDescriptorPool pool, VkDescriptorSetLayout layout) {
    VkDescriptorSetAllocateInfo ai{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
    ai.descriptorPool = pool; ai.descriptorSetCount = 1; ai.pSetLayouts = &layout;
    VkDescriptorSet s = VK_NULL_HANDLE; dd->AllocateDescriptorSets(dev, &ai, &s); return s;
}

static void wImg(const winfg::DeviceDispatch* dd, VkDevice dev, VkDescriptorSet set, uint32_t binding,
                 VkDescriptorType type, VkImageView view, VkSampler samp, VkImageLayout layout) {
    VkDescriptorImageInfo ii{}; ii.imageView = view; ii.sampler = samp; ii.imageLayout = layout;
    VkWriteDescriptorSet w{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
    w.dstSet = set; w.dstBinding = binding; w.descriptorCount = 1; w.descriptorType = type; w.pImageInfo = &ii;
    dd->UpdateDescriptorSets(dev, 1, &w, 0, nullptr);
}
static void wUbo(const winfg::DeviceDispatch* dd, VkDevice dev, VkDescriptorSet set, uint32_t binding,
                 VkBuffer buf, VkDeviceSize range) {
    VkDescriptorBufferInfo bi{}; bi.buffer = buf; bi.range = range;
    VkWriteDescriptorSet w{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
    w.dstSet = set; w.dstBinding = binding; w.descriptorCount = 1;
    w.descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER; w.pBufferInfo = &bi;
    dd->UpdateDescriptorSets(dev, 1, &w, 0, nullptr);
}
static void wSsbo(const winfg::DeviceDispatch* dd, VkDevice dev, VkDescriptorSet set, uint32_t binding,
                  VkBuffer buf, VkDeviceSize range) {
    VkDescriptorBufferInfo bi{}; bi.buffer = buf; bi.range = range;
    VkWriteDescriptorSet w{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
    w.dstSet = set; w.dstBinding = binding; w.descriptorCount = 1;
    w.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER; w.pBufferInfo = &bi;
    dd->UpdateDescriptorSets(dev, 1, &w, 0, nullptr);
}

static bool cholSolve6(double A[6][6], const double b[6], double x[6]) {
    double L[6][6] = {{0}};
    for (int i = 0; i < 6; ++i) {
        for (int j = 0; j <= i; ++j) {
            double s = A[i][j];
            for (int k = 0; k < j; ++k) s -= L[i][k] * L[j][k];
            if (i == j) {
                if (s <= 1e-20) return false;
                L[i][j] = std::sqrt(s);
            } else {
                L[i][j] = s / L[j][j];
            }
        }
    }
    double y[6];
    for (int i = 0; i < 6; ++i) { double s = b[i]; for (int k = 0; k < i; ++k) s -= L[i][k]*y[k]; y[i] = s / L[i][i]; }
    for (int i = 5; i >= 0; --i) { double s = y[i]; for (int k = i+1; k < 6; ++k) s -= L[k][i]*x[k]; x[i] = s / L[i][i]; }
    return true;
}
static void affFromParams(const double p[6], double M[3][3]) {
    M[0][0]=1.0+p[0]; M[0][1]=p[2];     M[0][2]=p[4];
    M[1][0]=p[1];     M[1][1]=1.0+p[3]; M[1][2]=p[5];
    M[2][0]=0.0;      M[2][1]=0.0;      M[2][2]=1.0;
}
static void paramsFromAff(const double M[3][3], double p[6]) {
    p[0]=M[0][0]-1.0; p[2]=M[0][1]; p[4]=M[0][2];
    p[1]=M[1][0];     p[3]=M[1][1]-1.0; p[5]=M[1][2];
}
static bool invAff(const double M[3][3], double out[3][3]) {
    double a=M[0][0], b=M[0][1], tx=M[0][2], c=M[1][0], d=M[1][1], ty=M[1][2];
    double det = a*d - b*c;
    if (std::fabs(det) < 1e-12) return false;
    double id = 1.0/det;
    out[0][0]= d*id;  out[0][1]= -b*id; out[0][2]= (b*ty - d*tx)*id;
    out[1][0]= -c*id; out[1][1]= a*id;  out[1][2]= (c*tx - a*ty)*id;
    out[2][0]=0.0; out[2][1]=0.0; out[2][2]=1.0;
    return true;
}
static void mulAff(const double A[3][3], const double B[3][3], double out[3][3]) {
    for (int i = 0; i < 2; ++i)
        for (int j = 0; j < 3; ++j)
            out[i][j] = A[i][0]*B[0][j] + A[i][1]*B[1][j] + A[i][2]*B[2][j];
    out[2][0]=0.0; out[2][1]=0.0; out[2][2]=1.0;
}

namespace winfg {

static bool ensureScratch(FrameGen* fg, const DeviceDispatch* dd, VkDevice dev,
                          const VkPhysicalDeviceMemoryProperties& mp, VkDescriptorPool pool,
                          VkSampler samp, VkExtent2D ext,
                          std::vector<FrameGen::Img>& pyrA, std::vector<FrameGen::Img>& pyrB,
                          std::vector<FrameGen::Img>& pyrAs,
                          std::vector<FrameGen::Img>& flowLvl, FrameGen::Img& expA, FrameGen::Img& expB,
                          const FrameGen::Pipe& pLuma, const FrameGen::Pipe& pDown,
                          const FrameGen::Pipe& pFlow, const FrameGen::Pipe& pExpand,
                          const FrameGen::Pipe& pSynth, const FrameGen::Pipe& pGmReduce,
                          const FrameGen::Pipe& pGmPrewarp, const FrameGen::Pipe& pFlowReg,
                          FrameGen::Img& flowRegA, FrameGen::Img& flowRegB, VkBuffer* gmSsbo,
                          int kLevels, const Config& cfg);

static void destroyScratch(const DeviceDispatch* dd, VkDevice dev, VkDescriptorPool pool) {
    auto freeUbo = [&](VkBuffer& b, VkDeviceMemory& m, void*& p) {
        if (m) dd->UnmapMemory(dev, m);
        if (b) dd->DestroyBuffer(dev, b, nullptr);
        if (m) dd->FreeMemory(dev, m, nullptr);
        b = VK_NULL_HANDLE; m = VK_NULL_HANDLE; p = nullptr;
    };
    auto& S = g_scratch;
    freeUbo(S.synthUbo,   S.synthUboMem,   S.synthUboPtr);
    freeUbo(S.expandUbo,  S.expandUboMem,  S.expandUboPtr);
    freeUbo(S.flowRegUbo, S.flowRegUboMem, S.flowRegUboPtr);
    for (size_t i = 0; i < S.flowUbo.size(); ++i) freeUbo(S.flowUbo[i], S.flowUboMem[i], S.flowUboPtr[i]);
    for (int s = 0; s < FrameGen::kGmSlots; ++s) freeUbo(S.gmUbo[s], S.gmUboMem[s], S.gmUboPtr[s]);
    if (pool && dd->ResetDescriptorPool) dd->ResetDescriptorPool(dev, pool, 0);
    S = ScratchState{};
}

void FrameGen::configure(const Config& c) {
    cfg_ = c; cfg_.sanitize();
    int want = flowFinestForPreset(cfg_.perfPreset);
    const int maxFinest = kLevels - 2;
    if (want > maxFinest) {
        WFG_LOGE("perf-preset=%d wants kFlowFinest=%d but only %d pyramid levels exist "
                 "(need >=2 solved) - clamping to %d", cfg_.perfPreset, want, kLevels, maxFinest);
        want = maxFinest;
    }
    if (want < 1) want = 1;

    const int prevFinest = flowFinest_;
    flowFinest_ = want;
    const bool live = (flowFinest_ != prevFinest) && ready_;
    WFG_LOGI("perf-preset=%d -> kFlowFinest=%d (was %d) live-rebuild=%d ready=%d",
             cfg_.perfPreset, flowFinest_, prevFinest, live ? 1 : 0, ready_ ? 1 : 0);

    if (!live) return;




    dd_->DeviceWaitIdle(device_);
    destroyScratch(dd_, device_, descPool_);
    if (!onResize(extent_, VK_FORMAT_UNDEFINED,  true))
        WFG_LOGE("perf-preset live rebuild: onResize FAILED at %ux%u - FG disabled until next resize",
                 extent_.width, extent_.height);
    for (int i = 0; i < 6; ++i) gmRun_[i] = 0.0;
    for (int s = 0; s < kGmSlots; ++s) { gmSlotValid_[s] = false; for (int i = 0; i < 6; ++i) gmLinAt_[s][i] = 0.0; }
    gmHaveEst_ = false; gmEngaged_ = false; gmStableCount_ = 0; gmLastTransPx_ = 0.0f;
    WFG_LOGI("perf-preset live rebuild DONE (kFlowFinest=%d, flow predictor reset)", flowFinest_);
}

bool FrameGen::updateGlobalMotion(uint32_t gmSlot, VkExtent2D fullExtent) {
    ++gmFrames_;
    if (!gmBuffersReady_ || cfg_.gmMode == 2  ) {

        gmEngaged_ = false; gmStableCount_ = 0;
        for (int i = 0; i < 6; ++i) gmRun_[i] = 0.0;
        for (int i = 0; i < 6; ++i) gmLinAt_[gmSlot][i] = gmRun_[i];
        gmLastTransPx_ = 0.0f;
        return false;
    }

    bool good = false;
    if (gmSlotValid_[gmSlot]) {
        const float* pt = (const float*)gmSsboPtr_[gmSlot];
        double sums[32]; for (int k = 0; k < 32; ++k) sums[k] = 0.0;
        for (int t = 0; t < kGmThreads; ++t) {
            const float* row = pt + (size_t)t * kGmStride;
            for (int k = 0; k < kGmStride; ++k) sums[k] += (double)row[k];
        }
        double coverage = sums[27];
        double lvlpx = double((fullExtent.width >> kGmLevel) * (fullExtent.height >> kGmLevel));
        double minCov = 0.03 * (lvlpx > 0 ? lvlpx : 1.0);
        if (coverage > minCov && coverage > 64.0) {
            double H[6][6]; int idx = 0;
            for (int r = 0; r < 6; ++r) for (int c = r; c < 6; ++c) { H[r][c] = H[c][r] = sums[idx]; ++idx; }
            double b[6]; for (int i = 0; i < 6; ++i) b[i] = sums[21 + i];
            for (int i = 0; i < 6; ++i) H[i][i] += 1e-3 * H[i][i] + 1e-9;
            double dp[6];
            if (cholSolve6(H, b, dp)) {
                bool bounded = std::fabs(dp[0]) < 0.5 && std::fabs(dp[1]) < 0.5 &&
                               std::fabs(dp[2]) < 0.5 && std::fabs(dp[3]) < 0.5 &&
                               std::fabs(dp[4]) < 0.25 && std::fabs(dp[5]) < 0.25;
                if (bounded) {
                    double Mlin[3][3], Md[3][3], Mdi[3][3], Mnew[3][3];
                    affFromParams(gmLinAt_[gmSlot], Mlin);
                    affFromParams(dp, Md);
                    if (invAff(Md, Mdi)) {
                        mulAff(Mlin, Mdi, Mnew);
                        double pnew[6]; paramsFromAff(Mnew, pnew);
                        bool sane = std::fabs(pnew[0]) < 0.6 && std::fabs(pnew[1]) < 0.6 &&
                                    std::fabs(pnew[2]) < 0.6 && std::fabs(pnew[3]) < 0.6 &&
                                    std::fabs(pnew[4]) < 0.5 && std::fabs(pnew[5]) < 0.5;
                        if (sane) { for (int i = 0; i < 6; ++i) gmRun_[i] = pnew[i]; good = true; gmHaveEst_ = true; }
                    }
                }
            }
        }
    }

    if (good) ++gmStableCount_; else gmStableCount_ = 0;

    switch (cfg_.gmMode) {
        case 1: gmEngaged_ = gmHaveEst_; break;
        case 2: gmEngaged_ = false; break;
        default: gmEngaged_ = (gmStableCount_ >= 3); break;
    }

    for (int i = 0; i < 6; ++i) gmLinAt_[gmSlot][i] = gmRun_[i];
    gmSlotValid_[gmSlot] = true;

    gmLastTransPx_ = (float)std::sqrt(
        (gmRun_[4]*fullExtent.width) * (gmRun_[4]*fullExtent.width) +
        (gmRun_[5]*fullExtent.height) * (gmRun_[5]*fullExtent.height));
    return gmEngaged_;
}

void FrameGen::record(VkCommandBuffer cmd, VkImageView prevView, VkImageView currView,
                      VkImageView outView, float alpha, uint32_t gmSlot) {
    if (!valid()) return;
    if (gmSlot >= (uint32_t)kGmSlots) gmSlot = 0;
    const bool m4 = (cfg_.model == 4);
    const Pipe& flowP   = m4 ? pFlowM4_   : pFlow_;
    const Pipe& expandP = m4 ? pExpandM4_ : pExpand_;

    if (!g_scratch.built || g_scratch.builtFor.width != extent_.width || g_scratch.builtFor.height != extent_.height) {
        ensureScratch(this, dd_, device_, memProps_, descPool_, linear_, extent_,
                      pyrA_, pyrB_, pyrAs_, flowLvl_, flowExpA_, flowExpB_,
                      pLuma_, pDown_, flowP, expandP, pSynth_, pGmReduce_, pGmPrewarp_,
                      pFlowReg_, flowRegA_, flowRegB_, gmSsbo_, kLevels, cfg_);
    }

    bool gmEngaged = updateGlobalMotion(gmSlot, extent_);
    if (g_scratch.gmUboPtr[gmSlot]) {
        GMUBO g{};
        g.lin[0]=(float)gmRun_[0]; g.lin[1]=(float)gmRun_[1]; g.lin[2]=(float)gmRun_[2]; g.lin[3]=(float)gmRun_[3];
        g.linT[0]=(float)gmRun_[4]; g.linT[1]=(float)gmRun_[5]; g.linT[2]=gmEngaged?1.0f:0.0f; g.linT[3]=0.0f;
        if (gmEngaged) {
            g.app[0]=(float)gmRun_[0]; g.app[1]=(float)gmRun_[1]; g.app[2]=(float)gmRun_[2]; g.app[3]=(float)gmRun_[3];
            g.appT[0]=(float)gmRun_[4]; g.appT[1]=(float)gmRun_[5];
        }
        std::memcpy(g_scratch.gmUboPtr[gmSlot], &g, sizeof(g));
    }
    {
        static int lastEng = -1;
        if (gmEngaged != (lastEng == 1) || (gmFrames_ % 300ull) == 0) {
            WFG_LOGI("C1 global-motion: engaged=%d mode=%d stable=%d transPx=%.1f (model=%d)",
                     gmEngaged ? 1 : 0, cfg_.gmMode, gmStableCount_, gmLastTransPx_, cfg_.model);
            lastEng = gmEngaged ? 1 : 0;
        }
    }

    SynthUBO su{cfg_.flowScale, alpha, cfg_.beta, cfg_.lambda, cfg_.epsilon, cfg_.photoScale, 0, 0,
                cfg_.hudX0, cfg_.hudY0, cfg_.hudX1, cfg_.hudY1};
    if (g_scratch.synthUboPtr) std::memcpy(g_scratch.synthUboPtr, &su, sizeof(su));

    wImg(dd_, device_, g_scratch.sLumaCurr, 32, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, currView, linear_, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
    wImg(dd_, device_, g_scratch.sLumaPrev, 32, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, prevView, linear_, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
    wImg(dd_, device_, g_scratch.sSynth, 32, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, prevView, linear_, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
    wImg(dd_, device_, g_scratch.sSynth, 33, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, currView, linear_, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
    wImg(dd_, device_, g_scratch.sSynth, 48, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, outView, VK_NULL_HANDLE, VK_IMAGE_LAYOUT_GENERAL);

    auto disp = [&](const Pipe& p, VkDescriptorSet set, VkExtent2D e) {
        dd_->CmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, p.pipeline);
        dd_->CmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, p.layout, 0, 1, &set, 0, nullptr);
        dd_->CmdDispatch(cmd, (e.width + 15) / 16, (e.height + 15) / 16, 1);
    };
    auto bar = [&](FrameGen::Img& i){ barrier(cmd, i.image, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL); };

    for (auto& i : pyrA_)    barrier(cmd, i.image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);
    for (auto& i : pyrB_)    barrier(cmd, i.image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);
    for (int l = flowFinest_; l < kLevels; ++l) barrier(cmd, pyrAs_[l].image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);
    barrier(cmd, flowExpA_.image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);
    barrier(cmd, flowExpB_.image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);
    barrier(cmd, flowRegA_.image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);
    barrier(cmd, flowRegB_.image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);
    {
        VkClearColorValue zero{};
        VkImageSubresourceRange rng{VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        for (int l = flowFinest_; l < kLevels; ++l) {
            VkImageMemoryBarrier b{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
            b.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED; b.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
            b.srcAccessMask = 0; b.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
            b.image = flowLvl_[l].image; b.subresourceRange = rng;
            b.srcQueueFamilyIndex = b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            dd_->CmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1, &b);
            dd_->CmdClearColorImage(cmd, flowLvl_[l].image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, &zero, 1, &rng);
            b.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL; b.newLayout = VK_IMAGE_LAYOUT_GENERAL;
            b.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT; b.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
            dd_->CmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 0, nullptr, 1, &b);
        }
    }

    disp(pLuma_, g_scratch.sLumaCurr, levelExtent(extent_, 0));
    disp(pLuma_, g_scratch.sLumaPrev, levelExtent(extent_, 0));
    bar(pyrA_[0]); bar(pyrB_[0]);
    for (int l = 1; l < kLevels; ++l) {
        disp(pDown_, g_scratch.sDownA[l - 1], levelExtent(extent_, l));
        disp(pDown_, g_scratch.sDownB[l - 1], levelExtent(extent_, l));
        bar(pyrA_[l]); bar(pyrB_[l]);
    }
    if (gmBuffersReady_ && g_scratch.sGmReduce[gmSlot]) {
        dd_->CmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pGmReduce_.pipeline);
        dd_->CmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pGmReduce_.layout, 0, 1, &g_scratch.sGmReduce[gmSlot], 0, nullptr);
        dd_->CmdDispatch(cmd, (uint32_t)(kGmThreads / 64), 1, 1);
        VkMemoryBarrier mb{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
        mb.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT; mb.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
        dd_->CmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT,
                                0, 1, &mb, 0, nullptr, 0, nullptr);
    }
    for (int l = flowFinest_; l < kLevels; ++l) {
        disp(pGmPrewarp_, g_scratch.sGmPrewarp[gmSlot][l], levelExtent(extent_, l));
        bar(pyrAs_[l]);
    }
    for (int l = kLevels - 1; l >= flowFinest_; --l) {
        disp(flowP, g_scratch.sFlow[l], levelExtent(extent_, l));
        bar(flowLvl_[l]);
    }

    const bool frEngaged = (cfg_.frMode != 2) && (cfg_.frIters > 0);
    if (frEngaged) {
        const int F = flowFinest_;
        const VkExtent2D fe = levelExtent(extent_, F);
        if (g_scratch.flowRegUboPtr) {
            FlowRegUBO fu{cfg_.frDt, cfg_.frLambda, cfg_.frEdge, cfg_.frEps};
            std::memcpy(g_scratch.flowRegUboPtr, &fu, sizeof(fu));
        }
        FrameGen::Img* last = nullptr;
        for (int it = 0; it < cfg_.frIters; ++it) {
            VkDescriptorSet set; FrameGen::Img* outImg;
            if      (it == 0)      { set = g_scratch.sFlowReg0;  outImg = &flowRegA_; }
            else if (it % 2 == 1)  { set = g_scratch.sFlowRegAB; outImg = &flowRegB_; }
            else                   { set = g_scratch.sFlowRegBA; outImg = &flowRegA_; }
            dd_->CmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pFlowReg_.pipeline);
            dd_->CmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pFlowReg_.layout, 0, 1, &set, 0, nullptr);
            dd_->CmdDispatch(cmd, (fe.width + 15) / 16, (fe.height + 15) / 16, 1);
            bar(*outImg);
            last = outImg;
        }
        VkMemoryBarrier mb{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
        mb.srcAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
        mb.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT;
        dd_->CmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                                0, 1, &mb, 0, nullptr, 0, nullptr);
        VkImageCopy region{};
        region.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
        region.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
        region.extent = {fe.width, fe.height, 1};
        dd_->CmdCopyImage(cmd, last->image, VK_IMAGE_LAYOUT_GENERAL,
                          flowLvl_[F].image, VK_IMAGE_LAYOUT_GENERAL, 1, &region);
        VkMemoryBarrier mb2{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
        mb2.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        mb2.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        dd_->CmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                                0, 1, &mb2, 0, nullptr, 0, nullptr);
    }
    {
        static int lastFr = -1;
        int fe = frEngaged ? 1 : 0;
        if (fe != lastFr || (gmFrames_ % 300ull) == 0) {
            WFG_LOGI("C2 flow-reg: engaged=%d mode=%d iters=%d lambda=%.2f dt=%.2f edge=%.1f eps=%.3f (model=%d)",
                     fe, cfg_.frMode, cfg_.frIters, cfg_.frLambda, cfg_.frDt, cfg_.frEdge, cfg_.frEps, cfg_.model);
            lastFr = fe;
        }
    }

    disp(expandP, g_scratch.sExpand[gmSlot], levelExtent(extent_, 0));
    bar(flowExpA_); bar(flowExpB_);
    disp(pSynth_, g_scratch.sSynth, levelExtent(extent_, 0));
}

static bool ensureScratch(FrameGen* fg, const DeviceDispatch* dd, VkDevice dev,
                          const VkPhysicalDeviceMemoryProperties& mp, VkDescriptorPool pool,
                          VkSampler samp, VkExtent2D ext,
                          std::vector<FrameGen::Img>& pyrA, std::vector<FrameGen::Img>& pyrB,
                          std::vector<FrameGen::Img>& pyrAs,
                          std::vector<FrameGen::Img>& flowLvl, FrameGen::Img& expA, FrameGen::Img& expB,
                          const FrameGen::Pipe& pLuma, const FrameGen::Pipe& pDown,
                          const FrameGen::Pipe& pFlow, const FrameGen::Pipe& pExpand,
                          const FrameGen::Pipe& pSynth, const FrameGen::Pipe& pGmReduce,
                          const FrameGen::Pipe& pGmPrewarp, const FrameGen::Pipe& pFlowReg,
                          FrameGen::Img& flowRegA, FrameGen::Img& flowRegB, VkBuffer* gmSsbo,
                          int kLevels, const Config& cfg) {
    auto& S = g_scratch;
    S = ScratchState{};
    const auto GEN = VK_IMAGE_LAYOUT_GENERAL;

    makeUbo(dd, dev, mp, sizeof(SynthUBO), S.synthUbo, S.synthUboMem, S.synthUboPtr);
    makeUbo(dd, dev, mp, sizeof(FlowUBO), S.expandUbo, S.expandUboMem, S.expandUboPtr);
    if (S.expandUboPtr) { FlowUBO u{cfg.flowScale, 0, 0.6f, 1.6f}; std::memcpy(S.expandUboPtr, &u, sizeof(u)); }
    makeUbo(dd, dev, mp, sizeof(FlowRegUBO), S.flowRegUbo, S.flowRegUboMem, S.flowRegUboPtr);
    if (S.flowRegUboPtr) { FlowRegUBO fu{cfg.frDt, cfg.frLambda, cfg.frEdge, cfg.frEps}; std::memcpy(S.flowRegUboPtr, &fu, sizeof(fu)); }
    S.flowUbo.resize(kLevels); S.flowUboMem.resize(kLevels); S.flowUboPtr.resize(kLevels);
    for (int l = 0; l < kLevels; ++l) {
        makeUbo(dd, dev, mp, sizeof(FlowUBO), S.flowUbo[l], S.flowUboMem[l], S.flowUboPtr[l]);
        if (S.flowUboPtr[l]) { FlowUBO u{cfg.flowScale, (uint32_t)l, 0.6f, 1.6f}; std::memcpy(S.flowUboPtr[l], &u, sizeof(u)); }
    }
    for (int s = 0; s < FrameGen::kGmSlots; ++s) {
        makeUbo(dd, dev, mp, sizeof(GMUBO), S.gmUbo[s], S.gmUboMem[s], S.gmUboPtr[s]);
        if (S.gmUboPtr[s]) { GMUBO g{}; std::memcpy(S.gmUboPtr[s], &g, sizeof(g)); }
    }

    S.sLumaCurr = allocSet(dd, dev, pool, pLuma.setLayout);
    S.sLumaPrev = allocSet(dd, dev, pool, pLuma.setLayout);
    wImg(dd, dev, S.sLumaCurr, 48, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, pyrA[0].view, VK_NULL_HANDLE, GEN);
    wImg(dd, dev, S.sLumaPrev, 48, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, pyrB[0].view, VK_NULL_HANDLE, GEN);

    S.sDownA.resize(kLevels - 1); S.sDownB.resize(kLevels - 1);
    for (int l = 1; l < kLevels; ++l) {
        S.sDownA[l-1] = allocSet(dd, dev, pool, pDown.setLayout);
        S.sDownB[l-1] = allocSet(dd, dev, pool, pDown.setLayout);
        wImg(dd, dev, S.sDownA[l-1], 32, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, pyrA[l-1].view, samp, GEN);
        wImg(dd, dev, S.sDownA[l-1], 48, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, pyrA[l].view, VK_NULL_HANDLE, GEN);
        wImg(dd, dev, S.sDownB[l-1], 32, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, pyrB[l-1].view, samp, GEN);
        wImg(dd, dev, S.sDownB[l-1], 48, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, pyrB[l].view, VK_NULL_HANDLE, GEN);
    }

    S.sFlow.resize(kLevels);
    for (int l = 0; l < kLevels; ++l) {
        S.sFlow[l] = allocSet(dd, dev, pool, pFlow.setLayout);
        int pred = (l + 1 < kLevels) ? l + 1 : l;
        VkImageView currLvl = (l >= fg->flowFinest()) ? pyrAs[l].view : pyrA[l].view;
        wUbo(dd, dev, S.sFlow[l], 0, S.flowUbo[l], sizeof(FlowUBO));
        wImg(dd, dev, S.sFlow[l], 32, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, pyrB[l].view, samp, GEN);
        wImg(dd, dev, S.sFlow[l], 33, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, currLvl, samp, GEN);
        wImg(dd, dev, S.sFlow[l], 34, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, flowLvl[pred].view, samp, GEN);
        wImg(dd, dev, S.sFlow[l], 48, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, flowLvl[l].view, VK_NULL_HANDLE, GEN);
    }

    if (gmSsbo) {
        for (int s = 0; s < FrameGen::kGmSlots; ++s) {
            if (!gmSsbo[s]) continue;
            S.sGmReduce[s] = allocSet(dd, dev, pool, pGmReduce.setLayout);
            wUbo (dd, dev, S.sGmReduce[s], 1,  S.gmUbo[s], sizeof(GMUBO));
            wSsbo(dd, dev, S.sGmReduce[s], 16, gmSsbo[s], VK_WHOLE_SIZE);
            wImg (dd, dev, S.sGmReduce[s], 32, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, pyrB[FrameGen::kGmLevel].view, samp, GEN);
            wImg (dd, dev, S.sGmReduce[s], 33, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, pyrA[FrameGen::kGmLevel].view, samp, GEN);
        }
    }

    for (int s = 0; s < FrameGen::kGmSlots; ++s) {
        S.sGmPrewarp[s].assign(kLevels, VK_NULL_HANDLE);
        for (int l = fg->flowFinest(); l < kLevels; ++l) {
            S.sGmPrewarp[s][l] = allocSet(dd, dev, pool, pGmPrewarp.setLayout);
            wUbo(dd, dev, S.sGmPrewarp[s][l], 1,  S.gmUbo[s], sizeof(GMUBO));
            wImg(dd, dev, S.sGmPrewarp[s][l], 32, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, pyrA[l].view, samp, GEN);
            wImg(dd, dev, S.sGmPrewarp[s][l], 48, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, pyrAs[l].view, VK_NULL_HANDLE, GEN);
        }
    }

    for (int s = 0; s < FrameGen::kGmSlots; ++s) {
        S.sExpand[s] = allocSet(dd, dev, pool, pExpand.setLayout);
        wUbo(dd, dev, S.sExpand[s], 0, S.expandUbo, sizeof(FlowUBO));
        wUbo(dd, dev, S.sExpand[s], 1, S.gmUbo[s], sizeof(GMUBO));
        wImg(dd, dev, S.sExpand[s], 32, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, flowLvl[fg->flowFinest()].view, samp, GEN);
        wImg(dd, dev, S.sExpand[s], 48, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, expA.view, VK_NULL_HANDLE, GEN);
        wImg(dd, dev, S.sExpand[s], 49, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, expB.view, VK_NULL_HANDLE, GEN);
    }

    {
        const int F = fg->flowFinest();
        S.sFlowReg0  = allocSet(dd, dev, pool, pFlowReg.setLayout);
        S.sFlowRegAB = allocSet(dd, dev, pool, pFlowReg.setLayout);
        S.sFlowRegBA = allocSet(dd, dev, pool, pFlowReg.setLayout);
        VkDescriptorSet all3[3] = {S.sFlowReg0, S.sFlowRegAB, S.sFlowRegBA};
        for (VkDescriptorSet s : all3) {
            wUbo(dd, dev, s, 0,  S.flowRegUbo, sizeof(FlowRegUBO));
            wImg(dd, dev, s, 33, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, flowLvl[F].view, samp, GEN);
            wImg(dd, dev, s, 34, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, pyrB[F].view,    samp, GEN);
        }
        wImg(dd, dev, S.sFlowReg0,  32, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, flowLvl[F].view, samp, GEN);
        wImg(dd, dev, S.sFlowReg0,  48, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,          flowRegA.view,   VK_NULL_HANDLE, GEN);
        wImg(dd, dev, S.sFlowRegAB, 32, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, flowRegA.view,   samp, GEN);
        wImg(dd, dev, S.sFlowRegAB, 48, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,          flowRegB.view,   VK_NULL_HANDLE, GEN);
        wImg(dd, dev, S.sFlowRegBA, 32, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, flowRegB.view,   samp, GEN);
        wImg(dd, dev, S.sFlowRegBA, 48, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,          flowRegA.view,   VK_NULL_HANDLE, GEN);
    }

    S.sSynth = allocSet(dd, dev, pool, pSynth.setLayout);
    wUbo(dd, dev, S.sSynth, 0, S.synthUbo, sizeof(SynthUBO));
    wImg(dd, dev, S.sSynth, 34, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, expA.view, samp, GEN);
    wImg(dd, dev, S.sSynth, 35, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, expB.view, samp, GEN);

    S.builtFor = ext; S.built = true;
    return true;
}

}
