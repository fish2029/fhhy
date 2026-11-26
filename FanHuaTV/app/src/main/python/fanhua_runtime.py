# -*- coding: utf-8 -*-
"""
繁华TV Python 沙箱运行时
由 Chaquopy 在 App 内调用，负责受限环境下执行导入的 PY 脚本。
对外暴露五个入口：inspect_script / fetch_config / search / parse_play / list_channels
"""
import json
import re
import sys
import io
import builtins

try:
    import fish_spider
except ImportError:
    fish_spider = None


def is_fish_script(source):
    """识别鱼壳/TVBox 的 class Spider 脚本（大小写两套方法名都认）"""
    return (
        "class Spider" in source
        or "class spider" in source
        or "def homeContent" in source
        or "def homecontent" in source
        or "def searchContent" in source
        or "def searchcontent" in source
        or "def playerContent" in source
        or "def playercontent" in source
        or "from base.spider" in source
        or "from base import spider" in source
    )


def probe(source):
    """诊断入口：报告脚本类名/实现了哪些方法/首页能否跑通，失败带完整 traceback。"""
    if fish_spider is None:
        return json.dumps({"class": "", "init": "fish_spider 模块未加载", "trace": ""},
                          ensure_ascii=False)
    if is_fish_script(source):
        return fish_spider.inspect_source(source)
    return json.dumps(
        {"class": "非鱼壳脚本", "methods": [], "init": "ok",
         "home": "脚本不是鱼壳结构，首页数据请用 JSON 接口", "trace": ""},
        ensure_ascii=False,
    )


def script_capabilities(source):
    """返回脚本支持的能力列表，供 Kotlin 侧校验/导入判断"""
    if is_fish_script(source):
        return ["config", "search", "detail", "play", "fish"]
    caps = []
    for fn in ("config", "search", "play", "channels"):
        if re.search(r"^\s*def\s+%s\s*\(" % fn, source, re.M):
            caps.append(fn)
    return caps

# 允许脚本使用的安全内建函数白名单
_SAFE_BUILTINS = {
    "len": len, "str": str, "int": int, "float": float, "bool": bool,
    "list": list, "dict": dict, "tuple": tuple, "set": set,
    "range": range, "enumerate": enumerate, "zip": zip,
    "min": min, "max": max, "sum": sum, "sorted": sorted,
    "abs": abs, "round": round, "isinstance": isinstance,
    "True": True, "False": False, "None": None,
}


class ScriptSandbox:
    """把用户脚本的执行限制在白名单内建函数与受控模块中。"""

    def __init__(self, source, extra_globals=None):
        self.source = source
        self.globals = {
            "__builtins__": dict(_SAFE_BUILTINS),
            "__name__": "__fanhua_script__",
        }
        if extra_globals:
            self.globals.update(extra_globals)
        self.logs = []

    def log(self, msg):
        self.logs.append(str(msg))

    def _base_globals(self):
        g = dict(self.globals)
        g["log"] = self.log
        g["print"] = self.log
        return g

    def execute(self, func_name, args):
        """执行脚本并调用指定函数，返回 (result, logs, error)。"""
        g = self._base_globals()
        try:
            exec(compile(self.source, "<fanhua_script>", "exec"), g, g)
            fn = g.get(func_name)
            if fn is None or not callable(fn):
                return None, self.logs, "function_not_found:%s" % func_name
            result = fn(*args)
            return result, self.logs, None
        except Exception as e:
            return None, self.logs, "%s: %s" % (type(e).__name__, e)


def inspect_script(source):
    """解析脚本头部元信息：名称/版本/站点/支持的能力。"""
    meta = {"name": "", "version": "1.0", "site": "", "author": "",
            "supports": ["config", "search", "play"]}
    for line in source.splitlines()[:40]:
        line = line.strip()
        m = re.match(r"#\s*(\w+)\s*[:：]\s*(.+)", line)
        if m:
            key, val = m.group(1).lower(), m.group(2).strip()
            if key in ("name", "版本", "sitename", "version", "author", "site"):
                mapping = {"sitename": "site", "版本": "version"}
                meta[mapping.get(key, key)] = val
    if not meta["name"]:
        # 鱼壳脚本多无注释头，退而求其次：从类名 / 站点名变量猜，取不到交给 Kotlin 用文件名兜底
        m = re.search(r"class\s+(\w+)\s*\(\s*Spider\s*\)", source, re.I)
        if m and m.group(1).lower() not in ("spider", "base"):
            meta["name"] = m.group(1)
    if not meta["site"]:
        m = re.search(r"https?://[\w.\-]+", source)
        if m:
            meta["site"] = m.group(0)
    return json.dumps(meta, ensure_ascii=False)


def _safe_args(value):
    try:
        return json.loads(value) if isinstance(value, str) else value
    except Exception:
        return value


def home(source, param_json):
    """首页：分类栏 + 推荐影片"""
    if is_fish_script(source) and fish_spider is not None:
        return fish_spider.fish_home(source, param_json)
    # 原生脚本没有 homeContent：从 config() 的 categories/class 里取分类栏。
    # 否则这类脚本导入后永远返回 not_fish，首页和分类页都不会出分类。
    try:
        raw = fetch_config(source, param_json)
        obj = json.loads(raw) if isinstance(raw, str) else raw
        err = obj.get("error")
        if err:
            return json.dumps({"result": {"classes": [], "videos": []},
                               "error": str(err)}, ensure_ascii=False)
        cfg = obj.get("result") or {}
        rows = cfg.get("categories") or cfg.get("class") or []
        classes = []
        for c in rows:
            if not isinstance(c, dict):
                continue
            name = _pick(c, "name", "type_name", "title")
            if not name:
                continue
            key = _pick(c, "key", "type_id", "id") or name
            classes.append({"type_id": str(key), "type_name": str(name)})
        return json.dumps({"result": {"classes": classes, "videos": []},
                           "error": None}, ensure_ascii=False)
    except Exception as e:
        return json.dumps({"result": {"classes": [], "videos": []},
                           "error": "%s: %s" % (type(e).__name__, e)}, ensure_ascii=False)


def _pick(d, *keys):
    for k in keys:
        v = d.get(k)
        if v not in (None, ""):
            return v
    return ""


def category(source, param_json):
    """分类页影片列表"""
    if is_fish_script(source) and fish_spider is not None:
        return fish_spider.fish_category(source, param_json)
    return json.dumps({"result": [], "error": "not_fish"}, ensure_ascii=False)


def fetch_config(source, param_json):
    """调用脚本 config(url) 返回站点配置 JSON 字符串。鱼壳脚本走 homeContent。"""
    if is_fish_script(source) and fish_spider is not None:
        return fish_spider.fish_config(source, param_json)
    param = _safe_args(param_json)
    sb = ScriptSandbox(source, {
        "requests": _safe_requests(),
        "json": json,
        "re": re,
    })
    result, logs, err = sb.execute("config", [param])
    return json.dumps({"result": result, "logs": logs, "error": err}, ensure_ascii=False)


def search(source, param_json):
    if is_fish_script(source) and fish_spider is not None:
        return fish_spider.fish_search(source, param_json)
    param = _safe_args(param_json)
    sb = ScriptSandbox(source, {"requests": _safe_requests(), "json": json, "re": re})
    result, logs, err = sb.execute("search", [param])
    return json.dumps({"result": result, "logs": logs, "error": err}, ensure_ascii=False)


def parse_play(source, param_json):
    if is_fish_script(source) and fish_spider is not None:
        return fish_spider.fish_play(source, param_json)
    param = _safe_args(param_json)
    sb = ScriptSandbox(source, {"requests": _safe_requests(), "json": json, "re": re})
    result, logs, err = sb.execute("play", [param])
    return json.dumps({"result": result, "logs": logs, "error": err}, ensure_ascii=False)


def detail(source, param_json):
    """影片详情（剧集列表）；鱼壳走 detailContent，普通脚本可选实现"""
    if is_fish_script(source) and fish_spider is not None:
        return fish_spider.fish_detail(source, param_json)
    param = _safe_args(param_json)
    sb = ScriptSandbox(source, {"requests": _safe_requests(), "json": json, "re": re})
    result, logs, err = sb.execute("detail", [param])
    return json.dumps({"result": result, "logs": logs, "error": err}, ensure_ascii=False)


def list_channels(source, param_json):
    param = _safe_args(param_json)
    sb = ScriptSandbox(source, {"requests": _safe_requests(), "json": json, "re": re})
    result, logs, err = sb.execute("channels", [param])
    return json.dumps({"result": result, "logs": logs, "error": err}, ensure_ascii=False)


def _safe_requests():
    """提供受限 requests：仅 get，强制超时，返回对象仅暴露 text/json。"""
    try:
        import requests
    except ImportError:
        # 纯计算脚本（config/search）不依赖网络，缺 requests 也要能跑
        return None

    class SafeResponse:
        def __init__(self, resp):
            self._r = resp

        @property
        def text(self):
            return self._r.text

        def json(self):
            return self._r.json()

        @property
        def status_code(self):
            return self._r.status_code

    class SafeRequests:
        @staticmethod
        def get(url, **kwargs):
            kwargs["timeout"] = min(kwargs.get("timeout", 8), 10)
            kwargs.pop("verify", None)
            return SafeResponse(requests.get(url, **kwargs))

    return SafeRequests()


def check_dependencies():
    """检查常用第三方库是否可用，返回缺失列表。"""
    missing = []
    for mod in ("requests", "json", "re"):
        try:
            __import__(mod)
        except ImportError:
            missing.append(mod)
    return json.dumps(missing)
