package com.pd.pds.server.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public final class GroupDtos {

    private GroupDtos() {
    }

    public record CreateGroup(@NotBlank String code, @NotBlank String name, String scenicId, String description) {
    }

    public record SetMembers(@NotNull List<String> userIds) {
    }

    public record GroupView(String id, String code, String name, String scenicId, String description, long memberCount) {
    }
}
