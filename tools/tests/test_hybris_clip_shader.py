"""Exercise the patched upstream converter; HYBRIS_CLIP_SOURCE enables the CI gate."""

import ctypes
import os
from pathlib import Path
import shutil
import struct
import subprocess
import tempfile
import unittest

if __package__:
    from .test_hybris_vendor_compat import vulkan_include
else:
    from test_hybris_vendor_compat import vulkan_include


ROOT = Path(__file__).resolve().parents[2]
WORD = ctypes.c_uint32
WORD_POINTER = ctypes.POINTER(WORD)


def instructions(data):
    words = struct.unpack(f"<{len(data) // 4}I", data)
    cursor = 5
    while cursor < len(words):
        count = words[cursor] >> 16
        assert count and cursor + count <= len(words)
        yield words[cursor] & 0xffff, words[cursor + 1:cursor + count]
        cursor += count


def shader(stage, whole_array=False):
    vertex = stage == "vertex"
    return "\n".join([
        "OpCapability Shader",
        "OpCapability ClipDistance" if vertex else "",
        # A recognized extension keeps the upstream cleanup conservative. The
        # direct-array conversion must be valid without relying on that cleanup.
        'OpExtension "SPV_KHR_storage_buffer_storage_class"',
        "OpMemoryModel Logical GLSL450",
        ('OpEntryPoint Vertex %main "main" %clip %position' if vertex else
         'OpEntryPoint Fragment %main "main" %color'),
        "" if vertex else "OpExecutionMode %main OriginUpperLeft",
        "OpSource Unknown 0",
        'OpSourceContinued "regression fixture"',
        'OpName %main "main"',
        'OpModuleProcessed "clip shader regression"',
        ("OpDecorate %clip BuiltIn ClipDistance\nOpDecorate %position BuiltIn Position"
         if vertex else "OpDecorate %color Location 0"),
        "%void = OpTypeVoid",
        "%function = OpTypeFunction %void",
        "%float = OpTypeFloat 32",
        "%uint = OpTypeInt 32 0",
        "%zero_index = OpConstant %uint 0",
        "%one_index = OpConstant %uint 1",
        "%two = OpConstant %uint 2",
        "%one = OpConstant %float 1",
        "%vector = OpTypeVector %float 4",
        "%rgba = OpConstantComposite %vector %one %one %one %one",
        "%vector_pointer = OpTypePointer Output %vector",
        ("%array = OpTypeArray %float %two\n"
         "%array_pointer = OpTypePointer Output %array\n"
         "%float_pointer = OpTypePointer Output %float\n"
         "%values = OpConstantComposite %array %one %one\n"
         "%clip = OpVariable %array_pointer Output\n"
         "%position = OpVariable %vector_pointer Output" if vertex else
         "%color = OpVariable %vector_pointer Output"),
        "%main = OpFunction %void None %function",
        "%entry = OpLabel",
        (("OpStore %clip %values" if whole_array else
          "%first = OpAccessChain %float_pointer %clip %zero_index\n"
          "OpStore %first %one\n"
          "%second = OpAccessChain %float_pointer %clip %one_index\n"
          "OpStore %second %one") + "\nOpStore %position %rgba" if vertex else
         "OpStore %color %rgba"),
        "OpReturn",
        "OpFunctionEnd",
        "",
    ])


def per_vertex_block_shader(model, use_clip):
    stage = "Geometry" if model == 3 else "TessellationEvaluation"
    capability = "Geometry" if model == 3 else "Tessellation"
    modes = (["OpExecutionMode %main InputPoints", "OpExecutionMode %main OutputPoints",
              "OpExecutionMode %main OutputVertices 1"] if model == 3 else
             ["OpExecutionMode %main Triangles", "OpExecutionMode %main SpacingEqual",
              "OpExecutionMode %main VertexOrderCcw"])
    body = [
        "%position_ptr = OpAccessChain %vector_pointer %out %zero_index",
        "OpStore %position_ptr %rgba",
    ]
    if use_clip:
        body += [
            "%clip_ptr = OpAccessChain %float_pointer %out %one_index %zero_index",
            "OpStore %clip_ptr %one",
        ]
    if model == 3:
        body += ["OpEmitVertex", "OpEndPrimitive"]
    return "\n".join([
        "OpCapability Shader", f"OpCapability {capability}", "OpCapability ClipDistance",
        "OpMemoryModel Logical GLSL450", f'OpEntryPoint {stage} %main "main" %out',
        *modes,
        "OpMemberDecorate %per_vertex 0 BuiltIn Position",
        "OpMemberDecorate %per_vertex 1 BuiltIn ClipDistance",
        "OpDecorate %per_vertex Block",
        "%void = OpTypeVoid", "%function = OpTypeFunction %void",
        "%float = OpTypeFloat 32", "%uint = OpTypeInt 32 0",
        "%zero_index = OpConstant %uint 0", "%one_index = OpConstant %uint 1",
        "%two = OpConstant %uint 2", "%one = OpConstant %float 1",
        "%vector = OpTypeVector %float 4",
        "%rgba = OpConstantComposite %vector %one %one %one %one",
        "%clip_array = OpTypeArray %float %two",
        "%per_vertex = OpTypeStruct %vector %clip_array",
        "%block_pointer = OpTypePointer Output %per_vertex",
        "%vector_pointer = OpTypePointer Output %vector",
        "%float_pointer = OpTypePointer Output %float",
        "%out = OpVariable %block_pointer Output",
        "%main = OpFunction %void None %function", "%entry = OpLabel",
        *body, "OpReturn", "OpFunctionEnd", "",
    ])


class HybrisClipShaderTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        source_value = os.environ.get("HYBRIS_CLIP_SOURCE")
        if not source_value:
            raise unittest.SkipTest("set HYBRIS_CLIP_SOURCE to the pinned libhybris checkout")
        # An explicitly enabled gate must fail if its tools/source are missing.
        cls.compiler = shutil.which(os.environ.get("CC", "cc"))
        cls.assembler = shutil.which("spirv-as")
        cls.validator = shutil.which("spirv-val")
        if not all((cls.compiler, cls.assembler, cls.validator)):
            raise RuntimeError("HYBRIS_CLIP_SOURCE requires a C compiler and spirv-tools")
        pristine = Path(source_value)
        if not (pristine / "hybris/vulkan/compat/spirv_clip.c").is_file():
            raise RuntimeError("HYBRIS_CLIP_SOURCE must point to the pinned libhybris checkout")
        cls.temporary = tempfile.TemporaryDirectory(prefix="droiddeck-clip-shader-")
        cls.addClassCleanup(cls.temporary.cleanup)
        cls.directory = Path(cls.temporary.name)
        checkout = cls.directory / "libhybris"
        subprocess.run(["git", "clone", "-q", "--no-hardlinks", str(pristine), str(checkout)],
                       check=True)
        for patch in sorted((ROOT / "tools/hybris/patches").glob("*.patch")):
            subprocess.run(["git", "-C", str(checkout), "apply", "--check", str(patch)],
                           check=True, capture_output=True, text=True)
            subprocess.run(["git", "-C", str(checkout), "apply", str(patch)],
                           check=True, capture_output=True, text=True)
        source = checkout / "hybris/vulkan/compat"
        cls.source = source
        allocator = cls.directory / "allocator.c"
        allocator.write_text(
            "#include <vulkan/vulkan.h>\n#include <stdlib.h>\n"
            "void *hybris_scaled_alloc(const VkAllocationCallbacks *a, size_t n, "
            "VkSystemAllocationScope s) { (void)a; (void)s; return malloc(n); }\n"
            "void hybris_scaled_free(const VkAllocationCallbacks *a, void *p) "
            "{ (void)a; free(p); }\n")
        library = cls.directory / "clip.so"
        subprocess.run([
            cls.compiler, "-shared", "-fPIC", "-std=gnu11", "-Wall", "-Wextra", "-Werror",
            "-I", str(vulkan_include()), "-I", str(source), str(source / "spirv_clip.c"),
            *(str(source / name) for name in
              ("spirv_builtins.c", "spirv_entry.c", "spirv_decorations.c")),
            str(allocator), "-o", str(library)], check=True, capture_output=True, text=True)
        cls.library = ctypes.CDLL(str(library))
        arguments = [WORD_POINTER, ctypes.c_size_t, WORD, ctypes.c_void_p,
                     ctypes.POINTER(WORD_POINTER), ctypes.POINTER(ctypes.c_size_t),
                     ctypes.POINTER(ctypes.c_uint)]
        cls.library.hybris_spirv_clip_vertex.argtypes = arguments
        cls.library.hybris_spirv_clip_fragment.argtypes = arguments[:3] + [WORD] + arguments[3:]
        cls.library.hybris_spirv_entry_builtin.argtypes = [
            WORD_POINTER, ctypes.c_size_t, WORD, ctypes.c_char_p, WORD, ctypes.c_void_p,
            ctypes.POINTER(ctypes.c_int), ctypes.POINTER(ctypes.c_char_p)]
        cls.library.hybris_scaled_free.argtypes = [ctypes.c_void_p, ctypes.c_void_p]
        cls.dispatch = cls.directory / "clip-dispatch-test"
        dispatch_built = subprocess.run([
            cls.compiler, "-std=gnu11", "-Wall", "-Wextra", "-Werror", "-pthread",
            "-I", str(vulkan_include()), "-I", str(source),
            str(Path(__file__).with_name("hybris_clip_dispatch_test.c")),
            *(str(source / name) for name in
              ("spirv_entry.c", "spirv_decorations.c", "spirv_builtins.c", "spirv_clip.c")),
            "-latomic", "-o", str(cls.dispatch)], capture_output=True, text=True)
        if dispatch_built.returncode:
            raise RuntimeError(dispatch_built.stdout + dispatch_built.stderr)

    def assemble(self, stage, whole_array=False):
        text = self.directory / "input.spvasm"
        binary = self.directory / "input.spv"
        text.write_text(shader(stage, whole_array))
        subprocess.run([self.assembler, "--target-env", "vulkan1.1", str(text), "-o", str(binary)],
                       check=True, capture_output=True, text=True)
        data = binary.read_bytes()
        self.validate(data)
        return data

    def validate(self, data):
        binary = self.directory / "validate.spv"
        binary.write_bytes(data)
        result = subprocess.run([self.validator, "--target-env", "vulkan1.1", str(binary)],
                                capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def assemble_text(self, source, preserve_ids=False):
        text = self.directory / "contract.spvasm"
        binary = self.directory / "contract.spv"
        text.write_text(source)
        command = [self.assembler, "--target-env", "vulkan1.1"]
        if preserve_ids:
            command.append("--preserve-numeric-ids")
        result = subprocess.run(command + [str(text), "-o", str(binary)],
                                capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        return binary.read_bytes()

    def entry_builtin(self, data, model, entry="main"):
        code = (WORD * (len(data) // 4)).from_buffer_copy(data)
        present, reason = ctypes.c_int(), ctypes.c_char_p()
        result = self.library.hybris_spirv_entry_builtin(
            code, len(data), model, entry.encode(), 3, None,
            ctypes.byref(present), ctypes.byref(reason))
        self.assertEqual(result, 0, reason.value.decode() if reason.value else "")
        return bool(present.value)

    def convert(self, data, stage):
        code = (WORD * (len(data) // 4)).from_buffer_copy(data)
        output, size, changed = WORD_POINTER(), ctypes.c_size_t(), ctypes.c_uint()
        arguments = [code, len(data), 7]
        if stage == "fragment":
            arguments.append(2)
        arguments += [None, ctypes.byref(output), ctypes.byref(size), ctypes.byref(changed)]
        function = (self.library.hybris_spirv_clip_vertex if stage == "vertex" else
                    self.library.hybris_spirv_clip_fragment)
        result = function(*arguments)
        try:
            self.assertEqual(result, 0)
            self.assertEqual(changed.value, 1)
            return ctypes.string_at(output, size.value)
        finally:
            self.library.hybris_scaled_free(None, output)

    def test_direct_array_keeps_interface_and_stores(self):
        for whole_array in (False, True):
            with self.subTest(whole_array=whole_array):
                original = self.assemble("vertex", whole_array)
                converted = self.convert(original, "vertex")
                self.validate(converted)
                before, after = list(instructions(original)), list(instructions(converted))
                self.assertEqual([v for op, v in before if op == 15],
                                 [v for op, v in after if op == 15])
                self.assertEqual([v for op, v in before if op in (59, 62, 65, 66)],
                                 [v for op, v in after if op in (59, 62, 65, 66)])
                self.assertFalse(any(op == 71 and v[1:] == (11, 3) for op, v in after))
                self.assertTrue(any(op == 71 and v[1:] == (30, 7) for op, v in after))

    def test_fragment_output_validates(self):
        self.validate(self.convert(self.assemble("fragment"), "fragment"))

    def test_execution_mode_id_stays_before_annotations(self):
        # Ubuntu's older SPIR-V tools do not know FPFastMathDefault. Exercise the
        # real converter's opcode331 layout handling independently of validator
        # support; current runtime dumps additionally validate with recent tools.
        for stage in ("vertex", "fragment"):
            with self.subTest(stage=stage):
                original = self.assemble(stage)
                stream = list(instructions(original))
                main = next(v[1] for op, v in stream if op == 15)
                float_type = next(v[0] for op, v in stream if op == 22)
                zero = next(v[1] for op, v in stream if op == 43 and v[2] == 0)
                position = max(i for i, (op, _) in enumerate(stream) if op in (15, 16)) + 1
                mode = (main, 6028, float_type, zero)  # FPFastMathDefault
                stream.insert(position, (331, mode))
                words = list(struct.unpack("<5I", original[:20]))
                for opcode, operands in stream:
                    words.extend([((len(operands) + 1) << 16) | opcode, *operands])
                changed = self.convert(struct.pack(f"<{len(words)}I", *words), stage)
                emitted = list(instructions(changed))
                self.assertEqual([v for op, v in emitted if op == 331], [mode])
                mode_index = next(i for i, (op, _) in enumerate(emitted) if op == 331)
                annotation_index = next(i for i, (op, _) in enumerate(emitted) if op in (71, 72))
                self.assertLess(mode_index, annotation_index)

    def test_contract_detects_selected_geometry_and_tessellation_clip(self):
        base = shader("vertex")
        function = "\n".join([
            "%void = OpTypeVoid", "%function = OpTypeFunction %void",
            "%main = OpFunction %void None %function", "%entry = OpLabel",
            "OpReturn", "OpFunctionEnd", "",
        ])
        cases = [
            (3, base.replace("OpCapability Shader", "OpCapability Shader\nOpCapability Geometry")
             .replace('OpEntryPoint Vertex %main "main"',
                      'OpEntryPoint Geometry %main "main"')
             .replace("OpSource Unknown 0",
                      "OpExecutionMode %main InputPoints\nOpExecutionMode %main OutputPoints\n"
                      "OpExecutionMode %main OutputVertices 1\nOpSource Unknown 0"),
             "\n".join(["OpCapability Shader", "OpCapability Geometry",
                         "OpMemoryModel Logical GLSL450", 'OpEntryPoint Geometry %main "main"',
                         "OpExecutionMode %main InputPoints", "OpExecutionMode %main OutputPoints",
                         "OpExecutionMode %main OutputVertices 1", function])),
            (2, base.replace("OpCapability Shader", "OpCapability Shader\nOpCapability Tessellation")
             .replace('OpEntryPoint Vertex %main "main"',
                      'OpEntryPoint TessellationEvaluation %main "main"')
             .replace("OpSource Unknown 0",
                      "OpExecutionMode %main Triangles\nOpExecutionMode %main SpacingEqual\n"
                      "OpExecutionMode %main VertexOrderCcw\nOpSource Unknown 0"),
             "\n".join(["OpCapability Shader", "OpCapability Tessellation",
                         "OpMemoryModel Logical GLSL450",
                         'OpEntryPoint TessellationEvaluation %main "main"',
                         "OpExecutionMode %main Triangles", "OpExecutionMode %main SpacingEqual",
                         "OpExecutionMode %main VertexOrderCcw", function])),
        ]
        for model, source, without_clip in cases:
            with self.subTest(model=model):
                self.assertTrue(self.entry_builtin(self.assemble_text(source), model))
                self.assertFalse(self.entry_builtin(self.assemble_text(without_clip), model))

    def test_contract_ignores_inactive_entry_and_unused_capability(self):
        inactive = "\n".join([
            "OpCapability Shader", "OpCapability ClipDistance",
            "OpMemoryModel Logical GLSL450",
            'OpEntryPoint Vertex %main "main" %position',
            'OpEntryPoint Vertex %other "other" %clip',
            "OpDecorate %position BuiltIn Position",
            "OpDecorate %clip BuiltIn ClipDistance",
            "%void = OpTypeVoid", "%function = OpTypeFunction %void",
            "%float = OpTypeFloat 32", "%two = OpConstant %float 2",
            "%uint = OpTypeInt 32 0", "%length = OpConstant %uint 2",
            "%vector = OpTypeVector %float 4", "%array = OpTypeArray %float %length",
            "%vector_ptr = OpTypePointer Output %vector",
            "%array_ptr = OpTypePointer Output %array",
            "%position = OpVariable %vector_ptr Output",
            "%clip = OpVariable %array_ptr Output",
            "%main = OpFunction %void None %function", "%main_label = OpLabel",
            "OpReturn", "OpFunctionEnd",
            "%other = OpFunction %void None %function", "%other_label = OpLabel",
            "OpReturn", "OpFunctionEnd", "",
        ])
        self.assertFalse(self.entry_builtin(self.assemble_text(inactive), 0))
        capability_only = shader("fragment").replace(
            "OpCapability Shader", "OpCapability Shader\nOpCapability ClipDistance")
        self.assertFalse(self.entry_builtin(self.assemble_text(capability_only), 4))

    def test_contract_uses_selected_entry_accesses_not_block_declarations(self):
        for model in (3, 2):
            with self.subTest(model=model, clip="unused"):
                unused = self.assemble_text(per_vertex_block_shader(model, False))
                self.validate(unused)
                self.assertFalse(self.entry_builtin(unused, model))
            with self.subTest(model=model, clip="stored"):
                stored = self.assemble_text(per_vertex_block_shader(model, True))
                self.validate(stored)
                self.assertTrue(self.entry_builtin(stored, model))

    def test_contract_ignores_unaccessed_direct_clip_interface(self):
        active = ("%first = OpAccessChain %float_pointer %clip %zero_index\n"
                  "OpStore %first %one\n"
                  "%second = OpAccessChain %float_pointer %clip %one_index\n"
                  "OpStore %second %one\n")
        source = shader("vertex").replace(active, "")
        source = source.replace(
            'OpExtension "SPV_KHR_storage_buffer_storage_class"',
            'OpExtension "SPV_KHR_storage_buffer_storage_class"\n'
            'OpExtension "SPV_GOOGLE_hlsl_functionality1"')
        source = source.replace(
            "OpDecorate %clip BuiltIn ClipDistance",
            'OpDecorate %clip BuiltIn ClipDistance\n'
            'OpDecorateStringGOOGLE %clip HlslSemanticGOOGLE "SV_ClipDistance"')
        unused = self.assemble_text(source)
        self.validate(unused)
        self.assertFalse(self.entry_builtin(unused, 0))

    def test_contract_does_not_treat_switch_or_extinst_literals_as_variable_uses(self):
        source = "\n".join([
            "OpCapability Shader", "OpCapability ClipDistance",
            '%20 = OpExtInstImport "GLSL.std.450"',
            "OpMemoryModel Logical GLSL450", 'OpEntryPoint Vertex %1 "main" %2 %3',
            "OpDecorate %2 BuiltIn ClipDistance", "OpDecorate %3 BuiltIn Position",
            "%4 = OpTypeVoid", "%5 = OpTypeFunction %4", "%6 = OpTypeFloat 32",
            "%7 = OpTypeInt 32 0", "%8 = OpConstant %7 0", "%9 = OpConstant %6 1",
            "%19 = OpConstant %7 2",
            "%10 = OpTypeVector %6 4", "%11 = OpConstantComposite %10 %9 %9 %9 %9",
            "%12 = OpTypeArray %6 %19", "%13 = OpTypePointer Output %12",
            "%14 = OpTypePointer Output %10", "%2 = OpVariable %13 Output",
            "%3 = OpVariable %14 Output", "%1 = OpFunction %4 None %5",
            "%15 = OpLabel", "OpStore %3 %11",
            # ExtInst instruction 2 also deliberately equals the unused variable ID.
            "%21 = OpExtInst %6 %20 RoundEven %9", "OpSelectionMerge %18 None",
            # Literal case value 2 deliberately equals the unused %2 variable ID.
            "OpSwitch %8 %18 2 %17", "%17 = OpLabel", "OpBranch %18",
            "%18 = OpLabel", "OpReturn", "OpFunctionEnd", "",
        ])
        module = self.assemble_text(source, preserve_ids=True)
        self.validate(module)
        self.assertFalse(self.entry_builtin(module, 0))

    def test_dispatch_guard_executes_before_downstream_creation(self):
        fixtures = {
            "active": per_vertex_block_shader(3, True),
            "unused": per_vertex_block_shader(3, False),
            "vertex": shader("vertex"),
            "fragment": shader("fragment"),
        }
        paths = []
        for name, source in fixtures.items():
            data = self.assemble_text(source)
            self.validate(data)
            path = self.directory / f"dispatch-{name}.spv"
            path.write_bytes(data)
            paths.append(path)
        ran = subprocess.run([str(self.dispatch), *(str(path) for path in paths)],
                             capture_output=True, text=True, timeout=30)
        self.assertEqual(ran.returncode, 0, ran.stdout + ran.stderr)
        self.assertIn("hybris clip dispatch contract ok", ran.stdout)

    def test_clip_policy_blocks_only_pipeline_bypasses(self):
        dispatch = (self.source / "shader_dispatch.c").read_text()
        self.assertIn("hybris_clip_command_allowed(name)", dispatch)
        clip_policy = (self.source / "clip_distance.c").read_text()
        command_policy = clip_policy[
            clip_policy.index("int hybris_clip_command_allowed"):
            clip_policy.index("void hybris_clip_filter_features")]
        self.assertIn("vkCreateShadersEXT", command_policy)
        self.assertNotIn("vkCmdSetVertexInputEXT", command_policy)


if __name__ == "__main__":
    unittest.main()
