package com.pd.pds.authz.model;

/** 数据范围类型。 */
public enum DataScopeType {
    /** 仅本人创建的数据。 */
    SELF,
    /** 本人所在部门。 */
    DEPT,
    /** 本人所在部门及下级。 */
    DEPT_TREE,
    /** 本人所属景区。 */
    SCENIC,
    /** 指定景区列表。 */
    SCENIC_LIST,
    /** 全集团。 */
    ALL,
    /** 自定义组织节点集合。 */
    CUSTOM,
    /** 指定数据域/业务域节点（PDK 治理台复核分派）。 */
    DOMAIN
}
