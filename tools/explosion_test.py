#!/usr/bin/env python3
"""Blows a temple up again and again in the vanilla 1.16.1 server and checks every crater for a survivable fall.

The explosion uses the server's own random, which is seeded differently every run, so every new world gets a
different crater. This measures how often it leaves anything a falling player could land on:

1. The world is generated with the temple's chunks loaded but not ticking, so the golem hangs in the shaft and
   nothing has exploded yet, and saved as a snapshot.
2. For each explosion the snapshot is restored, the temple's chunks are force-loaded so the golem drops onto the
   pressure plate and the TNT goes off, and the saved crater is scanned (see crater.py).

usage: explosion_test.py SEED TEMPLE_CHUNK_X TEMPLE_CHUNK_Z EXPLOSIONS --accept-eula [--server JAR] [--workers N]

The temple chunk is the finder's "/tp X 100 Z" divided by 16, e.g. /tp -208 100 160 is chunk -13 10.
"""
import argparse
import os
import queue
import re
import shutil
import subprocess
import sys
import threading
import time

import crater

SERVER_URL = 'https://piston-data.mojang.com/v1/objects/a412fd69db1f81db3f511c1463fd304675244077/server.jar'


def remove_tree(path):
    # on Windows the server's files can stay locked for a moment after it exits
    for _ in range(20):
        shutil.rmtree(path, ignore_errors=True)
        if not os.path.exists(path):
            return
        time.sleep(0.5)


class Server:
    def __init__(self, args, wd, port):
        with open(os.path.join(wd, 'eula.txt'), 'w') as f:
            f.write('eula=true\n')
        with open(os.path.join(wd, 'server.properties'), 'w') as f:
            f.write(f'level-seed={args.seed}\nonline-mode=false\nspawn-protection=0\nmax-players=1\nview-distance=10\n'
                    f'level-type=default\ngenerate-structures=true\nserver-port={port}\nenable-rcon=false\n'
                    f'snooper-enabled=false\n')
        self.proc = subprocess.Popen([args.java, '-Xmx2G', '-jar', os.path.abspath(args.server), 'nogui'], cwd=wd,
                                     stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                     text=True, encoding='utf-8', errors='replace', bufsize=1)
        self.lines = queue.Queue()
        threading.Thread(target=self._reader, daemon=True).start()
        try:
            # 1.16.1 sometimes hangs loading the spawn area when two worker threads load jigsaw pieces at once
            self.wait_for(r'Done \(', timeout=300)
        except (TimeoutError, RuntimeError):
            self.proc.kill()
            self.proc.wait()
            raise

    def _reader(self):
        for line in self.proc.stdout:
            self.lines.put(line.rstrip('\n'))
        self.lines.put(None)

    def wait_for(self, pattern, timeout=300):
        end, rx = time.time() + timeout, re.compile(pattern)
        while time.time() < end:
            try:
                line = self.lines.get(timeout=1)
            except queue.Empty:
                continue
            if line is None:
                raise RuntimeError('server exited')
            if rx.search(line):
                return line
        raise TimeoutError(pattern)

    def cmd(self, c, expect=None, timeout=20):
        while True:
            try:
                self.lines.get_nowait()
            except queue.Empty:
                break
        self.proc.stdin.write(c + '\n')
        self.proc.stdin.flush()
        return self.wait_for(expect, timeout) if expect else None

    def test(self, condition):
        """True, False, or None if the position isn't loaded."""
        line = self.cmd('execute if ' + condition, expect=r'(Test passed|Test failed|not loaded)')
        return None if 'not loaded' in line else 'passed' in line

    def stop(self):
        self.cmd('stop')
        try:
            self.proc.wait(timeout=120)
        except subprocess.TimeoutExpired:
            self.proc.kill()


def start_server(args, wd, port, report, snapshot=None):
    """Starts the server, from a fresh copy of the snapshot if there is one, and retries when it hangs."""
    for attempt in range(4):
        if snapshot:
            remove_tree(os.path.join(wd, 'world'))
            shutil.copytree(snapshot, os.path.join(wd, 'world'), dirs_exist_ok=True)
        try:
            return Server(args, wd, port)
        except (TimeoutError, RuntimeError) as e:
            report(f'RETRY server start failed ({type(e).__name__})')
            if not snapshot:
                remove_tree(os.path.join(wd, 'world'))
    raise RuntimeError('server never started')


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('seed', type=int)
    ap.add_argument('tx', type=int, help="temple chunk x, the finder's /tp x divided by 16")
    ap.add_argument('tz', type=int, help="temple chunk z, the finder's /tp z divided by 16")
    ap.add_argument('explosions', type=int)
    ap.add_argument('--accept-eula', action='store_true',
                    help='you accept the Minecraft EULA (https://aka.ms/MinecraftEULA), needed to run the server')
    ap.add_argument('--server', default=os.path.join(os.path.dirname(os.path.abspath(__file__)), 'server-1.16.1.jar'),
                    help='the vanilla 1.16.1 server jar (default: server-1.16.1.jar next to this script)')
    ap.add_argument('--java', default='java', help='the java command to run the server with')
    ap.add_argument('--work', default='explosion-tests', help='folder for the test worlds')
    ap.add_argument('--port', type=int, default=25650, help='first server port, each worker uses the next one')
    ap.add_argument('--workers', type=int, default=1, help='servers running explosions in parallel, 2 GB RAM each')
    ap.add_argument('--out', default=None, help='also append the results to this file')
    args = ap.parse_args()
    if not args.accept_eula:
        sys.exit('The server only runs if you accept the Minecraft EULA (https://aka.ms/MinecraftEULA). '
                 'Pass --accept-eula if you do.')
    if not os.path.exists(args.server):
        sys.exit(f'No server jar at {args.server}. Download the 1.16.1 server from\n  {SERVER_URL}\n'
                 f'and save it there, or pass --server with its path.')

    tx, tz = args.tx, args.tz
    cx, cz = tx * 16 + 10, tz * 16 + 10
    root = os.path.join(args.work, str(args.seed))
    snapshot, wd = os.path.join(root, 'snapshot'), os.path.join(root, 'run')
    remove_tree(root)
    os.makedirs(wd)
    out = open(args.out, 'a') if args.out else None

    def report(line):
        print(line, flush=True)
        if out:
            print(line, file=out, flush=True)

    # 1. Generate the world with the temple loaded but frozen. The chunk forced diagonally next to the temple's
    # makes the temple chunk ticking without entity ticks (level 32) and the ones around it border chunks (level 33).
    t0 = time.time()
    report(f'generating seed {args.seed} and saving the temple at chunk {tx} {tz} before it explodes...')
    server = start_server(args, wd, args.port, report)
    server.cmd(f'forceload add {(tx - 1) * 16} {(tz - 1) * 16}', expect=r'(Marked|already)', timeout=120)
    for _ in range(240):
        if server.test(f'block {cx} 51 {cz} minecraft:tnt') is not None \
                and server.test(f'block {(tx + 1) * 16 + 15} 0 {(tz + 1) * 16 + 15} minecraft:bedrock') is not None:
            break
        time.sleep(0.5)
    tnt = server.test(f'block {cx} 51 {cz} minecraft:tnt')
    golem = server.test(f'entity @e[type=minecraft:iron_golem,x={cx - 1},y=53,z={cz - 1},dx=3,dy=12,dz=3]')
    server.cmd('forceload remove all', expect=r'(Unmarked|No chunks)', timeout=60)
    server.stop()
    report(f'SNAPSHOT seed={args.seed} temple={tx},{tz} tnt_in_place={tnt} golem_in_shaft={golem} '
           f'secs={time.time() - t0:.0f}')
    if not tnt or not golem:
        report('No TNT or no golem in the shaft, so this temple doesn\'t explode on its own (or already did, if it '
               'is in the spawn chunks). Check the seed and the temple chunk.')
        return
    shutil.copytree(os.path.join(wd, 'world'), snapshot)

    # 2. every explosion: restore the snapshot, let the temple tick and scan the crater
    lock = threading.Lock()
    counts = {'next': 0, 'deadly': 0, 'done': 0}

    def worker(index):
        run_dir = os.path.join(root, f'run{index}')
        os.makedirs(run_dir, exist_ok=True)
        port = args.port + index
        while True:
            with lock:
                if counts['next'] >= args.explosions:
                    return
                counts['next'] += 1
                trial = counts['next']
            t0 = time.time()
            server = start_server(args, run_dir, port, report, snapshot)
            server.cmd(f'forceload add {(tx - 1) * 16} {(tz - 1) * 16} {(tx + 1) * 16} {(tz + 1) * 16}',
                       expect=r'(Marked|already)', timeout=120)
            exploded = False
            end = time.time() + 90
            while time.time() < end:
                if server.test(f'block {cx} 51 {cz} minecraft:tnt') is False \
                        and not server.test('entity @e[type=minecraft:tnt]'):
                    exploded = True
                    break
                time.sleep(0.5)
            # let loosened sand and gravel land, but one that falls into a chunk that isn't ticking never does
            settle = time.time() + 10
            while exploded and time.time() < settle and server.test('entity @e[type=minecraft:falling_block]'):
                time.sleep(0.5)
            time.sleep(1.0)
            server.stop()
            r = crater.analyze(os.path.join(run_dir, 'world'), tx, tz)
            ok = exploded and r['tnt_left'] == 0
            with lock:
                counts['done'] += ok
                counts['deadly'] += ok and r['deadly']
                report(f"EXPLOSION {trial} exploded={ok} deadly={r['deadly']} shaft_open_from_y={r['shaft_exit_y']} "
                       f"survivable={r['survivable']} water={r['water']} cobweb={r['cobweb']} "
                       f"closest_call={r['closest_call']} secs={time.time() - t0:.0f}")

    threads = [threading.Thread(target=worker, args=(i,)) for i in range(args.workers)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    report(f"SUMMARY seed={args.seed} temple={tx},{tz}: deadly after {counts['deadly']} of {counts['done']} explosions")
    remove_tree(root)


if __name__ == '__main__':
    main()
