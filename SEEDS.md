# Exploding temple seeds near spawn (Java 1.16.1)

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

Running `Main <base seed> <base seed + 1> 64` re-checks a single base seed and lists more sister seeds.
