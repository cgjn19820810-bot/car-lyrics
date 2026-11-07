# -*- coding: utf-8 -*-
"""
歌词源覆盖实测：拿真实歌名逐个源试，看谁能拿到带时间轴的歌词。

为什么要这个脚本：歌词源没有"官方文档"可查（除 LRCLIB），
QQ音乐 / 酷狗 / 咪咕 全是非官方接口，覆盖率和字段格式只能实测。
App 里每加一个源之前，先用这个跑一遍真实歌名。

用法:
    python -X utf8 probe_lyrics.py "阮天大" "上班族狂想曲"
    python -X utf8 probe_lyrics.py --only qq "歌手" "歌名"
"""
import sys
import json
import base64
import urllib.request
import urllib.parse
import socket

socket.setdefaulttimeout(15)
# 不走任何代理（本机有 WorkBuddy 的本地代理，会把境外源搞成 000）
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))

UA = ("Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")


def get(url, headers=None, data=None):
    h = {"User-Agent": UA}
    if headers:
        h.update(headers)
    req = urllib.request.Request(url, data=data, headers=h)
    with OPENER.open(req) as r:
        return r.read().decode("utf-8", "replace")


def enc(s):
    return urllib.parse.quote(s or "")


def body_lines(txt, n=20):
    """只取真正的歌词行，丢掉 [ti:] [ar:] [al:] [by:] [offset:] 这些元信息"""
    out = []
    for ln in (txt or "").split("\n"):
        ln = ln.strip()
        if not ln:
            continue
        if ln[0] == "[" and len(ln) > 3 and ln[3] == ":":
            continue
        out.append(ln)
        if len(out) >= n:
            break
    return out


def brief(txt, n=2):
    """截取前 n 行歌词，用于人工确认是不是同一首歌"""
    return " / ".join(body_lines(txt, n))


def show(txt, n=18):
    for ln in body_lines(txt, n):
        print("            " + ln[:60])


def has_ts(txt):
    """有没有 [mm:ss] 时间轴"""
    import re
    return bool(re.search(r"\[\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?\]", txt or ""))


# ---------------------------------------------------------------- LRCLIB
def lrclib(artist, title):
    url = ("https://lrclib.net/api/search?q=" + enc(artist + " " + title))
    body = get(url)
    arr = json.loads(body)
    if not arr:
        return None, "搜索无结果"
    for o in arr[:5]:
        s = o.get("syncedLyrics") or ""
        if s:
            return s, "synced"
        if o.get("plainLyrics"):
            return o["plainLyrics"], "plain"
    return None, "有记录但无歌词字段"


# ---------------------------------------------------------------- 网易云
def netease(artist, title):
    h = {"Referer": "https://music.163.com/"}
    kw = f"{title} {artist}"
    s = get("https://music.163.com/api/search/get/web?csrf_token=&s=" + enc(kw)
            + "&type=1&offset=0&total=true&limit=5", h)
    j = json.loads(s)
    songs = (j.get("result") or {}).get("songs") or []
    if not songs:
        return None, "搜不到"
    sid = songs[0]["id"]
    who = (songs[0].get("artists") or [{}])[0].get("name", "?")
    s2 = get(f"https://music.163.com/api/song/lyric?id={sid}&lv=1&kv=1&tv=-1", h)
    j2 = json.loads(s2)
    txt = ((j2.get("lrc") or {}).get("lyric") or "")
    if not txt.strip():
        return None, f"搜到《{songs[0].get('name')}》- {who}，但无歌词"
    return txt, f"{songs[0].get('name')} - {who}"


# ---------------------------------------------------------------- QQ音乐
def qq(artist, title):
    h = {"Referer": "https://y.qq.com/portal/player.html"}
    kw = f"{title} {artist}"
    s = get("https://c.y.qq.com/soso/fcgi-bin/client_search_cp?p=1&n=5&w="
            + enc(kw) + "&format=json", h)
    j = json.loads(s)
    lst = ((j.get("data") or {}).get("song") or {}).get("list") or []
    if not lst:
        return None, "搜不到"
    hit = lst[0]
    mid = hit.get("songmid")
    singers = "/".join(x.get("name", "") for x in (hit.get("singer") or []))
    s2 = get("https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid="
             + enc(mid) + "&format=json&nobase64=1", h)
    j2 = json.loads(s2)
    txt = j2.get("lyric") or ""
    if not txt.strip():
        return None, f"搜到《{hit.get('songname')}》- {singers}，但无歌词"
    return txt, f"{hit.get('songname')} - {singers}"


# ---------------------------------------------------------------- 酷狗
def kugou(artist, title):
    kw = f"{title} {artist}"
    # 注意：msearchcdn.kugou.com 的证书跟域名对不上（实测 SSL CERTIFICATE_VERIFY_FAILED），
    # 换 songsearch.kugou.com 这个老接口，证书正常。
    h = {"Referer": "https://www.kugou.com/"}
    s = get("https://songsearch.kugou.com/song_search_v2?keyword=" + enc(kw)
            + "&page=1&pagesize=5&platform=WebFilter&userid=-1&clientver=&filter=2"
              "&iscorrection=1&privilege_filter=0", h)
    j = json.loads(s)
    lst = (j.get("data") or {}).get("lists") or []
    if not lst:
        return None, "搜不到"
    # 优先挑歌名完全一致的
    hit = None
    for x in lst:
        if (x.get("SongName") or "").strip() == title.strip():
            hit = x
            break
    if hit is None:
        hit = lst[0]
    khash = hit.get("FileHash")
    name = hit.get("SongName", "")
    singer = hit.get("SingerName", "")
    s2 = get("https://krcs.kugou.com/search?ver=1&man=yes&client=mobi&keyword="
             + enc(kw) + "&hash=" + enc(khash) + "&album_id=&duration=")
    j2 = json.loads(s2)
    cands = (j2.get("candidates") or [])
    if not cands:
        return None, f"搜到《{name}》- {singer}，但无歌词候选"
    c = cands[0]
    s3 = get("https://lyrics.kugou.com/download?ver=1&client=pc&id=" + enc(c.get("id"))
             + "&accesskey=" + enc(c.get("accesskey")) + "&fmt=lrc&charset=utf8")
    j3 = json.loads(s3)
    content = j3.get("content") or ""
    if not content:
        return None, "歌词接口返回空"
    try:
        txt = base64.b64decode(content).decode("utf-8", "replace")
    except Exception:
        txt = content
    if not txt.strip():
        return None, "解码后为空"
    return txt, f"{name} - {singer}"


# ---------------------------------------------------------------- 咪咕
def migu(artist, title):
    kw = f"{title} {artist}"
    h = {"Referer": "https://m.music.migu.cn/"}
    s = get("https://m.music.migu.cn/migu/remoting/scr_search_tag?keyword="
            + enc(kw) + "&type=2&rows=5&pgc=1", h)
    j = json.loads(s)
    lst = j.get("musics") or []
    if not lst:
        return None, "搜不到"
    hit = lst[0]
    cid = hit.get("copyrightId")
    if not cid:
        return None, "无 copyrightId"
    s2 = get("https://music.migu.cn/v3/api/music/audioPlayer/getLyric?copyrightId="
             + enc(cid), h)
    j2 = json.loads(s2)
    txt = j2.get("lyric") or ""
    if not txt.strip():
        return None, f"搜到《{hit.get('songName')}》，但无歌词"
    return txt, f"{hit.get('songName')} - {hit.get('singerName')}"


SOURCES = [
    ("LRCLIB", lrclib),
    ("网易云", netease),
    ("QQ音乐", qq),
    ("酷狗", kugou),
    ("咪咕", migu),
]


def main():
    argv = sys.argv[1:]
    only = None
    full = False
    while argv and argv[0].startswith("--"):
        if argv[0] == "--only" and len(argv) > 1:
            only = argv[1]
            argv = argv[2:]
        elif argv[0] == "--full":
            full = True
            argv = argv[1:]
        else:
            break
    if len(argv) < 2:
        print(__doc__)
        return 1

    artist, title = argv[0], argv[1]
    print(f"=== 目标: {artist} - {title} ===\n")

    win = []
    for name, fn in SOURCES:
        if only and name != only:
            continue
        try:
            txt, note = fn(artist, title)
        except Exception as e:
            print(f"[{name:6}] 异常 {type(e).__name__}: {e}")
            continue
        if txt:
            tag = "带时间轴" if has_ts(txt) else "纯文本"
            print(f"[{name:6}] ✅ {tag} | {note}")
            if full:
                show(txt)
            else:
                print(f"          首行: {brief(txt)}")
            win.append(name)
        else:
            print(f"[{name:6}] ❌ {note}")
        print()

    print("=== 有结果的源: " + (", ".join(win) if win else "一个都没有") + " ===")
    return 0


if __name__ == "__main__":
    sys.exit(main())
