/* The layout of gosys.c's flat binary: the header at 0 (the patcher fills it in), the room for the
   call site's connecting code, the code with its entry first, read-only data, the trailer last. */
OUTPUT_FORMAT(elf64-littleaarch64)
ENTRY(cxgo_entry)
SECTIONS
{
  . = 0;
  .hdr      : { KEEP(*(.hdr)) }
  .thunks   : { . = ALIGN(16); KEEP(*(.thunks)) }
  .text     : { . = ALIGN(16); KEEP(*(.text.cxgo_entry)) *(.text .text.*) }
  .rodata   : { . = ALIGN(16); *(.rodata .rodata.*) }
  .trailer  : { . = ALIGN(64); KEEP(*(.trailer)) }
  /DISCARD/ : { *(.comment) *(.note*) *(.eh_frame*) *(.gnu*) *(.interp) }
}
