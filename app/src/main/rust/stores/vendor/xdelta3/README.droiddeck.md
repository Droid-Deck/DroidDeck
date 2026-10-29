Xdelta3 decoder from jmacd/xdelta revision 0525275fe4b553a10f38e455d30c60dc6ed9b45d (release3_0_apl), the
Apache-2.0 revision used by heroic-gogdl. Upstream sources are unmodified.
https://github.com/jmacd/xdelta/tree/0525275/xdelta3

DroidDeck's streaming wrapper is src/gog/delta.c. Encoding and secondary
compression are disabled, matching heroic-gogdl's decoder baseline. Unsupported
streams fall back to verified Galaxy chunks. No Python runtime is required.
