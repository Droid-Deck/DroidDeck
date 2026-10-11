/* SPDX-License-Identifier: GPL-3.0-or-later
 *
 * A small SPIR-V editor for the bridge's shader emulations: parse a module into instructions,
 * look things up, append/insert/remove instructions, and write it back.
 */
#ifndef VKB_SPIRV_EDIT_H
#define VKB_SPIRV_EDIT_H

#include <stddef.h>
#include <stdint.h>

/* Opcodes and enums used (from the SPIR-V spec). */
enum {
    SpvOpName = 5, SpvOpMemberName = 6, SpvOpExtension = 10, SpvOpExtInstImport = 11, SpvOpMemoryModel = 14,
    SpvOpEntryPoint = 15, SpvOpExecutionMode = 16, SpvOpCapability = 17, SpvOpTypeVoid = 19, SpvOpTypeBool = 20,
    SpvOpTypeInt = 21, SpvOpTypeFloat = 22, SpvOpTypeVector = 23, SpvOpTypeArray = 28, SpvOpTypeRuntimeArray = 29,
    SpvOpTypeStruct = 30, SpvOpTypePointer = 32, SpvOpTypeFunction = 33, SpvOpConstantTrue = 41,
    SpvOpConstantFalse = 42, SpvOpConstant = 43, SpvOpConstantComposite = 44, SpvOpFunction = 54,
    SpvOpFunctionParameter = 55, SpvOpFunctionEnd = 56, SpvOpVariable = 59, SpvOpLoad = 61, SpvOpStore = 62,
    SpvOpAccessChain = 65, SpvOpInBoundsAccessChain = 66, SpvOpDecorate = 71, SpvOpMemberDecorate = 72,
    SpvOpCompositeExtract = 81, SpvOpFOrdLessThan = 184, SpvOpLogicalOr = 166, SpvOpSelectionMerge = 247,
    SpvOpLabel = 248, SpvOpBranch = 249, SpvOpBranchConditional = 250, SpvOpReturn = 253, SpvOpKill = 252,
    SpvOpTerminateInvocation = 4416, SpvOpLine = 8, SpvOpNoLine = 317, SpvOpModuleProcessed = 330,
    SpvOpString = 7, SpvOpSource = 3, SpvOpSourceExtension = 4, SpvOpSourceContinued = 2, SpvOpExecutionModeId = 331,
    SpvOpDecorateId = 332, SpvOpDecorateString = 5632, SpvOpMemberDecorateString = 5633, SpvOpUndef = 1,
    SpvOpExtInst = 12, SpvOpIAdd = 128, SpvOpUDiv = 134, SpvOpISub = 130,
};
enum { SpvDecorationBuiltIn = 11, SpvDecorationLocation = 30, SpvDecorationComponent = 31, SpvDecorationBlock = 2,
       SpvDecorationFlat = 14, SpvDecorationPatch = 15 };
enum { SpvBuiltInPosition = 0, SpvBuiltInPointSize = 1, SpvBuiltInClipDistance = 3, SpvBuiltInCullDistance = 4,
       SpvBuiltInInstanceIndex = 43, SpvBuiltInBaseInstance = 4425 };
enum { SpvStorageClassInput = 1, SpvStorageClassOutput = 3, SpvStorageClassPrivate = 6, SpvStorageClassFunction = 7 };
enum { SpvCapabilityClipDistance = 32, SpvCapabilityCullDistance = 33, SpvCapabilityTessellationPointSize = 23,
       SpvCapabilityGeometryPointSize = 24, SpvCapabilityDrawParameters = 4427 };
enum { SpvExecutionModelVertex = 0, SpvExecutionModelTessellationControl = 1, SpvExecutionModelTessellationEvaluation = 2,
       SpvExecutionModelGeometry = 3, SpvExecutionModelFragment = 4, SpvExecutionModelGLCompute = 5 };

typedef struct spv_inst {
    uint16_t op;
    uint16_t n;        /* word count including the first word */
    uint32_t *w;       /* operand words (w[0] = first operand), n - 1 of them */
} spv_inst;

typedef struct spv_mod {
    uint32_t header[5];
    spv_inst *ins;
    uint32_t count, cap;
    int err;
} spv_mod;

int spv_parse(spv_mod *m, const uint32_t *code, size_t bytes);
/* Writes the module into a malloc'd buffer; returns its size in bytes (0 on failure). */
size_t spv_write(const spv_mod *m, uint32_t **out);
void spv_free(spv_mod *m);

static inline uint32_t spv_version(const spv_mod *m) { return m->header[1]; }
static inline uint32_t spv_new_id(spv_mod *m) { return m->header[3]++; }

/* Insert an instruction before index `at` (at = count appends). Operands copied. Returns index. */
uint32_t spv_insert(spv_mod *m, uint32_t at, uint16_t op, const uint32_t *ops, uint16_t nops);
void spv_remove(spv_mod *m, uint32_t at);

/* Index of the first instruction with this opcode (or of the defining instruction of `id`), or -1. */
int spv_find_op(const spv_mod *m, uint16_t op, uint32_t from);
int spv_find_def(const spv_mod *m, uint32_t id);
/* The id an instruction defines (result id), or 0. */
uint32_t spv_result_id(const spv_inst *in);

/* Section boundaries: index where new decorations / types / function code may be inserted. */
uint32_t spv_types_end(const spv_mod *m);       /* end of the types/constants/globals section */
uint32_t spv_annotations_end(const spv_mod *m); /* end of the decoration section */

/* Types and constants: found when they already exist (non-aggregate types must be unique). */
uint32_t spv_type_float(spv_mod *m, uint32_t width);
uint32_t spv_type_int(spv_mod *m, uint32_t width, uint32_t sign);
uint32_t spv_type_bool(spv_mod *m);
uint32_t spv_type_pointer(spv_mod *m, uint32_t storage, uint32_t pointee);
uint32_t spv_type_array(spv_mod *m, uint32_t elem, uint32_t len_const);
uint32_t spv_const_u32(spv_mod *m, uint32_t v);
uint32_t spv_const_f32(spv_mod *m, float v);

/* Decorations of an id: returns the operand (after the decoration) or -1. */
int64_t spv_get_decoration(const spv_mod *m, uint32_t id, uint32_t decoration);
void spv_remove_decoration(spv_mod *m, uint32_t id, uint32_t decoration);
int spv_has_capability(const spv_mod *m, uint32_t cap);
void spv_remove_capability(spv_mod *m, uint32_t cap);
void spv_add_capability(spv_mod *m, uint32_t cap);
/* The execution model of the (first) entry point, or -1. */
int spv_execution_model(const spv_mod *m);
/* Adds an interface variable to every entry point. */
void spv_add_interface(spv_mod *m, uint32_t var);
/* Highest Location used by variables of a storage class (members included), or -1. */
int spv_max_location(const spv_mod *m, uint32_t storage);
/* Length of an OpTypeArray (via its constant), or 0. */
uint32_t spv_array_length(const spv_mod *m, uint32_t array_type);

#endif
