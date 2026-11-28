package com.github.catvod.crawler;

import android.app.Activity;
import android.content.Context;
import com.github.catvod.net.Net;
import com.github.catvod.net.OkHttp;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import okhttp3.OkHttpClient;

/**
 * CatVod / TVBox 爬虫框架基类。
 *
 * jar 包内的 CSP 爬虫类（com.github.catvod.spider.XXX）全部继承此类，
 * 通过 DexClassLoader 动态加载后，调用此处定义的方法获取数据。
 *
 * 所有数据方法返回 JSON 字符串（MacCMS 格式），由 App 侧解析。
 * 基类提供默认空实现，子类按需覆盖，避免未实现的方法被调用时崩溃。
 *
 * 方法签名必须与 jar 内 DEX 字节码完全一致，否则反射加载时会抛 NoSuchMethodError。
 */
public abstract class Spider {

    /** TVBox 原版基类的字段：jar 内蜘蛛会直接读写 siteKey / net */
    public String siteKey;
    public Net net = new Net();

    protected Context context;
    protected String ext;
    protected SpiderApi spiderApi;

    /** 供 jar 内 Demo/JS 蜘蛛使用；普通蜘蛛会先判空 */
    public Activity getActivity() {
        return null;
    }

    /** 初始化（无 ext 参数版本） */
    public void init(Context context) {
        this.context = context;
    }

    /**
     * 初始化（带 ext 参数）。
     * ext 为 TVBox 订阅中 sites[].ext 的 JSON 字符串：
     * - 对象类型已序列化为 JSON 字符串
     * - 字符串类型若为相对路径（./开头）已下载后传内容
     * - 字符串类型若为 http 开头则原样传入
     */
    public void init(Context context, String ext) {
        this.ext = ext;
        // TVBox 原版基类就是这样写的：只重写 init(Context) 的蜘蛛也必须被初始化。
        init(context);
    }

    /** 初始化 SpiderApi（XBPQ 等高级爬虫使用，当前配置未用到，提供空实现） */
    public void initApi(SpiderApi spiderApi) {
        this.spiderApi = spiderApi;
    }

    /** 首页内容：返回 {"class":[{"type_id","type_name"}], "list":[{"vod_id","vod_name","vod_pic","vod_remarks"}]} */
    public String homeContent(boolean refresh) {
        return "{\"class\":[],\"list\":[]}";
    }

    /** 首页视频内容（部分爬虫使用，默认空） */
    public String homeVideoContent() {
        return "";
    }

    /**
     * 分类内容：返回 {"list":[{"vod_id","vod_name","vod_pic","vod_remarks",...}], "page":1, "pagecount":1}
     *
     * @param typeId   分类 ID
     * @param page     页码
     * @param filter   是否启用筛选
     * @param filterMap 筛选条件（area/class/lang/year 等）
     */
    public String categoryContent(String typeId, String page, boolean filter, HashMap<String, String> filterMap) {
        return "{\"list\":[]}";
    }

    /** 详情内容：返回 {"list":[{"vod_id","vod_name","vod_pic","vod_remarks","vod_content","vod_year","vod_actor","vod_director","vod_class","vod_play_from","vod_play_url"}]} */
    public String detailContent(List<String> ids) {
        return "{\"list\":[]}";
    }

    /** 搜索内容：返回 {"list":[{"vod_id","vod_name","vod_pic","vod_remarks"}]} */
    public String searchContent(String keyword, boolean quick) {
        return "{\"list\":[]}";
    }

    /** 搜索内容（带 siteKey 版本，部分爬虫实现） */
    public String searchContent(String keyword, boolean quick, String siteKey) {
        return "{\"list\":[]}";
    }

    /** 播放内容：返回 {"play_from":"...","play_url":"...","parse":0} */
    public String playerContent(String parseFlag, String id, List<String> flags) {
        return "";
    }

    /** 直播内容（部分 jar 蜘蛛实现） */
    public String liveContent(String url) {
        return "";
    }

    /** 自定义动作（部分 jar 蜘蛛实现） */
    public String action(String action) {
        return "";
    }

    /** 本地代理（部分 jar 蜘蛛实现） */
    public Object[] proxy(Map<String, String> params) {
        return null;
    }

    /** 判断 URL 是否为视频格式 */
    public boolean isVideoFormat(String url) {
        return false;
    }

    /** 是否手动检查视频格式 */
    public boolean manualVideoCheck() {
        return false;
    }

    /** 销毁爬虫，释放资源 */
    public void destroy() {
    }

    // ───────────── 静态工具方法（jar 内爬虫类直接以 Spider.xxx 调用） ─────────────

    /**
     * 返回安全的 DNS 解析器。
     * 原版 TVBox 基类提供防 DNS 污染的 DoH 解析器；部分爬虫（网盘类等）
     * 会调用 Spider.safeDns() 配置 OkHttp，缺失时抛 NoSuchMethodError。
     * 委托给兼容桩 com.github.catvod.net.OkHttp，保持与标准壳子一致。
     */
    public static okhttp3.Dns safeDns() {
        return OkHttp.safeDns();
    }

    /** jar 内爬虫常通过 Spider.client() 拿宿主 OkHttp 客户端 */
    public static OkHttpClient client() {
        return CLIENT;
    }

    private static final OkHttpClient CLIENT = OkHttp.newClient(true, 15, java.util.concurrent.TimeUnit.SECONDS);
}
