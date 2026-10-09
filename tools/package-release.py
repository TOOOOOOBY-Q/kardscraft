"""Package the compiled mod only after checking its test reports and runtime metadata."""
from pathlib import Path
from datetime import datetime, timezone
import hashlib
import json
import shutil
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parent.parent
reports = sorted((ROOT / "minecraft-mod/build/test-results/test").glob("TEST-*.xml"))
assert reports, "No Java test reports; build first"
suites = [ET.parse(p).getroot() for p in reports]
tests = sum(int(s.attrib["tests"]) for s in suites)
assert all(int(s.attrib["failures"]) == 0 and int(s.attrib["errors"]) == 0 for s in suites), "Java tests failed"
source = ROOT / "minecraft-mod/build/libs/kardscraft-0.1.0.jar"
with zipfile.ZipFile(source) as jar:
    entries = set(jar.namelist())
    metadata = json.loads(jar.read("fabric.mod.json"))
    assert metadata["id"] == "kardscraft" and metadata["version"] == "0.1.0"
    assert metadata["license"] == "MIT"
    assert jar.read("LICENSE_kardscraft") == (ROOT / "LICENSE").read_bytes()
    for names in metadata["entrypoints"].values():
        for name in names:
            assert name.replace(".", "/") + ".class" in entries, name
    mixins = json.loads(jar.read("kardscraft.mixins.json"))
    for name in mixins["mixins"] + mixins["client"]:
        assert (mixins["package"] + "." + name).replace(".", "/") + ".class" in entries, name
    json.loads(jar.read("data/kardscraft/damage_type/counter.json"))
    for tag in ["bypasses_invulnerability", "bypasses_armor", "bypasses_cooldown", "bypasses_shield", "bypasses_resistance", "bypasses_enchantments"]:
        assert "kardscraft:counter" in json.loads(jar.read(f"data/minecraft/tags/damage_type/{tag}.json"))["values"]
    assert not any(p.endswith("Test.class") for p in entries)

dist = ROOT / "dist"
dist.mkdir(exist_ok=True)
shutil.copy2(source, dist / source.name)
with zipfile.ZipFile(dist / "KardsBridge-v2.zip", "w", zipfile.ZIP_DEFLATED) as package:
    backend = ROOT / "kards-mod/KardsBridge"
    for path in sorted(backend.rglob("*")):
        if path.is_file():
            package.write(path, "KardsBridge/" + path.relative_to(backend).as_posix())
    package.write(ROOT / "LICENSE", "KardsBridge/LICENSE")
summary = {
    "packagedAt": datetime.now(timezone.utc).isoformat(),
    "javaTests": tests,
    "javaFailures": 0,
    "javaTestSuites": {s.attrib["name"]: int(s.attrib["tests"]) for s in suites},
    "jarEntrypointsAndResourcesValidated": True,
    "gameStartedByThisVerification": False,
    "manualGameplayValidation": "pending",
}
(dist / "verification.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
artifacts = [dist / source.name, dist / "KardsBridge-v2.zip"]

# Only include project sources and scripts, never local game data or caches.
files = [ROOT / name for name in (
    "README.md", "LICENSE", "THIRD_PARTY_NOTICES.md", ".gitignore",
    ".gitattributes", "bridge/.gitkeep",
)]
for pattern in ("*.cmd", "*.ps1", "tools/*.py", "tools/*.pom", "LICENSES/*"):
    files.extend(ROOT.glob(pattern))
mod = ROOT / "minecraft-mod"
files.extend(mod / name for name in (
    ".gitignore", "build.gradle", "gradle.properties", "settings.gradle",
    "gradlew", "gradlew.bat",
))
for tree in (mod / "src", mod / "gradle", ROOT / "kards-mod", ROOT / "tests"):
    files.extend(p for p in tree.rglob("*") if p.is_file() and "__pycache__" not in p.parts)
bundle = dist / "kardscraft-0.1.0-windows.zip"
with zipfile.ZipFile(bundle, "w", zipfile.ZIP_DEFLATED) as package:
    for path in sorted(set(files)):
        package.write(path, "kardscraft/" + path.relative_to(ROOT).as_posix())
    for path in artifacts + [dist / "verification.json"]:
        package.write(path, "kardscraft/dist/" + path.name)
artifacts.append(bundle)
(dist / "SHA256SUMS.txt").write_text("".join(hashlib.sha256(p.read_bytes()).hexdigest() + "  " + p.name + "\n" for p in artifacts), encoding="ascii")
print(f"Packaged Windows ZIP, JAR and Lua bridge with MIT license; {tests} Java tests passed; all entrypoints, mixins and damage resources present.")
