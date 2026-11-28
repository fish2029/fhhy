# -*- coding: utf-8 -*-
# name: 搜剧AI
# version: 2.0
# sitename: 搜剧AI
# author: fanhua
"""
搜剧AI 源（Souju AI）
=====================
对接 souju.ai（bpz2.app）全站数据：
- 分类：电影/电视剧/动漫/综艺/短剧，带类型/地区/语言/年份筛选
- 搜索：站内关键词搜索
- 详情：海报/评分/简介/演员 + 全集
- 播放：站内多线路解析，直接出 m3u8
纯标准库，class Spider(Spider) 结构。
"""

from base.spider import Spider

import hashlib
import hmac
import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request

BASE = 'https://bpz2.app'
KEY = 'f39d73aa7a6426203cdee1ef17b31d3b7ea8c23f4c59c62a3a8aa0f39ee5e79d'
UA = ('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 '
      '(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36')
TIMEOUT = 15

KINDS = [
    ('movie', '电影'),
    ('series', '电视剧'),
    ('anime', '动漫'),
    ('variety', '综艺'),
    ('short_drama', '短剧'),
]

# 筛选条件（取自站点引导接口，写死避免首页联网等待超时）
_FILTERS = {
    'movie': [
        ('genre', '分类', ['喜剧', '爱情', '动作', '犯罪', '科幻', '奇幻', '冒险', '灾难', '恐怖', '惊悚', '剧情', '战争', '歌舞', '经典', '悬疑', '动画', '同性', '网络电影']),
        ('area', '地区', ['大陆', '香港', '台湾', '日本', '韩国', '欧美', '英国', '泰国', '其它']),
        ('language', '语言', ['国语', '粤语', '英语', '韩语', '日语', '西班牙语', '法语', '德语', '意大利语', '泰国语', '其它']),
        ('year', '年份', ['今年', '去年', '更早', '90年代', '80年代', '1980年以前']),
        ('sort', '排序', ['updated_desc', 'heat_desc']),
    ],
    'series': [
        ('genre', '分类', ['偶像', '爱情', '言情', '古装', '历史', '玄幻', '谍战', '历险', '都市', '科幻', '军旅', '喜剧', '武侠', '江湖', '罪案', '青春', '家庭', '战争', '悬疑', '穿越', '宫廷', '神话', '商战', '警匪', '动作', '惊悚', '剧情', '同性', '奇幻']),
        ('area', '地区', ['大陆', '香港', '台湾', '日本', '韩国', '欧美', '英国', '泰国', '其它']),
        ('language', '语言', ['国语', '粤语', '英语', '韩语', '日语', '西班牙语', '法语', '德语', '意大利语', '泰国语', '其它']),
        ('year', '年份', ['今年', '去年', '更早', '90年代', '80年代', '1980年以前']),
        ('sort', '排序', ['updated_desc', 'heat_desc']),
    ],
    'anime': [
        ('genre', '分类', ['热血', '格斗', '机战', '少女', '竞技', '科幻', '魔幻', '爆笑', '推理', '冒险', '恋爱', '校园', '治愈', '泡面', '穿越', '灵异', '耽美', '剧场版', '其它']),
        ('area', '地区', ['大陆', '香港', '台湾', '日本', '韩国', '欧美', '英国', '泰国', '其它']),
        ('language', '语言', ['国语', '粤语', '英语', '韩语', '日语', '西班牙语', '法语', '德语', '意大利语', '泰国语', '其它']),
        ('year', '年份', ['今年', '去年', '更早', '90年代', '80年代', '1980年以前']),
        ('sort', '排序', ['updated_desc', 'heat_desc']),
    ],
    'variety': [
        ('genre', '分类', ['真人秀', '选秀', '网综', '脱口秀', '搞笑', '竞技', '情感', '访谈', '演唱会', '晚会', '其它']),
        ('area', '地区', ['大陆', '香港', '台湾', '日本', '韩国', '欧美', '英国', '泰国', '其它']),
        ('language', '语言', ['国语', '粤语', '英语', '韩语', '日语', '西班牙语', '法语', '德语', '意大利语', '泰国语', '其它']),
        ('year', '年份', ['今年', '去年', '更早', '90年代', '80年代', '1980年以前']),
        ('sort', '排序', ['updated_desc', 'heat_desc']),
    ],
    'short_drama': [
        ('genre', '分类', ['真人剧', 'comic', '偶像', '爱情', '言情', '古装', '历史', '玄幻', '谍战', '都市', '历险', '科幻', '军旅', '喜剧', '武侠', '江湖', '罪案', '青春', '家庭', '战争', '悬疑', '穿越', '宫廷', '神话', '商战', '警匪', '动作', '惊悚', '剧情', '同性', '奇幻', '女性', '系统', '逆袭', '无敌', '乡村', '古风', '赘婿', '萌宝', '种田', '犯罪', '年代', '志怪', '战神', '婚姻', '现言', '宫斗', '友情', '职场', '民国', '法律', '抗战', '时空', '反转', '仙侠', '末世', '体育', '刑侦', '成长', '游戏', '脑洞', '冒险', '恐怖', '古代', '医疗', '推理', '灾难', '搞笑', '玄学', '轻喜剧', '家族', '超凡', '日常', '伦理', '重生', '经营', '温情', '横屏', '竖屏']),
        ('area', '地区', ['大陆', '香港', '台湾', '日本', '韩国', '欧美', '英国', '泰国', '其它']),
        ('language', '语言', ['国语', '粤语', '英语', '韩语', '日语', '西班牙语', '法语', '德语', '意大利语', '泰国语', '其它']),
        ('year', '年份', ['今年', '去年', '更早', '90年代', '80年代', '1980年以前']),
        ('status', '状态', ['全集', '连载中']),
        ('orientation', '形式', ['横屏', '竖屏']),
        ('sort', '排序', ['updated_desc', 'heat_desc']),
    ],
}

_CACHE = {}


def _cache_get(key, ttl=600):
    item = _CACHE.get(key)
    if item and time.time() - item[0] < ttl:
        return item[1]
    return None


def _cache_set(key, value):
    if len(_CACHE) > 300:
        _CACHE.clear()
    _CACHE[key] = (time.time(), value)


def _signed_get(path, timeout=TIMEOUT):
    """带签名的 GET（与站点前端同款 HMAC-SHA256 签名）"""
    ts = str(int(time.time() * 1000))
    nonce = os.urandom(16).hex()
    msg = 'GET\n%s\n%s\n%s' % (path, ts, nonce)
    sig = hmac.new(KEY.encode(), msg.encode(), hashlib.sha256).hexdigest()
    req = urllib.request.Request(BASE + path, headers={
        'User-Agent': UA,
        'Accept': 'application/json',
        'Referer': BASE + '/',
        'x-ai-movie-timestamp': ts,
        'x-ai-movie-nonce': nonce,
        'x-ai-movie-signature': sig,
    })
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode('utf-8', 'ignore'))


def _signed_get_retry(path, tries=2):
    last = None
    for i in range(tries):
        try:
            return _signed_get(path)
        except Exception as e:
            last = e
            time.sleep(0.5)
    raise last


def _clean(s):
    return (s or '').strip()


def _bootstrap_filters():
    """筛选条件：直接用写死数据，首页零联网（站点引导接口只在需要时兜底）"""
    if _FILTERS:
        return _FILTERS
    cached = _cache_get('filters', 3600)
    if cached is not None:
        return cached
    filters = {}
    try:
        d = _signed_get('/v1/runtime/bootstrap', timeout=5)
        for cat in (d.get('catalog_ui') or {}).get('catalogs') or []:
            kind = cat.get('kind')
            groups = []
            for row in cat.get('rows') or []:
                opts = [o.get('key') for o in (row.get('options') or []) if o.get('key')]
                if opts:
                    groups.append((row.get('key'), row.get('label'), opts))
            if groups:
                filters[kind] = groups
        _cache_set('filters', filters)
    except Exception:
        pass
    return filters


def _card_to_vod(c):
    return {
        'vod_id': c.get('id') or '',
        'vod_name': _clean(c.get('title')),
        'vod_year': str(c.get('year') or ''),
        'vod_remarks': _clean(c.get('remarks')) or (c.get('genres') or [''])[0],
        'vod_pic': c.get('poster_url') or '',
        'type_name': '、'.join((c.get('genres') or [])[:3]),
    }


def _browse(params):
    path = '/v1/browse/catalog?' + urllib.parse.urlencode(params)
    d = _signed_get_retry(path)
    cards = d.get('cards') or []
    pag = d.get('pagination') or {}
    total = pag.get('total') or len(cards)
    pagecount = max(1, -(-total // 24))
    vods = [_card_to_vod(c) for c in cards if c.get('id')]
    return vods, total, pagecount


def _fetch_episodes(vid, first_page):
    """聚合全集（含翻页，最多 10 页）"""
    eps = list(first_page or [])
    seen = set(e.get('token') for e in eps)
    for page in range(2, 11):
        try:
            d = _signed_get_retry('/v1/catalog/%s/episodes?page=%d' % (
                urllib.parse.quote(vid, safe=''), page))
        except Exception:
            break
        if not isinstance(d, dict):
            break
        batch = d.get('episodes') or []
        pag = d.get('episode_pagination') or {}
        new = [e for e in batch if e.get('token') and e.get('token') not in seen]
        if not new:
            break
        seen.update(e['token'] for e in new)
        eps.extend(new)
        if pag.get('has_more') is False:
            break
    return eps


class Spider(Spider):
    def init(self, extend=""):
        pass

    def homeContent(self, filter=False):
        classes = [{'type_id': k, 'type_name': n} for k, n in KINDS]
        bf = _bootstrap_filters()
        filters = {}
        for k, _n in KINDS:
            groups = bf.get(k) or []
            if not groups:
                continue
            filters[k] = [{
                'key': key,
                'name': label,
                'value': [{'n': o, 'v': o} for o in opts],
            } for key, label, opts in groups]
        return {'class': classes, 'filters': filters}

    def homeVideoContent(self):
        return {'list': []}

    def categoryContent(self, tid, pg, filter=False, extend=None):
        if not isinstance(extend, dict):
            extend = {}
        pg = int(pg or 1)
        params = {'kind': tid, 'page': pg, 'limit': 24}
        for k in ('genre', 'area', 'language', 'year', 'status', 'orientation', 'sort'):
            v = extend.get(k)
            if v:
                params[k] = v
        vods, total, pagecount = _browse(params)
        return {
            'list': vods,
            'page': pg,
            'pagecount': pagecount,
            'total': total,
            'limit': len(vods),
        }

    def searchContent(self, key, quick=False, pg=1):
        pg = int(pg or 1)
        vods, total, pagecount = _browse({'q': key, 'page': pg, 'limit': 24})
        return {
            'list': vods,
            'page': pg,
            'pagecount': pagecount,
            'total': total,
            'limit': len(vods),
        }

    def detailContent(self, ids):
        if isinstance(ids, str):
            ids = [ids]
        vid = (ids or [''])[0]
        d = _signed_get_retry('/v1/catalog/' + urllib.parse.quote(vid, safe=''))
        title = _clean(d.get('title')) or '未知'
        eps = _fetch_episodes(vid, d.get('episodes'))
        desc_parts = []
        if d.get('year'):
            desc_parts.append('【年份】%s' % d['year'])
        if d.get('area'):
            desc_parts.append('【地区】%s' % d['area'])
        if d.get('genres'):
            desc_parts.append('【类型】%s' % '、'.join(d['genres']))
        if d.get('directors'):
            desc_parts.append('【导演】%s' % '、'.join(d['directors'][:5]))
        if d.get('actors'):
            desc_parts.append('【主演】%s' % '、'.join(d['actors'][:8]))
        if d.get('remarks'):
            desc_parts.append('【备注】%s' % d['remarks'])
        if d.get('description'):
            desc_parts.append('【简介】%s' % d['description'])
        play_eps = []
        for i, e in enumerate(eps, 1):
            name = _clean(e.get('display_name')) or _clean(e.get('title')) or ('第%d集' % i)
            play_eps.append('%s$%s' % (name, e['token']))
        if not play_eps:
            play_eps = ['暂无可播剧集$']
        vod = {
            'vod_id': vid,
            'vod_name': title,
            'vod_pic': d.get('poster_url') or '',
            'vod_year': str(d.get('year') or ''),
            'vod_area': _clean(d.get('area')),
            'vod_actor': '、'.join((d.get('actors') or [])[:8]),
            'vod_director': '、'.join((d.get('directors') or [])[:5]),
            'vod_content': '\n'.join(desc_parts),
            'vod_play_from': '搜剧AI',
            'vod_play_url': '#'.join(play_eps),
        }
        return {'list': [vod]}

    def playerContent(self, flag, pid, vipFlags=None):
        token = pid or ''
        url = ''
        try:
            r = _signed_get_retry('/v1/playback/resolve/' + urllib.parse.quote(token, safe=''), tries=1)
            lines = r.get('line_options') or []
            # 优先已解析的 m3u8 直链
            best = None
            for l in lines:
                u = _clean(l.get('url'))
                if not u or u.startswith('resolve://'):
                    continue
                if l.get('url_kind') == 'm3u8' or '.m3u8' in u or '.mp4' in u:
                    best = u
                    break
                if best is None:
                    best = u
            url = best or ''
        except Exception:
            url = ''
        return {
            'parse': 0,
            'playUrl': '',
            'url': url,
            'header': {'User-Agent': UA, 'Referer': BASE + '/'},
        }

    def localProxy(self, param):
        return [200, 'text/plain', '', '']
