package com.plateno.booking.internal.base.pojo;

import java.util.Date;

/**
 * 物流类型
 * @author mogt
 * @date 2016年11月29日
 */
public class MLogisticsType {
	
	/**
	 * 类型
	 */
    private Integer type;

    /**
     *  描述
     */
    private String description;

    /**
     * 创建时间
     */
    private Date createTime;

    public Integer getType() {
        return type;
    }

    public void setType(Integer type) {
        this.type = type;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description == null ? null : description.trim();
    }

    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }
}