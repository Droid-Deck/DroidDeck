#!/usr/bin/env python3
"""Embed droiddeck-boot-sound.wav into droiddeck-boot.html (MP3, base64) for the preview page."""
import base64, re, subprocess
from pathlib import Path
here = Path(__file__).parent
subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", str(here / "droiddeck-boot-sound.wav"),
                "-c:a", "libmp3lame", "-b:a", "128k", str(here / "_snd.mp3")], check=True)
b64 = base64.b64encode((here / "_snd.mp3").read_bytes()).decode(); (here / "_snd.mp3").unlink()
html = (here / "droiddeck-boot.html").read_text()
html = re.sub(r'/\*SOUND\*/".*?"/\*END\*/', lambda m: f'/*SOUND*/"{b64}"/*END*/', html, flags=re.S)
(here / "droiddeck-boot.html").write_text(html)
print(f"embedded {len(b64)/1024:.0f} KB of sound")
