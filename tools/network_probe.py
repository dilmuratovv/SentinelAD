#!/usr/bin/env python3
"""Cross-server dupe test for SentinelAD (localhost only): two Paper servers sharing an SQLite network store, the
test-only HuskSync stand-in (tools/fake-husksync) syncing the inventory through a shared folder, and one bot that
switches between them. A legit switch must stay clean; a switch after items leaked out of the player AFTER the save
(the cross-server dupe) must lose the leaked diamonds and sealed sword on the next server.

  NODE_PATH=... python3 tools/network_probe.py --paper paper.jar --java java --jar SentinelAD.jar \
      --fake FakeHuskSync.jar --mojang-cache dir --work dir --client-version 1.21.11 --accept-eula
"""
import argparse, queue, re, shutil, socket, subprocess, sys, threading, time
from pathlib import Path

BOT = Path(__file__).resolve().parent / 'client' / 'bot.cjs'


def free_port():
    with socket.socket() as s:
        s.bind(('127.0.0.1', 0))
        return s.getsockname()[1]


class Server:
    def __init__(self, a, name, port, shared):
        self.name, self.port = name, port
        self.dir = a.work / name
        if self.dir.exists():
            shutil.rmtree(self.dir)
        (self.dir / 'plugins' / 'SentinelAD').mkdir(parents=True)
        for jar in (a.jar, a.fake):
            shutil.copy2(jar, self.dir / 'plugins' / jar.name)
        if a.mojang_cache:
            shutil.copytree(a.mojang_cache, self.dir / 'cache')
        (self.dir / 'eula.txt').write_text('eula=true\n')
        (self.dir / 'server.properties').write_text(
            f'server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\nwhite-list=false\nenforce-whitelist=false\n'
            'max-players=5\nview-distance=2\nsimulation-distance=2\ngenerate-structures=false\nlevel-type=minecraft:flat\n'
            'spawn-protection=0\ndifficulty=peaceful\n')
        (self.dir / 'plugins' / 'SentinelAD' / 'config.yml').write_text(
            f'network:\n  enabled: true\n  server-name: {name}\n  jdbc-url: "jdbc:sqlite:{(shared / "network.db").resolve()}"\n')
        self.log, self.lines = [], queue.Queue()
        self.proc = subprocess.Popen([a.java, '-Xms256M', '-Xmx700M', f'-Dfakesync.dir={shared.resolve()}', '-jar',
                                      str(a.paper.resolve()), '--nogui'], cwd=self.dir, stdin=subprocess.PIPE,
                                     stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
        threading.Thread(target=lambda: [self.lines.put(l) for l in self.proc.stdout], daemon=True).start()

    def pump(self, until=None, seconds=0.0):
        end = time.monotonic() + seconds
        while True:
            try:
                l = self.lines.get(timeout=0.2)
                self.log.append(l)
                if until and until in l:
                    return l
            except queue.Empty:
                pass
            if time.monotonic() >= end:
                if until:
                    raise RuntimeError(f'{self.name}: no "{until}"')
                return None

    def cmd(self, text):
        self.proc.stdin.write(text + '\n'); self.proc.stdin.flush()

    def stop(self):
        try:
            self.cmd('stop'); self.proc.wait(60)
        except Exception:
            self.proc.kill()

    def text(self):
        return ''.join(self.log)


def main():
    p = argparse.ArgumentParser()
    for k in ('--paper', '--jar', '--fake', '--work'):
        p.add_argument(k, type=Path, required=True)
    p.add_argument('--java', required=True)
    p.add_argument('--mojang-cache', type=Path)
    p.add_argument('--client-version', default='1.21.11')
    p.add_argument('--accept-eula', action='store_true')
    a = p.parse_args()
    if not a.accept_eula:
        p.error('accept the Minecraft EULA first')
    shared = a.work / 'shared'
    if shared.exists():
        shutil.rmtree(shared)
    shared.mkdir(parents=True)
    A = Server(a, 'serverA', free_port(), shared)
    B = Server(a, 'serverB', free_port(), shared)
    results, bot = [], None

    def inventory(srv):
        srv.cmd('data get entity Alice Inventory')
        return srv.pump('entity data', 15) or ''

    def diamonds(t):
        return sum(int(x) for x in re.findall(r'id: "minecraft:diamond", count: (\d+)', t))

    try:
        A.pump('Done (', 300); B.pump('Done (', 300)
        for srv in (A, B):
            if 'Network dupe check on' not in srv.text():
                srv.pump('Network dupe check on', 5)
        bot = subprocess.Popen(['node', str(BOT), f'{A.port},{B.port}', 'Alice', a.client_version],
                               stdout=open(a.work / 'Alice.log', 'w'), stderr=subprocess.STDOUT)
        A.pump('Alice joined the game', 120)
        A.pump(seconds=3)
        A.cmd('give Alice diamond 32'); A.cmd('give Alice diamond_sword')
        A.pump(seconds=15)  # the ledger seals the sword; past the command window
        sword_a = re.findall(r'sentinelad:serial": "([0-9a-f-]{36})"', inventory(A))

        # 1. legit switch A -> B
        mark = len(B.log)
        A.cmd('kick Alice')
        B.pump('Alice joined the game', 60)
        B.pump('SYNCED Alice', 30)
        B.pump(seconds=6)
        inv_b = inventory(B)
        flagged = 'network dupe' in ''.join(B.log[mark:])
        ok = not flagged and diamonds(inv_b) == 32 and bool(sword_a) and sword_a[0] in inv_b
        results.append(('PASS' if ok else 'FAIL') + f' legit-server-switch: flagged={flagged} diamondsOnB={diamonds(inv_b)} swordKept={bool(sword_a) and sword_a[0] in inv_b}')

        # 2. dupe: items leave Alice after B saved her, then she joins A with the synced (older) data
        (shared / 'leak-Alice').write_text('1')
        mark = len(A.log)
        B.cmd('kick Alice')
        B.pump('LEAKED items of Alice', 20)
        A.pump('Alice joined the game', 60)
        A.pump('SYNCED Alice', 30)
        A.pump(seconds=6)
        inv_a = inventory(A)
        found = [l for l in A.log[mark:] if 'network dupe' in l]
        ground = B.pump(seconds=1)
        ok = bool(found) and diamonds(inv_a) == 0 and not (sword_a and sword_a[0] in inv_a)
        results.append(('PASS' if ok else 'FAIL') + f' cross-server-dupe: flagged={bool(found)} diamondsOnA={diamonds(inv_a)} '
                       f'swordOnA={bool(sword_a) and sword_a[0] in inv_a} | ' + (found[0].strip()[:260] if found else ''))
    finally:
        if bot:
            bot.kill()
        for srv in (A, B):
            srv.stop()
            (a.work / f'{srv.name}-output.log').write_text(srv.text())
    print('\n'.join(results))
    return 0 if all(r.startswith('PASS') for r in results) else 1


if __name__ == '__main__':
    sys.exit(main())
