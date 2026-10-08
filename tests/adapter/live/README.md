# Native migration smoke checks

This runner uses an explicitly selected disposable Forge server under a GTL host
checkout's `.local` directory. It does not contain or copy a world save and must
never target a player installation. The checks replace blocks around `(0,65,0)`
and `(33,65,0)` in that isolated world. World changes remain in the test world.

Requirements are Python 3.10+ with `psutil`, a JDK capable of reading the modpack's
classes, and a working Forge/AE/GTL test server with KubeJS and the dependencies
listed by `../tools/graph-server-probe/README.md`. The existing test modpack uses
Java 21. Production CGSE bytecode and the probe are compiled for Java 17.

```powershell
python tests/adapter/live/run.py `
  --host D:/java_proj/gtl_core/core-ae-cycle `
  --server D:/java_proj/gtl_core/core-ae-cycle/.local/review-completion-20261004/server `
  --java-home C:/path/to/jdk-21 `
  --core-jar D:/java_proj/gtl_core/core-ae-cycle/build/libs/gtlcore-1.2.3.2-fix3.jar `
  --aedump-jar tools/ae-dump/dist/ae-dump-1.0.0.jar `
  --label migration-smoke --run
```

Omitting `--run` compiles the probe and writes a script dependency inventory
without changing or starting the server. `--java` can select a separate runtime
executable; `--forge-args`, `--port`, `--timeout`, and `--work` are configurable.
The default port is 25584 and the server binds only to `127.0.0.1` during the run.
The supplied Forge args file must be inside that server directory.

Before installing anything, the runner checks both process working directories
and command lines, plus the listening port. It saves SHA-256-verified copies of
each replaced JAR, configuration or script under `build/live/<label>/backup` and
records every change in `restore.json`. It restores those files after stopping
its server process, including on ordinary test failures. A crashed runner can be
recovered using the same arguments plus
`--restore build/live/<label>/restore.json`; the server must already be stopped.
The runner never terminates another process to make room for its test.

The probe source now imports `org.cgse.core`. Existing KubeJS scripts in the
selected server get a temporary replacement of the old engine package name.
The inventory replan script follows `GraphCpuController.replanning` and its
portable `ReplanCoordinator` to observe checkpoint and backoff state. All temporary
script edits are restored.

The smoke sequence covers:

- Real storage changes, failed suffix replanning, unrelated-resource isolation,
  bounded retry delays, accepted input ownership and final material balance.
- Template duplication cycles with one and four retained seeds, real molecular
  assemblers, dispatch concurrency and final material balance.
- AEDUMP capturing the actual delegated coordinator and selected plan, manual
  `/ae dump latest`, and an automatic archive after a deliberately tiny planning
  budget. The budget is restored immediately after that diagnostic request.
- Complete archive ZIP/schema checks using `tests/ae-dump/validate_archives.py`.

`server.log`, checks, artifact hashes, restoration status and copies of the new
diagnostic archives stay in `build/live/<label>`. These are native dedicated-server
checks, not client rendering checks or physical GT machine processing tests.
