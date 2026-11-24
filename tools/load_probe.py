#!/usr/bin/env python3
"""Ledger load test (localhost only): a disposable Paper server with a ledger.dat of N sealed items, two bots that
keep the ledger busy, saves every 10 s, and the server's own /mspt numbers. Prints startup load time and tick cost.

  NODE_PATH=... python3 tools/load_probe.py --paper paper.jar --java java25 --jar SentinelAD.jar --via ... \
      --mojang-cache dir --work dir --items 500000 --accept-eula
"""
import argparse, os, queue, re, shutil, socket, subprocess, sys, threading, time, uuid
from pathlib import Path

BOT = Path(__file__).resolve().parent / 'client' / 'bot.cjs'


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--paper', type=Path, required=True)
    p.add_argument('--java', required=True)
    p.add_argument('--jar', type=Path, required=True)
    p.add_argument('--via', type=Path, action='append', default=[])
    p.add_argument('--mojang-cache', type=Path)
    p.add_argument('--work', type=Path, required=True)
    p.add_argument('--items', type=int, default=500_000)
    p.add_argument('--seconds', type=int, default=90)
    p.add_argument('--no-ad', action='store_true', help='baseline without SentinelAD')
    p.add_argument('--accept-eula', action='store_true')
    a = p.parse_args()
    if not a.accept_eula:
        p.error('accept the Minecraft EULA first')
    server = a.work / 'server'
    if server.exists():
        shutil.rmtree(server)
    (server / 'plugins' / 'SentinelAD').mkdir(parents=True)
    for jar in ([] if a.no_ad else [a.jar]) + a.via:
        shutil.copy2(jar, server / 'plugins' / jar.name)
    if a.mojang_cache:
        shutil.copytree(a.mojang_cache, server / 'cache')
    (server / 'eula.txt').write_text('eula=true\n')
    (server / 'plugins' / 'SentinelAD' / 'config.yml').write_text('ledger:\n  save-interval-seconds: 10\n')
    now = int(time.time() * 1000)
    with open(server / 'plugins' / 'SentinelAD' / 'ledger.dat', 'w') as f:
        f.write('#sentinelad-ledger v1 clean=true\n')
        for i in range(a.items):
            holder = f'B:{uuid.UUID(int=1)}:{i % 5000}:-60:{i // 5000}' if i % 3 else f'P:{uuid.UUID(int=i)}'
            f.write(f'{uuid.uuid4()}\t{holder}\t{now - i}\n')
    with socket.socket() as s:
        s.bind(('127.0.0.1', 0))
        port = s.getsockname()[1]
    (server / 'server.properties').write_text(
        f'server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\nwhite-list=false\nenforce-whitelist=false\n'
        'max-players=5\nview-distance=3\nsimulation-distance=3\ngenerate-structures=false\nlevel-type=minecraft:flat\n'
        'spawn-protection=0\ndifficulty=peaceful\n')
    proc = subprocess.Popen([a.java, '-Xms512M', '-Xmx1536M', '-jar', str(a.paper.resolve()), '--nogui'], cwd=server,
                            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
    lines = queue.Queue()
    threading.Thread(target=lambda: [lines.put(l) for l in proc.stdout], daemon=True).start()
    log = []

    def wait(marker, seconds):
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            try:
                l = lines.get(timeout=0.5)
            except queue.Empty:
                continue
            log.append(l)
            if marker in l:
                return l
        raise RuntimeError('timed out: ' + marker)

    def cmd(t):
        proc.stdin.write(t + '\n'); proc.stdin.flush()

    bots = []
    samples = []
    try:
        wait('Done (', 300)
        for name in ('Alice', 'Bob'):
            bots.append(subprocess.Popen(['node', str(BOT), str(port), name], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL))
        time.sleep(15)
        end = time.monotonic() + a.seconds
        while time.monotonic() < end:
            cmd('mspt')
            l = wait('5s', 10)
            nums = [float(x) for x in re.findall(r'(\d+\.\d+)', re.sub(r'\x1b\[[0-9;]*m', '', l))]
            if nums:
                samples.append(nums)
            time.sleep(5)
        if not a.no_ad:
            cmd('sad ledger stats')
            time.sleep(2)
    finally:
        for b in bots:
            b.kill()
        cmd('stop')
        try:
            proc.wait(60)
        except Exception:
            proc.kill()
        while not lines.empty():
            log.append(lines.get())
        (a.work / 'server-output.log').write_text(''.join(log))
    text = ''.join(log)
    loaded = re.search(r'Ledger loaded: (\d+)', text)
    # /mspt prints avg/min/max for 5s, 10s, 60s: the max of the 5 s window is the worst tick seen in it
    worst = max(s[2] for s in samples) if samples else None
    avg = sum(s[0] for s in samples) / len(samples) if samples else None
    print(f'items={a.items} loaded={loaded.group(1) if loaded else "-"} samples={len(samples)} '
          f'avg_mspt={avg:.2f} worst_tick_ms={worst:.1f}' if samples else 'no samples')
    stats = re.search(r'sealed items tracked.*', text)
    if stats:
        print(stats.group(0).strip())
    return 0


if __name__ == '__main__':
    sys.exit(main())
