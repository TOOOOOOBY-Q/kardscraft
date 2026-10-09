"""Exercise installation and byte-for-byte rollback in a disposable fake game tree."""
from pathlib import Path
import json
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent.parent

def run(script, *args):
    result = subprocess.run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(script), *map(str, args)], capture_output=True)
    if result.returncode:
        raise AssertionError(result.stdout.decode(errors="replace") + result.stderr.decode(errors="replace"))

with tempfile.TemporaryDirectory(prefix="kardscraft-installer-") as temp:
    project = Path(temp) / "Project with spaces"
    project.mkdir()
    for name in ("Install-KardsBridge.ps1", "Restore-KardsBridge.ps1"):
        shutil.copy2(ROOT / name, project / name)
    shutil.copytree(ROOT / "kards-mod" / "KardsBridge", project / "kards-mod" / "KardsBridge")
    game = Path(temp) / "Fake KARDS"
    mods = game / "kards/Binaries/Win64/ue4ss/Mods"
    original = {
        "mods.txt": b"OtherMod : 1\r\nKardsBridge : 0\r\n",
        "ConsoleEnablerMod/Scripts/main.lua": b'-- keep\r\nUEHelpers.FindFName("F10")\r\n',
        "KardsBridge/Scripts/main.lua": b"-- old bridge\n",
        "OtherMod/Scripts/main.lua": b"-- unrelated untouched\n",
    }
    for name, data in original.items():
        target = mods / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
    config = project / "minecraft-mod/run/config/kardscraft.properties"
    config.parent.mkdir(parents=True)
    config.write_text("explosionDamage=7\nbridgeDir=old\n", encoding="utf-8")
    run(project / "Install-KardsBridge.ps1", "-KardsRoot", game)
    backups = list((project / "tools/install-backups").iterdir())
    assert len(backups) == 1
    first = backups[0]
    assert "KardsBridge : 1" in (mods / "mods.txt").read_text()
    assert 'FindFName("F11")' in (mods / "ConsoleEnablerMod/Scripts/main.lua").read_text()
    assert "explosionDamage=7" in config.read_text()
    assert "bridgeDir=old" not in config.read_text()
    assert (mods / "OtherMod/Scripts/main.lua").read_bytes() == original["OtherMod/Scripts/main.lua"]
    settings = (mods / "KardsBridge/Scripts/bridge_settings.lua").read_text()
    assert "Project with spaces" in settings and "directory=" in settings
    assert 'damageMode="memory_setter"' in settings
    for name in ("main.lua", "bridge_core.lua", "kards_adapter.lua", "camera_follow.lua"):
        assert (mods / "KardsBridge/Scripts" / name).read_bytes() == (project / "kards-mod/KardsBridge/Scripts" / name).read_bytes()
    # Minecraft stores an escaped drive colon and CRLF. Reinstallation must not
    # rewrite an already-correct config or disturb Claude's overlay settings.
    stored_config = config.read_text().replace("C:/", "C\\:/").replace("\n", "\r\n").encode("utf-8")
    config.write_bytes(stored_config)
    installed = {p.relative_to(mods): p.read_bytes() for p in mods.rglob("*") if p.is_file()}
    run(project / "Install-KardsBridge.ps1", "-KardsRoot", game)
    assert config.read_bytes() == stored_config
    second = next(p for p in (project / "tools/install-backups").iterdir() if p != first)
    run(project / "Restore-KardsBridge.ps1", "-BackupDirectory", second)
    assert installed == {p.relative_to(mods): p.read_bytes() for p in mods.rglob("*") if p.is_file()}
    run(project / "Restore-KardsBridge.ps1", "-BackupDirectory", first)
    restored = {p.relative_to(mods).as_posix(): p.read_bytes() for p in mods.rglob("*") if p.is_file()}
    assert restored == original
    manifest = json.loads((first / "manifest.json").read_text(encoding="utf-8-sig"))
    assert len(manifest["files"]) == 7
print("Installer passed: isolated spaced paths, preserved config/mods, reinstall, exact two-stage rollback.")
