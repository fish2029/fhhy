# -*- coding: utf-8 -*-
"""
AI 搜索源（AI Search）
=====================
一个「AI 找片」源：豆瓣数据打底 + AI 点评/兜底。
- 搜索：豆瓣联想优先；被反爬时自动降级为 AI 生成片单（结果标注 AI 生成）
- 详情：豆瓣条目信息 + AI 一句话点评（免费 AI 通道，无需密钥）
- 播放：本源不提供播放流，详情页给出豆瓣页面入口，配合其他源观看
纯标准库，hipy 模块级函数约定。
"""

UA = ('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 '
      '(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36')
TIMEOUT = 12

# ---------- 基础工具 ----------

def _get(url):
    import urllib.request
    req = urllib.request.Request(url, headers={
        'User-Agent': UA,
        'Referer': 'https://movie.douban.com/',
        'Accept': 'application/json, text/plain, */*',
    })
    return urllib.request.urlopen(req, timeout=TIMEOUT).read().decode('utf-8', 'ignore')


def _ai(prompt):
    """免费 AI 通道（pollinations，无需密钥），失败返回空串"""
    import urllib.request, urllib.parse
    try:
        url = 'https://text.pollinations.ai/' + urllib.parse.quote(prompt, safe='')
        req = urllib.request.Request(url, headers={'User-Agent': UA})
        return urllib.request.urlopen(req, timeout=30).read().decode('utf-8', 'ignore').strip()
    except Exception:
        return ''


def _ai_json(prompt):
    """让 AI 返回 JSON 数组；剥围栏、容错解析，失败返回 []"""
    import json, re
    text = _ai(prompt)
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


def _strip_html(s):
    import re
    return re.sub(r'<[^>]+>', '', s or '').strip()


# ---------- 数据通道 ----------

def _douban_suggest(keyword):
    """豆瓣联想：返回统一片单格式，失败返回 []"""
    import urllib.parse, json
    try:
        d = json.loads(_get('https://movie.douban.com/j/subject_suggest?q=' + urllib.parse.quote(keyword)))
        out = []
        for it in d[:20]:
            if it.get('type') not in (None, 'movie', 'tv'):
                continue
            rating = it.get('rating') or {}
            out.append({
                'vod_id': 'db$' + (it.get('url') or ''),
                'vod_name': it.get('title', ''),
                'vod_year': it.get('year', ''),
                'vod_remarks': ('豆瓣 %.1f' % rating['value']) if rating.get('value') else '暂无评分',
                'vod_pic': it.get('img', ''),
                'type_name': it.get('sub_title', '') or ('电影' if it.get('type') == 'movie' else '剧集'),
            })
        return out
    except Exception:
        return []


def _douban_list(tag):
    """豆瓣榜单：返回统一片单格式，失败返回 []"""
    import urllib.parse, json
    try:
        d = json.loads(_get('https://movie.douban.com/j/search_subjects?tag=' +
                            urllib.parse.quote(tag) + '&sort=recommend&page_limit=24&page_start=0'))
        return [{
            'vod_id': 'db$' + it.get('url', ''),
            'vod_name': it.get('title', ''),
            'vod_remarks': ('豆瓣 %s' % it['rate']) if it.get('rate') else '暂无评分',
            'vod_pic': it.get('cover', ''),
            'type_name': tag,
        } for it in d.get('subjects', [])[:24]]
    except Exception:
        return []


def _ai_list(keyword, kind):
    """AI 生成片单（豆瓣不可用时的兜底）"""
    prompt = ('你是影视推荐助手。请推荐与「%s」相关的%s，共12部。'
              '只输出JSON数组，不要任何解释或代码围栏，格式：'
              '[{"name":"片名","year":"年份","rating":"8.5","type":"类型/题材",'
              '"brief":"30字以内一句话简介"}]' % (keyword, kind))
    out = []
    for it in _ai_json(prompt):
        if not it.get('name'):
            continue
        out.append({
            'vod_id': 'ai$' + it['name'],
            'vod_name': it['name'],
            'vod_year': str(it.get('year', '')),
            'vod_remarks': ('豆瓣 %s' % it['rating']) if it.get('rating') else 'AI 推荐',
            'vod_pic': '',
            'type_name': (it.get('type') or 'AI 生成') + ' · AI 生成',
        })
    return out


def _douban_detail(url):
    """豆瓣条目页解析：标题/评分/简介/主演，失败返回 {}"""
    try:
        html = _get(url)
        import re
        title = re.search(r'<title>\s*(.+?)\(豆瓣\)', html)
        rating = re.search(r'"ratingValue":\s*"?([\d.]+)', html)
        info = re.search(r'<div id="info">([\s\S]*?)</div>', html)
        summary = re.search(r'<span property="v:summary"[^>]*>([\s\S]*?)</span>', html)
        pic = re.search(r'rel="v:image"[^>]*href="([^"]+)"', html) or re.search(r'<img[^>]+src="([^"]+)"[^>]*rel="v:image"', html) \
            or re.search(r'<img[^>]+src="([^"]+)"[^>]*class="[^"]*nb-img', html)
        info_text = ''
        if info:
            info_text = _strip_html(info.group(1)).replace('\u00a0', ' ')
        return {
            'title': _strip_html(title.group(1)) if title else '',
            'rating': rating.group(1) if rating else '',
            'info': info_text[:500],
            'summary': _strip_html(summary.group(1)) if summary else '',
            'pic': (pic.group(1) if pic else ''),
        }
    except Exception:
        return {}


# ---------- hipy 模块级接口 ----------

def homeContent(filter):
    return {
        'class': [
            {'type_id': 'hot_movie', 'type_name': 'AI·热门电影'},
            {'type_id': 'hot_tv', 'type_name': 'AI·热门剧集'},
            {'type_id': 'rank_movie', 'type_name': 'AI·高分电影'},
            {'type_id': 'rank_tv', 'type_name': 'AI·高分剧集'},
        ],
        'filters': {},
    }


def categoryContent(tid, pg, filter, extend):
    import json
    pg = int(pg or 1)
    if pg > 1:
        return {'list': [], 'page': pg, 'pagecount': 1, 'total': 0, 'limit': 0}
    mapping = {
        'hot_movie': ('热门电影', '热门电影'),
        'hot_tv': ('热门剧集', '热门电视剧'),
        'rank_movie': ('高分电影', '豆瓣评分最高的电影'),
        'rank_tv': ('高分剧集', '豆瓣评分最高的电视剧'),
    }
    tag, ai_kind = mapping.get(tid, ('热门电影', '热门电影'))
    result = _douban_list(tag)
    source = '豆瓣'
    if not result:
        result = _ai_list(tag, ai_kind)
        source = 'AI 生成'
    return {
        'list': result,
        'page': 1,
        'pagecount': 1,
        'total': len(result),
        'limit': len(result),
        'source_note': source,
    }


def searchContent(key, quick, pg):
    import json
    if int(pg or 1) > 1:
        return {'list': [], 'page': 1, 'pagecount': 1, 'total': 0, 'limit': 0}
    result = _douban_suggest(key)
    source = '豆瓣'
    if not result:
        result = _ai_list(key, '影视作品')
        source = 'AI 生成'
    return {
        'list': result,
        'page': 1,
        'pagecount': 1,
        'total': len(result),
        'limit': len(result),
        'source_note': source,
    }


def detailContent(ids):
    import json
    raw = (ids or [''])[0]
    vod = {'vod_id': raw, 'vod_play_from': '豆瓣页面', 'vod_play_url': '豆瓣页面$' + raw.replace('db$', '', 1)}
    if raw.startswith('db$'):
        d = _douban_detail(raw[3:])
        name = d.get('title') or '未知'
        rating = d.get('rating') or ''
        summary = d.get('summary') or ''
        info = d.get('info') or ''
        pic = d.get('pic') or ''
        comment = _ai('用一句话点评电影/剧集《%s》，30字以内，直接说观点，不要客套。' % name)
        desc_parts = []
        if rating:
            desc_parts.append('【豆瓣评分】%s' % rating)
        if info:
            desc_parts.append('【条目信息】%s' % info)
        if summary:
            desc_parts.append('【剧情简介】%s' % summary)
        if comment:
            desc_parts.append('【AI 点评】%s' % comment)
        desc_parts.append('【提示】本源为 AI 搜索源，不提供播放；请复制片名到其他源搜索观看。')
        vod.update({
            'vod_name': name,
            'vod_pic': pic,
            'vod_content': '\n'.join(desc_parts),
            'vod_actor': '',
            'vod_director': '',
        })
    else:
        name = raw.replace('ai$', '', 1)
        comment = _ai('用一句话点评电影/剧集《%s》，30字以内，直接说观点。' % name)
        vod.update({
            'vod_name': name,
            'vod_pic': '',
            'vod_content': '【AI 点评】%s\n【提示】本源为 AI 搜索源，不提供播放；请复制片名到其他源搜索观看。' % (comment or '暂无点评'),
            'vod_play_from': '提示',
            'vod_play_url': '提示$本源不提供播放，请复制片名到其他源搜索',
        })
    return {'list': [vod]}


def playerContent(ids):
    import json
    url = (ids or [''])[0].replace('db$', '', 1)
    return {
        'parse': 0,
        'playUrl': '',
        'url': url,
        'header': {'User-Agent': UA},
    }


def localProxy(param):
    return [200, 'text/plain', '', '']
