package com.pd.pds.server.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.util.ArrayList;
import java.util.List;

public final class OrgDtos {

    private OrgDtos() {
    }

    public record CreateOrg(@NotBlank String parentId,
                            @NotBlank @Pattern(regexp = "COMPANY|SCENIC|DEPT|TEAM", message = "类型只能是 COMPANY/SCENIC/DEPT/TEAM") String type,
                            @NotBlank String code, @NotBlank String name, Integer sort, String leaderId) {
    }

    public record UpdateOrg(String name, Integer sort, String leaderId, Integer status, String parentId) {
    }

    public static final class OrgNode {
        public String id;
        public String parentId;
        public String type;
        public String code;
        public String name;
        public String scenicId;
        public Integer sort;
        public Integer status;
        public long userCount;
        public List<OrgNode> children = new ArrayList<>();
    }

    public record CreatePosition(@NotBlank String code, @NotBlank String name, String scenicId) {
    }
}
