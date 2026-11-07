/*
 * Clip and cull distances out of SPIR-V, for a GPU without either.
 *
 * Stripping: every ClipDistance or CullDistance variable becomes a Private one, so the shader still
 * writes and reads it but the rasterizer never sees it. Members of a gl_PerVertex block stay
 * declared (as glslang declares them unused anyway) and their accesses move to a Private stand-in.
 * Emulating: the last stage before rasterization writes its clip distances to a free varying
 * location instead, and the fragment shader reads them there and discards when one is negative.
 */
#include "mali_compat.h"

#include <spirv/unified1/spirv.h>
#include <stdlib.h>
#include <string.h>

#define OPCODE(word) ((word) & 0xffffu)
#define LENGTH(word) ((word) >> 16)
#define VERSION_1_4 0x00010400u

struct words {
    uint32_t *data;
    size_t count;
    size_t capacity;
};

static void push(struct words *words, uint32_t word)
{
    if (words->count == words->capacity) {
        words->capacity = words->capacity ? words->capacity * 2 : 64;
        words->data = realloc(words->data, words->capacity * sizeof(uint32_t));
    }
    words->data[words->count++] = word;
}

static void emit(struct words *words, SpvOp op, size_t count, const uint32_t *operands)
{
    push(words, (uint32_t)(count + 1) << 16 | op);
    for (size_t i = 0; i < count; i++)
        push(words, operands[i]);
}

#define EMIT(words, op, ...) \
    do { \
        const uint32_t operands_[] = {__VA_ARGS__}; \
        emit(words, op, sizeof(operands_) / sizeof(operands_[0]), operands_); \
    } while (0)

enum place { BEFORE, INSTEAD, AFTER };

struct edit {
    size_t offset;
    enum place place;
    struct words words;
};

struct new_type {
    SpvOp op;
    uint32_t operands[3];
    uint32_t count;
    uint32_t id;
};

struct module {
    uint32_t *w;
    size_t count;
    uint32_t version;
    uint32_t original_bound;
    uint32_t bound;
    size_t *def;
    size_t first_declaration;
    size_t first_function;
    struct edit *edits;
    size_t edit_count;
    struct new_type *new_types;
    size_t new_type_count;
    struct words globals;
    uint8_t *private_ids;
    uint32_t *interface_add;
    size_t interface_add_count;
    uint8_t *interface_remove;
    bool failed;
};

static bool declares_result_first(uint32_t op)
{
    return (op >= SpvOpTypeVoid && op <= SpvOpTypePipe) || op == SpvOpLabel || op == SpvOpExtInstImport ||
           op == SpvOpString;
}

static bool declares_result_second(uint32_t op)
{
    switch (op) {
    case SpvOpConstantTrue:
    case SpvOpConstantFalse:
    case SpvOpConstant:
    case SpvOpConstantComposite:
    case SpvOpConstantNull:
    case SpvOpSpecConstantTrue:
    case SpvOpSpecConstantFalse:
    case SpvOpSpecConstant:
    case SpvOpSpecConstantComposite:
    case SpvOpSpecConstantOp:
    case SpvOpUndef:
    case SpvOpVariable:
    case SpvOpFunction:
        return true;
    default:
        return false;
    }
}

static bool is_declaration(uint32_t op)
{
    return (op >= SpvOpTypeVoid && op <= SpvOpTypeForwardPointer) || declares_result_second(op);
}

static void unload(struct module *m)
{
    free(m->w);
    free(m->def);
    for (size_t i = 0; i < m->edit_count; i++)
        free(m->edits[i].words.data);
    free(m->edits);
    free(m->new_types);
    free(m->globals.data);
    free(m->private_ids);
    free(m->interface_add);
    free(m->interface_remove);
}

static bool load(struct module *m, const uint32_t *words, size_t count)
{
    memset(m, 0, sizeof(*m));
    if (count < 5 || words[0] != SpvMagicNumber || words[3] == 0 || words[3] > (1u << 22))
        return false;
    m->w = malloc(count * sizeof(uint32_t));
    memcpy(m->w, words, count * sizeof(uint32_t));
    m->count = count;
    m->version = words[1];
    m->original_bound = m->bound = words[3];
    m->def = calloc(m->bound, sizeof(size_t));
    /* Room for every id the rewrite can add. */
    m->private_ids = calloc(m->bound + 4096, 1);
    m->interface_remove = calloc(m->bound, 1);
    for (size_t i = 5; i < count;) {
        uint32_t length = LENGTH(m->w[i]);
        uint32_t op = OPCODE(m->w[i]);
        if (length == 0 || i + length > count) {
            unload(m);
            return false;
        }
        uint32_t result = 0;
        if (declares_result_first(op) && length > 1)
            result = m->w[i + 1];
        else if (declares_result_second(op) && length > 2)
            result = m->w[i + 2];
        if (result && result < m->bound)
            m->def[result] = i;
        if (!m->first_declaration && is_declaration(op))
            m->first_declaration = i;
        if (!m->first_function && op == SpvOpFunction)
            m->first_function = i;
        i += length;
    }
    if (!m->first_declaration || !m->first_function) {
        unload(m);
        return false;
    }
    return true;
}

static struct words *edit(struct module *m, size_t offset, enum place place)
{
    m->edits = realloc(m->edits, (m->edit_count + 1) * sizeof(*m->edits));
    struct edit *e = &m->edits[m->edit_count++];
    *e = (struct edit){.offset = offset, .place = place};
    return &e->words;
}

static uint32_t new_id(struct module *m)
{
    return m->bound++;
}

static bool is_private(const struct module *m, uint32_t id)
{
    return id < m->original_bound + 4096 && m->private_ids[id];
}

static void mark_private(struct module *m, uint32_t id)
{
    if (id < m->original_bound + 4096)
        m->private_ids[id] = 1;
    else
        m->failed = true;
}

/* The defining instruction of an id from the original module, or 0. */
static size_t definition(const struct module *m, uint32_t id)
{
    return id < m->original_bound ? m->def[id] : 0;
}

static uint32_t find_type(struct module *m, SpvOp op, const uint32_t *operands, uint32_t count)
{
    for (size_t i = 5; i < m->first_function; i += LENGTH(m->w[i])) {
        if (OPCODE(m->w[i]) == op && LENGTH(m->w[i]) == count + 2 &&
            !memcmp(&m->w[i + 2], operands, count * sizeof(uint32_t)))
            return m->w[i + 1];
    }
    for (size_t i = 0; i < m->new_type_count; i++) {
        struct new_type *t = &m->new_types[i];
        if (t->op == op && t->count == count && !memcmp(t->operands, operands, count * sizeof(uint32_t)))
            return t->id;
    }
    return 0;
}

static uint32_t add_type(struct module *m, struct words *where, SpvOp op, const uint32_t *operands, uint32_t count)
{
    uint32_t id = new_id(m);
    push(where, (count + 2) << 16 | op);
    push(where, id);
    for (uint32_t i = 0; i < count; i++)
        push(where, operands[i]);
    m->new_types = realloc(m->new_types, (m->new_type_count + 1) * sizeof(*m->new_types));
    struct new_type *t = &m->new_types[m->new_type_count++];
    *t = (struct new_type){.op = op, .count = count, .id = id};
    memcpy(t->operands, operands, count * sizeof(uint32_t));
    return id;
}

/* Right after its pointee, so a global variable declared before the end of the types can use it. */
static uint32_t pointer_type(struct module *m, uint32_t storage, uint32_t pointee)
{
    uint32_t operands[2] = {storage, pointee};
    uint32_t id = find_type(m, SpvOpTypePointer, operands, 2);
    if (id)
        return id;
    size_t pointee_def = definition(m, pointee);
    struct words *where = pointee_def ? edit(m, pointee_def, AFTER) : &m->globals;
    return add_type(m, where, SpvOpTypePointer, operands, 2);
}

static uint32_t scalar_type(struct module *m, SpvOp op, const uint32_t *operands, uint32_t count)
{
    uint32_t id = find_type(m, op, operands, count);
    return id ? id : add_type(m, &m->globals, op, operands, count);
}

static uint32_t float_type(struct module *m)
{
    const uint32_t width = 32;
    return scalar_type(m, SpvOpTypeFloat, &width, 1);
}

static uint32_t uint_type(struct module *m)
{
    const uint32_t operands[2] = {32, 0};
    return scalar_type(m, SpvOpTypeInt, operands, 2);
}

static uint32_t bool_type(struct module *m)
{
    return scalar_type(m, SpvOpTypeBool, NULL, 0);
}

static uint32_t constant(struct module *m, uint32_t type, uint32_t value)
{
    for (size_t i = 5; i < m->first_function; i += LENGTH(m->w[i])) {
        if (OPCODE(m->w[i]) == SpvOpConstant && LENGTH(m->w[i]) == 4 && m->w[i + 1] == type && m->w[i + 3] == value)
            return m->w[i + 2];
    }
    uint32_t id = new_id(m);
    EMIT(&m->globals, SpvOpConstant, type, id, value);
    return id;
}

static bool constant_value(const struct module *m, uint32_t id, uint32_t *value)
{
    size_t d = definition(m, id);
    if (!d || OPCODE(m->w[d]) != SpvOpConstant || LENGTH(m->w[d]) != 4)
        return false;
    *value = m->w[d + 3];
    return true;
}

/* What a pointer type points to, and where. */
static bool pointer_parts(const struct module *m, uint32_t pointer, uint32_t *storage, uint32_t *pointee)
{
    size_t d = definition(m, pointer);
    if (!d || OPCODE(m->w[d]) != SpvOpTypePointer)
        return false;
    *storage = m->w[d + 2];
    *pointee = m->w[d + 3];
    return true;
}

static bool is_float32(const struct module *m, uint32_t type)
{
    size_t d = definition(m, type);
    return d && OPCODE(m->w[d]) == SpvOpTypeFloat && m->w[d + 2] == 32;
}

/* Varying locations a type takes up. */
static uint32_t location_span(const struct module *m, uint32_t type, int depth)
{
    size_t d = definition(m, type);
    if (!d || depth > 8)
        return 1;
    uint32_t value;
    switch (OPCODE(m->w[d])) {
    case SpvOpTypeArray:
        return constant_value(m, m->w[d + 3], &value) ? value * location_span(m, m->w[d + 2], depth + 1) : 1;
    case SpvOpTypeMatrix:
        return m->w[d + 3] * location_span(m, m->w[d + 2], depth + 1);
    case SpvOpTypeStruct: {
        uint32_t span = 0;
        for (uint32_t i = 2; i < LENGTH(m->w[d]); i++)
            span += location_span(m, m->w[d + i], depth + 1);
        return span;
    }
    case SpvOpTypeVector: {
        size_t component = definition(m, m->w[d + 2]);
        bool wide = component && m->w[component + 2] == 64;
        return wide && m->w[d + 3] > 2 ? 2 : 1;
    }
    default:
        return 1;
    }
}

struct distance_var {
    uint32_t id;
    uint32_t built_in;
    uint32_t storage;
    size_t decoration;
};

struct distance_member {
    uint32_t structure;
    uint32_t member;
};

struct analysis {
    struct distance_var vars[8];
    uint32_t var_count;
    struct distance_member members[8];
    uint32_t member_count;
    bool member_locations;
};

static bool is_distance(uint32_t built_in)
{
    return built_in == SpvBuiltInClipDistance || built_in == SpvBuiltInCullDistance;
}

static void analyze(struct module *m, struct analysis *a)
{
    memset(a, 0, sizeof(*a));
    for (size_t i = 5; i < m->first_declaration; i += LENGTH(m->w[i])) {
        uint32_t op = OPCODE(m->w[i]);
        if (op == SpvOpDecorate && LENGTH(m->w[i]) == 4 && m->w[i + 2] == SpvDecorationBuiltIn &&
            is_distance(m->w[i + 3])) {
            uint32_t storage, pointee;
            size_t d = definition(m, m->w[i + 1]);
            if (a->var_count < 8 && d && OPCODE(m->w[d]) == SpvOpVariable &&
                pointer_parts(m, m->w[d + 1], &storage, &pointee)) {
                a->vars[a->var_count++] =
                    (struct distance_var){.id = m->w[i + 1], .built_in = m->w[i + 3], .storage = storage, .decoration = i};
            } else {
                m->failed = true;
            }
        } else if (op == SpvOpMemberDecorate && LENGTH(m->w[i]) == 5 && m->w[i + 3] == SpvDecorationBuiltIn &&
                   is_distance(m->w[i + 4])) {
            if (a->member_count < 8)
                a->members[a->member_count++] = (struct distance_member){m->w[i + 1], m->w[i + 2]};
            else
                m->failed = true;
        } else if (op == SpvOpMemberDecorate && LENGTH(m->w[i]) >= 5 && m->w[i + 3] == SpvDecorationLocation) {
            a->member_locations = true;
        }
    }
}

static bool uses_distances(const uint32_t *words, size_t count)
{
    for (size_t i = 5; i + 1 < count && LENGTH(words[i]); i += LENGTH(words[i])) {
        uint32_t op = OPCODE(words[i]);
        if (op == SpvOpCapability && (words[i + 1] == SpvCapabilityClipDistance || words[i + 1] == SpvCapabilityCullDistance))
            return true;
        if (op != SpvOpCapability && op != SpvOpExtension && op != SpvOpExtInstImport && op != SpvOpMemoryModel)
            break;
    }
    return false;
}

bool spirv_uses_distances(const uint32_t *words, size_t count)
{
    return count > 5 && words[0] == SpvMagicNumber && uses_distances(words, count);
}

static void remove_capabilities(struct module *m)
{
    for (size_t i = 5; i < m->first_declaration; i += LENGTH(m->w[i])) {
        if (OPCODE(m->w[i]) == SpvOpCapability &&
            (m->w[i + 1] == SpvCapabilityClipDistance || m->w[i + 1] == SpvCapabilityCullDistance))
            edit(m, i, INSTEAD);
    }
}

static void add_to_interface(struct module *m, uint32_t id)
{
    m->interface_add = realloc(m->interface_add, (m->interface_add_count + 1) * sizeof(uint32_t));
    m->interface_add[m->interface_add_count++] = id;
}

/* A standalone distance variable becomes Private, and its decorations go. */
static void privatize(struct module *m, const struct distance_var *var)
{
    size_t d = definition(m, var->id);
    uint32_t storage, pointee;
    pointer_parts(m, m->w[d + 1], &storage, &pointee);
    m->w[d + 1] = pointer_type(m, SpvStorageClassPrivate, pointee);
    m->w[d + 3] = SpvStorageClassPrivate;
    mark_private(m, var->id);
    /* Before SPIR-V 1.4 an entry point lists only its inputs and outputs. */
    if (m->version < VERSION_1_4)
        m->interface_remove[var->id] = 1;
    for (size_t i = 5; i < m->first_declaration; i += LENGTH(m->w[i])) {
        if (OPCODE(m->w[i]) == SpvOpDecorate && m->w[i + 1] == var->id)
            edit(m, i, INSTEAD);
    }
}

struct block_var {
    uint32_t id;
    uint32_t structure;
    bool arrayed;
    uint32_t array_length;
};

struct stand_in {
    uint32_t block;
    uint32_t member;
    uint32_t variable;
};

/* Every variable of a block type with a distance member, and a Private stand-in per member. */
static uint32_t make_stand_ins(struct module *m, const struct analysis *a, struct stand_in *stand_ins, uint32_t max)
{
    uint32_t count = 0;
    for (size_t i = m->first_declaration; i < m->first_function; i += LENGTH(m->w[i])) {
        if (OPCODE(m->w[i]) != SpvOpVariable)
            continue;
        uint32_t storage, pointee;
        if (!pointer_parts(m, m->w[i + 1], &storage, &pointee))
            continue;
        struct block_var var = {.id = m->w[i + 2], .structure = pointee};
        size_t d = definition(m, pointee);
        if (d && OPCODE(m->w[d]) == SpvOpTypeArray) {
            var.arrayed = true;
            var.structure = m->w[d + 2];
            var.array_length = m->w[d + 3];
        }
        for (uint32_t k = 0; k < a->member_count; k++) {
            if (a->members[k].structure != var.structure)
                continue;
            if (count == max) {
                m->failed = true;
                return count;
            }
            size_t s = definition(m, var.structure);
            uint32_t member_type = m->w[s + 2 + a->members[k].member];
            uint32_t type = member_type;
            if (var.arrayed) {
                const uint32_t operands[2] = {member_type, var.array_length};
                type = add_type(m, &m->globals, SpvOpTypeArray, operands, 2);
            }
            uint32_t variable = new_id(m);
            EMIT(&m->globals, SpvOpVariable, pointer_type(m, SpvStorageClassPrivate, type), variable,
                 SpvStorageClassPrivate);
            if (m->version >= VERSION_1_4)
                add_to_interface(m, variable);
            stand_ins[count++] = (struct stand_in){var.id, a->members[k].member, variable};
        }
    }
    return count;
}

static const struct stand_in *find_stand_in(const struct module *m, const struct stand_in *stand_ins, uint32_t count,
                                            uint32_t base, uint32_t member_index)
{
    uint32_t member;
    if (!constant_value(m, member_index, &member))
        return NULL;
    for (uint32_t i = 0; i < count; i++) {
        if (stand_ins[i].block == base && stand_ins[i].member == member)
            return &stand_ins[i];
    }
    return NULL;
}

static bool is_arrayed_block(const struct module *m, uint32_t variable)
{
    size_t d = definition(m, variable);
    uint32_t storage, pointee;
    if (!d || !pointer_parts(m, m->w[d + 1], &storage, &pointee))
        return false;
    size_t p = definition(m, pointee);
    return p && OPCODE(m->w[p]) == SpvOpTypeArray;
}

/*
 * One pass over the function bodies: accesses to a distance member go to its stand-in, and every
 * pointer derived from a Private one becomes Private too. A Private pointer handed to a function
 * or merged through a phi would need its types changed beyond this, so that fails the rewrite.
 */
static void retarget_accesses(struct module *m, const struct stand_in *stand_ins, uint32_t stand_in_count)
{
    for (size_t i = m->first_function; i < m->count; i += LENGTH(m->w[i])) {
        uint32_t op = OPCODE(m->w[i]);
        uint32_t length = LENGTH(m->w[i]);
        switch (op) {
        case SpvOpAccessChain:
        case SpvOpInBoundsAccessChain: {
            uint32_t base = m->w[i + 3];
            bool arrayed = is_arrayed_block(m, base);
            uint32_t member_position = arrayed ? 5 : 4;
            const struct stand_in *stand_in =
                length > member_position
                    ? find_stand_in(m, stand_ins, stand_in_count, base, m->w[i + member_position])
                    : NULL;
            uint32_t storage, pointee;
            if (stand_in) {
                if (!pointer_parts(m, m->w[i + 1], &storage, &pointee)) {
                    m->failed = true;
                    break;
                }
                /* Before edit(): a new pointer type is an edit too, and would move the chain's buffer. */
                uint32_t type = pointer_type(m, SpvStorageClassPrivate, pointee);
                struct words *chain = edit(m, i, INSTEAD);
                push(chain, (length - 1) << 16 | op);
                push(chain, type);
                push(chain, m->w[i + 2]);
                push(chain, stand_in->variable);
                for (uint32_t k = 4; k < length; k++) {
                    if (k != member_position)
                        push(chain, m->w[i + k]);
                }
                mark_private(m, m->w[i + 2]);
            } else if (is_private(m, base)) {
                if (!pointer_parts(m, m->w[i + 1], &storage, &pointee)) {
                    m->failed = true;
                    break;
                }
                m->w[i + 1] = pointer_type(m, SpvStorageClassPrivate, pointee);
                mark_private(m, m->w[i + 2]);
            }
            break;
        }
        case SpvOpPtrAccessChain:
        case SpvOpCopyObject:
            if (is_private(m, m->w[i + 3])) {
                uint32_t storage, pointee;
                if (!pointer_parts(m, m->w[i + 1], &storage, &pointee)) {
                    m->failed = true;
                    break;
                }
                m->w[i + 1] = pointer_type(m, SpvStorageClassPrivate, pointee);
                mark_private(m, m->w[i + 2]);
            }
            break;
        case SpvOpFunctionCall:
            for (uint32_t k = 4; k < length; k++) {
                if (is_private(m, m->w[i + k]))
                    m->failed = true;
            }
            break;
        case SpvOpPhi:
        case SpvOpSelect:
            for (uint32_t k = 3; k < length; k++) {
                if (is_private(m, m->w[i + k]))
                    m->failed = true;
            }
            break;
        default:
            break;
        }
    }
}

static uint32_t string_words(const uint32_t *words, uint32_t available)
{
    for (uint32_t i = 0; i < available; i++) {
        uint32_t word = words[i];
        if (!(word & 0xff) || !(word & 0xff00) || !(word & 0xff0000) || !(word & 0xff000000))
            return i + 1;
    }
    return available;
}

static void rewrite_interfaces(struct module *m)
{
    for (size_t i = 5; i < m->first_declaration; i += LENGTH(m->w[i])) {
        if (OPCODE(m->w[i]) != SpvOpEntryPoint)
            continue;
        uint32_t length = LENGTH(m->w[i]);
        uint32_t first = 3 + string_words(&m->w[i + 3], length - 3);
        struct words *entry = edit(m, i, INSTEAD);
        push(entry, 0);
        for (uint32_t k = 1; k < first; k++)
            push(entry, m->w[i + k]);
        for (uint32_t k = first; k < length; k++) {
            uint32_t id = m->w[i + k];
            if (id >= m->original_bound || !m->interface_remove[id])
                push(entry, id);
        }
        for (size_t k = 0; k < m->interface_add_count; k++)
            push(entry, m->interface_add[k]);
        entry->data[0] = (uint32_t)entry->count << 16 | SpvOpEntryPoint;
    }
}

static void emit_edits(const struct module *m, struct words *out, size_t offset, enum place place, bool *replaced)
{
    for (size_t k = 0; k < m->edit_count; k++) {
        const struct edit *e = &m->edits[k];
        if (e->offset != offset || e->place != place)
            continue;
        for (size_t j = 0; j < e->words.count; j++)
            push(out, e->words.data[j]);
        if (replaced)
            *replaced = true;
    }
}

static bool finish(struct module *m, struct spirv_code *out)
{
    if (m->failed)
        return false;
    rewrite_interfaces(m);
    struct words result = {0};
    for (int i = 0; i < 5; i++)
        push(&result, m->w[i]);
    result.data[3] = m->bound;
    for (size_t i = 5; i < m->count; i += LENGTH(m->w[i])) {
        if (i == m->first_function) {
            for (size_t j = 0; j < m->globals.count; j++)
                push(&result, m->globals.data[j]);
        }
        emit_edits(m, &result, i, BEFORE, NULL);
        bool replaced = false;
        emit_edits(m, &result, i, INSTEAD, &replaced);
        if (!replaced) {
            for (uint32_t j = 0; j < LENGTH(m->w[i]); j++)
                push(&result, m->w[i + j]);
        }
        emit_edits(m, &result, i, AFTER, NULL);
    }
    out->words = result.data;
    out->count = result.count;
    return true;
}

static void strip(struct module *m, const struct analysis *a, uint32_t keep)
{
    for (uint32_t i = 0; i < a->var_count; i++) {
        if (a->vars[i].id != keep)
            privatize(m, &a->vars[i]);
    }
    struct stand_in stand_ins[16];
    uint32_t count = make_stand_ins(m, a, stand_ins, 16);
    retarget_accesses(m, stand_ins, count);
    remove_capabilities(m);
}

bool spirv_strip_distances(const uint32_t *words, size_t count, struct spirv_code *out)
{
    struct module m;
    if (!load(&m, words, count))
        return false;
    struct analysis a;
    analyze(&m, &a);
    strip(&m, &a, 0);
    bool done = finish(&m, out);
    unload(&m);
    return done;
}

static const struct distance_var *clip_output(const struct module *m, const struct analysis *a, uint32_t *length)
{
    for (uint32_t i = 0; i < a->var_count; i++) {
        const struct distance_var *var = &a->vars[i];
        if (var->built_in != SpvBuiltInClipDistance || var->storage != SpvStorageClassOutput)
            continue;
        uint32_t storage, pointee;
        pointer_parts(m, m->w[definition(m, var->id) + 1], &storage, &pointee);
        size_t d = definition(m, pointee);
        if (d && OPCODE(m->w[d]) == SpvOpTypeArray && is_float32(m, m->w[d + 2]) &&
            constant_value(m, m->w[d + 3], length))
            return var;
    }
    return NULL;
}

void spirv_clip_info(const uint32_t *words, size_t count, enum clip_role role, struct clip_info *info)
{
    memset(info, 0, sizeof(*info));
    struct module m;
    if (!load(&m, words, count)) {
        info->locations_used = UINT32_MAX;
        return;
    }
    struct analysis a;
    analyze(&m, &a);
    if (role == CLIP_PRODUCER)
        info->has_clip = clip_output(&m, &a, &info->clip_count) != NULL;
    uint32_t storage_wanted = role == CLIP_PRODUCER ? SpvStorageClassOutput : SpvStorageClassInput;
    for (size_t i = 5; i < m.first_declaration; i += LENGTH(m.w[i])) {
        if (OPCODE(m.w[i]) != SpvOpDecorate || LENGTH(m.w[i]) != 4 || m.w[i + 2] != SpvDecorationLocation)
            continue;
        size_t d = definition(&m, m.w[i + 1]);
        uint32_t storage, pointee;
        if (!d || OPCODE(m.w[d]) != SpvOpVariable || !pointer_parts(&m, m.w[d + 1], &storage, &pointee) ||
            storage != storage_wanted)
            continue;
        uint32_t end = m.w[i + 3] + location_span(&m, pointee, 0);
        if (end > info->locations_used)
            info->locations_used = end;
    }
    if (a.member_locations || m.failed)
        info->locations_used = UINT32_MAX;
    unload(&m);
}

/* Fragment shader: read the clip distances at `location` and discard when any is negative. */
static void add_clip_test(struct module *m, uint32_t location, uint32_t clip_count)
{
    uint32_t float_id = float_type(m);
    uint32_t uint_id = uint_type(m);
    uint32_t bool_id = bool_type(m);
    const uint32_t array_operands[2] = {float_id, constant(m, uint_id, clip_count)};
    uint32_t array_id = add_type(m, &m->globals, SpvOpTypeArray, array_operands, 2);
    uint32_t input_pointer = pointer_type(m, SpvStorageClassInput, array_id);
    uint32_t element_pointer = pointer_type(m, SpvStorageClassInput, float_id);
    uint32_t zero = constant(m, float_id, 0);
    uint32_t indices[8];
    for (uint32_t i = 0; i < clip_count; i++)
        indices[i] = constant(m, uint_id, i);
    uint32_t input = new_id(m);
    EMIT(&m->globals, SpvOpVariable, input_pointer, input, SpvStorageClassInput);
    EMIT(edit(m, m->first_declaration, BEFORE), SpvOpDecorate, input, SpvDecorationLocation, location);
    add_to_interface(m, input);

    for (size_t i = 5; i < m->first_declaration; i += LENGTH(m->w[i])) {
        if (OPCODE(m->w[i]) != SpvOpEntryPoint || m->w[i + 1] != SpvExecutionModelFragment)
            continue;
        size_t function = definition(m, m->w[i + 2]);
        if (!function) {
            m->failed = true;
            return;
        }
        size_t label = function + LENGTH(m->w[function]);
        if (label >= m->count || OPCODE(m->w[label]) != SpvOpLabel) {
            m->failed = true;
            return;
        }
        /* Function variables must open the entry block, so the test goes after them. */
        size_t last = label;
        for (size_t k = label + LENGTH(m->w[label]); k < m->count; k += LENGTH(m->w[k])) {
            uint32_t op = OPCODE(m->w[k]);
            if (op != SpvOpVariable && op != SpvOpLine && op != SpvOpNoLine)
                break;
            last = k;
        }
        uint32_t original_label = m->w[label + 1];
        uint32_t entry = new_id(m), kill = new_id(m), join = new_id(m);
        EMIT(edit(m, label, INSTEAD), SpvOpLabel, entry);
        struct words *test = edit(m, last, AFTER);
        uint32_t any = 0;
        for (uint32_t c = 0; c < clip_count; c++) {
            uint32_t pointer = new_id(m), value = new_id(m), negative = new_id(m);
            EMIT(test, SpvOpAccessChain, element_pointer, pointer, input, indices[c]);
            EMIT(test, SpvOpLoad, float_id, value, pointer);
            EMIT(test, SpvOpFOrdLessThan, bool_id, negative, value, zero);
            if (any) {
                uint32_t either = new_id(m);
                EMIT(test, SpvOpLogicalOr, bool_id, either, any, negative);
                any = either;
            } else {
                any = negative;
            }
        }
        EMIT(test, SpvOpSelectionMerge, join, SpvSelectionControlMaskNone);
        EMIT(test, SpvOpBranchConditional, any, kill, join);
        EMIT(test, SpvOpLabel, kill);
        push(test, 1u << 16 | SpvOpKill);
        EMIT(test, SpvOpLabel, join);
        EMIT(test, SpvOpBranch, original_label);
        EMIT(test, SpvOpLabel, original_label);
    }
}

bool spirv_emulate_clip(const uint32_t *words, size_t count, enum clip_role role, uint32_t location,
                        uint32_t clip_count, struct spirv_code *out)
{
    if (clip_count == 0 || clip_count > 8)
        return false;
    struct module m;
    if (!load(&m, words, count))
        return false;
    struct analysis a;
    analyze(&m, &a);
    uint32_t keep = 0;
    if (role == CLIP_PRODUCER) {
        uint32_t length = 0;
        const struct distance_var *clip = clip_output(&m, &a, &length);
        if (!clip || length != clip_count) {
            unload(&m);
            return false;
        }
        keep = clip->id;
        m.w[clip->decoration + 2] = SpvDecorationLocation;
        m.w[clip->decoration + 3] = location;
    }
    strip(&m, &a, keep);
    if (role == CLIP_CONSUMER)
        add_clip_test(&m, location, clip_count);
    bool done = finish(&m, out);
    unload(&m);
    return done;
}
