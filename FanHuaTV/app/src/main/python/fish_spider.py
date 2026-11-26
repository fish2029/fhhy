# -*- coding: utf-8 -*-
"""
鱼壳 / TVBox Spider 脚本适配层。

鱼壳脚本是 `class Spider(Spider)` 类结构（from base.spider import Spider），
用 init/homeContent/categoryContent/searchContent/detailContent/playerContent 方法，
返回标准 TVBox 字典（vod_id / vod_name / vod_pic / vod_play_url ...）。

现实中脚本写法差异很大，本层做四件事：
  1) 提供 base.spider 的 Spider 基类桩（同时暴露大写 Spider 与小写 spider），
     并补全脚本常用的 fetch/getHtml/getProxyUrl 等辅助方法；
  2) 兼容大小写两套方法名（homeContent / homecontent）与不同 init 签名；
  3) 把鱼壳方法桥接成沙箱统一入口，TVBox 结果转成 App 的 {"result": [...]}；
  4) 任何异常都带完整 traceback 返回，便于定位到具体行。
"""
import json
import re
import sys
import traceback
import types

try:
    import requests as _requests
except Exception:
    _requests = None


def _install_requests_shim():
    """没有 pip 包环境（Chaquopy 只带标准库），但鱼壳脚本十有八九直接
    `import requests` —— 一旦 ImportError，整个脚本 exec 失败，首页分类全空。
    这里给一个基于 urllib 的最小 requests 替身（注册进 sys.modules），
    覆盖音乐类脚本最常用的 get/post/text/json/status_code/headers/Session。
    """
    global _requests
    if _requests is not None:
        return _requests
    import urllib.request as _ur
    import urllib.error as _ue
    import urllib.parse as _up
    import ssl as _ssl
    import json as _json

    class RequestException(Exception):
        pass

    class Timeout(RequestException):
        pass

    class HTTPError(RequestException):
        def __init__(self, response=None, *args):
            super().__init__(*args)
            self.response = response

    _ctx = _ssl.create_default_context()
    _ctx.check_hostname = False
    _ctx.verify_mode = _ssl.CERT_NONE

    class Response(object):
        def __init__(self, raw, body):
            self._raw = raw
            self.status_code = getattr(raw, "code", 200) or 200
            self.url = getattr(raw, "url", "") or ""
            self.content = body
            try:
                ctype = raw.headers.get("Content-Type", "") or ""
            except Exception:
                ctype = ""
            self.encoding = "utf-8"
            if "charset=" in ctype:
                self.encoding = ctype.split("charset=")[-1].split(";")[0].strip() or "utf-8"

        @property
        def headers(self):
            try:
                return self._raw.headers
            except Exception:
                return {}

        @property
        def ok(self):
            return self.status_code < 400

        @property
        def apparent_encoding(self):
            return self.encoding

        @property
        def text(self):
            for enc in (self.encoding, "utf-8", "gb18030"):
                try:
                    return self.content.decode(enc)
                except Exception:
                    continue
            return self.content.decode("utf-8", "ignore")

        def json(self):
            return _json.loads(self.text)

        def raise_for_status(self):
            if self.status_code >= 400:
                raise HTTPError(self)

        def iter_lines(self):
            return self.text.splitlines()

        def close(self):
            pass

    def _build(url, kwargs):
        method = kwargs.pop("method", None)
        headers = dict(kwargs.pop("headers", {}) or {})
        headers.setdefault("User-Agent", "Mozilla/5.0")
        params = kwargs.pop("params", None)
        if params:
            url += ("&" if "?" in url else "?") + _up.urlencode(params)
        data = kwargs.pop("data", None)
        payload = kwargs.pop("json", None)
        if payload is not None:
            data = _json.dumps(payload, ensure_ascii=False).encode("utf-8")
            headers.setdefault("Content-Type", "application/json")
        if isinstance(data, str):
            data = data.encode("utf-8")
        timeout = kwargs.pop("timeout", None) or 15
        for junk in ("verify", "allow_redirects", "proxies", "stream",
                     "cert", "hooks", "auth", "files"):
            kwargs.pop(junk, None)
        if method is None:
            method = "POST" if data is not None else "GET"
        req = _ur.Request(url, data=data, headers=headers, method=method)
        try:
            raw = _ur.urlopen(req, timeout=timeout, context=_ctx)
            return Response(raw, raw.read())
        except _ue.HTTPError as e:
            body = b""
            try:
                body = e.read()
            except Exception:
                pass
            return Response(e, body)
        except Exception as e:
            raise Timeout("%s" % e)

    class Session(object):
        def __init__(self):
            self.headers = {"User-Agent": "Mozilla/5.0"}
            self.cookies = {}

        def _merged(self, kw):
            kw = dict(kw)
            h = dict(self.headers)
            h.update({str(k).title(): v for k, v in (kw.pop("headers", {}) or {}).items()})
            kw["headers"] = h
            return kw

        def get(self, url, **kw):
            return _build(url, self._merged(kw))

        def post(self, url, **kw):
            kw.setdefault("method", "POST")
            return _build(url, self._merged(kw))

        def request(self, method, url, **kw):
            kw["method"] = method
            return _build(url, self._merged(kw))

        def head(self, url, **kw):
            return self.request("HEAD", url, **kw)

        def close(self):
            pass

    class _Codes(object):
        ok = 200
        not_found = 404

    class _Shim(object):
        codes = _Codes()

        @staticmethod
        def get(url, **kw):
            return _build(url, dict(kw))

        @staticmethod
        def post(url, **kw):
            kw.setdefault("method", "POST")
            return _build(url, dict(kw))

        @staticmethod
        def request(method, url, **kw):
            kw["method"] = method
            return _build(url, dict(kw))

        @staticmethod
        def head(url, **kw):
            return _Shim.request("HEAD", url, **kw)

    # 异常类/Session 在类体内不能直接自引用（class body 不构成闭包），挂到类上
    _Shim.Session = Session
    _Shim.RequestException = RequestException
    _Shim.Timeout = Timeout
    _Shim.HTTPError = HTTPError

    mod = _Shim()
    sys.modules["requests"] = mod
    _requests = mod
    return mod


# fish_spider 被 fanhua_runtime 先导入，在这里装好替身，脚本里再 `import requests` 就不会炸
_install_requests_shim()


class _SpiderBase(object):
    """鱼壳 Spider 基类桩：脚本会继承它并调用这些方法。"""

    # ---- 生命周期 ----
    def init(self, extend=""):
        self.extend = extend or ""

    def destroy(self):
        pass

    # ---- 元信息 ----
    def getName(self):
        return ""

    def getDependence(self):
        return []

    def isVideoFormat(self, url):
        return False

    def manualVideoCheck(self):
        return False

    # ---- 缓存（TVBox 里由宿主实现，这里给空实现避免 AttributeError）----
    def getCache(self, key):
        return None

    def setCache(self, key, value, expire=None):
        return ""

    def delCache(self, key):
        pass

    # ---- 默认返回空结构：脚本未实现某个方法时不会崩 ----
    def homeContent(self, filter=False):
        return {"class": [], "filters": {}}

    def homeVideoContent(self):
        return {"list": []}

    def categoryContent(self, tid, pg, filter=False, extend=""):
        return {"list": []}

    def searchContent(self, key, quick=False, pg=1):
        return {"list": []}

    def detailContent(self, ids):
        return {"list": []}

    def playerContent(self, flag, pid, vipFlags=None):
        return {"play": "", "url": ""}

    def localProxy(self, param):
        return {}

    # ---- 脚本常用的 HTTP / 文本辅助 ----
    def fetch(self, url, headers=None, timeout=10, **kwargs):
        """TVBox base.spider 提供 fetch(url)，很多脚本直接用它取页面。"""
        if _requests is None:
            return ""
        try:
            resp = _requests.get(
                url,
                headers=headers or {"User-Agent": "Mozilla/5.0"},
                timeout=min(int(timeout or 10), 15),
                verify=False,
                **kwargs
            )
            resp.encoding = resp.apparent_encoding or "utf-8"
            return resp.text
        except Exception:
            return ""

    def getHtml(self, url, headers=None, timeout=10):
        return self.fetch(url, headers=headers, timeout=timeout)

    def getProxyUrl(self, *args, **kwargs):
        return ""

    def regStr(self, text, pattern, group=1):
        m = re.search(pattern, text or "")
        return m.group(group) if m else ""

    def cleanText(self, text):
        return re.sub(r"<[^>]+>", "", text or "").strip()

    def joinUrl(self, base, url):
        if not url:
            return ""
        if url.startswith("http"):
            return url
        return base.rstrip("/") + "/" + url.lstrip("/")

    def html2json(self, html):
        try:
            return json.loads(html)
        except Exception:
            return {}


def _install_base_stub():
    """在 sys.modules 里注入 base.spider 桩，让脚本的 import 成功。

    同时暴露大写 Spider 和小写 spider —— 两种写法在 TVBox 生态里都存在。
    """
    base_mod = sys.modules.get("base")
    if base_mod is None:
        base_mod = types.ModuleType("base")
        base_mod.__path__ = []          # 伪装成 package，兼容 import base.spider
        sys.modules["base"] = base_mod

    spider_mod = sys.modules.get("base.spider")
    if spider_mod is None:
        spider_mod = types.ModuleType("base.spider")
        sys.modules["base.spider"] = spider_mod
    # 两个名字都指向同一个基类，覆盖大小写两种写法
    spider_mod.Spider = _SpiderBase
    spider_mod.spider = _SpiderBase
    base_mod.spider = spider_mod
    return spider_mod


def _find_spider_class(namespace):
    """找出脚本里的 Spider 类：优先名字匹配，其次任何继承自基类的类。"""
    candidates = []
    for name, val in namespace.items():
        if isinstance(val, type) and issubclass(val, _SpiderBase) and val is not _SpiderBase:
            candidates.append((name, val))
    if not candidates:
        # 有些脚本不继承基类，只是恰好叫 Spider
        for name, val in namespace.items():
            if isinstance(val, type) and (name in ("Spider", "spider")):
                candidates.append((name, val))
    if not candidates:
        raise RuntimeError("脚本里没有找到 Spider 类（需要 class Spider(Spider) 结构）")
    for name, val in candidates:
        if name in ("Spider", "spider"):
            return val
    return candidates[0][1]


def _new_instance(cls):
    """实例化：先无参，失败再试空字符串（部分脚本 __init__ 带必填参数）。"""
    try:
        return cls()
    except TypeError:
        return cls("")


def _init_instance(inst, ext=""):
    """调用 init：兼容 init() / init(extend) / init(extend="") 三种签名。

    ext 来自 TVBox 订阅的 sites[].ext，很多脚本靠它区分不同站点/线路，
    传错会直接导致 homeContent 返回空。
    """
    for args in ((ext,), (), ("",)):
        try:
            inst.init(*args)
            return
        except TypeError:
            continue
        except Exception:
            # init 内部报错不致命，继续跑 homeContent
            return


def _ext_of(param_json):
    """从参数 JSON 里取 TVBox 的 ext"""
    try:
        p = json.loads(param_json) if isinstance(param_json, str) else (param_json or {})
        return str(p.get("ext", "") or "")
    except Exception:
        return ""


# Spider 实例缓存：脚本源码 hash + ext 作为 key。
# 原实现每次详情/播放都重新 exec 整个脚本源码（init 里常有网络请求），
# 一次播放解析串 2~3 个请求，非常容易超时卡顿；缓存后同一脚本只执行一次。
_SPIDER_CACHE = {}


def load_spider(source, ext=""):
    """执行鱼壳脚本源码，返回 Spider 实例（带缓存，避免重复 exec + init）。"""
    key = "%s|%s" % (hash(source), str(ext))
    cached = _SPIDER_CACHE.get(key)
    if cached is not None:
        return cached
    _install_base_stub()
    namespace = {"__name__": "__fish_spider__", "__file__": "spider.py"}
    exec(compile(source, "<fish_spider>", "exec"), namespace, namespace)
    cls = _find_spider_class(namespace)
    inst = _new_instance(cls)
    if hasattr(inst, "init"):
        _init_instance(inst, ext)
    # 缓存上限控制：超限只淘汰最早入缓存的实例，不全清——
    # 之前满 8 个直接 clear()，py 站点一多（20+）来回切分类就把缓存打穿，
    # 每次都要重新 exec 整个脚本 + 跑 init（init 里常有网络请求），分类加载明显变慢
    if len(_SPIDER_CACHE) >= 24:
        _SPIDER_CACHE.pop(next(iter(_SPIDER_CACHE)), None)
    _SPIDER_CACHE[key] = inst
    return inst


def _resolve_method(inst, names):
    """在实例上找到脚本真正实现的方法（跳过基类桩的默认实现）。

    names 按优先级排列，覆盖 camelCase / 全小写两种命名。
    """
    for name in names:
        fn = getattr(inst, name, None)
        if not callable(fn):
            continue
        base_fn = getattr(_SpiderBase, name, None)
        if base_fn is not None and getattr(fn, "__func__", fn) is base_fn:
            continue          # 只是桩的默认实现，说明脚本没写这个方法
        return fn
    return None


def _invoke(inst, names, arg_variants):
    """按方法名候选 + 参数个数候选依次尝试调用，返回 (result, method_name)。"""
    fn = _resolve_method(inst, names)
    if fn is None:
        return None, None
    last_err = None
    for args in arg_variants:
        try:
            return fn(*args), names[0]
        except TypeError as e:
            last_err = e
            continue
    if last_err is not None:
        raise last_err
    return None, names[0]


# ===== TVBox 结果 -> App VodItem 字段映射 =====
def _pick(d, *keys):
    for k in keys:
        v = d.get(k)
        if v not in (None, ""):
            return v
    return ""


def _to_vod(item):
    if not isinstance(item, dict):
        return None
    vod = {
        "id": str(_pick(item, "vod_id", "id", "videoid")),
        "title": str(_pick(item, "vod_name", "name", "title")),
        "cover": str(_pick(item, "vod_pic", "pic", "cover")),
        "rating": str(_pick(item, "vod_score", "rating", "score")),
        "year": str(_pick(item, "vod_year", "year")),
        "category": str(_pick(item, "vod_class", "category")),
        "desc": str(_pick(item, "vod_content", "desc", "description")),
        "remarks": str(_pick(item, "vod_remarks", "remarks")),
        "director": str(_pick(item, "vod_director", "director")),
        "actor": str(_pick(item, "vod_actor", "actor")),
        "episodes": [],
    }
    # 播放列表：vod_play_url / vod_play_list -> 展开成 episodes
    play_raw = _pick(item, "vod_play_url", "vod_play_list", "play_url")
    if isinstance(play_raw, dict):
        play_raw = next(iter(play_raw.values()), "")
    if isinstance(play_raw, str) and play_raw:
        ep = []
        # 标准格式：名称$地址#名称$地址 ；多播放源用 $$$ 分隔，全部展开成线路，
        # 线路名从 vod_play_from 按 $$$ 一一对应取，缺失时按序号补「线路N」
        from_raw = str(_pick(item, "vod_play_from", "play_from", "") or "")
        from_parts = [p.strip() for p in re.split(r"\${3}", from_raw)]
        sources = [s for s in re.split(r"\${3}", play_raw) if s.strip()]
        for idx, source in enumerate(sources):
            line_name = from_parts[idx] if idx < len(from_parts) and from_parts[idx] else ("线路%d" % (idx + 1))
            for group in source.split("#"):
                if not group:
                    continue
                if "$" in group:
                    name, url = group.split("$", 1)
                    ep.append({"name": name, "url": url, "playFrom": line_name})
                elif "," in group:
                    name, url = group.split(",", 1)
                    ep.append({"name": name, "url": url, "playFrom": line_name})
                elif group.strip():
                    ep.append({"name": "播放", "url": group, "playFrom": line_name})
        vod["episodes"] = ep
    return vod


def _vod_list_from(result):
    if isinstance(result, list):
        raw = result
    elif isinstance(result, dict):
        raw = result.get("list", result.get("result", []))
        if isinstance(raw, dict):
            raw = raw.get("list", [])
    else:
        raw = []
    out = []
    for x in raw:
        v = _to_vod(x)
        if v:
            out.append(v)
    return out


def _err(e):
    """统一错误结构：message 给用户看，trace 给开发定位到行。"""
    return {
        "error": "%s: %s" % (type(e).__name__, e),
        "trace": traceback.format_exc(),
    }


# ===== 对沙箱暴露的统一入口 =====

def fish_home(source, param_json):
    """首页数据：homeContent 的 class -> 分类栏；homeVideoContent/categoryContent -> 推荐影片。"""
    try:
        sp = load_spider(source, _ext_of(param_json))
        home, _ = _invoke(sp, ("homeContent", "homecontent"),
                          [(True,), (False,), ()])
        classes = home.get("class", []) if isinstance(home, dict) else []
        norm_classes = []
        for c in classes:
            if isinstance(c, dict):
                tname = _pick(c, "type_name", "name", "title", "cate_name")
                # 有些脚本的 class 只有名字没有 type_id：用名字当 tid，
                # 否则 5 个分类的 tid 全是空串，categoryContent 每次都返回同一批影片。
                tid = _pick(c, "type_id", "tid", "id", "cate_id") or tname
                norm_classes.append({"type_id": str(tid), "type_name": str(tname)})

        videos = []
        # 优先 homeVideoContent（首页推荐）
        try:
            hv, _ = _invoke(sp, ("homeVideoContent", "homevideocontent"), [()])
            videos = _vod_list_from(hv)
        except Exception:
            videos = []

        # 没有推荐就取第一个分类第一页
        if not videos and norm_classes:
            try:
                tid = norm_classes[0]["type_id"]
                cc, _ = _invoke(sp, ("categoryContent", "categorycontent"),
                                [(tid, "1", False, ""), (tid, "1", False), (tid, "1"), (tid,)])
                videos = _vod_list_from(cc)
            except Exception:
                videos = []

        return json.dumps(
            {"result": {"classes": norm_classes, "videos": videos}, "error": None},
            ensure_ascii=False,
        )
    except Exception as e:
        payload = {"result": {"classes": [], "videos": []}}
        payload.update(_err(e))
        return json.dumps(payload, ensure_ascii=False)


def fish_category(source, param_json):
    """分类页数据：categoryContent(tid, pg, filter, extend) -> 影片列表"""
    try:
        param = json.loads(param_json) if isinstance(param_json, str) else (param_json or {})
        tid = str(param.get("tid", param.get("id", "")))
        pg = str(param.get("pg", param.get("page", "1")))
        sp = load_spider(source, str(param.get("ext", "")))
        # TVBox 约定：categoryContent 第 4 参是筛选条件字典。脚本（爱听歌手分类等）
        # 会直接 extend.get(...) 取筛选项，传空串会 AttributeError 整页空——
        # 统一转成 dict：没传筛选时给空字典，脚本自己兜默认值
        extend = param.get("extend", param.get("filters"))
        if isinstance(extend, str):
            extend = json.loads(extend) if extend.strip() else {}
        if not isinstance(extend, dict):
            extend = {}
        result, _ = _invoke(sp, ("categoryContent", "categorycontent"),
                            [(tid, pg, False, extend), (tid, pg, False), (tid, pg), (tid,)])
        return json.dumps({"result": _vod_list_from(result), "error": None}, ensure_ascii=False)
    except Exception as e:
        payload = {"result": []}
        payload.update(_err(e))
        return json.dumps(payload, ensure_ascii=False)


def fish_config(source, param_json):
    """返回站点分类（供分类页/站点注册）"""
    try:
        sp = load_spider(source, _ext_of(param_json))
        home, _ = _invoke(sp, ("homeContent", "homecontent"), [(True,), (False,), ()])
        classes = home.get("class", []) if isinstance(home, dict) else []
        return json.dumps({"result": classes, "error": None}, ensure_ascii=False)
    except Exception as e:
        payload = {"result": []}
        payload.update(_err(e))
        return json.dumps(payload, ensure_ascii=False)


def fish_search(source, param_json):
    try:
        param = json.loads(param_json) if isinstance(param_json, str) else (param_json or {})
        key = param.get("keyword", param.get("key", ""))
        sp = load_spider(source, str(param.get("ext", "")))
        result, _ = _invoke(sp, ("searchContent", "searchcontent"),
                            [(key, False, 1), (key, False), (key,)])
        return json.dumps({"result": _vod_list_from(result), "error": None}, ensure_ascii=False)
    except Exception as e:
        payload = {"result": []}
        payload.update(_err(e))
        return json.dumps(payload, ensure_ascii=False)


def fish_detail(source, param_json):
    """用 vod_id 调 detailContent，返回带 episodes 的影片详情"""
    try:
        param = json.loads(param_json) if isinstance(param_json, str) else (param_json or {})
        vid = str(param.get("id", param.get("vodId", "")))
        if not vid:
            return json.dumps({"result": None, "error": "no id"}, ensure_ascii=False)
        sp = load_spider(source, str(param.get("ext", "")))
        result, _ = _invoke(sp, ("detailContent", "detailcontent"), [([vid],), (vid,)])
        items = _vod_list_from(result)
        return json.dumps({"result": items[0] if items else None, "error": None}, ensure_ascii=False)
    except Exception as e:
        payload = {"result": None}
        payload.update(_err(e))
        return json.dumps(payload, ensure_ascii=False)


def fish_play(source, param_json):
    try:
        param = json.loads(param_json) if isinstance(param_json, str) else (param_json or {})
        sp = load_spider(source, str(param.get("ext", "")))
        raw = str(param.get("playUrl", param.get("url", "")))
        flag = str(param.get("flag", "obv"))
        pid = raw
        if "|" in raw:
            parts = raw.split("|")
            if len(parts) >= 3:
                flag, pid = parts[0], parts[2]
            elif len(parts) == 2:
                flag, pid = parts[0], parts[1]
        if not pid:
            pid = str(param.get("id", ""))
        # 只有 vod_id（纯数字）时，先取详情拿第一集播放页地址
        if pid and pid.isdigit():
            det, _ = _invoke(sp, ("detailContent", "detailcontent"), [([pid],), (pid,)])
            items = _vod_list_from(det)
            eps = items[0].get("episodes", []) if items else []
            if eps:
                pid = str(eps[0].get("url", ""))
        result, _ = _invoke(sp, ("playerContent", "playercontent"),
                            [(flag, pid, None), (flag, pid)])
        play_url = ""
        res_parse = 0
        res_jx = 0
        res_header = ""
        if isinstance(result, dict):
            picked = _pick(result, "url", "play")
            if isinstance(picked, (list, tuple)):
                # TVBox 音质列表约定：[音质名, 地址, 音质名, 地址...]，取第一个像地址的元素。
                # 整串 str() 会变成 "['名', 'http...']" 垃圾字符串，播放器认不出（酷我音乐就挂在这）
                play_url = next(
                    (u for u in picked if isinstance(u, str) and u.startswith(("http://", "https://"))),
                    ""
                )
                if not play_url and len(picked) > 1 and isinstance(picked[1], str):
                    play_url = picked[1]
            else:
                play_url = str(picked)
            try:
                res_parse = int(result.get("parse", 0) or 0)
            except Exception:
                res_parse = 0
            try:
                res_jx = int(result.get("jx", 0) or 0)
            except Exception:
                res_jx = 0
            # header 透传：spider 指定的播放请求头（Referer/UA/Cookie），
            # 统一序列化成 JSON 字符串，Kotlin 端解析后存入 PlayHeaderStore
            hdr = result.get("header")
            if isinstance(hdr, dict):
                res_header = json.dumps(hdr, ensure_ascii=False)
            elif isinstance(hdr, str):
                res_header = hdr
        # parse/jx/header 透传给 Kotlin 端：parse/jx=1 表示需嗅探（App 端拦截），
        # header 是该地址的请求头要求（播放时应用）
        return json.dumps({"result": play_url, "parse": res_parse, "jx": res_jx,
                           "header": res_header,
                           "error": None if play_url else "no_url"},
                          ensure_ascii=False)
    except Exception as e:
        payload = {"result": ""}
        payload.update(_err(e))
        return json.dumps(payload, ensure_ascii=False)


def inspect_source(source):
    """诊断用：报告类名、实现了哪些方法、init 与 homeContent 是否可跑，失败带 traceback。"""
    info = {"class": "", "methods": [], "init": "ok", "home": "ok", "trace": ""}
    try:
        sp = load_spider(source)
        info["class"] = type(sp).__name__
        wanted = ("init", "homeContent", "homecontent", "homeVideoContent", "homevideocontent",
                  "categoryContent", "categorycontent", "searchContent", "searchcontent",
                  "detailContent", "detailcontent", "playerContent", "playercontent")
        info["methods"] = [n for n in wanted if _resolve_method(sp, (n,)) is not None]
        try:
            home, _ = _invoke(sp, ("homeContent", "homecontent"), [(True,), (False,), ()])
            classes = home.get("class", []) if isinstance(home, dict) else []
            info["home"] = "ok（%d 个分类）" % len(classes)
        except Exception as e:
            info["home"] = "%s: %s" % (type(e).__name__, e)
            info["trace"] = traceback.format_exc()
    except Exception as e:
        info["init"] = "%s: %s" % (type(e).__name__, e)
        info["trace"] = traceback.format_exc()
    return json.dumps(info, ensure_ascii=False)
