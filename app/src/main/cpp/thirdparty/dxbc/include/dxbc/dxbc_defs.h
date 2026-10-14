#pragma once

#include "dxbc_enums.h"

namespace dxvk {
  
  constexpr size_t DxbcMaxInterfaceRegs = 32;
  constexpr size_t DxbcMaxOperandCount  = 8;
  
  enum class DxbcOperandKind {
    DstReg,
    SrcReg,
    Imm32,
  };
  
  enum class DxbcInstClass {
    Declaration,
    CustomData,
    ControlFlow,
    GeometryEmit,
    Atomic,
    AtomicCounter,
    Barrier,
    BitExtract,
    BitInsert,
    BitScan,
    BufferQuery,
    BufferLoad,
    BufferStore,
    ConvertFloat16,
    ConvertFloat64,
    HullShaderPhase,
    HullShaderInstCnt,
    Interpolate,
    NoOperation,
    SparseCheckAccess,
    TextureQuery,
    TextureQueryLod,
    TextureQueryMs,
    TextureQueryMsPos,
    TextureFetch,
    TextureGather,
    TextureSample,
    TypedUavLoad,
    TypedUavStore,
    VectorAlu,
    VectorCmov,
    VectorCmp,
    VectorDeriv,
    VectorDot,
    VectorIdiv,
    VectorImul,
    VectorMsad,
    VectorShift,
    VectorSinCos,
    Undefined,
  };
  
  struct DxbcInstOperandFormat {
    DxbcOperandKind kind;
    DxbcScalarType  type;
  };
  
  struct DxbcInstFormat {
    uint32_t              operandCount      = 0;
    DxbcInstClass         instructionClass  = DxbcInstClass::Undefined;
    DxbcInstOperandFormat operands[DxbcMaxOperandCount];
  };
  
  DxbcInstFormat dxbcInstructionFormat(DxbcOpcode opcode);
  
}