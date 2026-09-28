"""Looks at an exploded temple's crater in a saved world for anywhere a falling player could survive.

The player drops from the temple floor (feet at Y=65) down the 3x3 shaft and strafes in the air as far as they can.
Landing on anything with their feet at Y 43 or higher is survivable, and so is landing in water or cobwebs.
"""
import math
import os

import nbt

PASSABLE = ('air', 'cave_air', 'void_air')


def reach_table(exit_y=57.0, v0=0.22):
    """How far sideways the player can get by the time their feet pass each Y (index), sprint strafing in the air
    (0.026 blocks/tick, 0.91 drag) from leaving the shaft at exit_y with v0 blocks/tick, like the finder's
    DeadlyFall."""
    reach = [0.0] * 66
    y, vy, vh, drift, height = 65.0, 0.0, v0, 0.0, 65
    while height >= 0:
        if y <= exit_y:
            vh += 0.026
            drift += vh
            vh *= 0.91
        y += vy
        vy = (vy - 0.08) * 0.98
        while height >= 0 and y <= height:
            reach[height] = drift
            height -= 1
    return reach


def analyze(world_dir, tx, tz):
    """The crater of the temple in chunk (tx, tz): what a player falling down the shaft can land on."""
    w = nbt.World(os.path.join(world_dir, 'region'))
    sx, sz = tx * 16 + 9, tz * 16 + 9

    def need(x, z):
        # how far the player's centre has to move from anywhere in the shaft for their 0.6 wide hitbox to overlap
        # the column
        return math.hypot(max(0.0, sx - x - 1.0, x - sx - 3.0), max(0.0, sz - z - 1.0, z - sz - 3.0))

    def enterable(b):
        return b in PASSABLE or b.startswith('water') or b.startswith('lava') or b == 'cobweb'

    # The player is stuck in the 3x3 shaft until their feet are low enough that the explosion left two blocks of
    # air on some side of it to move into; from there they can strafe away
    ring = [(sx - 1, z) for z in range(sz, sz + 3)] + [(sx + 3, z) for z in range(sz, sz + 3)] \
        + [(x, sz - 1) for x in range(sx, sx + 3)] + [(x, sz + 3) for x in range(sx, sx + 3)]
    exit_y = 0
    for x, z in ring:
        for y in range(63, 0, -1):
            if w.block(x, y, z) in PASSABLE and w.block(x, y + 1, z) in PASSABLE:
                exit_y = max(exit_y, y)
                break
    # 0.25 blocks/tick is about the most a run across the 3x3 shaft gives
    real_reach = reach_table(exit_y, 0.25)

    # Everywhere the player can get to steering in the air (not through walls), and what they land on there.
    # A column is reachable at height y if it is within steering reach and connected through open blocks at y
    # to a column that was reachable one block higher.
    reachable = {(x, z) for x in range(sx, sx + 3) for z in range(sz, sz + 3)}
    landings = {'water': [], 'cobweb': [], 'lava': 0, 'survivable_solid': [], 'lethal_solid': 0}
    for y in range(63, 0, -1):
        # a tenth of a block more than the simulated air strafing reaches
        r = real_reach[y] + 0.1
        frontier = [(x, z) for x, z in reachable if enterable(w.block(x, y, z)) and need(x, z) <= r]
        seen = set(frontier)
        while frontier:
            x, z = frontier.pop()
            for dx in (-1, 0, 1):
                for dz in (-1, 0, 1):
                    n = (x + dx, z + dz)
                    # moving sideways into a column needs room for the whole body, two blocks high
                    if n in seen or need(*n) > r or not enterable(w.block(n[0], y, n[1])) \
                            or not enterable(w.block(n[0], y + 1, n[1])):
                        continue
                    seen.add(n)
                    frontier.append(n)
        falling = set()
        for x, z in seen:
            b = w.block(x, y, z)
            if b.startswith('water'):
                landings['water'].append((x, y, z))
            elif b == 'cobweb':
                landings['cobweb'].append((x, y, z))
            elif b.startswith('lava'):
                landings['lava'] += 1
            elif not enterable(w.block(x, y - 1, z)):
                if y >= 43:
                    landings['survivable_solid'].append((x, y - 1, z, w.block(x, y - 1, z)))
                else:
                    landings['lethal_solid'] += 1
            else:
                falling.add((x, z))
        reachable = falling
        if not reachable:
            break

    # Closest call: of every block with two blocks of air above it that a player could stand on at feet Y 43+, how
    # far the nearest is beyond reach (walls ignored). Negative means in reach. With the explosion's real shaft
    # opening, and with the finder's worst case (open up to Y=57, 0.26 blocks/tick).
    worst_reach = reach_table(57, 0.26)
    closest, worst_closest = (99.0,), (99.0,)
    for x in range(sx - 9, sx + 12):
        for z in range(sz - 9, sz + 12):
            n = need(x, z)
            if n > 9:
                continue
            for y in range(42, 63):
                b = w.block(x, y, z)
                if enterable(b) or w.block(x, y + 1, z) not in PASSABLE or w.block(x, y + 2, z) not in PASSABLE:
                    continue
                m = n - real_reach[y + 1]
                if m < closest[0]:
                    closest = (round(m, 2), x, y, z, b)
                m = n - worst_reach[y + 1]
                if m < worst_closest[0]:
                    worst_closest = (round(m, 2), x, y, z, b)

    tnt_left = sum(w.block(sx + dx, 51, sz + dz) == 'tnt' for dx in range(3) for dz in range(3))
    deadly = not landings['water'] and not landings['cobweb'] and not landings['survivable_solid']
    return {'deadly': deadly, 'tnt_left': tnt_left, 'shaft_exit_y': exit_y,
            'lava': landings['lava'], 'lethal_solid': landings['lethal_solid'],
            'water': landings['water'][:5], 'cobweb': landings['cobweb'][:5],
            'survivable': landings['survivable_solid'][:5],
            'closest_call': closest, 'worst_closest_call': worst_closest}
