# -*- coding: utf-8 -*-
"""把 Java 生成的 Shazam 指纹直接 POST 到 Apple/Shazam 识曲接口，验证它能否被识别。

请求格式与 shazamio 的 send_recognize_request_v2 完全一致（只差注入我们自己的 uri）。
"""
import asyncio, json, os, time, uuid, random
import aiohttp
from shazamio.user_agent import USER_AGENTS

URL = ("https://amp.shazam.com/discovery/v5/en-US/GB/iphone/-/tag/{u1}/{u2}"
       "?sync=true&webv3=true&sampling=true&connected=&shazamapiversion=v3"
       "&sharehub=true&hubv5minorversion=v5.1&hidelb=true&video=v3")


def build_body(uri: str, samples: int) -> dict:
    return {
        "timezone": "Europe/Moscow",
        "signature": {"uri": uri, "samplems": samples},
        "timestamp": int(time.time()),
        "context": {},
        "geolocation": {},
    }


async def send(uri: str, samples: int):
    headers = {
        "X-Shazam-Platform": "IPHONE",
        "X-Shazam-AppVersion": "14.1.0",
        "Accept": "*/*",
        "Accept-Language": "en-US",
        "Accept-Encoding": "gzip, deflate",
        "User-Agent": random.choice(USER_AGENTS),
        "Content-Type": "application/json",
    }
    url = URL.format(u1=str(uuid.uuid4()).upper(), u2=str(uuid.uuid4()).upper())
    body = build_body(uri, samples)
    async with aiohttp.ClientSession() as sess:
        async with sess.post(url, headers=headers, json=body) as r:
            print("HTTP", r.status)
            text = await r.text()
    return text


async def main():
    here = os.path.dirname(os.path.abspath(__file__))
    uri = open(os.path.join(here, "mine.uri.txt"), encoding="utf-8").read().strip()
    samples = 192000
    t0 = time.time()
    text = await send(uri, samples)
    dt = time.time() - t0
    print(f"[{dt:.1f}s] 响应长度 {len(text)} 字节")
    try:
        data = json.loads(text)
        matches = data.get("matches") or []
        if matches:
            tr = matches[0].get("track", {})
            print("识别成功：", tr.get("title"), "-", tr.get("subtitle"))
            print("track key:", tr.get("key"), "| share:", tr.get("share", {}).get("subject"))
        else:
            print("无匹配。原始响应头部：", text[:400])
    except Exception as e:
        print("解析失败：", e, "原始响应头部：", text[:400])


if __name__ == "__main__":
    asyncio.run(main())
