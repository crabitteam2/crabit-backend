#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
python3 - <<'PY'
from pathlib import Path
from zipfile import ZipFile
jars=[p for p in Path('build/libs').glob('*.jar') if not p.name.endswith('-plain.jar')]
if len(jars)!=1: raise SystemExit('Build exactly one bootJar before verifying isolation')
with ZipFile(jars[0]) as jar:
    forbidden=[n for n in jar.namelist() if n.startswith('BOOT-INF/classes/') and
        any(x in n for x in ['/simulation/', 'SimulationClock', 'SimulationReplay', '/Simulation', 'cash-oracle-v1.schema.json'])]
    if forbidden: raise SystemExit('Simulation execution code leaked into bootJar: '+repr(forbidden))
print('PASS: bootJar excludes simulation clock/replay classes (artifact structure only)')
PY
