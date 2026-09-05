#!/usr/bin/env python3
"""Final idempotent source migration. Earlier repairs are already versioned as normal sources."""
from pathlib import Path
root = Path(__file__).resolve().parents[1]
base = root / 'app/src/main/java/com/openjarvis'
# None of these upstream components is called by the guarded Murena runtime.
# Preserve the original implementation for future reviewed work, outside active source sets.
# This avoids adding foreground/notification permissions solely for unreachable legacy code.
for name in ('ui/OverlayService.kt', 'local/DownloadService.kt', 'watch/ScreenWatcher.kt'):
    source = base / name
    target = root / ('app/src/legacyReference/' + name + '.txt')
    if source.exists():
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(source.read_text())
        source.unlink()
print('Unused legacy source archived; no test or lint rule disabled.')
