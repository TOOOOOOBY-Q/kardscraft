"""Verify the in-memory defense adapter in an isolated Lua runtime, without games."""
from pathlib import Path
import sys
import tempfile

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools/lua-test-runtime"))
from lupa import LuaRuntime

lua = LuaRuntime(unpack_returned_tuples=True)
scripts = ROOT / "kards-mod/KardsBridge/Scripts"
lua.globals().package.path = str(scripts / "?.lua").replace("\\", "/") + ";" + lua.globals().package.path
for path in scripts.glob("*.lua"):
    lua.compile(path.read_text(encoding="utf-8"), name=str(path))
with tempfile.TemporaryDirectory(prefix="kards-memory-test-") as temp:
    lua.globals().TEST_TEMP = temp.replace("\\", "/")
    lua.execute((ROOT / "tests/lua/kards_memory_test.lua").read_text(encoding="utf-8"))
print("Memory adapter syntax and isolated mock checks passed; gameplay validation remains separate.")
