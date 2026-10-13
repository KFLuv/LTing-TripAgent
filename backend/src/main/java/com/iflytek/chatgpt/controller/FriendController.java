package com.iflytek.chatgpt.controller;

import com.iflytek.chatgpt.social.SocialService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpSession;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 好友：列表 / 添加 / 删除 / 查自己的 ID 与邀请码。
 * 添加支持两种入口——用户名（ID）或对方分享的邀请码。
 */
@RestController
public class FriendController {

    private final SocialService social;

    public FriendController(SocialService social) {
        this.social = social;
    }

    @PostMapping(value = "/friend/list", produces = "application/json;charset=UTF-8")
    public Map<String, Object> list(HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        String me = ChatController.currentUser(session);
        if (me == null) {
            return fail(result, "请先登录");
        }
        result.put("status", "success");
        result.put("me", me);
        result.put("inviteCode", social.ensureInviteCode(me));
        result.put("friends", social.listFriends(me));
        return result;
    }

    @PostMapping(value = "/friend/add", produces = "application/json;charset=UTF-8")
    public Map<String, Object> add(@RequestParam(required = false) String target, HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        String me = ChatController.currentUser(session);
        if (me == null) {
            return fail(result, "请先登录");
        }
        String message = social.addFriend(me, target);
        boolean ok = message.startsWith("已添加好友");
        result.put("status", ok ? "success" : "error");
        result.put("message", message);
        if (ok) {
            result.put("friends", social.listFriends(me));
        }
        return result;
    }

    @PostMapping(value = "/friend/delete", produces = "application/json;charset=UTF-8")
    public Map<String, Object> delete(@RequestParam(required = false) String friend, HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        String me = ChatController.currentUser(session);
        if (me == null) {
            return fail(result, "请先登录");
        }
        if (friend == null || friend.trim().isEmpty()) {
            return fail(result, "缺少好友标识");
        }
        social.removeFriend(me, friend.trim());
        result.put("status", "success");
        result.put("message", "已删除好友");
        return result;
    }

    private Map<String, Object> fail(Map<String, Object> result, String message) {
        result.put("status", "error");
        result.put("message", message);
        return result;
    }
}
