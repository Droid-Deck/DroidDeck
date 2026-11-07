import copy
from contextlib import redirect_stdout
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest


spec = importlib.util.spec_from_file_location(
    "capability_report", Path(__file__).resolve().parents[1] / "vulkan/capability_report.py")
report = importlib.util.module_from_spec(spec)
spec.loader.exec_module(report)
FIXTURES = Path(__file__).resolve().parent / "fixtures/vulkan-capabilities"


IDENTITY = {
    "vendorID": 0x13B5,
    "deviceID": 0xB8A20000,
    "deviceName": "Mali-G715",
    "driverVersion": 0x0D803000,
    "deviceType": "VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU",
    "apiVersion": 4211031,
    "pipelineCacheUUID": list(range(16)),
}
FORMAT_FLAGS = [
    "VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT",
    "VK_FORMAT_FEATURE_TRANSFER_SRC_BIT",
    "VK_FORMAT_FEATURE_TRANSFER_DST_BIT",
]


def profiles(extension=True):
    return {
        "$schema": "https://schema.khronos.org/vulkan/profiles-0.8-latest.json",
        "capabilities": {"device": {
            "extensions": {"VK_EXT_example": 2} if extension else {},
            "features": {
                "VkPhysicalDeviceFeatures": {"robustBufferAccess": True},
                "VkPhysicalDeviceDynamicRenderingFeaturesKHR": {"dynamicRendering": True},
                "VkPhysicalDeviceShaderDrawParametersFeatures": {"shaderDrawParameters": True},
            },
            "properties": {
                "VkPhysicalDeviceProperties": {
                    **IDENTITY,
                    "limits": {
                        "minMemoryMapAlignment": 64,
                        "lineWidthRange": [1, 4095.94],
                        "timestampPeriod": 40.6901,
                        "sampledImageColorSampleCounts": [
                            "VK_SAMPLE_COUNT_1_BIT", "VK_SAMPLE_COUNT_4_BIT",
                            "VK_SAMPLE_COUNT_8_BIT", "VK_SAMPLE_COUNT_16_BIT"],
                    },
                },
                "VkPhysicalDevicePipelineRobustnessProperties": {
                    "defaultRobustnessImages": "VK_PIPELINE_ROBUSTNESS_IMAGE_BEHAVIOR_ROBUST_IMAGE_ACCESS"},
                "VkPhysicalDeviceLineRasterizationPropertiesKHR": {"lineSubPixelPrecisionBits": 8},
                "VkPhysicalDeviceCooperativeMatrixPropertiesKHR": {
                    "cooperativeMatrixSupportedStages": ["VK_SHADER_STAGE_COMPUTE_BIT"]},
                "VkPhysicalDeviceDescriptorBufferPropertiesEXT": {"samplerDescriptorSize": 32},
            },
            "formats": {
                "VK_FORMAT_ASTC_4x4_SFLOAT_BLOCK_EXT": {
                    "VkFormatProperties": {"optimalTilingFeatures": FORMAT_FLAGS}},
                "VK_FORMAT_G8B8G8R8_422_UNORM_KHR": {
                    "VkFormatProperties": {"optimalTilingFeatures": FORMAT_FLAGS}},
            },
            "queueFamiliesProperties": [],
        }},
        "profiles": {"fixture": {"api-version": "1.3.0"}},
    }


def vkjson(extension=True):
    return {
        "apiVersion": 1,
        "devices": [{
            "extensions": ([{"extensionName": "VK_EXT_example", "specVersion": 2}] if extension else []),
            "features": {"robustBufferAccess": 1.0},
            "properties": {
                **{key: value for key, value in IDENTITY.items() if key != "deviceType"},
                "deviceType": 1.0,
                "driverVersion": "0x000000000d803000",
                "limits": {
                    "minMemoryMapAlignment": "0x0000000000000040",
                    "lineWidthRange": [1.0, 4095.9375],
                    "timestampPeriod": 40.690105438232422,
                    "sampledImageColorSampleCounts": 29.0,
                },
            },
            "formats": [
                [1000066000.0, {"linearTilingFeatures": 0.0, "optimalTilingFeatures": 49153.0,
                                "bufferFeatures": 0.0}],
                [1000156000.0, {"linearTilingFeatures": 0.0, "optimalTilingFeatures": 49153.0,
                                "bufferFeatures": 0.0}],
                [9.0, {"linearTilingFeatures": 0.0, "optimalTilingFeatures": 0.0,
                       "bufferFeatures": 0.0}],
            ],
            "core11": {"features": {"shaderDrawParameters": 1}},
            "core13": {"features": {"dynamicRendering": 1}},
            "core14": {"properties": {"defaultRobustnessImages": 2, "lineSubPixelPrecisionBits": 8}},
            "cooperativeMatrixPropertiesKHR": {"cooperativeMatrixSupportedStages": 32},
            "descriptorBufferPropertiesEXT": {"samplerDescriptorSize": "0x20"},
        }],
    }


class VulkanCapabilityReportTest(unittest.TestCase):
    def test_sanitized_historical_three_capture_corpus(self):
        native, bare, compat = (
            report.load_view(FIXTURES / name) for name in ("native.json", "bare.json", "compat.json"))
        views = (native, bare, compat)
        self.assertEqual([len(view["unsupported"]) for view in views], [0, 0, 0])
        self.assertEqual([len(view["unknown"]) for view in views], [0, 0, 0])
        self.assertEqual([len({path.split(".")[1] for path in view["formats"]}) for view in views],
                         [187, 187, 202])
        self.assertEqual([len(view["input_inconsistencies"]) for view in views], [13, 0, 0])

        result = report.compare_views(native, bare, compat)
        summaries = {mode: comparison["summary"] for mode, comparison in result["comparisons"].items()}
        self.assertEqual(summaries["native-android->glibc-bare"]["unexpected_count"], 14)
        self.assertEqual(summaries["native-android->glibc-bare"]["unexpected_by_group"], {"extensions": 14})
        self.assertEqual(summaries["glibc-bare->glibc-compat"]["unexpected_count"], 26)
        self.assertEqual(summaries["glibc-bare->glibc-compat"]["unexpected_by_group"], {
            "extensions": 6, "features": 2, "formats": 15, "limits": 2, "properties": 1})
        self.assertEqual([summaries[mode]["coverage_unknown_group_count"] for mode in report.MODES],
                         [28, 13, 40])
        self.assertFalse(result["differences_ok"])
        self.assertFalse(result["coverage_complete"])
        self.assertFalse(result["ok"])
        for comparison in result["comparisons"].values():
            for change in comparison["unexpected"]:
                self.assertEqual(change["owner"], "unavailable")

    def test_registry_includes_extended_formats_promoted_flags_and_core14_aliases(self):
        self.assertEqual(report.FORMAT_NAMES[1000066000], "VK_FORMAT_ASTC_4x4_SFLOAT_BLOCK_EXT")
        self.assertEqual(report.FORMAT_NAMES[1000156000], "VK_FORMAT_G8B8G8R8_422_UNORM_KHR")
        flags = report.ENUM_TABLES["VkFormatFeatureFlagBits"]
        self.assertEqual(flags["VK_FORMAT_FEATURE_TRANSFER_SRC_BIT"], 16384)
        self.assertEqual(flags["VK_FORMAT_FEATURE_TRANSFER_DST_BIT"], 32768)
        self.assertIn("VkPhysicalDeviceVulkan14Properties.defaultRobustnessImages", report.MEMBER_ALIASES)
        self.assertIn("VkPhysicalDeviceVulkan14Properties.lineSubPixelPrecisionBits", report.MEMBER_ALIASES)

    def test_typed_values_normalize_enums_flags_hex_bool_and_bytes(self):
        view = report.normalize_view(vkjson())
        self.assertEqual(view["identity"]["deviceID"], 0xB8A20000)
        self.assertEqual(view["properties"]["base.deviceType"], "VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU")
        self.assertEqual(view["properties"]["base.pipelineCacheUUID"], "hex:000102030405060708090a0b0c0d0e0f")
        self.assertEqual(view["properties"]["descriptorBufferPropertiesEXT.samplerDescriptorSize"], 32)
        self.assertEqual(view["limits"]["minMemoryMapAlignment"], 64)
        self.assertEqual(view["limits"]["sampledImageColorSampleCounts"], [
            "VK_SAMPLE_COUNT_1_BIT", "VK_SAMPLE_COUNT_4_BIT",
            "VK_SAMPLE_COUNT_8_BIT", "VK_SAMPLE_COUNT_16_BIT"])
        self.assertEqual(view["properties"]["cooperativeMatrixPropertiesKHR.cooperativeMatrixSupportedStages"],
                         ["VK_SHADER_STAGE_COMPUTE_BIT"])
        self.assertIs(view["features"]["base.robustBufferAccess"], True)

    def test_real_shaped_producers_are_semantically_equivalent(self):
        native = report.normalize_view(vkjson())
        bare = report.normalize_view(profiles())
        result = report.compare_views(native, bare, copy.deepcopy(bare))
        self.assertTrue(result["differences_ok"])
        self.assertTrue(result["coverage_complete"])
        self.assertTrue(result["ok"])
        self.assertEqual(result["comparisons"]["native-android->glibc-bare"]["unexpected"], [])
        self.assertEqual(result["comparisons"]["native-android->glibc-bare"]["coverage_unknown"], [])

    def test_all_zero_vkjson_format_is_equivalent_to_absent_profile_format(self):
        native = report.normalize_view(vkjson())
        bare = report.normalize_view(profiles())
        self.assertFalse(any("VK_FORMAT_R8_UNORM" in path for path in native["formats"]))
        result = report.compare_views(native, bare, copy.deepcopy(bare))
        self.assertFalse(any(change["path"].startswith("formats.format.VK_FORMAT_R8_UNORM")
                             for change in result["comparisons"]["native-android->glibc-bare"]["unexpected"]))

    def test_sparse_properties_have_one_equivalent_path_across_producers(self):
        native_capture, profile_capture = vkjson(), profiles()
        sparse = {"residencyStandard2DBlockShape": True, "residencyNonResidentStrict": False}
        native_capture["devices"][0]["properties"]["sparseProperties"] = sparse
        profile_capture["capabilities"]["device"]["properties"]["VkPhysicalDeviceProperties"]["sparseProperties"] = sparse
        native, bare = report.normalize_view(native_capture), report.normalize_view(profile_capture)
        self.assertTrue(report.compare_views(native, bare, copy.deepcopy(bare))["ok"])
        self.assertFalse(any(path.startswith("sparseProperties.") for path in native["coverage"]["properties"]))

    def test_missing_extended_format_capture_is_unknown_in_both_directions(self):
        capture = profiles()
        capture["capabilities"]["device"]["formats"]["VK_FORMAT_ASTC_4x4_SFLOAT_BLOCK_EXT"]["VkFormatProperties3"] = {
            "optimalTilingFeatures": ["VK_FORMAT_FEATURE_2_SAMPLED_IMAGE_BIT"],
        }
        base, extended = report.normalize_view(vkjson()), report.normalize_view(capture)
        for source, target in ((base, extended), (extended, base)):
            with self.subTest(source_has_extended=source is extended):
                result = report.compare_views(source, target, copy.deepcopy(target))
                comparison = result["comparisons"]["native-android->glibc-bare"]
                self.assertEqual(comparison["unexpected"], [])
                self.assertEqual([item["path"] for item in comparison["coverage_unknown"]], [
                    "formats.format.VK_FORMAT_ASTC_4x4_SFLOAT_BLOCK.formatProperties3.optimalTilingFeatures",
                ])
                self.assertTrue(result["differences_ok"])
                self.assertFalse(result["coverage_complete"])
                self.assertFalse(result["ok"])

    def test_one_semantic_change_produces_exactly_one_known_difference(self):
        native = report.normalize_view(vkjson())
        changed = profiles()
        changed["capabilities"]["device"]["features"]["VkPhysicalDeviceFeatures"]["robustBufferAccess"] = False
        bare = report.normalize_view(changed)
        result = report.compare_views(native, bare, copy.deepcopy(bare))
        differences = result["comparisons"]["native-android->glibc-bare"]["unexpected"]
        self.assertEqual(differences, [{
            "mode": "native-android->glibc-bare",
            "path": "features.base.robustBufferAccess",
            "transition": "changed",
            "before": True,
            "after": False,
            "expected": False,
            "owner": "unavailable",
        }])

    def test_boolean_policy_values_do_not_match_json_numbers(self):
        native = report.normalize_view(vkjson())
        changed = profiles()
        changed["capabilities"]["device"]["features"]["VkPhysicalDeviceFeatures"]["robustBufferAccess"] = False
        bare = report.normalize_view(changed)
        mistyped = [{
            "mode": "native-android->glibc-bare",
            "path": "features.base.robustBufferAccess",
            "transition": "changed",
            "before": 1,
            "after": 0,
            "reason": "mistyped boolean policy",
            "owner": "compat",
        }]
        result = report.compare_views(native, bare, copy.deepcopy(bare), mistyped)
        comparison = result["comparisons"]["native-android->glibc-bare"]
        self.assertEqual(comparison["expected"], [])
        self.assertEqual(comparison["unexpected"][0]["owner"], "unavailable")
        self.assertEqual(result["unmatched_policy"], mistyped)
        self.assertFalse(result["differences_ok"])

    def test_missing_unqueried_field_is_coverage_unknown_not_capability_loss(self):
        native = report.normalize_view(vkjson())
        missing = profiles()
        del missing["capabilities"]["device"]["properties"]["VkPhysicalDeviceDescriptorBufferPropertiesEXT"]
        bare = report.normalize_view(missing)
        result = report.compare_views(native, bare, copy.deepcopy(bare))
        comparison = result["comparisons"]["native-android->glibc-bare"]
        self.assertEqual(comparison["unexpected"], [])
        self.assertEqual([item["path"] for item in comparison["coverage_unknown"]],
                         ["properties.descriptorBufferPropertiesEXT.samplerDescriptorSize"])
        self.assertTrue(result["differences_ok"])
        self.assertFalse(result["coverage_complete"])
        self.assertFalse(result["ok"])

    def test_promoted_core14_property_matches_extension_structure(self):
        native = report.normalize_view(vkjson())
        bare = report.normalize_view(profiles())
        path = "pipelineRobustnessProperties.defaultRobustnessImages"
        self.assertEqual(native["properties"][path], bare["properties"][path])

    def test_conflicting_alias_observations_are_preserved_and_fail_closed(self):
        capture = vkjson()
        device = capture["devices"][0]
        device["shaderDrawParameterFeatures"] = {"shaderDrawParameters": 0}
        device["shaderDrawParametersFeatures"] = {"shaderDrawParameters": 1}
        view = report.normalize_view(capture)
        conflict = next(item for item in view["input_inconsistencies"]
                        if item["path"] == "shaderDrawParametersFeatures.shaderDrawParameters")
        self.assertEqual([item["value"] for item in conflict["observations"]], [False, True, True])
        result = report.compare_views(view, report.normalize_view(profiles()), report.normalize_view(profiles()))
        self.assertFalse(result["coverage_complete"])
        self.assertTrue(result["input_inconsistencies"]["native-android"])

    def test_unknown_capture_field_is_preserved_without_numeric_coercion(self):
        capture = vkjson()
        capture["devices"][0]["futureProducerField"] = {"opaque": "0x20"}
        capture["futureRoot"] = {"opaque": "0x40"}
        view = report.normalize_view(capture)
        self.assertEqual(view["unknown"]["futureProducerField"], {"opaque": "0x20"})
        self.assertEqual(view["unknown"]["root.futureRoot"], {"opaque": "0x40"})
        self.assertEqual([item["path"] for item in view["unsupported"]],
                         ["device.futureProducerField", "root.futureRoot"])

    def test_malformed_known_type_fails_coverage_closed(self):
        capture = vkjson()
        capture["devices"][0]["properties"]["deviceID"] = 1.5
        view = report.normalize_view(capture)
        self.assertNotIn("deviceID", view["identity"])
        self.assertTrue(any(item["path"] == "deviceID" and item["reason"] == "expected integer registry value"
                            for item in view["unsupported"]))
        result = report.compare_views(view, report.normalize_view(profiles()), report.normalize_view(profiles()))
        self.assertFalse(result["coverage_complete"])
        self.assertFalse(result["ok"])

    def test_exact_policy_covers_all_three_modes_and_assigns_owner(self):
        native = report.normalize_view(vkjson(extension=True))
        bare = report.normalize_view(vkjson(extension=False))
        compat_capture = vkjson(extension=False)
        compat_capture["devices"][0]["extensions"] = [{"extensionName": "VK_EXT_other", "specVersion": 1}]
        compat = report.normalize_view(compat_capture)
        policy = [
            {"mode": "native-android->glibc-bare", "path": "extensions.VK_EXT_example",
             "transition": "lost", "before": 2, "after": None,
             "reason": "documented boundary", "owner": "compat"},
            {"mode": "glibc-bare->glibc-compat", "path": "extensions.VK_EXT_other",
             "transition": "gained", "before": None, "after": 1,
             "reason": "compat addition", "owner": "compat"},
            {"mode": "native-android->glibc-compat", "path": "extensions.VK_EXT_example",
             "transition": "lost", "before": 2, "after": None,
             "reason": "documented boundary", "owner": "compat"},
            {"mode": "native-android->glibc-compat", "path": "extensions.VK_EXT_other",
             "transition": "gained", "before": None, "after": 1,
             "reason": "compat addition", "owner": "compat"},
        ]
        result = report.compare_views(native, bare, compat, policy)
        self.assertTrue(result["ok"])
        self.assertEqual(result["comparisons"]["native-android->glibc-bare"]["ownership"]["compat"],
                         ["extensions.VK_EXT_example"])

    def test_api_version_is_a_policy_bound_capability_not_identity(self):
        native = report.normalize_view(vkjson())
        lowered = vkjson()
        lowered["devices"][0]["properties"]["apiVersion"] = 4206592
        bare = report.normalize_view(lowered)
        policy = []
        for mode in ("native-android->glibc-bare", "native-android->glibc-compat"):
            policy.append({"mode": mode, "path": "properties.base.apiVersion", "transition": "changed",
                           "before": 4211031, "after": 4206592,
                           "reason": "known API cap", "owner": "compat"})
        result = report.compare_views(native, bare, copy.deepcopy(bare), policy)
        self.assertEqual(result["identity_mismatches"], [])
        self.assertTrue(result["ok"])

    def test_policy_rejects_wildcards_duplicates_extra_fields_and_bad_transitions(self):
        base = {"mode": "native-android->glibc-bare", "path": "extensions.VK_EXT_example",
                "transition": "lost", "before": 2, "after": None,
                "reason": "documented", "owner": "compat"}
        with self.assertRaisesRegex(report.InputError, "wildcards"):
            self.load_policy({"expected_differences": [{**base, "path": "extensions.VK_*"}]})
        with self.assertRaisesRegex(report.InputError, "duplicate policy"):
            self.load_policy({"expected_differences": [base, copy.deepcopy(base)]})
        with self.assertRaisesRegex(report.InputError, "exactly"):
            self.load_policy({"expected_differences": [{**base, "extra": True}]})
        with self.assertRaisesRegex(report.InputError, "after null"):
            self.load_policy({"expected_differences": [{**base, "after": 1}]})
        with self.assertRaisesRegex(report.InputError, "scalar capability"):
            self.load_policy({"expected_differences": [{**base, "before": {"not": "a leaf"}}]})

    def test_duplicate_json_keys_extensions_and_ambiguous_devices_fail(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "duplicate.json"
            path.write_text('{"devices": [], "devices": []}')
            with self.assertRaisesRegex(report.InputError, "duplicate JSON key"):
                report.load_view(path)
        duplicate = vkjson()
        duplicate["devices"][0]["extensions"].append(copy.deepcopy(duplicate["devices"][0]["extensions"][0]))
        with self.assertRaisesRegex(report.InputError, "duplicate vkjson extension"):
            report.normalize_view(duplicate)
        ambiguous = vkjson()
        ambiguous["devices"].append(copy.deepcopy(ambiguous["devices"][0]))
        with self.assertRaisesRegex(report.InputError, "exactly one"):
            report.normalize_view(ambiguous)

    def test_loading_never_changes_input_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "capture.json"
            source = json.dumps(vkjson(), indent=3) + "\n"
            path.write_text(source)
            report.load_view(path)
            self.assertEqual(path.read_text(), source)

    def test_bad_json_and_missing_view_exit_nonzero(self):
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            bad = directory / "bad.json"
            bad.write_text("{")
            good = directory / "good.json"
            good.write_text(json.dumps(vkjson()))
            output = io.StringIO()
            with redirect_stdout(output):
                code = report.main(["--native", str(good), "--bare", str(good), "--compat", str(bad)])
            self.assertEqual(code, 2)
            self.assertFalse(json.loads(output.getvalue())["ok"])

    def load_policy(self, value):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "policy.json"
            path.write_text(json.dumps(value))
            return report.load_policy(path)


if __name__ == "__main__":
    unittest.main()
