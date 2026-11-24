#!/usr/bin/env python3
"""SentinelAD with a real Minecraft client (localhost only): legitimate play through real GUI clicks must raise no
finding and keep every seal. Reuses the virtual-display client bench of SentinelAC (tools/realclient_probe.py).

  <venv>/bin/python tools/ad_realclient.py --bench ../SentinelAC/code/tools --paper paper.jar --java java25 \
      --jar SentinelAD.jar --plugin ViaVersion.jar --plugin ViaBackwards.jar --portablemc <venv>/bin/portablemc \
      --mc client/mc --client-java java21 --mojang-cache dir --work dir --accept-eula
"""
import argparse, re, shutil, sys, time
from pathlib import Path

GUI_W, GUI_H = 176, 166  # vanilla container screens at gui scale 1


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--bench', type=Path, required=True)
    p.add_argument('--paper', type=Path, required=True)
    p.add_argument('--java', required=True)
    p.add_argument('--client-java', required=True)
    p.add_argument('--jar', type=Path, required=True)
    p.add_argument('--plugin', type=Path, action='append', default=[])
    p.add_argument('--portablemc', type=Path, required=True)
    p.add_argument('--mc', type=Path, required=True)
    p.add_argument('--client', default='fabric:1.21.11')
    p.add_argument('--client-heap', default='900m')
    p.add_argument('--mojang-cache', type=Path)
    p.add_argument('--work', type=Path, required=True)
    p.add_argument('--accept-eula', action='store_true')
    a = p.parse_args()
    if not a.accept_eula:
        p.error('accept the Minecraft EULA first')
    sys.path.insert(0, str(a.bench.resolve()))
    import realclient_probe as bench
    from Xlib import X
    from Xlib.ext import xtest
    a.work.mkdir(parents=True, exist_ok=True)
    W, H = bench.W, bench.H
    left, top = (W - GUI_W) // 2, (H - GUI_H) // 2
    player = bench.PLAYER

    if a.mojang_cache:  # the Server class wipes its directory; give paperclip the Mojang jar without a download
        orig_init = bench.Server.__init__

        def init(self, work, paper, java, plugins):
            cache = work / 'server' / 'cache'
            real_rmtree = shutil.rmtree

            def keep_cache(path, *x, **y):
                real_rmtree(path, *x, **y)
                shutil.copytree(a.mojang_cache, cache)
            shutil.rmtree = keep_cache
            try:
                if not (work / 'server').exists():
                    (work / 'server').mkdir(parents=True)
                orig_init(self, work, paper, java, plugins)
            finally:
                shutil.rmtree = real_rmtree
        bench.Server.__init__ = init

    server = bench.Server(a.work, a.paper.resolve(), a.java, [a.jar] + a.plugin)
    server.pump('Done (', 300)
    client = bench.Client(a, server.port)

    def point(x, y):
        xtest.fake_input(client.display, X.MotionNotify, x=int(x), y=int(y))
        client.display.sync()
        time.sleep(0.08)

    def container_slot(col, row):
        return left + 8 + 18 * col + 8, top + 18 + 18 * row + 8

    def inv_slot(col, row):  # main inventory rows 0-2 under a 3-row container / the inventory screen
        return left + 8 + 18 * col + 8, top + 84 + 18 * row + 8

    def hotbar(col):
        return left + 8 + 18 * col + 8, top + 142 + 8

    def craft_grid(i):  # 2x2 grid of the inventory screen
        return left + 98 + 18 * (i % 2) + 8, top + 18 + 18 * (i // 2) + 8

    def click_at(xy, button=1, shift=False):
        point(*xy)
        if shift:
            client.hold('Shift_L', True)
        xtest.fake_input(client.display, X.ButtonPress, button); client.display.sync(); time.sleep(0.06)
        xtest.fake_input(client.display, X.ButtonRelease, button); client.display.sync()
        if shift:
            client.hold('Shift_L', False)
        time.sleep(0.35)

    log_dir = server.dir / 'plugins' / 'SentinelAD' / 'logs'

    def findings():
        out = []
        for f in sorted(log_dir.glob('dupe-*.log')):
            out += [l for l in f.read_text(errors='replace').splitlines() if l.strip()]
        return out

    def inventory_nbt():
        server.cmd(f'data get entity {player} Inventory')
        line = server.pump('entity data', 10)
        return line or ''

    def serials(text):
        return re.findall(r'"sentinelad:serial"\s*:\s*"([0-9a-f-]{36})"', text)

    def face():
        server.cmd(f'tp {player} 0.5 -60 0.5 0 35')
        server.pump(seconds=1.5)

    results = []

    def check(name, before, extra_ok=True, note=''):
        new = findings()[before:]
        ok = not new and extra_ok
        results.append(('PASS ' if ok else 'FAIL ') + name + ('' if ok else f': {new} {note}'))
        client.screenshot(a.work / f'{name}.png')
        print(results[-1], flush=True)

    try:
        client.launch()
        server.pump(player + ' joined the game', 300)
        server.pump(seconds=10)
        client.center()
        server.cmd(f'gamemode survival {player}')
        server.cmd(f'clear {player}')
        server.cmd(f'give {player} diamond_sword')
        server.cmd(f'give {player} diamond 32')
        server.cmd(f'give {player} bundle[bundle_contents=[{{id:"minecraft:diamond",count:8}}]]')
        server.cmd(f'give {player} shulker_box')
        server.cmd(f'give {player} netherite_pickaxe')
        server.cmd('setblock 0 -60 1 chest')
        face()
        server.pump(seconds=13)  # past the console-command window; the ledger seals everything
        sealed = serials(inventory_nbt())
        print('sealed items:', len(sealed), flush=True)

        # 1. chest: shift-click a sealed sword and diamonds in and out
        n = len(findings())
        client.right(); time.sleep(1.2)
        click_at(hotbar(0), shift=True); click_at(hotbar(1), shift=True)
        click_at(container_slot(0, 0), shift=True); click_at(container_slot(1, 0), shift=True)
        client.tap('Escape'); server.pump(seconds=4)
        check('chest-shift-click', n, len(serials(inventory_nbt())) == len(sealed))

        # 2. take the diamonds out of the bundle while the chest is open
        n = len(findings())
        client.right(); time.sleep(1.2)
        click_at(hotbar(2), button=3)          # right-click the bundle: its stack goes onto the cursor
        click_at(container_slot(4, 1))         # put it into the chest
        client.tap('Escape'); server.pump(seconds=4)
        server.cmd('data get block 0 -60 1 Items')
        chest = server.pump('block data', 10) or ''
        check('bundle-unpack-chest-open', n, 'diamond' in chest, note=chest.strip()[-160:])

        # 3. ender chest round trip
        n = len(findings())
        server.cmd('setblock 0 -60 1 ender_chest'); server.pump(seconds=1)
        client.right(); time.sleep(1.2)
        click_at(hotbar(0), shift=True); click_at(container_slot(0, 0), shift=True)
        client.tap('Escape'); server.pump(seconds=4)
        check('ender-chest-round-trip', n, len(serials(inventory_nbt())) == len(sealed))

        # 4. sword into the 2x2 crafting grid and back (the inventory screen)
        n = len(findings())
        client.tap('e'); time.sleep(1.0)
        click_at(hotbar(0)); click_at(craft_grid(0)); server.pump(seconds=4)  # sits in the grid during rescans
        client.tap('Escape'); server.pump(seconds=4)
        check('crafting-grid-round-trip', n, len(serials(inventory_nbt())) == len(sealed))

        # 5. place the sealed shulker box, fill it, break it, pick it up: same seal afterwards
        n = len(findings())
        server.cmd('setblock 0 -60 1 air'); server.pump(seconds=1)
        before = set(serials(inventory_nbt()))
        client.tap('4'); time.sleep(0.3)
        client.right(); server.pump(seconds=2)           # place on the ground in front
        client.right(); time.sleep(1.2)                  # open it
        click_at(hotbar(1), shift=True)                  # diamonds in
        client.tap('Escape'); server.pump(seconds=2)
        client.tap('5'); time.sleep(0.3)                 # pickaxe
        client.button(1, True); time.sleep(2.5); client.button(1, False)
        client.hold('w', True); time.sleep(0.8); client.hold('w', False)
        server.pump(seconds=4)
        after_text = inventory_nbt()
        after = set(serials(after_text))
        kept = before <= after
        check('shulker-place-fill-break', n, kept and 'shulker_box' in after_text,
              note=f'seal kept={kept} before={len(before)} after={len(after)}')
        face()

        # 6. drop the sword and pick it up again
        n = len(findings())
        client.tap('1'); time.sleep(0.2)
        client.tap('q'); server.pump(seconds=1.5)
        client.hold('w', True); time.sleep(0.9); client.hold('w', False)
        server.pump(seconds=4)
        check('drop-and-pickup', n, len(serials(inventory_nbt())) >= len(sealed) - 1)

        # 7. real chunk unload/load: a chest with diamonds, the player travels 2000 blocks away and back
        n = len(findings())
        server.cmd('setblock 6 -60 6 chest')
        server.cmd('item replace block 6 -60 6 container.0 with diamond 40')
        server.pump(seconds=12)  # past the console-command window
        server.cmd(f'tp {player} 2000.5 -60 2000.5'); server.pump(seconds=20)
        server.cmd(f'tp {player} 0.5 -60 0.5 0 35'); server.pump(seconds=15)
        server.cmd('data get block 6 -60 6 Items')
        far = server.pump('block data', 10) or ''
        check('travel-unload-reload', n, 'diamond' in far, note=far.strip()[-120:])

        # 8. abrupt disconnect with diamonds in the crafting grid and on the cursor, then rejoin
        n = len(findings())
        server.cmd(f'give {player} diamond 20'); server.pump(seconds=12)
        before_text = inventory_nbt()
        client.tap('e'); time.sleep(1.0)
        click_at(inv_slot(0, 0)); click_at(craft_grid(1), button=3)  # stack on cursor, one into the grid
        time.sleep(0.5)
        client.stop()                                                 # the game process dies: no clean quit
        server.pump(player + ' left the game', 60)
        server.pump(seconds=3)
        client.launch()
        server.pump(player + ' joined the game', 300)
        server.pump(seconds=12)
        client.center()
        after_text = inventory_nbt()
        count = lambda t: sum(int(x) for x in re.findall(r'id: "minecraft:diamond", count: (\d+)', t))
        check('disconnect-with-grid-and-cursor', n, count(after_text) == count(before_text),
              note=f'diamonds before={count(before_text)} after={count(after_text)}')
    finally:
        client.close()
        server.cmd('stop')
        try:
            server.process.wait(60)
        except Exception:
            server.process.kill()
        (a.work / 'server-output.log').write_text(''.join(server.log))
        (a.work / 'results.txt').write_text('\n'.join(results) + '\n')
    print('DONE', sum(r.startswith('PASS') for r in results), '/', len(results))
    return 0 if all(r.startswith('PASS') for r in results) else 1


if __name__ == '__main__':
    sys.exit(main())
