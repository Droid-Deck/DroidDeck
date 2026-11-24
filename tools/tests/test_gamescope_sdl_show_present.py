from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
PATCH = ROOT / "tools/gamescope/patches/0141-sdl-present-only-to-a-shown-window.patch"


def hunks():
    return PATCH.read_text().split("\n@@ ")[1:]


def hunk_with(text):
    return next(hunk for hunk in hunks() if text in hunk)


def added(hunk):
    return [line[1:] for line in hunk.splitlines() if line.startswith("+") and not line.startswith("+++")]


def removed(hunk):
    return [line[1:] for line in hunk.splitlines() if line.startswith("-") and not line.startswith("---")]


def set_window_shown():
    text = "\n".join(added(hunk_with("CSDLConnector::SetWindowShown( bool bShown )\n")))
    start = text.index("void CSDLConnector::SetWindowShown")
    return text[start:text.index("\n\t}", start) + 3] + "\n"


def present_gate():
    lines = hunk_with("int CSDLConnector::Present(").splitlines()
    body = lines[next(i for i, line in enumerate(lines) if "int CSDLConnector::Present(" in line) + 1:]
    return "\n".join(line[1:] for line in body if line.startswith("+")) + "\n"


HARNESS = r'''
#include <atomic>
#include <chrono>
#include <cstdio>
#include <mutex>
#include <thread>

struct SDL_Window {};

static std::atomic<bool> g_bAwaitingConfigure{ false };
static std::atomic<bool> g_bMapped{ false };
static std::atomic<int> g_nPresentsBeforeConfigure{ 0 };
static std::atomic<int> g_nPresentsWhileHidden{ 0 };
static std::atomic<int> g_nPresents{ 0 };

static void SDL_ShowWindow( SDL_Window * )
{
	g_bAwaitingConfigure = true;
	std::this_thread::sleep_for( std::chrono::milliseconds( 3 ) );
	g_bAwaitingConfigure = false;
	g_bMapped = true;
}

static void SDL_HideWindow( SDL_Window * )
{
	g_bMapped = false;
}

static void vulkan_present_to_window()
{
	for ( int i = 0; i < 2; i++ )
	{
		if ( g_bAwaitingConfigure )
			g_nPresentsBeforeConfigure++;
		if ( !g_bMapped )
			g_nPresentsWhileHidden++;
		std::this_thread::sleep_for( std::chrono::microseconds( 100 ) );
	}
	g_nPresents++;
}

struct CSDLConnector
{
	void SetWindowShown( bool bShown );
	int Present();
	SDL_Window *m_pWindow = nullptr;
	std::mutex m_WindowShownMutex;
	bool m_bWindowShown = false;
};

SET_WINDOW_SHOWN

int CSDLConnector::Present()
{
PRESENT_GATE
	vulkan_present_to_window();
	return 0;
}

int main()
{
	CSDLConnector connector;
	std::atomic<bool> bStop{ false };
	std::thread paint( [&] { while ( !bStop ) connector.Present(); } );
	for ( int i = 0; i < 150; i++ )
	{
		connector.SetWindowShown( true );
		std::this_thread::sleep_for( std::chrono::milliseconds( 1 ) );
		connector.SetWindowShown( false );
		std::this_thread::sleep_for( std::chrono::milliseconds( 1 ) );
	}
	connector.SetWindowShown( true );
	std::this_thread::sleep_for( std::chrono::milliseconds( 5 ) );
	bStop = true;
	paint.join();
	printf( "%d %d %d\n", g_nPresentsBeforeConfigure.load(), g_nPresentsWhileHidden.load(), g_nPresents.load() );
	return 0;
}
'''


def run_harness(gate):
    with tempfile.TemporaryDirectory() as tmp:
        source = Path(tmp) / "show_present.cpp"
        binary = Path(tmp) / "show_present"
        source.write_text(HARNESS.replace("SET_WINDOW_SHOWN", set_window_shown()).replace("PRESENT_GATE", gate))
        subprocess.run(["c++", "-std=c++17", "-Wall", "-Werror", "-pthread", str(source), "-o", str(binary)], check=True)
        output = subprocess.run([str(binary)], capture_output=True, text=True, check=True, timeout=60).stdout
        return [int(value) for value in output.split()]


@unittest.skipUnless(shutil.which("c++"), "needs a C++ compiler")
class ShowPresentRaceTest(unittest.TestCase):
    def test_no_present_reaches_the_surface_while_it_waits_for_configure_or_is_hidden(self):
        before_configure, while_hidden, presents = run_harness(present_gate())
        self.assertEqual(0, before_configure)
        self.assertEqual(0, while_hidden)
        self.assertGreater(presents, 0)

    def test_without_the_gate_the_harness_sees_presents_before_configure(self):
        before_configure, while_hidden, presents = run_harness("")
        self.assertGreater(before_configure, 0)
        self.assertGreater(while_hidden, 0)
        self.assertGreater(presents, 0)


class ShowPresentWiringTest(unittest.TestCase):
    def test_visibility_event_shows_and_hides_through_the_connector(self):
        hunk = hunk_with("m_Connector.SetWindowShown( m_bShown );")
        self.assertIn("\t\t\t\t\t\t\tm_Connector.SetWindowShown( m_bShown );", added(hunk))
        self.assertTrue(any("SDL_ShowWindow( m_Connector.GetSDLWindow() );" in line for line in removed(hunk)))
        self.assertTrue(any("SDL_HideWindow( m_Connector.GetSDLWindow() );" in line for line in removed(hunk)))

    def test_only_the_connector_calls_sdl_show_and_hide(self):
        lines = [line for hunk in hunks() for line in added(hunk)]
        self.assertEqual(["\t\t\tSDL_ShowWindow( m_pWindow );"], [line for line in lines if "SDL_ShowWindow" in line])
        self.assertEqual(["\t\t\tSDL_HideWindow( m_pWindow );"], [line for line in lines if "SDL_HideWindow" in line])

    def test_gate_comes_before_composition(self):
        hunk = hunk_with("int CSDLConnector::Present(")
        lines = hunk.splitlines()
        gate = lines.index("+\t\tif ( !lock.owns_lock() || !m_bWindowShown )")
        composite = next(i for i, line in enumerate(lines) if "vulkan_composite(" in line)
        self.assertTrue(lines[composite].startswith(" "))
        self.assertLess(gate, composite)


if __name__ == "__main__":
    unittest.main()
