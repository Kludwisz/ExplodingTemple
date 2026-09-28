"""Minimal reader for Minecraft 1.16 saves: NBT, and block names out of the region files."""
import gzip
import io
import os
import struct
import zlib


def _read(f, n):
    b = f.read(n)
    if len(b) != n:
        raise EOFError
    return b


def read_tag(f, t):
    if t == 1: return struct.unpack('>b', _read(f, 1))[0]
    if t == 2: return struct.unpack('>h', _read(f, 2))[0]
    if t == 3: return struct.unpack('>i', _read(f, 4))[0]
    if t == 4: return struct.unpack('>q', _read(f, 8))[0]
    if t == 5: return struct.unpack('>f', _read(f, 4))[0]
    if t == 6: return struct.unpack('>d', _read(f, 8))[0]
    if t == 7:
        n = struct.unpack('>i', _read(f, 4))[0]
        return _read(f, n)
    if t == 8:
        n = struct.unpack('>H', _read(f, 2))[0]
        return _read(f, n).decode('utf-8', 'replace')
    if t == 9:
        et = _read(f, 1)[0]
        n = struct.unpack('>i', _read(f, 4))[0]
        return [read_tag(f, et) for _ in range(n)]
    if t == 10:
        d = {}
        while True:
            tt = _read(f, 1)[0]
            if tt == 0:
                return d
            nl = struct.unpack('>H', _read(f, 2))[0]
            name = _read(f, nl).decode('utf-8', 'replace')
            d[name] = read_tag(f, tt)
    if t == 11:
        n = struct.unpack('>i', _read(f, 4))[0]
        return list(struct.unpack('>%di' % n, _read(f, 4 * n)))
    if t == 12:
        n = struct.unpack('>i', _read(f, 4))[0]
        return list(struct.unpack('>%dq' % n, _read(f, 8 * n)))
    raise ValueError('bad tag %d' % t)


def parse(data):
    f = io.BytesIO(data)
    t = _read(f, 1)[0]
    nl = struct.unpack('>H', _read(f, 2))[0]
    _read(f, nl)
    return read_tag(f, t)


def load_gz(path):
    with gzip.open(path, 'rb') as fh:
        return parse(fh.read())


def load_region_chunk(region_path, cx, cz):
    """The chunk's NBT for absolute chunk coordinates (cx, cz) from an .mca file, or None."""
    with open(region_path, 'rb') as fh:
        data = fh.read()
    idx = 4 * ((cx & 31) + (cz & 31) * 32)
    off = int.from_bytes(data[idx:idx + 3], 'big')
    if off == 0:
        return None
    start = off * 4096
    length = struct.unpack('>i', data[start:start + 4])[0]
    comp = data[start + 4]
    payload = data[start + 5:start + 4 + length]
    if comp == 2:
        raw = zlib.decompress(payload)
    elif comp == 1:
        raw = gzip.decompress(payload)
    else:
        raw = payload
    return parse(raw)


class World:
    """Block names by absolute position in a 1.16 world's region folder, water and lava with their level."""

    def __init__(self, region_dir):
        self.region_dir = region_dir
        self.chunks = {}

    def _chunk(self, cx, cz):
        key = (cx, cz)
        if key not in self.chunks:
            path = os.path.join(self.region_dir, 'r.%d.%d.mca' % (cx >> 5, cz >> 5))
            data = load_region_chunk(path, cx, cz) if os.path.exists(path) else None
            sections = {}
            status = None
            if data is not None:
                level = data['Level']
                status = level.get('Status')
                for sec in level.get('Sections', []):
                    if 'Palette' not in sec or 'BlockStates' not in sec:
                        continue
                    pal = []
                    for st in sec['Palette']:
                        name = st['Name'].replace('minecraft:', '')
                        props = st.get('Properties', {})
                        if name in ('water', 'lava') and 'level' in props:
                            name = '%s[%s]' % (name, props['level'])
                        pal.append(name)
                    bits = max(4, (len(pal) - 1).bit_length())
                    per_long = 64 // bits
                    mask = (1 << bits) - 1
                    longs = [v & 0xFFFFFFFFFFFFFFFF for v in sec['BlockStates']]
                    idx = [(longs[i // per_long] >> ((i % per_long) * bits)) & mask for i in range(4096)]
                    sections[sec['Y']] = (pal, idx)
            self.chunks[key] = (sections, status)
        return self.chunks[key]

    def status(self, cx, cz):
        return self._chunk(cx, cz)[1]

    def block(self, x, y, z):
        sections, _ = self._chunk(x >> 4, z >> 4)
        sec = sections.get(y >> 4)
        if sec is None:
            return 'air'
        pal, idx = sec
        return pal[idx[((y & 15) << 8) | ((z & 15) << 4) | (x & 15)]]
