package com.wenjin.service;

import com.wenjin.dto.AdminUserImportResultVO;
import org.springframework.web.multipart.MultipartFile;

/**
 * 管理后台用户批量导入（xlsx）。全有或全无：任一行有错则一行不写。
 */
public interface AdminUserImportService {

    /** 生成导入模板（表头：用户名、姓名、角色、初始密码；附一行示例）。 */
    byte[] buildTemplate();

    /** 解析并校验 xlsx；全部合法才单事务写入。errors 非空时 created=0。 */
    AdminUserImportResultVO importUsers(MultipartFile file);
}
