# -*- coding: utf-8 -*-
# name: 示例站点
# version: 1.0
# sitename: 示例源
# author: fanhua
"""
繁华TV PY 脚本模板 —— 用户可复制此模板改造。
必须实现 config / search / play 三个函数，可选 channels 用于直播。
"""

SITE_API = "https://example.com/api"


def config(param):
    """返回站点/分类配置，格式与远程 JSON 一致。"""
    return {
        "sites": [{"key": "demo", "name": "示例站", "api": SITE_API}],
        "categories": [{"name": "电影"}, {"name": "电视剧"}],
    }


def search(param):
    """param: {"keyword": "xxx"}，返回影片列表。"""
    keyword = param.get("keyword", "")
    return [
        {"title": keyword + " 第1集", "cover": "", "rating": "8.5", "playUrl": ""}
    ]


def play(param):
    """param: {"playUrl": "...", "id": "..."}，返回真实播放地址 m3u8/mp4。"""
    return param.get("playUrl", "")


def channels(param):
    """可选：返回直播频道列表。"""
    return []
