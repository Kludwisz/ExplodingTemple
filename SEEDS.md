# Exploding temple seeds near spawn (Java 1.16.1)

## Deadly seeds

In these worlds the desert temple blows itself up. A pillager outpost's iron golem is placed inside the
temple's hidden shaft, drops onto the pressure plate and sets off the TNT. The explosion opens the shaft into
a ravine with a lava lake at the bottom. A player with an empty inventory who breaks through the temple
floor falls about 55 blocks.

The explosion is random, so every new world gets its own crater, and whether it leaves anything to land on
depends on it. How often each temple stays deadly was measured by blowing it up again and again in the
vanilla server, see [Testing](#testing).

### Temple at -198 170: deadly after 98 of 100 explosions

| Seed | World spawn | Temple shaft | Distance |
|---|---|---|---|
| `-1286900188361416486` | -93 63 64 | -198 170 | 149 blocks |
| `1329972670117552346` | 48 70 222 | -198 170 | 251 blocks |
| `-5722945821321355046` | 0 71 14 | -198 170 | 252 blocks |
| `-6287303149626220326` | 0 71 14 | -198 170 | 252 blocks |
| `-7185771275286634278` | 0 71 14 | -198 170 | 252 blocks |
| `4975355093497258202` | 0 71 14 | -198 170 | 252 blocks |
| `8539109773630873818` | 0 73 14 | -198 170 | 252 blocks |
| `-1121955852008972070` | 0 74 13 | -198 170 | 253 blocks |

All of them are sister seeds of structure seed `3405159702746`, found from base seed `3063286574034` with
`Main 3063286574034 3063286574035 300` in an earlier version of the finder (commit `4b2acd7`), which lists 314
sister seeds with the spawn within 300 blocks of the temple. They share the temple, the outpost and the ravine;
biomes and the spawn point differ. Under the shaft is a lava lake of about 290 blocks.

For the first seed the finder estimated the spawn 238 blocks away, but the game puts it 149 blocks from the
temple. That is inside the spawn chunks, so the temple blows up as soon as the world is created. In the other
worlds it goes off when a player first comes within simulation distance of it.

The ravine is wide open under the TNT chamber. There is no rock anywhere a player could steer to below it,
and the rock around the chamber only starts at Y 51 with the ravine underneath, so the explosion has nothing
to chip into a ledge. Both times the fall wasn't deadly, gravel the explosion shook loose from a deposit next
to the chamber fell onto a ledge in the ravine wall at Y 39, two blocks east of the shaft, and piled up 3 high.
The top of the pile, at Y 42, is just high enough to survive landing on, with half a heart left, and a player
strafing toward it could reach it. Otherwise, in the last 80 explosions where it was measured, the nearest
spot to land on stayed at least 0.17 blocks out of reach. The fall was also deadly in all 8 freshly generated
test worlds.

### Temple at 298 -486: deadly in about 3 out of 4 worlds

| Seed | World spawn | Temple shaft | Distance |
|---|---|---|---|
| `-8984679548996753866` | 256 71 -256 | 298 -486 | 234 blocks |
| `-7573926965722945994` | 240 69 -256 | 298 -486 | 237 blocks |
| `-1215407241829226954` | 224 64 -256 | 298 -486 | 242 blocks |
| `-1402588101341813194` | 224 63 -256 | 298 -486 | 242 blocks |
| `2344969738583860790` | 224 69 -256 | 298 -486 | 242 blocks |
| `9118665053125797430` | 224 64 -256 | 298 -486 | 242 blocks |
| `-5340141550547179978` | 208 64 -256 | 298 -486 | 247 blocks |
| `4083359194748872246` | 208 64 -256 | 298 -486 | 247 blocks |
| `-3798784578079627722` | 256 78 -240 | 298 -486 | 250 blocks |
| `2112471407820858934` | 240 68 -192 | 298 -486 | 300 blocks |

All of them are sister seeds of structure seed `1707607385654`, found from base seed `1574709398113` with
`Main 1574709398113 1574709398114 300` in an earlier version of the finder (commit `4b2acd7`), which lists 55
sister seeds with the spawn 234 to 300 blocks from the temple. The temple is outside the spawn chunks, so it
goes off when a player first comes within simulation distance of it. Under the shaft is a lava lake of 280
blocks.

Here the ravine walls come within reach just below the crater, where the loose TNT goes off, and there is
rock around the chamber at Y 50. The explosion left something in reach after 5 of 30 test explosions and in
5 of 14 freshly generated worlds. Every seed in the table was deadly in its own test world, but a new world
gets its own explosion. The spots a perfect strafer could reach were:

- a block of ravine wall the TNT chipped at Y 42 or 43, about 2.2 blocks diagonally from the shaft (5 times);
- a ledge 2 to 3 blocks away at Y 43 to 50, a step in the ravine wall or chipped by the TNT, in reach because
  the explosion also blew the shaft open up to Y 57 or 58 (3 times);
- a block of the TNT chamber left right next to the shaft (twice).

### Testing

Each seed in both tables was generated in the vanilla 1.16.1 server, with the 9x9 chunks around the temple
force-loaded for 45 seconds so the golem drops and spring water has time to flow. Then the saved world was
scanned. The TNT was gone, and no water or cobweb got anywhere a falling player can reach.

To see how often the explosion leaves something to land on, each temple was also generated with its chunks
loaded but not ticking, so the golem hadn't dropped yet, and the world was saved. Then the temple was blown
up again and again, restoring that save before each explosion, and every crater was scanned for anything a
player falling down the shaft could reach while strafing perfectly in the air.

## Why the fall is deadly

- The player falls from the temple floor (feet at Y=65). Fall damage is the fall distance minus 3, so
  landing on anything at Y 42 or lower is 20+ damage. That kills a player with 20 HP and no armour.
- Landing in the lava doesn't help either. In shallow lava they hit the bottom at full speed. In deep lava
  they sink and can't climb out before burning.
- Only water or cobwebs can save them, or a spot to land on at Y 43 or higher.
- Sprint-strafing in the air they drift about 3 blocks sideways by Y=43, and about 8 by the time they reach
  the lava. How far exactly depends on how high the explosion opens the shaft walls, since they can't leave
  the shaft before that. In 140 test explosions that was Y 53 to 56, three times Y 57 and once Y 58.

## What the finder checks

These rules come from the tests above. The finder takes the shaft to be blown open up to Y 57 (the fifth
argument of `Main` changes that) and checks everything a player could reach from there:

- nothing to land on at Y 43 or higher. Below that, lava and stone kill alike, so the shaft doesn't have to
  end in lava;
- no rock at all in reach below the crater, from Y 42 to 50, and open ravine around the chamber at Y 50, so
  there's nothing for the TNT to blow into a ledge. Temples that fail this left ledges in testing, from 1 in
  4 explosions to every single one;
- no ledge in reach from Y 32 to 41 either, where sand or gravel the explosion shakes loose could pile up to
  a height you can survive landing on. That's what happened twice at the temple at -198 170;
- no mineshaft (cobwebs, planks);
- no water. Water springs are placed exactly as the game does, and their flow is simulated over the caves
  and ravines within 4 chunks of the temple. Spring water flooded the ravines under all the earlier seeds
  below.

Rivers and oceans near the temple change which caves generate, so every result is checked again with a
port of the game's own 1.16.1 cave and ravine carvers.

Neither temple above passes all of these, so the current finder skips both. A temple that does pass them
should be deadly however the TNT goes off, but those are rare. Of 178 temples earlier searches found with a
ravine under the shaft and a deadly fall, 3 pass, and none of those 3 has a dry ravine and a golem that
drops as well. The finder checks about 750M base seeds a second on 4 cores, so expect days of searching for
one within 300 blocks of spawn.

## Earlier seeds: the temple explodes, but the fall is survivable

These came from the first version of the finder (commit `6776a48`), before the fall had to be deadly.
Spring water flows into the ravines under them, so falling in is survivable.

Each seed below was generated with the vanilla 1.16.1 dedicated server, with default settings and no
force-loading. About 20 seconds after startup, all 9 TNT under the temple were gone, the pressure plate
was destroyed, and the iron golem was lying at the bottom of the ravine under the temple. The world spawn
is read from the generated `level.dat`. Every temple is 2-3 chunks from the spawn chunk, well inside the
spawn chunks, so it explodes as soon as the world starts ticking.

| Seed | World spawn | Temple shaft | Distance |
|---|---|---|---|
| `-6183441914434248472` | -124 64 22 | -150 26 | 26 blocks |
| `-1906429643315830552` | -119 61 41 | -150 26 | 34 blocks |
| `2107966474531545320` | -112 64 32 | -150 26 | 38 blocks |
| `5312840285017766474` | 32 63 160 | 42 122 | 39 blocks |
| `-7207729354684325656` | -125 63 60 | -150 26 | 42 blocks |
| `769552960272376040` | -112 64 48 | -150 26 | 44 blocks |
| `-4792392853871494582` | 16 64 158 | 42 122 | 44 blocks |
| `2780972869505415754` | 16 75 160 | 42 122 | 46 blocks |
| `4551450473015441994` | 88 68 111 | 42 122 | 47 blocks |

The seeds come from two structure seeds, so the temple, outpost and ravine are the same within a group.
Biomes and spawn position differ.

- Temple at `-150 26` (chunk -10 1): sister seeds of structure seed `373945442536`, found from base seed
  `32072313824`.
- Temple at `42 122` (chunk 2 7): sister seeds of structure seed `99604134474`, which is also its base
  seed.
