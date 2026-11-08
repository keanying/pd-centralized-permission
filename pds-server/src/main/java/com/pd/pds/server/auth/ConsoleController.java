package com.pd.pds.server.auth;

import com.pd.pds.server.config.PdsProperties;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 随 PDS 一起发布的管理后台（pds-web 构建产物位于 classpath:/static/console/）：
 * 运行时配置 /console/config.js 由 PDS 按 pds.console.* 生成；前端路由（history 模式）回退到 index.html。
 */
@Controller
public class ConsoleController {

    private final PdsProperties props;
    private final tools.jackson.databind.json.JsonMapper json;

    public ConsoleController(PdsProperties props, tools.jackson.databind.json.JsonMapper json) {
        this.props = props;
        this.json = json;
    }

    @GetMapping(value = "/console/config.js", produces = "application/javascript;charset=UTF-8")
    @ResponseBody
    public ResponseEntity<String> config() {
        PdsProperties.Console c = props.getConsole();
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("title", c.getTitle());
        cfg.put("brandName", c.getBrandName());
        cfg.put("issuer", props.getIssuer());
        cfg.put("apiBaseUrl", c.getApiBaseUrl());
        cfg.put("clientId", c.getClientId());
        cfg.put("basePath", c.getBasePath() + "/");
        String body = "window.__PDS_CONFIG__ = " + json.writeValueAsString(cfg) + ";\n";
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType("application/javascript;charset=UTF-8")).body(body);
    }

    /** 前端路由回退：/console、/console/xxx（非静态文件）→ index.html。 */
    @GetMapping({"/console", "/console/", "/console/{p1:[^.]*}", "/console/{p1:[^.]*}/{p2:[^.]*}", "/console/{p1:[^.]*}/{p2:[^.]*}/{p3:[^.]*}"})
    public String spa() {
        return "forward:/console/index.html";
    }
}
