package com.pd.pds.server.auth;

import com.pd.pds.common.BizException;
import com.pd.pds.server.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.WebAttributes;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 统一登录页、首次登录修改密码页、认证中心首页。
 */
@Controller
public class LoginController {

    private final UserService users;
    private final HttpSessionRequestCache requestCache = new HttpSessionRequestCache();

    public LoginController(UserService users) {
        this.users = users;
    }

    @GetMapping("/login")
    public String login(HttpServletRequest request, Model model) {
        HttpSession session = request.getSession(false);
        boolean error = request.getParameterMap().containsKey("error");
        if (error && session != null
                && session.getAttribute(WebAttributes.AUTHENTICATION_EXCEPTION) instanceof AuthenticationException e) {
            model.addAttribute("error", LoginHandlers.reason(e));
        } else if (error) {
            model.addAttribute("error", "账号或密码错误");
        }
        if (request.getParameterMap().containsKey("logout")) {
            model.addAttribute("message", "您已安全退出");
        }
        return "login";
    }

    @GetMapping("/password/change")
    public String changeForm(Authentication auth, Model model) {
        model.addAttribute("username", auth.getName());
        return "password-change";
    }

    @PostMapping("/password/change")
    public String change(Authentication auth, @RequestParam String oldPassword, @RequestParam String newPassword,
                         @RequestParam String confirmPassword, HttpServletRequest request, HttpServletResponse response,
                         Model model) {
        model.addAttribute("username", auth.getName());
        if (!newPassword.equals(confirmPassword)) {
            model.addAttribute("error", "两次输入的新密码不一致");
            return "password-change";
        }
        try {
            users.changeOwnPassword(auth.getName(), oldPassword, newPassword);
        } catch (BizException e) {
            model.addAttribute("error", e.getMessage());
            return "password-change";
        }
        request.getSession().removeAttribute(LoginHandlers.MUST_CHANGE_PASSWORD);
        SavedRequest saved = requestCache.getRequest(request, response);
        return "redirect:" + (saved != null ? saved.getRedirectUrl() : "/");
    }

    @GetMapping("/")
    public String home(Authentication auth, HttpServletRequest request, Model model) {
        if (Boolean.TRUE.equals(request.getSession().getAttribute(LoginHandlers.MUST_CHANGE_PASSWORD))) {
            return "redirect:/password/change";
        }
        model.addAttribute("username", auth.getName());
        return "home";
    }
}
