package org.apache.iotdb.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.github.jeffreyning.mybatisplus.anno.MppMultiId;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import java.io.Serializable;
import java.util.Date;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * <p>
 * 
 * </p>
 *
 * @author IoTDB
 * @since 2025-06-24
 */
@Getter
@Setter
@ToString
@ApiModel(value = "Table1对象", description = "")
public class Table1 implements Serializable {

    private static final long serialVersionUID = 1L;

    @MppMultiId
    @TableField("time")
    private Date time;

    @MppMultiId
    @TableField("region")
    private String region;

    @MppMultiId
    @TableField("plant_id")
    private String plantId;

    @MppMultiId
    @TableField("device_id")
    private String deviceId;

    private String modelId;

    private String maintenance;

    private Float temperature;

    private Float humidity;

    private Boolean status;

    private Date arrivalTime;
}
