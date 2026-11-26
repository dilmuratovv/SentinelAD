#!/usr/bin/env python3
"""Live duplication test for SentinelAD: a disposable Paper server, the test-only dupe-harness plugin and two bots.

  python3 tools/dupe_probe.py --paper paper.jar --java /path/to/java25 --jar SentinelAD.jar --harness SentinelADHarness.jar \
      --via ViaVersion.jar --via ViaBackwards.jar --mojang-cache dir_with_mojang_jar --work /disk/dir --accept-eula

The harness copies items through the API (that is what a server bug amounts to) and makes legitimate moves, then
checks what SentinelAD did. Bots need minecraft-protocol (NODE_PATH). Localhost only.
"""
import argparse, os, queue, shutil, socket, subprocess, sys, threading, time
from pathlib import Path

BOT = Path(__file__).resolve().parent / 'client' / 'bot.cjs'


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--paper', type=Path, required=True)
    p.add_argument('--java', required=True)
    p.add_argument('--jar', type=Path, required=True)
    p.add_argument('--harness', type=Path, required=True)
    p.add_argument('--via', type=Path, action='append', default=[])
    p.add_argument('--mojang-cache', type=Path)
    p.add_argument('--work', type=Path, required=True)
    p.add_argument('--client-version', default='26.1')
    p.add_argument('--paper-audit', action='store_true', help='also restart with a dupe-enabling Paper setting')
    p.add_argument('--crash-test', action='store_true', help='kill the server with SIGKILL mid-game and check the crash journal')
    p.add_argument('--accept-eula', action='store_true')
    a = p.parse_args()
    if not a.accept_eula:
        p.error('Accept the Minecraft EULA to run an isolated test server')

    server = a.work / 'server'
    if server.exists():
        shutil.rmtree(server)
    (server / 'plugins').mkdir(parents=True)
    for jar in [a.jar, a.harness] + a.via:
        shutil.copy2(jar, server / 'plugins' / jar.name)
    if a.mojang_cache:
        shutil.copytree(a.mojang_cache, server / 'cache')
    (server / 'eula.txt').write_text('eula=true\n')
    with socket.socket() as s:
        s.bind(('127.0.0.1', 0))
        port = s.getsockname()[1]
    (server / 'server.properties').write_text(
        f'server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\nwhite-list=false\nenforce-whitelist=false\nmax-players=5\nview-distance=3\nsimulation-distance=3\n'
        'generate-structures=false\nlevel-type=minecraft:flat\nspawn-protection=0\ndifficulty=peaceful\nspawn-monsters=false\n')

    def start():
        proc = subprocess.Popen([a.java, '-Xms512M', '-Xmx1G', '-jar', str(a.paper.resolve()), '--nogui'], cwd=server,
                                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
        lines = queue.Queue()
        threading.Thread(target=lambda: [lines.put(l) for l in proc.stdout], daemon=True).start()
        return proc, lines

    log = []

    def wait(lines, marker, seconds):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            try:
                line = lines.get(timeout=0.5)
            except queue.Empty:
                continue
            log.append(line)
            if marker in line:
                return line
        raise RuntimeError('timed out waiting for ' + marker)

    def stop(proc):
        try:
            proc.stdin.write('stop\n'); proc.stdin.flush(); proc.wait(60)
        except Exception:
            proc.kill()

    env = dict(os.environ)
    if a.crash_test:
        return crash_test(a, server, port, start, wait, stop, log, env)
    bots = []
    proc, lines = start()
    ok = False
    try:
        wait(lines, 'Done (', 600)
        for name in ('Alice', 'Bob'):
            bots.append(subprocess.Popen(['node', str(BOT), str(port), name, a.client_version], env=env,
                                         stdout=open(a.work / f'{name}.log', 'w'), stderr=subprocess.STDOUT))
        done = wait(lines, 'HARNESS DONE', 600)
        wait(lines, 'LEDGER', 10)
        ok = True
    finally:
        for b in bots:
            b.kill()
        stop(proc)
        (a.work / 'server-output.log').write_text(''.join(log))
    results = (server / 'plugins' / 'SentinelADHarness' / 'results.txt').read_text()
    print(results)
    print(done.strip())

    if a.paper_audit:
        g = server / 'config' / 'paper-global.yml'
        text = g.read_text().replace('allow-piston-duplication: false', 'allow-piston-duplication: true')
        g.write_text(text)
        (server / 'plugins' / a.harness.name).unlink()
        proc, lines = start()
        try:
            wait(lines, 'Done (', 600)
            time.sleep(1)
            while not lines.empty():
                log.append(lines.get())
        finally:
            stop(proc)
        warned = any('allow-piston-duplication' in l and 'SentinelAD' in l for l in log)
        print(('PASS' if warned else 'FAIL') + ' paper-audit-warning')
        (a.work / 'server-output.log').write_text(''.join(log))
    return 0 if ok and 'FAIL' not in results else 1


def crash_test(a, server, port, start, wait, stop, log, env):
    """Phase 1 takes items and logs Alice out, then the server is killed (SIGKILL, no save); phase 2 checks."""
    phase = server / 'plugins' / 'SentinelADHarness'
    phase.mkdir(parents=True, exist_ok=True)
    result = 'FAIL crash-rollback-dupe: no result'
    for n in ('1', '2'):
        (phase / 'crash-phase').write_text(n)
        proc, lines = start()
        bots = []
        try:
            wait(lines, 'Done (', 600)
            for name in ('Alice', 'Bob'):
                bots.append(subprocess.Popen(['node', str(BOT), str(port), name, a.client_version], env=env,
                                             stdout=open(a.work / f'{name}-{n}.log', 'w'), stderr=subprocess.STDOUT))
            if n == '1':
                wait(lines, 'READY TO CRASH', 300)
                proc.kill()  # a crash: nothing is saved
                proc.wait(30)
            else:
                result = wait(lines, 'crash-rollback-dupe', 300).strip()
        finally:
            for b in bots:
                b.kill()
            if proc.poll() is None:
                stop(proc)
    (a.work / 'server-output.log').write_text(''.join(log))
    print(result)
    return 0 if 'PASS' in result else 1


if __name__ == '__main__':
    sys.exit(main())
