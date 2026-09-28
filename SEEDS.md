# Exploding temple seeds near spawn (Java 1.16.1)

## Deadly seeds

In these worlds the desert temple blows itself up. A pillager outpost's iron golem is placed inside the
temple's hidden shaft, drops onto the pressure plate and sets off the TNT. The explosion opens the shaft into
a ravine with a lava lake at the bottom. A player with an empty inventory who breaks through the temple
floor falls about 55 blocks, and every spot they can steer to in the air kills them.

| Seed | World spawn | Temple shaft | Distance | Test world |
|---|---|---|---|---|
| `-8984679548996753866` | 256 71 -256 | 298 -486 | 234 blocks | deadly |
| `-7573926965722945994` | 240 69 -256 | 298 -486 | 237 blocks | deadly |
| `-1215407241829226954` | 224 64 -256 | 298 -486 | 242 blocks | deadly |
| `-1402588101341813194` | 224 63 -256 | 298 -486 | 242 blocks | deadly |
| `2344969738583860790` | 224 69 -256 | 298 -486 | 242 blocks | deadly |
| `9118665053125797430` | 224 64 -256 | 298 -486 | 242 blocks | deadly |
| `-5340141550547179978` | 208 64 -256 | 298 -486 | 247 blocks | deadly |
| `4083359194748872246` | 208 64 -256 | 298 -486 | 247 blocks | deadly |
| `-3798784578079627722` | 256 78 -240 | 298 -486 | 250 blocks | deadly |
| `2112471407820858934` | 240 68 -192 | 298 -486 | 300 blocks | deadly |

All of them are sister seeds of structure seed `1707607385654`, found from base seed `1574709398113` with
`Main 1574709398113 1574709398114 300`, which lists 55 sister seeds with the spawn 234 to 300 blocks from
the temple. They share the temple, the outpost and the ravine; biomes and the spawn point differ. The temple
is outside the spawn chunks, so it goes off when a player first comes within simulation distance of it.

Each seed was generated in the vanilla 1.16.1 server, with the 9x9 chunks around the temple force-loaded
for 45 seconds so the golem drops and spring water has time to flow. Then the saved world was scanned.
The TNT was gone. Under the shaft is a lava lake of 280 blocks, with lava under all of the shaft and at
least 3 blocks around it. A falling player can't reach any water or cobweb, or anywhere at Y 43 or higher to
land on.

**The explosion is different in every new world.** Primed TNT gets knocked into the ravine and goes off at
different heights. Of 14 test worlds made from these sister seeds, 9 were deadly as above. In the other 5
the TNT left a ledge:

- twice, a wall block right next to the shaft that is easy to land on;
- three times, a ledge chipped into the ravine wall about 2.2 blocks from the shaft at Y 43, which only a
  perfect diagonal fall could just reach.

The seeds marked deadly were deadly in their test world, but a new world made from them can still get one
of those ledges. Each new world gets its own explosion, so if it matters, look at the crater under the
floor first.

### Why the fall is deadly

- The player falls from the temple floor (feet at Y=65). Fall damage is the fall distance minus 3, so
  landing on anything at Y 42 or lower is 20+ damage. That kills a player with 20 HP and no armour.
- Landing in the lava doesn't help either. In shallow lava they hit the bottom at full speed. In deep lava
  they sink and can't climb out before burning.
- Only water or cobwebs can save them, or a spot to land on at Y 43 or higher.
- Sprint-strafing in the air they drift at most about 3 blocks sideways by Y=43, and about 7 by the time
  they reach the lava.

The finder checks everything in that reach:

- lava at Y=10 and open air up to Y=48 for 2 blocks around the middle of the shaft;
- no ledge at Y 43 or higher;
- no mineshaft (cobwebs, planks);
- no water. Water springs are placed exactly as the game does, and their flow is simulated over the caves
  and ravines within 4 chunks of the temple. Spring water flooded the ravines under all the earlier seeds
  below.

Rivers and oceans near the temple change which caves generate, so every result is checked again with a
port of the game's own 1.16.1 cave and ravine carvers.

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
