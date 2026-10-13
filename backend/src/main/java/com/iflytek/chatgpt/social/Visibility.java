package com.iflytek.chatgpt.social;

/**
 * 可见权限：public（公开）/ friends（仅好友）/ private（仅自己）。
 * <p>
 * 单独抽成静态工具，是因为好几个 Controller 的保存方法都要归一这个值，
 * 不值得为了它给每个 Controller 都注入一次 SocialService。
 */
public final class Visibility {

    public static final String PUBLIC = "public";
    public static final String FRIENDS = "friends";
    public static final String PRIVATE = "private";

    private Visibility() {
    }

    /** 归一：非法值当公开，避免脏数据把内容意外藏起来或意外露出去 */
    public static String normalize(String v) {
        String s = v == null ? "" : v.trim().toLowerCase();
        if (FRIENDS.equals(s) || PRIVATE.equals(s)) {
            return s;
        }
        return PUBLIC;
    }
}
