package com.pd.pds.authz.model;

import java.time.Instant;

/** 授权有效期，from/to 为空表示不限。 */
public record Validity(Instant from, Instant to) {

    public static final Validity ALWAYS = new Validity(null, null);

    public boolean isActive(Instant now) {
        return (from == null || !now.isBefore(from)) && (to == null || now.isBefore(to));
    }
}
