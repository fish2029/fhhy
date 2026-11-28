# -*- coding: utf-8 -*-
# name: AI网盘
# version: 3.0
# sitename: AI网盘
# author: fanhua
"""
AI 网盘源（AI Pan）
==================
一个「AI 找片 + 网盘直接看」源：
- 分类：豆瓣榜单打底（带封面评分），AI 生成片单兜底
- 搜索：网盘聚合直搜（快、准），无结果才 AI 兜底
- 详情：AI 一句话点评 + 网盘资源聚合（夸克/UC/百度/迅雷/天翼/115/123）
- 播放：直接给出网盘分享链接，配合 App 网盘线路转存观看
纯标准库，class Spider(Spider) 结构。
"""

from base.spider import Spider

import json
import re
import time
import urllib.error
import urllib.parse
import urllib.request

UA = ('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 '
      '(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36')
TIMEOUT = 12
PANSOU = 'https://so.252035.xyz/api/search'
CACHE_TTL = 600

# 网盘类型 -> 展示名（顺序即线路顺序）
PAN_TYPES = [
    ('quark', '夸克网盘'),
    ('uc', 'UC网盘'),
    ('aliyun', '阿里云盘'),
    ('baidu', '百度网盘'),
    ('xunlei', '迅雷云盘'),
    ('tianyi', '天翼云盘'),
    ('115', '115网盘'),
    ('123', '123云盘'),
]

_CACHE = {}


def _now():
    return time.time()


def _cache_get(key):
    item = _CACHE.get(key)
    if item and _now() - item[0] < CACHE_TTL:
        return item[1]
    return None


def _cache_set(key, value):
    if len(_CACHE) > 200:
        _CACHE.clear()
    _CACHE[key] = (_now(), value)


def _get(url, timeout=TIMEOUT):
    req = urllib.request.Request(url, headers={'User-Agent': UA})
    return urllib.request.urlopen(req, timeout=timeout).read().decode('utf-8', 'ignore')


def _ai(prompt, timeout=25):
    """免费 AI 通道（pollinations，无需密钥），失败返回空串"""
    try:
        url = 'https://text.pollinations.ai/' + urllib.parse.quote(prompt, safe='')
        req = urllib.request.Request(url, headers={'User-Agent': UA})
        return urllib.request.urlopen(req, timeout=timeout).read().decode('utf-8', 'ignore').strip()
    except Exception:
        return ''


def _ai_json(prompt, timeout=25):
    """让 AI 返回 JSON 数组；剥围栏、容错解析，失败返回 []"""
    text = _ai(prompt, timeout)
    if not text:
        return []
    text = re.sub(r'^```(json)?|```$', '', text.strip(), flags=re.M).strip()
    m = re.search(r'\[.*\]', text, re.S)
    if not m:
        return []
    try:
        data = json.loads(m.group(0))
        return data if isinstance(data, list) else []
    except Exception:
        return []


def _clean(s):
    return re.sub(r'[\s\u3000·：:，,。.\-—_【】\[\]()（）]+', '', s or '')


def _pansou(kw):
    """网盘聚合搜索：返回 {类型: [(note, url, pwd, datetime), ...]}，带缓存"""
    cached = _cache_get('pansou$' + kw)
    if cached is not None:
        return cached
    result = {}
    try:
        d = None
        for _attempt in range(2):
            try:
                d = json.loads(_get(PANSOU + '?kw=' + urllib.parse.quote(kw) + '&res=merge', timeout=15))
                break
            except Exception:
                if _attempt:
                    raise
        merged = (d.get('data') or {}).get('merged_by_type') or {} if d else {}
        for ptype, _label in PAN_TYPES:
            items = merged.get(ptype) or []
            seen, rows = set(), []
            for it in items:
                url = (it.get('url') or '').strip()
                if not url or url in seen:
                    continue
                seen.add(url)
                rows.append((
                    (it.get('note') or '').strip()[:60],
                    url,
                    (it.get('password') or '').strip(),
                    it.get('datetime') or '',
                ))
            rows.sort(key=lambda r: r[3], reverse=True)
            if rows:
                result[ptype] = rows[:10]
    except Exception:
        result = {}
    _cache_set('pansou$' + kw, result)
    return result


def _match_rows(rows, title):
    """按片名过滤噪声：优先完整匹配，退化到前缀匹配"""
    t = _clean(title)
    if not t:
        return rows
    full = [r for r in rows if t in _clean(r[0])]
    if full:
        return full
    head = t[:4] if len(t) >= 4 else t[:2]
    return [r for r in rows if head and head in _clean(r[0])]


def _ai_list(keyword, kind, timeout=25):
    """AI 生成片单"""
    prompt = ('你是影视推荐助手。请推荐与「%s」相关的%s，共12部，必须是真实存在的作品。'
              '只输出JSON数组，不要任何解释或代码围栏，格式：'
              '[{"name":"片名","year":"年份","rating":"8.5","type":"类型/题材",'
              '"brief":"30字以内一句话简介"}]' % (keyword, kind))
    out = []
    for it in _ai_json(prompt, timeout):
        if not it.get('name'):
            continue
        out.append({
            'vod_id': 'ai$' + it['name'],
            'vod_name': it['name'],
            'vod_year': str(it.get('year', '')),
            'vod_remarks': ('评分 %s' % it['rating']) if it.get('rating') else 'AI 推荐',
            'vod_pic': '',
            'type_name': (it.get('type') or 'AI 生成') + ' · AI 生成',
        })
    return out


def _douban_list(tag):
    """豆瓣榜单：返回统一片单格式，失败返回 []"""
    try:
        d = json.loads(_get('https://movie.douban.com/j/search_subjects?tag=' +
                            urllib.parse.quote(tag) + '&sort=recommend&page_limit=24&page_start=0',
                            timeout=8))
        return [{
            'vod_id': 'ai$' + it.get('title', ''),
            'vod_name': it.get('title', ''),
            'vod_remarks': ('豆瓣 %s' % it['rate']) if it.get('rate') else '暂无评分',
            'vod_pic': it.get('cover', ''),
            'type_name': tag,
        } for it in d.get('subjects', [])[:24]]
    except Exception:
        return []


def _build_play(name):
    """聚合网盘线路：返回 (play_from, play_url, 统计描述)"""
    data = _pansou(name)
    labels, lines, stat = [], [], []
    for ptype, label in PAN_TYPES:
        rows = _match_rows(data.get(ptype) or [], name)
        if not rows:
            continue
        eps = []
        for note, url, pwd, _dt in rows:
            tag = note or '网盘分享'
            if pwd:
                tag += '〔码%s〕' % pwd
            eps.append('%s$%s' % (tag, url))
        labels.append(label)
        lines.append('#'.join(eps))
        stat.append('%s%d条' % (label, len(rows)))
    return labels, lines, '、'.join(stat)


class Spider(Spider):
    def init(self, extend=""):
        pass

    def homeContent(self, filter=False):
        return {
            'class': [
                {'type_id': 'hot_movie', 'type_name': 'AI·热门电影'},
                {'type_id': 'hot_tv', 'type_name': 'AI·热门剧集'},
                {'type_id': 'rank_movie', 'type_name': 'AI·高分电影'},
                {'type_id': 'rank_tv', 'type_name': 'AI·高分剧集'},
                {'type_id': 'short_drama', 'type_name': 'AI·短剧精选'},
            ],
            'filters': {},
        }

    def homeVideoContent(self):
        return {'list': []}

    def categoryContent(self, tid, pg, filter=False, extend=None):
        pg = int(pg or 1)
        if pg > 1:
            return {'list': [], 'page': pg, 'pagecount': 1, 'total': 0, 'limit': 0}
        mapping = {
            'hot_movie': '近期热门电影',
            'hot_tv': '近期热门电视剧',
            'rank_movie': '豆瓣评分最高的经典电影',
            'rank_tv': '豆瓣评分最高的经典电视剧',
            'short_drama': '热门网络短剧',
        }
        kind = mapping.get(tid, '近期热门电影')
        douban_tag = {'hot_movie': '热门电影', 'hot_tv': '热门电视剧',
                      'rank_movie': '豆瓣高分', 'rank_tv': '国产剧', 'short_drama': '热门电影'}.get(tid, '热门电影')
        result = _douban_list(douban_tag)
        source = '豆瓣'
        if not result:
            result = _ai_list(kind, kind)
            source = 'AI 生成'
        return {
            'list': result,
            'page': 1,
            'pagecount': 1,
            'total': len(result),
            'limit': len(result),
            'source_note': source,
        }

    def searchContent(self, key, quick=False, pg=1):
        if int(pg or 1) > 1:
            return {'list': [], 'page': 1, 'pagecount': 1, 'total': 0, 'limit': 0}
        # 优先网盘聚合直搜：快、准，片名即结果
        data = _pansou(key)
        total = 0
        for ptype, _label in PAN_TYPES:
            total += len(_match_rows(data.get(ptype) or [], key))
        if total:
            return {
                'list': [{
                    'vod_id': 'ai$' + key,
                    'vod_name': key,
                    'vod_remarks': '网盘资源 %d 条' % total,
                    'vod_pic': '',
                    'type_name': '网盘资源 · 精确匹配',
                }],
                'page': 1,
                'pagecount': 1,
                'total': 1,
                'limit': 1,
                'source_note': '网盘聚合',
            }
        # 兜底：AI 生成片单（限时 20s，避免拖满搜索超时）
        result = _ai_list(key, '影视作品（优先完全匹配「%s」的作品）' % key, timeout=20)
        return {
            'list': result,
            'page': 1,
            'pagecount': 1,
            'total': len(result),
            'limit': len(result),
            'source_note': 'AI 生成',
        }

    def detailContent(self, ids):
        if isinstance(ids, str):
            ids = [ids]
        raw = (ids or [''])[0]
        name = raw.split('$', 1)[1] if '$' in raw else raw
        comment = _ai('用一句话点评电影/剧集《%s》，30字以内，直接说观点，不要客套。' % name)
        labels, lines, stat = _build_play(name)
        if not labels:
            labels, lines = ['提示'], ['未找到网盘资源$本片暂未搜到网盘资源，可稍后重试或换个片名']
            stat = ''
        desc_parts = ['【资源说明】AI 找片 + 网盘聚合，播放前请先转存到自己网盘']
        if stat:
            desc_parts.insert(0, '【找到资源】%s' % stat)
        if comment:
            desc_parts.append('【AI 点评】%s' % comment)
        vod = {
            'vod_id': raw,
            'vod_name': name,
            'vod_pic': '',
            'vod_content': '\n'.join(desc_parts),
            'vod_play_from': '$$$'.join(labels),
            'vod_play_url': '$$$'.join(lines),
        }
        return {'list': [vod]}

    def playerContent(self, flag, pid, vipFlags=None):
        url = pid or ''
        return {
            'parse': 0,
            'playUrl': '',
            'url': url,
            'header': {'User-Agent': UA},
        }

    def localProxy(self, param):
        return [200, 'text/plain', '', '']
