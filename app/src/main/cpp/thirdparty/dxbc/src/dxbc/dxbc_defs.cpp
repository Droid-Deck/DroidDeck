#include "dxbc_defs.h"

namespace dxvk {
  
  const std::array<DxbcInstFormat, 235> g_instructionFormats = {{
    { 3, DxbcInstClass::VectorAlu, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
    } },
    { 3, DxbcInstClass::VectorAlu, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32 },
    } },
    { 0, DxbcInstClass::ControlFlow },
    { 1, DxbcInstClass::ControlFlow, {
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32 },
    } },
    { 1, DxbcInstClass::ControlFlow, {
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32 },
    } },
    { 2, DxbcInstClass::ControlFlow, {
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32 },
    } },
    { 1, DxbcInstClass::ControlFlow, {
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32 },
    } },
    { 0, DxbcInstClass::ControlFlow },
    { 1, DxbcInstClass::ControlFlow, {
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32 },
    } },
    { 0, DxbcInstClass::GeometryEmit },
    { 0, DxbcInstClass::ControlFlow },
    { 2, DxbcInstClass::VectorDeriv, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
    } },
    { 2, DxbcInstClass::VectorDeriv, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
    } },
    { 1, DxbcInstClass::ControlFlow, {
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32 },
    } },
    { 3, DxbcInstClass::VectorAlu, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
    } },
    { 3, DxbcInstClass::VectorDot, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
    } },
    { 3, DxbcInstClass::VectorDot, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
    } },
    { 3, DxbcInstClass::VectorDot, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
    } },
    { 0, DxbcInstClass::ControlFlow },
    { 0, DxbcInstClass::GeometryEmit },
    { 0, DxbcInstClass::GeometryEmit },
    { 0, DxbcInstClass::ControlFlow },
    { 0, DxbcInstClass::ControlFlow },
    { 0, DxbcInstClass::ControlFlow },
    { 3, DxbcInstClass::VectorCmp, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32  },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
    } },
    { 2, DxbcInstClass::VectorAlu, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
    } },
    { 2, DxbcInstClass::VectorAlu, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
    } },
    { 2, DxbcInstClass::VectorAlu, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Sint32  },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
    } },
    { 2, DxbcInstClass::VectorAlu, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32  },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
    } },
    { 3, DxbcInstClass::VectorCmp, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32  },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
    } },
    { 3, DxbcInstClass::VectorAlu, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Sint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32 },
    } },
    { 1, DxbcInstClass::ControlFlow, {
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32 },
    } },
    { 3, DxbcInstClass::VectorCmp, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Sint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32 },
    } },
    { 3, DxbcInstClass::VectorCmp, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Sint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32 },
    } },
    { 3, DxbcInstClass::VectorCmp, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Sint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32 },
    } },
    { 4, DxbcInstClass::VectorAlu, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Sint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32 },
    } },
    { 3, DxbcInstClass::VectorAlu, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Sint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32 },
    } },
    { 3, DxbcInstClass::VectorAlu, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Sint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32 },
    } },
    { 4, DxbcInstClass::VectorImul, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Sint32 },
      { DxbcOperandKind::DstReg, DxbcScalarType::Sint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32 },
    } },
    { 3, DxbcInstClass::VectorCmp, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Sint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32 },
    } },
    { 2, DxbcInstClass::VectorAlu, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Sint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32 },
    } },
    { 3, DxbcInstClass::VectorShift, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Sint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32 },
    } },
    { 3, DxbcInstClass::VectorShift, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Sint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32 },
    } },
    { 2, DxbcInstClass::VectorAlu, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32  },
    } },
    { 1, DxbcInstClass::ControlFlow, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32 },
    } },
    { 3, DxbcInstClass::TextureFetch, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32  },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
    } },
    { 4, DxbcInstClass::TextureFetch, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32  },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32  },
    } },
    { 2, DxbcInstClass::VectorAlu, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
    } },
    { 0, DxbcInstClass::ControlFlow },
    { 3, DxbcInstClass::VectorCmp, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32  },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
    } },
    { 4, DxbcInstClass::VectorAlu, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
    } },
    { 3, DxbcInstClass::VectorAlu, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
    } },
    { 3, DxbcInstClass::VectorAlu, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
    } },
    { 0, DxbcInstClass::CustomData },
    { 2, DxbcInstClass::VectorAlu, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
    } },
    { 4, DxbcInstClass::VectorCmov, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32  },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
    } },
    { 3, DxbcInstClass::VectorAlu, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
    } },
    { 3, DxbcInstClass::VectorCmp, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32  },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
    } },
    { 0, DxbcInstClass::NoOperation },
    { 2, DxbcInstClass::VectorAlu, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32 },
    } },
    { 3, DxbcInstClass::VectorAlu, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32 },
    } },
    { 3, DxbcInstClass::TextureQuery, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32  },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
    } },
    { 0, DxbcInstClass::ControlFlow },
    { 1, DxbcInstClass::ControlFlow, {
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32 },
    } },
    { 2, DxbcInstClass::VectorAlu, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
    } },
    { 2, DxbcInstClass::VectorAlu, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
    } },
    { 2, DxbcInstClass::VectorAlu, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
    } },
    { 2, DxbcInstClass::VectorAlu, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
    } },
    { 2, DxbcInstClass::VectorAlu, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
    } },
    { 4, DxbcInstClass::TextureSample, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
    } },
    { 5, DxbcInstClass::TextureSample, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
    } },
    { 5, DxbcInstClass::TextureSample, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
    } },
    { 5, DxbcInstClass::TextureSample, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
    } },
    { 6, DxbcInstClass::TextureSample, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
    } },
    { 5, DxbcInstClass::TextureSample, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
    } },
    { 2, DxbcInstClass::VectorAlu, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
    } },
    { 1, DxbcInstClass::ControlFlow, {
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32 },
    } },
    { 3, DxbcInstClass::VectorSinCos, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
    } },
    { 4, DxbcInstClass::VectorIdiv, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32 },
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32 },
    } },
    { 3, DxbcInstClass::VectorCmp, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32 },
    } },
    { 3, DxbcInstClass::VectorCmp, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32 },
    } },
    { 4, DxbcInstClass::VectorImul, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32 },
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32 },
    } },
    { 4, DxbcInstClass::VectorAlu, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32 },
    } },
    { 3, DxbcInstClass::VectorAlu, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32 },
    } },
    { 3, DxbcInstClass::VectorAlu, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32 },
    } },
    { 3, DxbcInstClass::VectorShift, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32 },
    } },
    { 2, DxbcInstClass::VectorAlu, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32  },
    } },
    { 3, DxbcInstClass::VectorAlu, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32 },
    } },
    { 2, DxbcInstClass::Declaration, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::Imm32,  DxbcScalarType::Uint32  },
    } },
    { 1, DxbcInstClass::Declaration, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
    } },
    { 1, DxbcInstClass::Declaration, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
    } },
    { 2, DxbcInstClass::Declaration, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::Imm32,  DxbcScalarType::Uint32  },
    } },
    { 0, DxbcInstClass::Declaration },
    { 0, DxbcInstClass::Declaration },
    { 1, DxbcInstClass::Declaration, {
      { DxbcOperandKind::Imm32,  DxbcScalarType::Uint32 },
    } },
    { 1, DxbcInstClass::Declaration, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
    } },
    { 2, DxbcInstClass::Declaration, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::Imm32,  DxbcScalarType::Uint32  },
    } },
    { 2, DxbcInstClass::Declaration, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::Imm32,  DxbcScalarType::Uint32  },
    } },
    { 1, DxbcInstClass::Declaration, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
    } },
    { 2, DxbcInstClass::Declaration, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::Imm32,  DxbcScalarType::Uint32  },
    } },
    { 2, DxbcInstClass::Declaration, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::Imm32,  DxbcScalarType::Uint32  },
    } },
    { 1, DxbcInstClass::Declaration, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
    } },
    { 2, DxbcInstClass::Declaration, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::Imm32,  DxbcScalarType::Uint32  },
    } },
    { 2, DxbcInstClass::Declaration, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::Imm32,  DxbcScalarType::Uint32  },
    } },
    { 1, DxbcInstClass::Declaration, {
      { DxbcOperandKind::Imm32, DxbcScalarType::Uint32 },
    } },
    { 3, DxbcInstClass::Declaration, {
      { DxbcOperandKind::Imm32, DxbcScalarType::Uint32 },
      { DxbcOperandKind::Imm32, DxbcScalarType::Uint32 },
      { DxbcOperandKind::Imm32, DxbcScalarType::Uint32 },
    } },
    { 0, DxbcInstClass::Declaration },
    { 0, DxbcInstClass::Undefined },
    { 4, DxbcInstClass::TextureQueryLod, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
    } },
    { 4, DxbcInstClass::TextureGather, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
    } },
    { 3, DxbcInstClass::TextureQueryMsPos, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32  },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32  },
    } },
    { 2, DxbcInstClass::TextureQueryMs, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32  },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
    } },
    { },
    { 0, DxbcInstClass::HullShaderPhase },
    { 0, DxbcInstClass::HullShaderPhase },
    { 0, DxbcInstClass::HullShaderPhase },
    { 0, DxbcInstClass::HullShaderPhase },
    { 1, DxbcInstClass::GeometryEmit, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32  },
    } },
    { 1, DxbcInstClass::GeometryEmit, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32  },
    } },
    { 1, DxbcInstClass::GeometryEmit, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32  },
    } },
    { },
    { 2, DxbcInstClass::BufferQuery, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32  },
    } },
    { 2, DxbcInstClass::VectorDeriv, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
    } },
    { 2, DxbcInstClass::VectorDeriv, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
    } },
    { 2, DxbcInstClass::VectorDeriv, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
    } },
    { 2, DxbcInstClass::VectorDeriv, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
    } },
    { 5, DxbcInstClass::TextureGather, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
    } },
    { 5, DxbcInstClass::TextureGather, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32  },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
    } },
    { 6, DxbcInstClass::TextureGather, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32  },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
    } },
    { 2, DxbcInstClass::VectorAlu, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
    } },
    { 2, DxbcInstClass::ConvertFloat16, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32  },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
    } },
    { 2, DxbcInstClass::ConvertFloat16, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32  },
    } },
    { },
    { },
    { 2, DxbcInstClass::VectorAlu, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32 },
    } },
    { 2, DxbcInstClass::BitScan, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32 },
    } },
    { 2, DxbcInstClass::BitScan, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32 },
    } },
    { 2, DxbcInstClass::BitScan, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32 },
    } },
    { 4, DxbcInstClass::BitExtract, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32 },
    } },
    { 4, DxbcInstClass::BitExtract, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Sint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32 },
    } },
    { 5, DxbcInstClass::BitInsert, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32 },
    } },
    { 2, DxbcInstClass::VectorAlu, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32 },
    } },
    { 5, DxbcInstClass::VectorCmov, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32  },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
    } },
    { 1, DxbcInstClass::Declaration, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32  },
    } },
    { },
    { },
    { },
    { 0, DxbcInstClass::Declaration },
    { 0, DxbcInstClass::Declaration },
    { 0, DxbcInstClass::Declaration },
    { 0, DxbcInstClass::Declaration },
    { 0, DxbcInstClass::Declaration },
    { 1, DxbcInstClass::Declaration, {
      { DxbcOperandKind::Imm32, DxbcScalarType::Float32 },
    } },
    { 1, DxbcInstClass::HullShaderInstCnt, {
      { DxbcOperandKind::Imm32, DxbcScalarType::Uint32 },
    } },
    { 1, DxbcInstClass::HullShaderInstCnt, {
      { DxbcOperandKind::Imm32, DxbcScalarType::Uint32 },
    } },
    { 3, DxbcInstClass::Declaration, {
      { DxbcOperandKind::Imm32, DxbcScalarType::Uint32 },
      { DxbcOperandKind::Imm32, DxbcScalarType::Uint32 },
      { DxbcOperandKind::Imm32, DxbcScalarType::Uint32 },
    } },
    { 2, DxbcInstClass::Declaration, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::Imm32,  DxbcScalarType::Uint32  },
    } },
    { 1, DxbcInstClass::Declaration, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
    } },
    { 2, DxbcInstClass::Declaration, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::Imm32,  DxbcScalarType::Uint32  },
    } },
    { 2, DxbcInstClass::Declaration, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::Imm32,  DxbcScalarType::Uint32  },
    } },
    { 3, DxbcInstClass::Declaration, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::Imm32,  DxbcScalarType::Uint32  },
      { DxbcOperandKind::Imm32,  DxbcScalarType::Uint32  },
    } },
    { 1, DxbcInstClass::Declaration, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
    } },
    { 2, DxbcInstClass::Declaration, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::Imm32,  DxbcScalarType::Uint32  },
    } },
    { 3, DxbcInstClass::TypedUavLoad, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32  },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32  },
    } },
    { 3, DxbcInstClass::TypedUavStore, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32  },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32  },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
    } },
    { 3, DxbcInstClass::BufferLoad, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32 },
    } },
    { 3, DxbcInstClass::BufferStore, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32 },
    } },
    { 4, DxbcInstClass::BufferLoad, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32 },
    } },
    { 4, DxbcInstClass::BufferStore, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32 },
    } },
    { 3, DxbcInstClass::Atomic, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32 },
    } },
    { 3, DxbcInstClass::Atomic, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32 },
    } },
    { 3, DxbcInstClass::Atomic, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32 },
    } },
    { 4, DxbcInstClass::Atomic, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32 },
    } },
    { 3, DxbcInstClass::Atomic, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32 },
    } },
    { 3, DxbcInstClass::Atomic, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Sint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32 },
    } },
    { 3, DxbcInstClass::Atomic, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Sint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32 },
    } },
    { 3, DxbcInstClass::Atomic, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32 },
    } },
    { 3, DxbcInstClass::Atomic, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32 },
    } },
    { 2, DxbcInstClass::AtomicCounter, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32 },
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32 },
    } },
    { 2, DxbcInstClass::AtomicCounter, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32 },
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32 },
    } },
    { 4, DxbcInstClass::Atomic, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32 },
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32 },
    } },
    { 4, DxbcInstClass::Atomic, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32 },
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32 },
    } },
    { 4, DxbcInstClass::Atomic, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32 },
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32 },
    } },
    { 4, DxbcInstClass::Atomic, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32 },
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32 },
    } },
    { 4, DxbcInstClass::Atomic, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32 },
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32 },
    } },
    { 5, DxbcInstClass::Atomic, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32 },
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32 },
    } },
    { 4, DxbcInstClass::Atomic, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Sint32 },
      { DxbcOperandKind::DstReg, DxbcScalarType::Sint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32 },
    } },
    { 4, DxbcInstClass::Atomic, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Sint32 },
      { DxbcOperandKind::DstReg, DxbcScalarType::Sint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32 },
    } },
    { 4, DxbcInstClass::Atomic, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32 },
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32 },
    } },
    { 4, DxbcInstClass::Atomic, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32 },
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32 },
    } },
    { 0, DxbcInstClass::Barrier },
    { 3, DxbcInstClass::VectorAlu, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float64 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float64 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float64 },
    } },
    { 3, DxbcInstClass::VectorAlu, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float64 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float64 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float64 },
    } },
    { 3, DxbcInstClass::VectorAlu, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float64 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float64 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float64 },
    } },
    { 3, DxbcInstClass::VectorAlu, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float64 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float64 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float64 },
    } },
    { 3, DxbcInstClass::VectorCmp, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32  },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float64 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float64 },
    } },
    { 3, DxbcInstClass::VectorCmp, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32  },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float64 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float64 },
    } },
    { 3, DxbcInstClass::VectorCmp, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32  },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float64 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float64 },
    } },
    { 3, DxbcInstClass::VectorCmp, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32  },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float64 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float64 },
    } },
    { 2, DxbcInstClass::VectorAlu, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float64 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float64 },
    } },
    { 4, DxbcInstClass::VectorCmov, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float64 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32  },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float64 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float64 },
    } },
    { 2, DxbcInstClass::ConvertFloat64, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float64 },
    } },
    { 2, DxbcInstClass::ConvertFloat64, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float64 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
    } },
    { 3, DxbcInstClass::Interpolate, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32  },
    } },
    { 3, DxbcInstClass::Interpolate, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32  },
    } },
    { 2, DxbcInstClass::Interpolate, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
    } },
    { 1, DxbcInstClass::Declaration, {
      { DxbcOperandKind::Imm32, DxbcScalarType::Uint32 },
    } },
    { },
    { },
    { },
    { 3, DxbcInstClass::VectorAlu, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float64 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float64 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float64 },
    } },
    { 4, DxbcInstClass::VectorAlu, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float64 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float64 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float64 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float64 },
    } },
    { 2, DxbcInstClass::VectorAlu, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float64 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float64 },
    } },
    { 4, DxbcInstClass::VectorMsad, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32 },
    } },
    { 2, DxbcInstClass::ConvertFloat64, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Sint32  },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float64 },
    } },
    { 2, DxbcInstClass::ConvertFloat64, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32  },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float64 },
    } },
    { 2, DxbcInstClass::ConvertFloat64, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float64 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32  },
    } },
    { 2, DxbcInstClass::ConvertFloat64, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float64 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32  },
    } },
    { },
    { 5, DxbcInstClass::TextureGather, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32  },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
    } },
    { 6, DxbcInstClass::TextureGather, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32  },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
    } },
    { 6, DxbcInstClass::TextureGather, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32  },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32  },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
    } },
    { 7, DxbcInstClass::TextureGather, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32  },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32  },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
    } },
    { 4, DxbcInstClass::TextureFetch, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32  },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32  },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
    } },
    { 5, DxbcInstClass::TextureFetch, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32  },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32  },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32  },
    } },
    { 4, DxbcInstClass::TypedUavLoad, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32  },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32  },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32  },
    } },
    { 4, DxbcInstClass::BufferLoad, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32 },
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32 },
    } },
    { 5, DxbcInstClass::BufferLoad, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32 },
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Sint32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32 },
    } },
    { 6, DxbcInstClass::TextureSample, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32  },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
    } },
    { 6, DxbcInstClass::TextureSample, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32  },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
    } },
    { 6, DxbcInstClass::TextureSample, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32  },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
    } },
    { 7, DxbcInstClass::TextureSample, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32  },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
    } },
    { 8, DxbcInstClass::TextureSample, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32  },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
    } },
    { 7, DxbcInstClass::TextureSample, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32  },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Float32 },
    } },
    { 2, DxbcInstClass::SparseCheckAccess, {
      { DxbcOperandKind::DstReg, DxbcScalarType::Uint32  },
      { DxbcOperandKind::SrcReg, DxbcScalarType::Uint32  },
    } },
  }};
  
  
  DxbcInstFormat dxbcInstructionFormat(DxbcOpcode opcode) {
    const uint32_t idx = static_cast<uint32_t>(opcode);
    
    return (idx < g_instructionFormats.size())
      ? g_instructionFormats.at(idx)
      : DxbcInstFormat();
  }
  
}