#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
python3 - <<'CHECK'
from pathlib import Path
import subprocess
base = '3be5bd332b3fc94382dea917c883b3a1300e81de'
def original(path):
    return subprocess.check_output(['git', 'show', base + ':' + path], text=True)
fixture = original('app/src/test/java/dev/healthbridge/V011KnownGapsTest.kt')
expected = ''.join(line for line in fixture.splitlines(keepends=True)
                   if line.strip() != 'import org.junit.Ignore' and not line.strip().startswith('@Ignore('))
assert Path('app/src/gapReproduction/kotlin/dev/healthbridge/V011KnownGapsTest.kt').read_text() == expected
path = 'app/src/main/java/dev/healthbridge/ChunkOutbox.kt'
def cleanup(text):
    return text[text.index('    private fun readReceipt'):text.index('    private fun preflight')]
assert cleanup(Path(path).read_text()) == cleanup(original(path))
for name in ('Store.kt', 'SyncControl.kt'):
    path = 'app/src/main/java/dev/healthbridge/' + name
    assert Path(path).read_text() == original(path), path
build = 'app/build.gradle.kts'
def production_dependencies(text):
    return [line.strip() for line in text.splitlines()
            if line.strip().startswith(('implementation(', 'ksp(', 'applicationId =', 'versionCode =', 'versionName ='))]
assert production_dependencies(Path(build).read_text()) == production_dependencies(original(build))
for path in ('app/src/main/java/dev/healthbridge/ChunkOutbox.kt',
             'app/src/main/java/dev/healthbridge/ChunkPlanner.kt'):
    # Constants are compared at their authoritative declaration sites.
    if Path(path).exists():
        def constants(text):
            return [line for line in text.splitlines() if 'const val ' in line]
        assert constants(Path(path).read_text()) == constants(original(path)), path
stream = 'app/src/main/java/dev/healthbridge/StreamingChunkPlanner.kt'
def envelope(text):
    return text[text.index('internal data class FreshGenerationLimits('):text.index('internal data class FreshGenerationLimits(') + 350].split(')')[0]
assert envelope(Path(stream).read_text()) == envelope(original(stream))
print('Original gap assertions non-ignored; cleanup, protected sources, production deps and constants equal base')
CHECK
