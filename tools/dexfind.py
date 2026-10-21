#!/usr/bin/env python3
"""
最小 DEX 解析器：按「方法签名特征」定位宿主里的关键方法，不依赖混淆名。

背景：云服务每版混淆名都变（wo.c → 别的），硬编码必然失效。
但**方法签名**是稳定的：福利中心 URL 的构造函数一定接收
`WebUrlConstants$UrlType` 枚举并返回 String；打开网页的方法一定接收
(Activity, String)。按签名找，就跨版本稳定。

用法：
  python3 dexfind.py <dex文件> url        找 UrlType -> String 的方法
  python3 dexfind.py <dex文件> launch     找 (Activity, String) -> ? 的方法
  python3 dexfind.py <dex文件> contains <子串>   找名字含子串的类
"""
import struct
import sys


class Dex:
    def __init__(self, path):
        self.b = open(path, 'rb').read()
        b = self.b
        self.string_ids_size, self.string_ids_off = struct.unpack_from('<II', b, 0x38)
        self.type_ids_size, self.type_ids_off = struct.unpack_from('<II', b, 0x40)
        self.proto_ids_size, self.proto_ids_off = struct.unpack_from('<II', b, 0x48)
        self.method_ids_size, self.method_ids_off = struct.unpack_from('<II', b, 0x58)
        self.class_defs_size, self.class_defs_off = struct.unpack_from('<II', b, 0x60)
        self._strings = {}

    def uleb128(self, off):
        result = shift = 0
        while True:
            b = self.b[off]
            off += 1
            result |= (b & 0x7f) << shift
            if not (b & 0x80):
                return result, off
            shift += 7

    def string(self, idx):
        if idx in self._strings:
            return self._strings[idx]
        off = struct.unpack_from('<I', self.b, self.string_ids_off + 4 * idx)[0]
        _, off = self.uleb128(off)
        end = self.b.index(b'\x00', off)
        s = self.b[off:end].decode('utf-8', 'replace')
        self._strings[idx] = s
        return s

    def type_str(self, idx):
        return self.string(struct.unpack_from('<I', self.b, self.type_ids_off + 4 * idx)[0])

    def proto(self, idx):
        shorty, ret, params_off = struct.unpack_from('<III', self.b, self.proto_ids_off + 12 * idx)
        params = []
        if params_off:
            size = struct.unpack_from('<I', self.b, params_off)[0]
            for i in range(size):
                t = struct.unpack_from('<H', self.b, params_off + 4 + 2 * i)[0]
                params.append(self.type_str(t))
        return self.type_str(ret), params

    def methods(self):
        for i in range(self.method_ids_size):
            cls, proto, name = struct.unpack_from('<HHI', self.b, self.method_ids_off + 8 * i)
            yield self.type_str(cls), self.proto(proto), self.string(name)


def find_url_builder(d):
    hits = []
    for cls, (ret, params), name in d.methods():
        if ret != 'Ljava/lang/String;':
            continue
        if len(params) == 1 and params[0].endswith('WebUrlConstants$UrlType;'):
            hits.append((cls, name, params[0]))
    return hits


def find_launcher(d):
    hits = []
    for cls, (ret, params), name in d.methods():
        if len(params) == 2 and params[0] == 'Landroid/app/Activity;' and params[1] == 'Ljava/lang/String;':
            hits.append((cls, name, ret))
    return hits


def find_contains(d, needle):
    hits = set()
    for cls, (ret, params), name in d.methods():
        if needle in cls or needle in name:
            hits.add(cls)
    return sorted(hits)


def main():
    path, mode = sys.argv[1], sys.argv[2]
    d = Dex(path)
    if mode == 'url':
        for cls, name, p in find_url_builder(d):
            print(f"URL builder: {cls}  ->  {name}({p})")
    elif mode == 'launch':
        for cls, name, ret in find_launcher(d):
            print(f"launcher:    {cls}  ->  {name}(Activity, String) : {ret}")
    elif mode == 'contains':
        for c in find_contains(d, sys.argv[3]):
            print(c)
    elif mode == 'stats':
        print(f"strings={d.string_ids_size} types={d.type_ids_size} "
              f"protos={d.proto_ids_size} methods={d.method_ids_size} classes={d.class_defs_size}")


if __name__ == '__main__':
    main()
