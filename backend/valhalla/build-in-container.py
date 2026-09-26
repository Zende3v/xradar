#!/usr/bin/env python3
"""Construction isolée. Exécutée seulement dans image Valhalla fixée par digest."""
import json
from pathlib import Path
import resource
import subprocess
import time


def merge(base, patch):
    for key, value in patch.items():
        if isinstance(value, dict):
            merge(base.setdefault(key, {}), value)
        else:
            base[key] = value
    return base


def main():
    data = Path('/data')
    started = time.monotonic()
    metrics = {'ok': False, 'stages': []}
    try:
        base = json.loads(subprocess.check_output(['valhalla_build_config'], text=True))
        config = merge(base, json.loads(Path('/tools/valhalla.json').read_text()))
        (data / 'valhalla.json').write_text(json.dumps(config, indent=2) + '\n')
        tz_work = data / 'timezone-build'
        tz_work.mkdir()
        with (data / 'tz_world.sqlite').open('wb') as output:
            subprocess.run(['valhalla_build_timezones'], cwd=tz_work, stdout=output, check=True)
        # Script amont peut masquer certaines erreurs GEOS : vérifier base, pas seulement code retour.
        import sqlite3
        with sqlite3.connect(data / 'tz_world.sqlite') as db:
            assert db.execute('SELECT count(*) FROM tz_world').fetchone()[0] > 0
        for command in [
            ['valhalla_build_admins', '-c', '/data/valhalla.json', '/input/france.osm.pbf'],
            ['valhalla_build_tiles', '-c', '/data/valhalla.json', '/input/france.osm.pbf'],
            ['valhalla_build_extract', '-c', '/data/valhalla.json'],
        ]:
            at = time.monotonic()
            subprocess.run(command, check=True)
            metrics['stages'].append({'command': command[0], 'seconds': round(time.monotonic() - at, 3)})
        for name in ['admin.sqlite', 'tz_world.sqlite', 'tiles.tar']:
            assert (data / name).stat().st_size > 0, name
        metrics['ok'] = True
    finally:
        metrics['seconds'] = round(time.monotonic() - started, 3)
        metrics['max_child_rss_kib'] = resource.getrusage(resource.RUSAGE_CHILDREN).ru_maxrss
        peak = Path('/sys/fs/cgroup/memory.peak')
        metrics['cgroup_peak_bytes'] = int(peak.read_text()) if peak.exists() else None
        (data / 'build-metrics.json').write_text(json.dumps(metrics, indent=2) + '\n')


if __name__ == '__main__':
    main()
