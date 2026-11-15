from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]
PATCH = ROOT / "tools/gamescope/patches/0936-convar-env-bool-zero.patch"


class GamescopeConvarEnvTest(unittest.TestCase):
    def test_number_given_to_a_bool_convar_is_compared_with_zero(self):
        text = PATCH.read_text()
        self.assertIn("diff --git a/src/main.cpp b/src/main.cpp", text)
        self.assertIn("type( c.value ) == 'boolean' and type( v ) == 'number' then v = ( v ~= 0 )", text)
        self.assertIn("-\t\t\t\t\t\tauto override_script = std::format( \"gamescope.convars.{}.value = {}\", name, value );", text)


if __name__ == "__main__":
    unittest.main()
