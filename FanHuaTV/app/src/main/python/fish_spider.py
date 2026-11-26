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

    class HTTPAdapter(object):
        """requests.adapters.HTTPAdapter 替身：脚本常用它配重试/连接池，
        这里只吞参数（重试/池化由 _build 的 urllib 直连承担）。"""

        def __init__(self, *args, **kwargs):
            self.max_retries = kwargs.get("max_retries")
            for junk in ("pool_connections", "pool_maxsize", "pool_block"):
                kwargs.pop(junk, None)

        def mount(self, *args, **kwargs):
            pass

    class Session(object):
        def __init__(self):
            self.headers = {"User-Agent": "Mozilla/5.0"}
            self.cookies = {}

        def mount(self, prefix, adapter):
            pass  # 替身无连接池，挂载是空操作

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
    _Shim.HTTPAdapter = HTTPAdapter

    mod = _Shim()
    sys.modules["requests"] = mod
    # `from requests.adapters import HTTPAdapter` 走的是独立子模块导入，
    # 光有 mod.adapters 属性不够，必须把 requests.adapters 注册进 sys.modules
    adapters_mod = types.ModuleType("requests.adapters")
    adapters_mod.HTTPAdapter = HTTPAdapter
    mod.adapters = adapters_mod
    sys.modules["requests.adapters"] = adapters_mod
    _requests = mod
    return mod


def _install_urllib3_shim():
    """urllib3 替身：道长系脚本常 `from urllib3.util.retry import Retry`
    配 requests 重试。只给 Retry 类吞参数，真实重试由 _build 承担。"""
    try:
        import urllib3  # noqa: F401
        return
    except Exception:
        pass
    if "urllib3" in sys.modules:
        return

    class Retry(object):
        def __init__(self, *args, **kwargs):
            self.total = kwargs.get("total")
            self.backoff_factor = kwargs.get("backoff_factor")
            self.status_forcelist = kwargs.get("status_forcelist")

    u3 = types.ModuleType("urllib3")
    util = types.ModuleType("urllib3.util")
    retry = types.ModuleType("urllib3.util.retry")
    retry.Retry = Retry
    u3.util = util
    util.Retry = Retry
    u3.Retry = Retry
    sys.modules["urllib3"] = u3
    sys.modules["urllib3.util"] = util
    sys.modules["urllib3.util.retry"] = retry


# fish_spider 被 fanhua_runtime 先导入，在这里装好替身，脚本里再 `import requests` 就不会炸
_install_requests_shim()
_install_urllib3_shim()


def _install_pyquery_shim():
    """pyquery 替身：道长系脚本（爱听/酷我等）开头就 `from pyquery import PyQuery`，
    手机版 Chaquopy 没装这个包，import 直接 ModuleNotFoundError，整个脚本 exec
    失败、分类全空。电视版 pyramid 走 pip install 没这问题，手机版照 requests
    替身的路子，用标准库 html.parser 实现脚本实际用到的子集：
    pq(html) / 选择器调用 / eq / clone / remove / items / outerHtml / text / html / attr。
    """
    try:
        import pyquery  # noqa: F401
        return  # 真包在，不装替身
    except Exception:
        pass
    if "pyquery" in sys.modules:
        return

    import html.parser as _hp
    import html as _htmlmod

    _VOID = {"area", "base", "br", "col", "embed", "hr", "img", "input",
             "link", "meta", "param", "source", "track", "wbr"}

    class _Node(object):
        __slots__ = ("tag", "attrs", "children", "parent")

        def __init__(self, tag, attrs=None, parent=None):
            self.tag = tag.lower()
            self.attrs = attrs or {}
            self.children = []          # _Node 或 str（文本）
            self.parent = parent

        def _iter(self):
            for ch in self.children:
                if isinstance(ch, _Node):
                    yield ch
                    for d in ch._iter():
                        yield d

        def _texts(self):
            for ch in self.children:
                if isinstance(ch, str):
                    yield ch
                else:
                    for t in ch._texts():
                        yield t

        def _inner(self):
            out = []
            for ch in self.children:
                if isinstance(ch, str):
                    out.append(_htmlmod.escape(ch, quote=False))
                else:
                    out.append(_outer(ch))
            return "".join(out)

        def _copy(self, parent=None):
            n = _Node(self.tag, dict(self.attrs), parent)
            for ch in self.children:
                n.children.append(ch if isinstance(ch, str) else ch._copy(n))
            return n

    def _outer(n):
        a = "".join(' %s="%s"' % (k, _htmlmod.escape(str(v), quote=True))
                    for k, v in (n.attrs or {}).items())
        if n.tag in _VOID and not n.children:
            return "<%s%s/>" % (n.tag, a)
        return "<%s%s>%s</%s>" % (n.tag, a, n._inner(), n.tag)

    class _Builder(_hp.HTMLParser):
        def __init__(self):
            _hp.HTMLParser.__init__(self, convert_charrefs=True)
            self.root = _Node("document")
            self.stack = [self.root]

        @staticmethod
        def _attrs(attrs):
            return {k: (v if v is not None else "") for k, v in attrs}

        def handle_starttag(self, tag, attrs):
            node = _Node(tag, self._attrs(attrs), self.stack[-1])
            self.stack[-1].children.append(node)
            if tag.lower() not in _VOID:
                self.stack.append(node)

        def handle_startendtag(self, tag, attrs):
            node = _Node(tag, self._attrs(attrs), self.stack[-1])
            self.stack[-1].children.append(node)

        def handle_endtag(self, tag):
            t = tag.lower()
            for i in range(len(self.stack) - 1, 0, -1):
                if self.stack[i].tag == t:
                    del self.stack[i:]
                    return

        def handle_data(self, data):
            if data:
                self.stack[-1].children.append(data)

    # ---- 选择器：tag / .cls / #id / [attr] / [attr*=v] / :eq(n) 等 ----
    def _parse_simple(s):
        tag, classes, eid, attrs, pseudo = None, [], None, [], None
        i = 0
        while i < len(s):
            c = s[i]
            if c == "*":
                i += 1
            elif c == ".":
                m = re.match(r"\.([\w\-]+)", s[i:])
                if not m:
                    return None
                classes.append(m.group(1))
                i += m.end()
            elif c == "#":
                m = re.match(r"#([\w\-]+)", s[i:])
                if not m:
                    return None
                eid = m.group(1)
                i += m.end()
            elif c == "[":
                j = s.find("]", i)
                if j < 0:
                    return None
                attrs.append(s[i + 1:j].strip())
                i = j + 1
            elif c == ":":
                m = re.match(r":(\w+)(?:\((\d+)\))?", s[i:])
                if not m:
                    return None
                pseudo = (m.group(1).lower(), m.group(2))
                i += m.end()
            else:
                m = re.match(r"[a-zA-Z][\w\-]*", s[i:])
                if not m:
                    return None
                tag = m.group(0).lower()
                i += m.end()
        return tag, classes, eid, attrs, pseudo

    def _match_attr(node, cond):
        m = re.match(r"([\w\-]+)\s*(\*|\^|\$)?=\s*(.*)$", cond)
        if m:
            name, op, val = m.group(1), m.group(2), m.group(3).strip().strip("\"'")
            have = str(node.attrs.get(name, "") or "")
            if op == "*":
                return val in have
            if op == "^":
                return have.startswith(val)
            if op == "$":
                return have.endswith(val)
            return have == val
        return node.attrs.get(cond.strip()) is not None

    def _match_simple(node, simple):
        """返回 (是否匹配, 伪类)；伪类单独返回，集合层面统一处理。"""
        parsed = _parse_simple(simple)
        if parsed is None:
            return False, None
        tag, classes, eid, attrs, pseudo = parsed
        if tag and tag != "*" and node.tag != tag:
            return False, None
        if classes:
            cl = (node.attrs.get("class") or "").split()
            if not all(c in cl for c in classes):
                return False, None
        if eid is not None and node.attrs.get("id") != eid:
            return False, None
        for a in attrs:
            if not _match_attr(node, a):
                return False, None
        return True, pseudo

    def _select(root, selector):
        """从 root 的后代里选，返回 [_Node]；支持逗号分组、空格后代链、eq/first/last/gt/lt。"""
        out, seen = [], set()
        for part in str(selector).split(","):
            part = part.strip()
            if not part:
                continue
            chain = part.split()
            cand = []

            def walk(n, ci):
                ok, p = _match_simple(n, chain[ci])
                if not ok:
                    return
                if ci + 1 < len(chain):
                    for d in n._iter():
                        walk(d, ci + 1)
                else:
                    cand.append((n, p))

            # 真 pyquery 的上下文选择包含元素自身（lxml cssselect 语义）
            for n in [root] + list(root._iter()):
                walk(n, 0)
            pseudo = None
            for _, p in cand:
                if p:
                    pseudo = p
            if pseudo:
                name, arg = pseudo
                try:
                    idx = int(arg) if arg else 0
                except ValueError:
                    idx = 0
                if name == "first":
                    cand = cand[:1]
                elif name == "last":
                    cand = cand[-1:]
                elif name == "eq" or name == "nth-child":
                    cand = cand[idx:idx + 1]
                elif name == "gt":
                    cand = cand[idx + 1:]
                elif name == "lt":
                    cand = cand[:idx]
            for n, _ in cand:
                if id(n) not in seen:
                    seen.add(id(n))
                    out.append(n)
        return out

    class PyQuery(object):
        def __init__(self, nodes=None):
            if isinstance(nodes, str):
                b = _Builder()
                try:
                    b.feed(nodes)
                    b.close()
                except Exception:
                    pass
                top = [n for n in b.root.children if isinstance(n, _Node)]
                # lxml 会把残缺片段包进 html>body（如 pq('<div/>')('body') 也能命中），
                # 这里对齐：顶层不是 html 就补一层 html>body 包裹；空输入不包裹
                if top and not (len(top) == 1 and top[0].tag == "html"):
                    html_n = _Node("html")
                    body_n = _Node("body", parent=html_n)
                    for n in top:
                        n.parent = body_n
                        body_n.children.append(n)
                    for ch in b.root.children:
                        if isinstance(ch, str) and ch.strip():
                            body_n.children.append(ch)
                    html_n.children.append(body_n)
                    top = [html_n]
                self._nodes = top
            elif isinstance(nodes, PyQuery):
                # 真 pyquery 用底层 lxml 元素重建；这里取原节点，避免 list() 迭代产出 PyQuery
                self._nodes = list(nodes._nodes)
            else:
                self._nodes = [n._nodes[0] if isinstance(n, PyQuery) else n
                               for n in (nodes or [])]

        def __call__(self, selector=None):
            if not selector:
                return PyQuery(self._nodes)
            out, seen = [], set()
            for n in self._nodes:
                for m in _select(n, selector):
                    if id(m) not in seen:
                        seen.add(id(m))
                        out.append(m)
            return PyQuery(out)

        def __len__(self):
            return len(self._nodes)

        def __bool__(self):
            return len(self._nodes) > 0

        def __iter__(self):
            return iter(PyQuery([n]) for n in self._nodes)

        def eq(self, i):
            if -len(self._nodes) <= i < len(self._nodes):
                return PyQuery([self._nodes[i]])
            return PyQuery([])

        def clone(self):
            return PyQuery([n._copy(None) for n in self._nodes])

        def remove(self, selector=None):
            if selector:
                targets = [m for n in self._nodes for m in _select(n, selector)]
            else:
                targets = self._nodes
            for n in targets:
                p = n.parent
                if p is not None and n in p.children:
                    p.children.remove(n)
                    n.parent = None
            return self

        def items(self):
            return iter(PyQuery([n]) for n in self._nodes)

        def text(self):
            parts = []
            for n in self._nodes:
                parts.extend(t for t in n._texts() if t)
            return " ".join("".join(parts).split())

        def html(self):
            return self._nodes[0]._inner() if self._nodes else ""

        def outerHtml(self):
            return _outer(self._nodes[0]) if self._nodes else ""

        def attr(self, name, value=None):
            if not self._nodes:
                return None
            return self._nodes[0].attrs.get(name)

        def find(self, selector):
            return self(selector)

        def children(self, selector=None):
            out, seen = [], set()
            for n in self._nodes:
                for ch in n.children:
                    if isinstance(ch, _Node) and id(ch) not in seen:
                        seen.add(id(ch))
                        out.append(ch)
            pq = PyQuery(out)
            return pq(selector) if selector else pq

        def parent(self):
            out, seen = [], set()
            for n in self._nodes:
                p = n.parent
                if p is not None and id(p) not in seen:
                    seen.add(id(p))
                    out.append(p)
            return PyQuery(out)

    mod = types.ModuleType("pyquery")
    mod.PyQuery = PyQuery
    mod.pq = PyQuery
    sys.modules["pyquery"] = mod


def _install_misc_shims():
    """道长系脚本还常 import ujson / jsonpath，一并给标准库替身。"""
    if "ujson" not in sys.modules:
        try:
            import ujson  # noqa: F401
        except Exception:
            u = types.ModuleType("ujson")
            u.loads = json.loads
            u.dumps = json.dumps
            u.load = json.load
            u.dump = json.dump
            sys.modules["ujson"] = u
    if "jsonpath" not in sys.modules:
        try:
            import jsonpath  # noqa: F401
        except Exception:
            def _jsonpath(data, expr):
                try:
                    if not expr or not expr.startswith("$"):
                        return False
                    tokens = re.findall(
                        r"\.([A-Za-z_][\w\-]*)|\[(\*|\d+)\]", expr[1:])
                    cur = [data]
                    for name, idx in tokens:
                        nxt = []
                        for d in cur:
                            if name:
                                if isinstance(d, dict) and name in d:
                                    nxt.append(d[name])
                            elif isinstance(d, list):
                                if idx == "*":
                                    nxt.extend(d)
                                else:
                                    i = int(idx)
                                    if -len(d) <= i < len(d):
                                        nxt.append(d[i])
                        cur = nxt
                        if not cur:
                            return False
                    return cur if cur else False
                except Exception:
                    return False

            jp = types.ModuleType("jsonpath")
            jp.jsonpath = _jsonpath
            sys.modules["jsonpath"] = jp


_install_pyquery_shim()
_install_misc_shims()


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
        # 歌词/歌手海报透传：音乐类脚本 playerContent 可能返回 artwork/lyric 字段，
        # 原样带给 Kotlin 端写入播放总线，播放页起播后拉取渲染
        res_artwork = ""
        res_lyric = ""
        if isinstance(result, dict):
            aw = result.get("artwork") or result.get("cover") or ""
            if isinstance(aw, str):
                res_artwork = aw
            ly = result.get("lyric") or result.get("lyrics") or ""
            if isinstance(ly, str):
                res_lyric = ly
        # parse/jx/header/artwork/lyric 透传给 Kotlin 端：parse/jx=1 表示需嗅探（App 端拦截），
        # header 是该地址的请求头要求（播放时应用）
        return json.dumps({"result": play_url, "parse": res_parse, "jx": res_jx,
                           "header": res_header,
                           "artwork": res_artwork, "lyric": res_lyric,
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
