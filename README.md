# Exploding temple finder (Minecraft Java 1.16.1)

Finds seeds where a pillager outpost's iron golem is placed inside a desert temple's hidden shaft, falls onto the
pressure plate and blows the temple up by itself, and where falling down the shaft afterwards kills a player with
an empty inventory. The seeds found so far, and how they were tested, are in [SEEDS.md](SEEDS.md).

## Searching for seeds

The cave library the finder uses calls native code through Java's foreign function API and needs Java 23. Gradle
itself runs on whatever Java you have (8 to 23, so the Java 21 of a Minecraft server is fine) and compiles and runs
the finder with a JDK 23: one that is installed, or else one it downloads by itself (about 200 MB, once). The first
run also downloads Gradle and the libraries.

```
gradlew.bat run --args="4131000000000 281474976710656 300"      (Windows)
./gradlew run --args="4131000000000 281474976710656 300"        (Linux, macOS)
```

The arguments are the first base seed, the base seed to stop before, how far the temple may be from the world
spawn in blocks, and optionally the number of threads (all cores by default) and how high up the shaft the
explosion is assumed to blow the walls open at worst (Y 57 by default, 58 is stricter). Base seeds from about
1,856,000,000,000 to 4,131,000,000,000 have been searched already. 281474976710656 is the end of the seed space,
so the search just runs until you stop it.

A close spawn mostly comes with a temple close to 0 0, as the game puts the spawn within about 270 blocks of it. Of
the 9 temples earlier searches found world seeds for, the 2 within 150 blocks of 0 0 had world seeds with the spawn
within 50 blocks, and the 7 further out had none within 100. So with a small spawn distance the finder only checks
temples near 0 0: with 50 it gets through base seeds about 1.7 times as fast as with 300, but finds fewer temples.
Every result says how far the spawn is.

It prints a `[progress]` line every minute. `got a candidate structure seed` is not a result yet. A structure seed
only decides where the structures and caves go, the biomes come from the whole world seed, and 65536 world seeds
share each structure seed. Typed into Minecraft, the structure seed is just one of them, which almost never has a
desert where the temple goes. So the finder checks all 65536 and prints how many get through each check in turn:

```
  structure seed 6982474136324, sister seeds left after each check: biomes 4687, dry 0, spawn estimate close 0, structures spawn 0, golem drops 0, spawn close 0, deadly with vanilla carvers 0
```

- `biomes`: a desert at the temple and an outpost biome at the outpost
- `dry`: no spring water flows into the fall (here water floods the ravine in all 4687)
- `spawn estimate close`, `structures spawn`: quick versions of the spawn and biome checks
- `golem drops`: built on the real terrain, the outpost puts its golem low enough in the shaft to fall
- `spawn close`: the world spawn is within the distance you gave
- `deadly with vanilla carvers`: still deadly with the game's own caves. These are the results

Water rules out most candidates and the golem most of the rest; in past searches about 1 candidate in 100 had
world seeds. Each result is a line like

```
Got full world seed: -5722945821321355046 /tp -208 100 160 | spawn 0 71 14 | 252 blocks from spawn | ...
```

The `/tp` coordinates are the corner of the temple's chunk. To resume a stopped search, start again at the first
base seed plus the count on the last progress line; a bit of overlap doesn't matter.

## Testing a seed with repeated explosions

The explosion is random, so every new world gets a different crater. `tools/explosion_test.py` measures how often a
temple stays deadly: it generates the world in the vanilla 1.16.1 server with the temple frozen before the golem
drops, then blows it up again and again from that save and checks every crater for anything a falling player
could reach by strafing perfectly.

Needs Python 3.8 or newer and Java to run the server (it was tested with Java 21).

1. Download the official 1.16.1 server from
   https://piston-data.mojang.com/v1/objects/a412fd69db1f81db3f511c1463fd304675244077/server.jar
   and save it as `tools/server-1.16.1.jar`.
2. Run it with the seed, the temple chunk (the `/tp` x and z divided by 16) and the number of explosions:

   ```
   python tools/explosion_test.py -5722945821321355046 -13 10 20 --accept-eula --workers 2
   ```

   `--accept-eula` means you accept the Minecraft EULA (https://aka.ms/MinecraftEULA), which the server needs.
   `--workers` runs that many servers at once, each takes about 2 GB of RAM and 30 to 90 seconds per explosion.

Each explosion prints a line like

```
EXPLOSION 3 exploded=True deadly=True shaft_open_from_y=55 survivable=[] water=[] cobweb=[] closest_call=(0.72, -200, 53, 173, 'diorite') secs=88
```

`survivable` lists blocks a player could land on and live, `shaft_open_from_y` is how high the explosion opened the
shaft walls (the higher, the sooner a falling player can start steering), and `closest_call` is how many blocks
the nearest block to stand on was out of reach, with its position. At the end it prints how many explosions were
deadly. The test worlds go into an `explosion-tests` folder and are deleted afterwards.
