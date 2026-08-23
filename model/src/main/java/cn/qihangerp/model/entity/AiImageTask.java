package cn.qihangerp.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * AI生图任务表
 * @TableName ai_image_task
 */
@TableName(value = "ai_image_task")
@Data
public class AiImageTask implements Serializable {

    public static final Integer STATUS_PENDING = 0;
    public static final Integer STATUS_RUNNING = 1;
    public static final Integer STATUS_SUCCESS = 2;
    public static final Integer STATUS_FAILED = 3;

    /** 参考图类型: 纯文生图 */
    public static final String REF_TYPE_TEXT = "TEXT";
    /** 参考图类型: 网络图片URL */
    public static final String REF_TYPE_URL = "URL";
    /** 参考图类型: 本地上传文件 */
    public static final String REF_TYPE_FILE = "FILE";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 提示词 */
    private String prompt;

    /** 尺寸 宽x高 */
    private String size;

    /** 模型档位: auto/standard/2k/4k */
    private String model;

    /** 参考图类型: TEXT/URL/FILE */
    private String refType;

    /** 参考图数量 */
    private Integer refCount;

    /** 状态: 0待处理 1生成中 2成功 3失败 */
    private Integer status;

    /** 结果图地址 */
    private String resultUrl;

    /** 失败原因 */
    private String errorMsg;

    /** 耗时(秒) */
    private Integer costSeconds;

    /** 创建人 */
    private String createBy;

    /** 创建时间 */
    private Date createTime;

    private static final long serialVersionUID = 1L;
}
