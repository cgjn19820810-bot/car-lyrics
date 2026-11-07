# -*- coding: utf-8 -*-
"""dump shazamio-core 生成的真实指纹，作为 Java 移植的黄金基准。"""
import asyncio, sys, base64, binascii, os

HERE = os.path.dirname(os.path.abspath(__file__))

async def main(path):
    from shazamio_core import Recognizer
    rec = Recognizer(segment_duration_seconds=12)
    sig = await rec.recognize_path(value=path, options=None)
    print("=== Signature 对象字段 ===")
    print("timezone:", sig.timezone)
    print("timestamp:", sig.timestamp)
    s = sig.signature
    print("signature.uri 前 80 字符:", s.uri[:80])
    print("signature.samples:", s.samples)
    # 解析 uri：是否带 data URI 前缀
    if s.uri.startswith("data:"):
        b64 = s.uri.split(",", 1)[1]
    else:
        b64 = s.uri
    raw = base64.b64decode(b64)
    print("raw 指纹字节数:", len(raw))
    print("raw 前 16 字节 hex:", raw[:16].hex(" "))
    # 解析 header
    import struct
    magic1, crc32, sz, magic2 = struct.unpack_from("<IIII", raw, 0)
    print(f"magic1={magic1:#x} (期望 0xcafe2580)")
    print(f"crc32={crc32:#x}")
    print(f"size_minus_header={sz}")
    print(f"magic2={magic2:#x} (期望 0x94119c00)")
    calc = binascii.crc32(raw[8:]) & 0xFFFFFFFF
    print(f"crc32 重算={calc:#x} 匹配={calc == crc32}")
    # 存原始二进制 + b64 供 Java 对比
    open(os.path.join(HERE, "golden.raw"), "wb").write(raw)
    open(os.path.join(HERE, "golden.uri.txt"), "w").write(s.uri)

if __name__ == "__main__":
    # 默认用本目录的 sample.wav（版权音乐，未随仓库分发 ——
    # 自己切一段 12 秒的 16kHz 单声道 wav 放这里即可，见 test/README.md）
    p = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "sample.wav")
    asyncio.run(main(p))
