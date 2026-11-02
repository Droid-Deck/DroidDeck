"""Compile and exercise the pinned libhybris MaliBridge distance lowering."""

import ctypes
import os
from pathlib import Path
import shutil
import struct
import subprocess
import tempfile
import unittest
from unittest import mock

if __package__:
    from .test_hybris_vendor_compat import vulkan_include
else:
    from test_hybris_vendor_compat import vulkan_include

ROOT = Path(__file__).resolve().parents[2]
WORD = ctypes.c_uint32
WORD_POINTER = ctypes.POINTER(WORD)


def strict_c_flags_from_macros(macros):
    flags = ["-std=gnu11", "-Wall", "-Wextra", "-Werror", "-Wno-unused-parameter"]
    names = {line.split()[1] for line in macros.splitlines()
             if line.startswith("#define ") and len(line.split()) >= 2}
    if "__clang__" in names:
        return flags + ["-Wno-tautological-constant-out-of-range-compare"]
    if "__GNUC__" in names:
        return flags + ["-Wno-type-limits"]
    return flags


def strict_c_flags(compiler):
    macros = subprocess.run([compiler, "-dM", "-E", "-x", "c", "-"], input="",
                            capture_output=True, text=True, check=True).stdout
    return strict_c_flags_from_macros(macros)


class StrictCompilerFlagsTest(unittest.TestCase):
    def test_clang_wins_over_gcc_compatibility_macros(self):
        self.assertIn("-Wno-tautological-constant-out-of-range-compare",
                      strict_c_flags_from_macros("#define __clang__ 1\n#define __GNUC__ 4\n"))
        self.assertNotIn("-Wno-type-limits",
                         strict_c_flags_from_macros("#define __clang__ 1\n#define __GNUC__ 4\n"))

    def test_gcc_preprocessor_macros_select_gcc_warning_name(self):
        self.assertIn("-Wno-type-limits", strict_c_flags_from_macros("#define __GNUC__ 13\n"))

    def test_unidentified_compiler_keeps_strict_defaults(self):
        self.assertNotIn("-Wno-type-limits", strict_c_flags_from_macros("#define __STDC__ 1\n"))

    @mock.patch.object(subprocess, "run")
    def test_generic_ubuntu_cc_is_classified_from_preprocessor_not_program_name(self, run):
        run.return_value = subprocess.CompletedProcess([], 0, "#define __GNUC__ 13\n", "")
        flags = strict_c_flags("cc")
        self.assertIn("-Wno-type-limits", flags)
        self.assertEqual(run.call_args.args[0], ["cc", "-dM", "-E", "-x", "c", "-"])


class DistanceCode(ctypes.Structure):
    _fields_ = [("code", WORD_POINTER), ("size", ctypes.c_size_t),
                ("cull_location", ctypes.c_uint32), ("cull_count", ctypes.c_uint32),
                ("cull_used", ctypes.c_int)]


class DistanceStage(ctypes.Structure):
    _fields_ = [("stage", ctypes.c_uint32), ("entry", ctypes.c_char_p),
                ("code", WORD_POINTER), ("size", ctypes.c_size_t)]


def instructions(data):
    words = struct.unpack(f"<{len(data) // 4}I", data)
    cursor = 5
    while cursor < len(words):
        count = words[cursor] >> 16
        assert count and cursor + count <= len(words)
        yield words[cursor] & 0xffff, words[cursor + 1:cursor + count]
        cursor += count


def shader(stage):
    vertex = stage == "vertex"
    return "\n".join([
        "OpCapability Shader", "OpCapability ClipDistance" if vertex else "",
        "OpMemoryModel Logical GLSL450",
        ('OpEntryPoint Vertex %main "main" %clip %position' if vertex else
         'OpEntryPoint Fragment %main "main" %color'),
        "" if vertex else "OpExecutionMode %main OriginUpperLeft",
        "OpSource Unknown 0", 'OpSourceContinued "distance regression fixture"',
        'OpName %main "main"', 'OpModuleProcessed "MaliBridge distance regression"',
        ("OpDecorate %clip BuiltIn ClipDistance\nOpDecorate %position BuiltIn Position"
         if vertex else "OpDecorate %color Location 0"),
        "%void = OpTypeVoid", "%function = OpTypeFunction %void",
        "%float = OpTypeFloat 32", "%uint = OpTypeInt 32 0",
        "%zero_index = OpConstant %uint 0", "%one_index = OpConstant %uint 1",
        "%two = OpConstant %uint 2", "%one = OpConstant %float 1",
        "%vector = OpTypeVector %float 4",
        "%rgba = OpConstantComposite %vector %one %one %one %one",
        "%vector_pointer = OpTypePointer Output %vector",
        ("%array = OpTypeArray %float %two\n%array_pointer = OpTypePointer Output %array\n"
         "%float_pointer = OpTypePointer Output %float\n%clip = OpVariable %array_pointer Output\n"
         "%position = OpVariable %vector_pointer Output" if vertex else
         "%color = OpVariable %vector_pointer Output"),
        "%main = OpFunction %void None %function", "%entry = OpLabel",
        ("%first = OpAccessChain %float_pointer %clip %zero_index\nOpStore %first %one\n"
         "%second = OpAccessChain %float_pointer %clip %one_index\nOpStore %second %one\n"
         "OpStore %position %rgba" if vertex else "OpStore %color %rgba"),
        "OpReturn", "OpFunctionEnd", "",
    ])


def cull_vertex_shader(include_clip=True):
    interfaces = "%cull %position" + (" %clip" if include_clip else "")
    capabilities = ["OpCapability Shader", "OpCapability CullDistance"]
    decorations = ["OpDecorate %cull BuiltIn CullDistance",
                   "OpDecorate %position BuiltIn Position"]
    variables = ["%cull = OpVariable %array_pointer Output",
                 "%position = OpVariable %vector_pointer Output"]
    stores = ["%cull_first = OpAccessChain %float_pointer %cull %zero_index",
              "OpStore %cull_first %one",
              "%cull_second = OpAccessChain %float_pointer %cull %one_index",
              "OpStore %cull_second %one"]
    if include_clip:
        capabilities.append("OpCapability ClipDistance")
        decorations.append("OpDecorate %clip BuiltIn ClipDistance")
        variables.append("%clip = OpVariable %array_pointer Output")
        stores += ["%clip_first = OpAccessChain %float_pointer %clip %zero_index",
                   "OpStore %clip_first %one",
                   "%clip_second = OpAccessChain %float_pointer %clip %one_index",
                   "OpStore %clip_second %one"]
    stores.append("OpStore %position %rgba")
    return "\n".join([
        *capabilities, "OpMemoryModel Logical GLSL450",
        f'OpEntryPoint Vertex %main "main" {interfaces}',
        *decorations,
        "%void = OpTypeVoid", "%function = OpTypeFunction %void",
        "%float = OpTypeFloat 32", "%uint = OpTypeInt 32 0",
        "%zero_index = OpConstant %uint 0", "%one_index = OpConstant %uint 1",
        "%two = OpConstant %uint 2", "%one = OpConstant %float 1",
        "%vector = OpTypeVector %float 4",
        "%rgba = OpConstantComposite %vector %one %one %one %one",
        "%array = OpTypeArray %float %two", "%array_pointer = OpTypePointer Output %array",
        "%float_pointer = OpTypePointer Output %float",
        "%vector_pointer = OpTypePointer Output %vector", *variables,
        "%main = OpFunction %void None %function", "%entry = OpLabel", *stores,
        "OpReturn", "OpFunctionEnd", "",
    ])


def per_vertex_block_shader(model):
    stage = "Geometry" if model == 3 else "TessellationEvaluation"
    capability = "Geometry" if model == 3 else "Tessellation"
    modes = (["OpExecutionMode %main InputPoints", "OpExecutionMode %main OutputPoints",
              "OpExecutionMode %main OutputVertices 1"] if model == 3 else
             ["OpExecutionMode %main Triangles", "OpExecutionMode %main SpacingEqual",
              "OpExecutionMode %main VertexOrderCcw"])
    body = ["%position_ptr = OpAccessChain %vector_pointer %out %zero_index",
            "OpStore %position_ptr %rgba",
            "%clip_ptr = OpAccessChain %float_pointer %out %one_index %zero_index",
            "OpStore %clip_ptr %one"]
    if model == 3:
        body += ["OpEmitVertex", "OpEndPrimitive"]
    return "\n".join([
        "OpCapability Shader", f"OpCapability {capability}", "OpCapability ClipDistance",
        "OpMemoryModel Logical GLSL450", f'OpEntryPoint {stage} %main "main" %out', *modes,
        "OpMemberDecorate %per_vertex 0 BuiltIn Position",
        "OpMemberDecorate %per_vertex 1 BuiltIn ClipDistance", "OpDecorate %per_vertex Block",
        "%void = OpTypeVoid", "%function = OpTypeFunction %void", "%float = OpTypeFloat 32",
        "%uint = OpTypeInt 32 0", "%zero_index = OpConstant %uint 0",
        "%one_index = OpConstant %uint 1", "%two = OpConstant %uint 2",
        "%one = OpConstant %float 1", "%vector = OpTypeVector %float 4",
        "%rgba = OpConstantComposite %vector %one %one %one %one",
        "%clip_array = OpTypeArray %float %two", "%per_vertex = OpTypeStruct %vector %clip_array",
        "%block_pointer = OpTypePointer Output %per_vertex", "%vector_pointer = OpTypePointer Output %vector",
        "%float_pointer = OpTypePointer Output %float", "%out = OpVariable %block_pointer Output",
        "%main = OpFunction %void None %function", "%entry = OpLabel", *body,
        "OpReturn", "OpFunctionEnd", "",
    ])


class HybrisClipShaderTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        source_value = os.environ.get("HYBRIS_CLIP_SOURCE")
        if not source_value:
            raise unittest.SkipTest("set HYBRIS_CLIP_SOURCE to the pinned libhybris checkout")
        cls.compiler = shutil.which(os.environ.get("CC", "cc"))
        cls.assembler = shutil.which("spirv-as")
        cls.validator = shutil.which("spirv-val")
        if not all((cls.compiler, cls.assembler, cls.validator)):
            raise RuntimeError("HYBRIS_CLIP_SOURCE requires a C compiler and spirv-tools")
        pristine = Path(source_value)
        if not (pristine / "hybris/vulkan/compat/malibridge/spirv_clip.c").is_file():
            raise RuntimeError("HYBRIS_CLIP_SOURCE must point to the pinned libhybris checkout")
        cls.temporary = tempfile.TemporaryDirectory(prefix="droiddeck-distance-shader-")
        cls.addClassCleanup(cls.temporary.cleanup)
        cls.directory = Path(cls.temporary.name)
        checkout = cls.directory / "libhybris"
        subprocess.run(["git", "clone", "-q", "--no-hardlinks", str(pristine), str(checkout)], check=True)
        for patch in sorted((ROOT / "tools/hybris/patches").glob("*.patch")):
            subprocess.run(["git", "-C", str(checkout), "apply", "--check", str(patch)], check=True)
            subprocess.run(["git", "-C", str(checkout), "apply", str(patch)], check=True)
        source = checkout / "hybris/vulkan/compat"
        allocator = cls.directory / "allocator.c"
        allocator.write_text(
            "#include <vulkan/vulkan.h>\n#include <stdlib.h>\n"
            "void *hybris_scaled_alloc(const VkAllocationCallbacks *a, size_t n, "
            "VkSystemAllocationScope s) { (void)a; (void)s; return malloc(n); }\n"
            "void hybris_scaled_free(const VkAllocationCallbacks *a, void *p) "
            "{ (void)a; free(p); }\n")
        library = cls.directory / "distance.so"
        built = subprocess.run([
            cls.compiler, "-shared", "-fPIC", *strict_c_flags(cls.compiler),
            "-I", str(vulkan_include()), "-I", str(source),
            str(source / "distance_lowering.c"), str(source / "spirv_entry.c"),
            str(source / "spirv_decorations.c"), str(source / "spirv_builtins.c"),
            str(source / "primitive_adapter.c"),
            *(str(source / "malibridge" / name) for name in
              ("spirv_edit.c", "spirv_clip.c", "spirv_pervertex.c")),
            str(allocator), "-o", str(library)], capture_output=True, text=True)
        if built.returncode:
            raise RuntimeError(built.stdout + built.stderr)
        cls.library = ctypes.CDLL(str(library))
        cls.library.hybris_distance_module.argtypes = [
            ctypes.c_uint, ctypes.c_int, WORD_POINTER, ctypes.c_size_t, ctypes.c_void_p,
            ctypes.POINTER(DistanceCode), ctypes.POINTER(ctypes.c_char_p)]
        cls.library.hybris_distance_pipeline.argtypes = [
            ctypes.c_uint, ctypes.c_int, ctypes.c_uint, ctypes.POINTER(DistanceStage),
            ctypes.c_void_p, ctypes.POINTER(DistanceCode), ctypes.POINTER(ctypes.c_char_p)]
        cls.library.hybris_spirv_primitive_adapter_topology.argtypes = [
            WORD_POINTER, ctypes.c_size_t, ctypes.c_uint32, ctypes.c_uint32,
            ctypes.c_uint32, ctypes.c_int, ctypes.c_void_p, ctypes.c_void_p,
            ctypes.POINTER(WORD_POINTER), ctypes.POINTER(ctypes.c_size_t),
            ctypes.POINTER(ctypes.c_char_p)]
        cls.library.hybris_scaled_free.argtypes = [ctypes.c_void_p, ctypes.c_void_p]

    def assemble(self, stage):
        text = self.directory / f"{stage}.spvasm"
        binary = self.directory / f"{stage}.spv"
        text.write_text(shader(stage))
        subprocess.run([self.assembler, "--target-env", "vulkan1.1", str(text), "-o", str(binary)],
                       check=True, capture_output=True, text=True)
        data = binary.read_bytes()
        self.validate(data)
        return data

    def assemble_text(self, name, source):
        text = self.directory / f"{name}.spvasm"
        binary = self.directory / f"{name}.spv"
        text.write_text(source)
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

    def convert_module(self, data):
        code = (WORD * (len(data) // 4)).from_buffer_copy(data)
        out, reason = DistanceCode(), ctypes.c_char_p()
        result = self.library.hybris_distance_module(
            1, 0, code, len(data), None, ctypes.byref(out), ctypes.byref(reason))
        self.assertEqual(result, 0, reason.value.decode() if reason.value else "")
        try:
            self.assertTrue(out.code and out.size)
            return ctypes.string_at(out.code, out.size)
        finally:
            self.library.hybris_scaled_free(None, out.code)

    def test_module_demotes_clip_and_preserves_logical_layout(self):
        original = self.assemble("vertex")
        stream = list(instructions(original))
        main = next(operands[1] for op, operands in stream if op == 15)
        float_type = next(operands[0] for op, operands in stream if op == 22)
        zero = next(operands[1] for op, operands in stream if op == 43 and operands[2] == 0)
        mode = (main, 6028, float_type, zero)
        position = max(i for i, (op, _) in enumerate(stream) if op in (15, 16)) + 1
        stream.insert(position, (331, mode))
        words = list(struct.unpack("<5I", original[:20]))
        for opcode, operands in stream:
            words.extend([((len(operands) + 1) << 16) | opcode, *operands])
        converted = self.convert_module(struct.pack(f"<{len(words)}I", *words))
        emitted = list(instructions(converted))
        self.assertFalse(any(op == 17 and operands == (32,) for op, operands in emitted))
        self.assertFalse(any(op == 71 and operands[1:] == (11, 3) for op, operands in emitted))
        self.assertTrue(any(op == 71 and operands[1] == 30 for op, operands in emitted))
        annotation = next(i for i, (op, _) in enumerate(emitted) if op in (71, 72))
        for opcode in (2, 330, 331):
            self.assertLess(next(i for i, (op, _) in enumerate(emitted) if op == opcode), annotation)

    def test_pipeline_adds_fragment_discard(self):
        data = [self.assemble("vertex"), self.assemble("fragment")]
        words = [(WORD * (len(item) // 4)).from_buffer_copy(item) for item in data]
        stages = (DistanceStage * 2)(
            DistanceStage(1, b"main", words[0], len(data[0])),
            DistanceStage(16, b"main", words[1], len(data[1])))
        outputs, reason = (DistanceCode * 2)(), ctypes.c_char_p()
        result = self.library.hybris_distance_pipeline(
            1, 0, 2, stages, None, outputs, ctypes.byref(reason))
        self.assertEqual(result, 0, reason.value.decode() if reason.value else "")
        try:
            converted = [ctypes.string_at(item.code, item.size) for item in outputs]
            for item in converted:
                self.validate(item)
            self.assertTrue(any(op in (252, 4416) for op, _ in instructions(converted[1])))
        finally:
            for item in outputs:
                self.library.hybris_scaled_free(None, item.code)

    def test_geometry_and_tessellation_blocks_are_demoted(self):
        for model in (3, 2):
            with self.subTest(model=model):
                converted = self.convert_module(
                    self.assemble_text(f"block-{model}", per_vertex_block_shader(model)))
                self.validate(converted)
                emitted = list(instructions(converted))
                self.assertFalse(any(op == 72 and operands[2:] == (11, 3)
                                     for op, operands in emitted))
                self.assertTrue(any(op == 71 and operands[1] == 30 for op, operands in emitted))

    def test_cull_metadata_generates_exact_point_line_and_triangle_stages(self):
        vertex = self.assemble_text("clip-cull", cull_vertex_shader(include_clip=True))
        fragment = self.assemble("fragment")
        words = [(WORD * (len(item) // 4)).from_buffer_copy(item) for item in (vertex, fragment)]
        stages = (DistanceStage * 2)(
            DistanceStage(1, b"main", words[0], len(vertex)),
            DistanceStage(16, b"main", words[1], len(fragment)))
        outputs, reason = (DistanceCode * 2)(), ctypes.c_char_p()
        result = self.library.hybris_distance_pipeline(
            3, 0, 2, stages, None, outputs, ctypes.byref(reason))
        self.assertEqual(result, 0, reason.value.decode() if reason.value else "")
        try:
            self.assertEqual(outputs[0].cull_used, 1)
            self.assertEqual(outputs[0].cull_count, 2)
            self.assertGreater(outputs[0].cull_location, 0)
            self.assertTrue(any(op in (252, 4416) for op, _ in
                                instructions(ctypes.string_at(outputs[1].code, outputs[1].size))))
            lowered = ctypes.string_at(outputs[0].code, outputs[0].size)
            self.assertFalse(any(op == 71 and operands[1:] == (11, 3)
                                 for op, operands in instructions(lowered)))
            for topology in (0, 1, 3):
                with self.subTest(topology=topology):
                    geometry, size, adapter_reason = WORD_POINTER(), ctypes.c_size_t(), ctypes.c_char_p()
                    result = self.library.hybris_spirv_primitive_adapter_topology(
                        (WORD * (len(lowered) // 4)).from_buffer_copy(lowered), len(lowered),
                        outputs[0].cull_location, outputs[0].cull_count, topology, 0, None, None,
                        ctypes.byref(geometry), ctypes.byref(size), ctypes.byref(adapter_reason))
                    self.assertEqual(result, 0, adapter_reason.value.decode() if adapter_reason.value else "")
                    try:
                        generated = ctypes.string_at(geometry, size.value)
                        self.validate(generated)
                        self.assertTrue(any(op == 17 and operands == (2,)
                                            for op, operands in instructions(generated)))
                    finally:
                        self.library.hybris_scaled_free(None, geometry)
        finally:
            for item in outputs:
                self.library.hybris_scaled_free(None, item.code)

    def test_cull_adapter_rejects_unsupported_topology_without_output(self):
        vertex = self.assemble_text("cull-only", cull_vertex_shader(include_clip=False))
        geometry, size, reason = WORD_POINTER(), ctypes.c_size_t(), ctypes.c_char_p()
        result = self.library.hybris_spirv_primitive_adapter_topology(
            (WORD * (len(vertex) // 4)).from_buffer_copy(vertex), len(vertex),
            1, 2, 10, 0, None, None, ctypes.byref(geometry), ctypes.byref(size), ctypes.byref(reason))
        self.assertEqual(result, -8)
        self.assertFalse(geometry)
        self.assertEqual(size.value, 0)

    def test_cull_pipeline_appends_geometry_and_rejects_unsafe_state(self):
        vertex = self.assemble_text("cull-dispatch-vertex", cull_vertex_shader(include_clip=False))
        fragment = self.assemble_text("cull-dispatch-fragment", shader("fragment"))
        geometry = self.assemble_text("cull-dispatch-geometry", per_vertex_block_shader(3))
        paths = []
        for name, data in (("vertex", vertex), ("fragment", fragment), ("geometry", geometry)):
            path = self.directory / f"cull-dispatch-{name}.spv"
            path.write_bytes(data)
            paths.append(path)
        source = self.directory / "libhybris/hybris/vulkan/compat"
        helper = ROOT / "tools/tests/hybris_cull_dispatch_test.c"
        program = self.directory / "cull-dispatch-test"
        built = subprocess.run([
            self.compiler, *strict_c_flags(self.compiler),
            "-I", str(vulkan_include()), "-I", str(source), str(helper),
            *(str(source / name) for name in
              ("distance_lowering.c", "spirv_entry.c", "spirv_decorations.c", "spirv_builtins.c",
               "primitive_adapter.c")),
            *(str(source / "malibridge" / name) for name in
              ("spirv_edit.c", "spirv_clip.c", "spirv_pervertex.c")),
            str(self.directory / "allocator.c"), "-o", str(program)],
            capture_output=True, text=True)
        self.assertEqual(built.returncode, 0, built.stdout + built.stderr)
        ran = subprocess.run([str(program), *(str(path) for path in paths)],
                             capture_output=True, text=True, timeout=30)
        self.assertEqual(ran.returncode, 0, ran.stdout + ran.stderr)
        self.assertIn("hybris clip dispatch contract ok", ran.stdout)


if __name__ == "__main__":
    unittest.main()
