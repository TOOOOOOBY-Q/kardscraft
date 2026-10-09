"""Run protocol/native-adapter mocks without UE4SS or either game.

Install the isolated test dependency with:
  python -m pip install --target tools/lua-test-runtime lupa==2.8
Then run: python tools/test-kards-bridge.py
These checks cannot validate native game behavior or server acceptance.
"""
from pathlib import Path
import json
import sys
import tempfile

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools" / "lua-test-runtime"))
from lupa import LuaRuntime

lua = LuaRuntime(unpack_returned_tuples=True)
scripts = ROOT / "kards-mod" / "KardsBridge" / "Scripts"
lua.globals().package.path = str(scripts / "?.lua").replace("\\", "/") + ";" + lua.globals().package.path
for path in scripts.glob("*.lua"):
    lua.compile(path.read_text(encoding="utf-8"), name=str(path))
with tempfile.TemporaryDirectory(prefix="kards-bridge-test-") as temp:
    lua.globals().TEST_TEMP = temp.replace("\\", "/")
    result = lua.execute((ROOT / "tests" / "lua" / "kards_bridge_test.lua").read_text(encoding="utf-8"))
    state = json.loads(result)
    assert state["protocol"] == 2 and state["end"] == 1
    assert state["cards"][2]["def"] == 5
    assert state["cards"][2]["rawDefense"] == 1234567
    assert isinstance(state["results"], list)
    assert not state["serverPersistenceVerified"]
print("Lua syntax, JSON interoperability, and isolated file publication passed.")
