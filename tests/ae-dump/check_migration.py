"""Check AEDUMP extraction boundaries and exercise real coordinator diagnostic capture."""
from pathlib import Path
import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import zipfile


ROOT = Path(__file__).resolve().parents[2]
TOOL = ROOT / "tools/ae-dump"
JAVA = TOOL / "src/main/java/org/gtlcore/aedump"
ROUTER_DESCRIPTOR = (
    "(Lorg/gtlcore/gtlcore/integration/ae2/graph/GtlPatternCatalog;"
    "Lappeng/api/networking/IGrid;Lappeng/me/service/CraftingService;"
    "Lnet/minecraft/world/level/Level;Lappeng/api/networking/security/IActionSource;"
    "Lappeng/api/stacks/AEKey;JLappeng/api/networking/crafting/CalculationStrategy;"
    "Lorg/cgse/core/GraphJobRuntime$ReplanCheckpoint;ZZ)"
    "Lorg/gtlcore/gtlcore/integration/ae2/graph/GraphPlanningRequest;"
)


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def static_checks():
    for name in ["build.gradle", "settings.gradle", "gradle.properties", "gradlew", "gradlew.bat",
                 "build-with-core.init.gradle", "USAGE.txt", "gradle/wrapper/gradle-wrapper.properties"]:
        require((TOOL / name).is_file(), "Missing migrated build/documentation file: " + name)
    with zipfile.ZipFile(TOOL / "gradle/wrapper/gradle-wrapper.jar") as wrapper:
        require("org/gradle/wrapper/GradleWrapperMain.class" in wrapper.namelist(), "Incomplete Gradle wrapper")
    sources = list((TOOL / "src/main/java").rglob("*.java"))
    require(len(sources) >= 15, "AEDUMP production source set is incomplete")
    for source in sources:
        text = source.read_text(encoding="utf-8")
        require("org.gtlcore.gtlcore.integration.ae2.graph.core" not in text and
                "org/gtlcore/gtlcore/integration/ae2/graph/core/" not in text,
                "Stale engine namespace or JVM descriptor: " + str(source))
    config = json.loads((TOOL / "src/main/resources/aedump.mixins.json").read_text(encoding="utf-8"))
    require(config["required"] and config["injectors"]["defaultRequire"] == 1, "Mixin failures became optional")
    require(set(config["mixins"]) == {"RouterMixin", "LegacyMixin", "RequestWorkMixin", "BudgetMixin", "PlanningWorkMixin"},
            "Migrated instrumentation surface changed")
    for name in config["mixins"]:
        require((JAVA / "mixin" / (name + ".java")).is_file(), "Missing mixin source: " + name)
    router = (JAVA / "mixin/RouterMixin.java").read_text(encoding="utf-8")
    require('method = "begin' + ROUTER_DESCRIPTOR + '"' in router, "Router JVM descriptor does not name the extracted checkpoint")
    script = (TOOL / "build-with-core.init.gradle").read_text(encoding="utf-8")
    require("findProperty('cgseAeDumpDir')" in script and "p.file(location).canonicalFile" in script,
            "Host build must use explicit cgseAeDumpDir")
    require("projectDir.parentFile" not in script, "Host build assumes a neighboring checkout")
    require("compileJava" in script and "addNestedDependencies = false" in script,
            "AEDUMP should compile against the host without bundling it")
    print("AEDUMP static checks passed: source set, wrapper, mixins, descriptor and explicit host build path", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", default=os.environ.get("JAVA_HOME"))
    parser.add_argument("--static-only", action="store_true")
    parser.add_argument("--host-classes", help="Optional GTL compile output/classpath; verify actual Router JVM descriptor")
    args = parser.parse_args()
    static_checks()
    subprocess.run([sys.executable, str(ROOT / "tests/ae-dump/test_validate_archives.py")], check=True, timeout=30)
    if args.static_only:
        return

    def executable(name):
        if args.java_home:
            path = Path(args.java_home) / "bin" / (name + (".exe" if os.name == "nt" else ""))
            if path.is_file():
                return str(path)
        path = shutil.which(name)
        if path:
            return path
        parser.error("Cannot find " + name + "; set --java-home or JAVA_HOME to JDK 17")

    output = ROOT / "build/tests/ae-dump"
    classes = output / "classes"
    classes.mkdir(parents=True, exist_ok=True)
    sources = sorted((ROOT / "src/main/java").rglob("*.java"))
    sources += [JAVA / "Reflect.java", JAVA / "RequestState.java"]
    sources += sorted((ROOT / "tests/ae-dump/java").rglob("*.java"))
    arguments = output / "sources.args"
    arguments.write_text("\n".join('"' + source.as_posix() + '"' for source in sources), encoding="utf-8")
    subprocess.run([executable("javac"), "--release", "17", "-encoding", "UTF-8", "-d", str(classes), "@" + str(arguments)],
                   check=True, timeout=120)
    subprocess.run([executable("java"), "-ea", "-cp", str(classes), "org.gtlcore.aedump.RequestStateTest"],
                   check=True, timeout=30)
    if args.host_classes:
        bytecode = subprocess.check_output([executable("javap"), "-private", "-s", "-classpath", args.host_classes,
                                           "org.gtlcore.gtlcore.integration.ae2.graph.CraftingEngineRouter"],
                                          text=True, encoding="utf-8", timeout=30)
        require(re.search(r"\bbegin\([^\n]*\);\s+descriptor: " + re.escape(ROUTER_DESCRIPTOR), bytecode) is not None,
                "Actual GTL Router.begin bytecode does not match the AEDUMP Mixin selector")
        print("AEDUMP Router Mixin descriptor matches compiled GTL host bytecode", flush=True)


if __name__ == "__main__":
    main()
