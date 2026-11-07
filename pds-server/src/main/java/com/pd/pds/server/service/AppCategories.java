package com.pd.pds.server.service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 应用分类，对应客户总体架构分层。PDS 面向全部系统通用，以下为各层的已知系统（编码小写即应用编码）。
 */
public final class AppCategories {

    private AppCategories() {
    }

    public static final Map<String, String> ALL = new LinkedHashMap<>();

    static {
        ALL.put("APPLICATION", "应用层（票付通、景数通、数字大脑、畅游、各类 APP）");
        ALL.put("QUERY", "查数层（PDQ 即席自助、PBI 数易 BI、PAP 智能体平台/PDA）");
        ALL.put("SECURITY", "统一权限（PDS）");
        ALL.put("DATA_OUTLET", "数据出口（PDT 标签中心、PDK 知识服务、PDD 数据服务）");
        ALL.put("OFFLINE_GOVERNANCE", "离线治理（PDG）");
        ALL.put("REALTIME_GOVERNANCE", "实时治理（PDE）");
        ALL.put("DATA_CLEANSING", "数据清洗（PDP 数据清洗、PDL 智能标注、PDR 审核质检）");
        ALL.put("DATA_INTEGRATION", "数据集成（PDC）");
        ALL.put("FOUNDATION", "公共底座（PBS）");
        ALL.put("FEEDBACK", "回流平台");
    }
}
