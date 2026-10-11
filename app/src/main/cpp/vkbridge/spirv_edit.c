/* SPDX-License-Identifier: GPL-3.0-or-later */
#include "spirv_edit.h"

#include <stdlib.h>
#include <string.h>

int spv_parse(spv_mod *m, const uint32_t *code, size_t bytes)
{
    memset(m, 0, sizeof(*m));
    if (bytes < 20 || (bytes & 3) || code[0] != 0x07230203u) return -1;
    memcpy(m->header, code, 20);
    size_t words = bytes / 4, pos = 5;
    while (pos < words) {
        uint32_t n = code[pos] >> 16, op = code[pos] & 0xffff;
        if (n == 0 || pos + n > words) return -1;
        spv_insert(m, m->count, (uint16_t)op, code + pos + 1, (uint16_t)(n - 1));
        if (m->err) return -1;
        pos += n;
    }
    return 0;
}

size_t spv_write(const spv_mod *m, uint32_t **out)
{
    size_t words = 5;
    for (uint32_t i = 0; i < m->count; i++) words += m->ins[i].n;
    uint32_t *buf = malloc(words * 4);
    if (!buf) return 0;
    memcpy(buf, m->header, 20);
    size_t pos = 5;
    for (uint32_t i = 0; i < m->count; i++) {
        const spv_inst *in = &m->ins[i];
        buf[pos++] = ((uint32_t)in->n << 16) | in->op;
        memcpy(buf + pos, in->w, (size_t)(in->n - 1) * 4);
        pos += in->n - 1;
    }
    *out = buf;
    return words * 4;
}

void spv_free(spv_mod *m)
{
    for (uint32_t i = 0; i < m->count; i++) free(m->ins[i].w);
    free(m->ins);
    memset(m, 0, sizeof(*m));
}

uint32_t spv_insert(spv_mod *m, uint32_t at, uint16_t op, const uint32_t *ops, uint16_t nops)
{
    if (m->count == m->cap) {
        uint32_t nc = m->cap ? m->cap * 2 : 1024;
        spv_inst *ni = realloc(m->ins, nc * sizeof(*ni));
        if (!ni) {
            m->err = 1;
            return 0;
        }
        m->ins = ni;
        m->cap = nc;
    }
    if (at > m->count) at = m->count;
    memmove(&m->ins[at + 1], &m->ins[at], (m->count - at) * sizeof(spv_inst));
    spv_inst *in = &m->ins[at];
    in->op = op;
    in->n = (uint16_t)(nops + 1);
    in->w = malloc((size_t)(nops ? nops : 1) * 4);
    if (!in->w) m->err = 1;
    else if (nops) memcpy(in->w, ops, (size_t)nops * 4);
    m->count++;
    return at;
}

void spv_remove(spv_mod *m, uint32_t at)
{
    if (at >= m->count) return;
    free(m->ins[at].w);
    memmove(&m->ins[at], &m->ins[at + 1], (m->count - at - 1) * sizeof(spv_inst));
    m->count--;
}

int spv_find_op(const spv_mod *m, uint16_t op, uint32_t from)
{
    for (uint32_t i = from; i < m->count; i++)
        if (m->ins[i].op == op) return (int)i;
    return -1;
}

static int is_type(uint16_t op) { return op >= 19 && op <= 38; }
static int is_const(uint16_t op) { return (op >= 41 && op <= 46) || (op >= 48 && op <= 52); }

uint32_t spv_result_id(const spv_inst *in)
{
    if (in->n < 2) return 0;
    if (is_type(in->op) || in->op == SpvOpLabel || in->op == SpvOpString || in->op == SpvOpExtInstImport || in->op == 73)
        return in->w[0];
    switch (in->op) {
    case SpvOpName: case SpvOpMemberName: case SpvOpDecorate: case SpvOpMemberDecorate: case SpvOpEntryPoint:
    case SpvOpExecutionMode: case SpvOpCapability: case SpvOpExtension: case SpvOpMemoryModel: case SpvOpStore:
    case SpvOpBranch: case SpvOpBranchConditional: case SpvOpSelectionMerge: case SpvOpReturn: case SpvOpKill:
    case SpvOpFunctionEnd: case SpvOpLine: case SpvOpNoLine: case SpvOpSource: case SpvOpSourceExtension:
    case SpvOpDecorateId: case SpvOpDecorateString: case SpvOpMemberDecorateString: case SpvOpModuleProcessed:
    case SpvOpExecutionModeId: case 246 /* LoopMerge */: case 251 /* Switch */: case 254 /* ReturnValue */:
    case 255 /* Unreachable */: case 74: case 75: case 224 /* ControlBarrier */: case 225 /* MemoryBarrier */:
    case SpvOpTerminateInvocation:
        return 0;
    default:
        return in->n >= 3 ? in->w[1] : 0;
    }
}

int spv_find_def(const spv_mod *m, uint32_t id)
{
    for (uint32_t i = 0; i < m->count; i++)
        if (spv_result_id(&m->ins[i]) == id) return (int)i;
    return -1;
}

uint32_t spv_types_end(const spv_mod *m)
{
    int f = spv_find_op(m, SpvOpFunction, 0);
    return f < 0 ? m->count : (uint32_t)f;
}

uint32_t spv_annotations_end(const spv_mod *m)
{
    uint32_t end = spv_types_end(m);
    for (uint32_t i = 0; i < end; i++) {
        uint16_t op = m->ins[i].op;
        if (is_type(op) || is_const(op) || op == SpvOpVariable || op == SpvOpUndef) return i;
    }
    return end;
}

static uint32_t add_global(spv_mod *m, uint16_t op, const uint32_t *ops, uint16_t n)
{
    uint32_t at = spv_types_end(m);
    spv_insert(m, at, op, ops, n);
    return at;
}

uint32_t spv_type_float(spv_mod *m, uint32_t width)
{
    for (uint32_t i = 0; i < m->count; i++)
        if (m->ins[i].op == SpvOpTypeFloat && m->ins[i].w[1] == width && m->ins[i].n == 3) return m->ins[i].w[0];
    uint32_t id = spv_new_id(m), ops[2] = {id, width};
    add_global(m, SpvOpTypeFloat, ops, 2);
    return id;
}

uint32_t spv_type_int(spv_mod *m, uint32_t width, uint32_t sign)
{
    for (uint32_t i = 0; i < m->count; i++)
        if (m->ins[i].op == SpvOpTypeInt && m->ins[i].w[1] == width && m->ins[i].w[2] == sign) return m->ins[i].w[0];
    uint32_t id = spv_new_id(m), ops[3] = {id, width, sign};
    add_global(m, SpvOpTypeInt, ops, 3);
    return id;
}

uint32_t spv_type_bool(spv_mod *m)
{
    for (uint32_t i = 0; i < m->count; i++)
        if (m->ins[i].op == SpvOpTypeBool) return m->ins[i].w[0];
    uint32_t id = spv_new_id(m);
    add_global(m, SpvOpTypeBool, &id, 1);
    return id;
}

uint32_t spv_type_pointer(spv_mod *m, uint32_t storage, uint32_t pointee)
{
    for (uint32_t i = 0; i < m->count; i++)
        if (m->ins[i].op == SpvOpTypePointer && m->ins[i].w[1] == storage && m->ins[i].w[2] == pointee) return m->ins[i].w[0];
    uint32_t id = spv_new_id(m), ops[3] = {id, storage, pointee};
    add_global(m, SpvOpTypePointer, ops, 3);
    return id;
}

uint32_t spv_type_array(spv_mod *m, uint32_t elem, uint32_t len_const)
{
    for (uint32_t i = 0; i < m->count; i++) {
        if (m->ins[i].op == SpvOpTypeArray && m->ins[i].w[1] == elem && m->ins[i].w[2] == len_const &&
            spv_get_decoration(m, m->ins[i].w[0], 6 /* ArrayStride */) < 0)
            return m->ins[i].w[0];
    }
    uint32_t id = spv_new_id(m), ops[3] = {id, elem, len_const};
    add_global(m, SpvOpTypeArray, ops, 3);
    return id;
}

uint32_t spv_const_u32(spv_mod *m, uint32_t v)
{
    uint32_t t = spv_type_int(m, 32, 0);
    for (uint32_t i = 0; i < m->count; i++)
        if (m->ins[i].op == SpvOpConstant && m->ins[i].w[0] == t && m->ins[i].n == 4 && m->ins[i].w[2] == v) return m->ins[i].w[1];
    uint32_t id = spv_new_id(m), ops[3] = {t, id, v};
    add_global(m, SpvOpConstant, ops, 3);
    return id;
}

uint32_t spv_const_f32(spv_mod *m, float v)
{
    uint32_t t = spv_type_float(m, 32), bits;
    memcpy(&bits, &v, 4);
    for (uint32_t i = 0; i < m->count; i++)
        if (m->ins[i].op == SpvOpConstant && m->ins[i].w[0] == t && m->ins[i].n == 4 && m->ins[i].w[2] == bits) return m->ins[i].w[1];
    uint32_t id = spv_new_id(m), ops[3] = {t, id, bits};
    add_global(m, SpvOpConstant, ops, 3);
    return id;
}

int64_t spv_get_decoration(const spv_mod *m, uint32_t id, uint32_t decoration)
{
    for (uint32_t i = 0; i < m->count; i++) {
        const spv_inst *in = &m->ins[i];
        if (in->op == SpvOpDecorate && in->w[0] == id && in->w[1] == decoration) return in->n > 3 ? (int64_t)in->w[2] : 0;
    }
    return -1;
}

void spv_remove_decoration(spv_mod *m, uint32_t id, uint32_t decoration)
{
    for (uint32_t i = 0; i < m->count;) {
        const spv_inst *in = &m->ins[i];
        if (in->op == SpvOpDecorate && in->w[0] == id && in->w[1] == decoration) spv_remove(m, i);
        else i++;
    }
}

int spv_has_capability(const spv_mod *m, uint32_t cap)
{
    for (uint32_t i = 0; i < m->count; i++)
        if (m->ins[i].op == SpvOpCapability && m->ins[i].w[0] == cap) return 1;
    return 0;
}

void spv_remove_capability(spv_mod *m, uint32_t cap)
{
    for (uint32_t i = 0; i < m->count;) {
        if (m->ins[i].op == SpvOpCapability && m->ins[i].w[0] == cap) spv_remove(m, i);
        else i++;
    }
}

void spv_add_capability(spv_mod *m, uint32_t cap)
{
    if (!spv_has_capability(m, cap)) spv_insert(m, 0, SpvOpCapability, &cap, 1);
}

int spv_execution_model(const spv_mod *m)
{
    int e = spv_find_op(m, SpvOpEntryPoint, 0);
    return e < 0 ? -1 : (int)m->ins[e].w[0];
}

void spv_add_interface(spv_mod *m, uint32_t var)
{
    for (uint32_t i = 0; i < m->count; i++) {
        spv_inst *in = &m->ins[i];
        if (in->op != SpvOpEntryPoint) continue;
        uint32_t *nw = realloc(in->w, (size_t)in->n * 4);
        if (!nw) {
            m->err = 1;
            return;
        }
        nw[in->n - 1] = var;
        in->w = nw;
        in->n++;
    }
}

uint32_t spv_array_length(const spv_mod *m, uint32_t array_type)
{
    int d = spv_find_def(m, array_type);
    if (d < 0 || m->ins[d].op != SpvOpTypeArray) return 0;
    int c = spv_find_def(m, m->ins[d].w[2]);
    if (c < 0 || m->ins[c].op != SpvOpConstant) return 0;
    return m->ins[c].w[2];
}

/* Number of locations a type occupies. */
static uint32_t loc_count(const spv_mod *m, uint32_t type, int depth)
{
    int d = spv_find_def(m, type);
    if (d < 0 || depth > 16) return 1;
    const spv_inst *in = &m->ins[d];
    switch (in->op) {
    case SpvOpTypeArray: return spv_array_length(m, type) * loc_count(m, in->w[1], depth + 1);
    case 24 /* Matrix */: return in->w[2] * loc_count(m, in->w[1], depth + 1);
    case SpvOpTypeStruct: {
        uint32_t s = 0;
        for (uint32_t k = 1; k < (uint32_t)in->n - 1; k++) s += loc_count(m, in->w[k], depth + 1);
        return s;
    }
    case SpvOpTypeVector: {
        int e = spv_find_def(m, in->w[1]);
        uint32_t width = e >= 0 ? m->ins[e].w[1] : 32;
        return (width == 64 && in->w[2] > 2) ? 2 : 1;
    }
    default: return 1;
    }
}

int spv_max_location(const spv_mod *m, uint32_t storage)
{
    int max = -1;
    for (uint32_t i = 0; i < m->count; i++) {
        const spv_inst *in = &m->ins[i];
        if (in->op != SpvOpVariable || in->w[2] != storage) continue;
        int p = spv_find_def(m, in->w[0]);
        if (p < 0) continue;
        uint32_t pointee = m->ins[p].w[2];
        int64_t loc = spv_get_decoration(m, in->w[1], SpvDecorationLocation);
        if (loc >= 0) {
            int top = (int)loc + (int)loc_count(m, pointee, 0) - 1;
            if (top > max) max = top;
            continue;
        }
        /* A block with member locations. */
        for (uint32_t k = 0; k < m->count; k++) {
            const spv_inst *md = &m->ins[k];
            if (md->op == SpvOpMemberDecorate && md->w[0] == pointee && md->w[2] == SpvDecorationLocation) {
                int st = spv_find_def(m, pointee);
                uint32_t mt = st >= 0 && md->w[1] + 1 < m->ins[st].n ? m->ins[st].w[1 + md->w[1]] : 0;
                int top = (int)md->w[3] + (int)loc_count(m, mt, 0) - 1;
                if (top > max) max = top;
            }
        }
    }
    return max;
}
