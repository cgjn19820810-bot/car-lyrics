# -*- coding: utf-8 -*-
"""
ShazamIO 连通性 + 识别实测。
目标：验证三件事
  1. 能不能连上 Apple 的 Shazam 服务（国内直连 vs 走本地代理）
  2. 识别流程跑不跑得通
  3. 识别结果（先用 Sojiro - 希望 这首纯音乐当样本）
跑法：
  python shazam_test.py [音频路径] [--proxy http://127.0.0.1:56300]
"""
import asyncio
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))

async def main(path):
    from shazamio import Shazam, Serialize

    t0 = time.time()
    try:
        async with Shazam() as shazam:
            out = await shazam.recognize(path)
            dt = time.time() - t0
            if not out or not out.get("matches"):
                print(f"[{dt:.1f}s] 无匹配（matches 为空）")
                return
            track = Serialize.full_track(out).track
            print(f"[{dt:.1f}s] 识别成功: {track.title} - {track.subtitle}")
    except Exception as e:
        dt = time.time() - t0
        print(f"[{dt:.1f}s] 识别失败: {type(e).__name__}: {str(e)[:400]}")

if __name__ == "__main__":
    # 默认用本目录 sample.wav（版权音乐，未随仓库分发，见 test/README.md）
    path = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "sample.wav")
    print(f"样本: {path}")
    # 先直连跑一次
    try:
        asyncio.run(asyncio.wait_for(main(path), timeout=60))
    except asyncio.TimeoutError:
        print("直连超时（60s）—— Apple 服务国内直连不通，需走代理")
    except Exception as e:
        print("顶层异常:", type(e).__name__, str(e)[:400])
