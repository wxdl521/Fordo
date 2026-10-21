package com.wenjin.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.dto.AdminUserImportResultVO;
import com.wenjin.entity.SysUser;
import com.wenjin.mapper.SysUserMapper;
import com.wenjin.security.UserRole;
import com.wenjin.service.impl.AdminUserImportServiceImpl;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AdminUserImportServiceImpl：xlsx 解析 + 全有或全无的校验/写入 + 模板。
 */
@ExtendWith(MockitoExtension.class)
class AdminUserImportServiceImplTest {

    private static final String[] HEADER = {"用户名", "姓名", "角色", "初始密码"};

    @Mock
    private SysUserMapper userMapper;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private AdminUserService adminUserService;

    private AdminUserImportServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AdminUserImportServiceImpl(userMapper, passwordEncoder, adminUserService);
    }

    // ───────────────────────── 成功路径 ─────────────────────────

    @Test
    @DisplayName("导入全部合法：编码密码、单次 insertAll、created=行数；角色接受 教师/学生/1/2（文本或数字）")
    void import_allValid() throws Exception {
        when(userMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());
        when(passwordEncoder.encode(anyString())).thenReturn("HASH");

        byte[] xlsx = workbook(HEADER,
                row("teacher01", "李老师", "教师", "Passw0rd1"),
                row("student01", "张三", "学生", "Passw0rd2"),
                row("teacher02", "", "1", "Passw0rd3"),
                new Object[]{"student02", "赵六", 2, "Passw0rd4"});   // 数字角色单元格

        AdminUserImportResultVO result = service.importUsers(file("users.xlsx", xlsx));

        assertThat(result.getCreated()).isEqualTo(4);
        assertThat(result.getErrors()).isEmpty();
        ArgumentCaptor<List<SysUser>> captor = ArgumentCaptor.forClass(List.class);
        verify(adminUserService).insertAll(captor.capture());
        List<SysUser> users = captor.getValue();
        assertThat(users).extracting(SysUser::getUsername)
                .containsExactly("teacher01", "student01", "teacher02", "student02");
        assertThat(users).extracting(SysUser::getRole)
                .containsExactly(UserRole.TEACHER.code(), UserRole.STUDENT.code(),
                        UserRole.TEACHER.code(), UserRole.STUDENT.code());
        assertThat(users).extracting(SysUser::getPassword).containsOnly("HASH");
        assertThat(users).extracting(SysUser::getStatus).containsOnly(1);
        assertThat(users.get(0).getRealName()).isEqualTo("李老师");
        assertThat(users.get(2).getRealName()).isNull();
    }

    @Test
    @DisplayName("数字单元格的用户名/密码按整数文本读取；空行被跳过")
    void import_numericCellsAndBlankRows() throws Exception {
        when(userMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());
        when(passwordEncoder.encode(anyString())).thenReturn("HASH");

        byte[] xlsx = workbook(HEADER,
                new Object[]{20240001, "学生甲", "学生", 12345678},
                new Object[]{null, null, null, null},
                new Object[]{20240002, "学生乙", "学生", 87654321});

        AdminUserImportResultVO result = service.importUsers(file("users.xlsx", xlsx));

        assertThat(result.getCreated()).isEqualTo(2);
        ArgumentCaptor<List<SysUser>> captor = ArgumentCaptor.forClass(List.class);
        verify(adminUserService).insertAll(captor.capture());
        assertThat(captor.getValue()).extracting(SysUser::getUsername).containsExactly("20240001", "20240002");
        verify(passwordEncoder).encode("12345678");
        verify(passwordEncoder).encode("87654321");
    }

    @Test
    @DisplayName("模板：表头 + 一行示例；模板本身可被原样导入")
    void template_roundTripsThroughImport() throws Exception {
        byte[] template = service.buildTemplate();

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(template))) {
            Sheet sheet = wb.getSheetAt(0);
            Row header = sheet.getRow(0);
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("用户名");
            assertThat(header.getCell(1).getStringCellValue()).isEqualTo("姓名");
            assertThat(header.getCell(2).getStringCellValue()).isEqualTo("角色");
            assertThat(header.getCell(3).getStringCellValue()).isEqualTo("初始密码");
            assertThat(sheet.getRow(1)).as("示例行").isNotNull();
        }

        when(userMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());
        when(passwordEncoder.encode(anyString())).thenReturn("HASH");
        AdminUserImportResultVO result = service.importUsers(file("template.xlsx", template));
        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getCreated()).isEqualTo(1);
    }

    // ───────────────────────── 全有或全无 ─────────────────────────

    @Test
    @DisplayName("含错误行：返回全部错误行（Excel 行号）、created=0、不编码不写库")
    void import_withErrors_writesNothing() throws Exception {
        when(userMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(List.of(existing("taken01")));

        byte[] xlsx = workbook(HEADER,
                row("goodone1", "好人", "学生", "Passw0rd1"),            // 行2 合法
                row("ab", "太短", "学生", "Passw0rd2"),                  // 行3 用户名过短
                row("goodone2", "x", "管理员", "Passw0rd3"),             // 行4 角色非法
                row("goodone3", "x", "学生", "short"),                   // 行5 密码过短
                row("GoodOne1", "重名", "教师", "Passw0rd5"),            // 行6 与行2 重复（忽略大小写）
                row("taken01", "已存在", "学生", "Passw0rd6"),           // 行7 库内已存在
                row("", "无名", "学生", "Passw0rd7"));                   // 行8 用户名为空

        AdminUserImportResultVO result = service.importUsers(file("users.xlsx", xlsx));

        assertThat(result.getCreated()).isZero();
        assertThat(result.getErrors()).extracting(AdminUserImportResultVO.RowError::getRow)
                .containsExactly(3, 4, 5, 6, 7, 8);
        assertThat(result.getErrors().get(0).getUsername()).isEqualTo("ab");
        assertThat(result.getErrors().get(0).getMessage()).contains("用户名长度");
        assertThat(result.getErrors().get(1).getMessage()).contains("角色");
        assertThat(result.getErrors().get(2).getMessage()).contains("密码长度");
        assertThat(result.getErrors().get(3).getMessage()).contains("第 2 行").contains("重复");
        assertThat(result.getErrors().get(4).getMessage()).contains("已存在");
        assertThat(result.getErrors().get(5).getMessage()).contains("用户名不能为空");
        verify(adminUserService, never()).insertAll(anyList());
        verify(passwordEncoder, never()).encode(anyString());
    }

    @Test
    @DisplayName("同一行多个问题合并到一条错误里；空行不计错但占用行号")
    void import_multipleProblemsInOneRow_andBlankRowKeepsNumbering() throws Exception {
        byte[] xlsx = workbook(HEADER,
                new Object[]{null, null, null, null},                        // 行2 空行
                row("ab", "x", "神仙", "1"));                                // 行3 三个问题

        AdminUserImportResultVO result = service.importUsers(file("users.xlsx", xlsx));

        assertThat(result.getCreated()).isZero();
        assertThat(result.getErrors()).hasSize(1);
        AdminUserImportResultVO.RowError err = result.getErrors().get(0);
        assertThat(err.getRow()).isEqualTo(3);
        assertThat(err.getMessage()).contains("用户名长度").contains("角色").contains("密码长度");
        verify(adminUserService, never()).insertAll(anyList());
    }

    @Test
    @DisplayName("并发冲突：insertAll 抛 USER_EXISTS 原样向上传播，不吞")
    void import_insertConflict_propagates() throws Exception {
        when(userMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());
        when(passwordEncoder.encode(anyString())).thenReturn("HASH");
        org.mockito.Mockito.doThrow(new BusinessException(ResultCode.USER_EXISTS, "用户名已存在"))
                .when(adminUserService).insertAll(anyList());

        byte[] xlsx = workbook(HEADER, row("racer001", "x", "学生", "Passw0rd1"));

        assertThatThrownBy(() -> service.importUsers(file("users.xlsx", xlsx)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(ResultCode.USER_EXISTS.getCode()));
    }

    // ───────────────────────── 文件级拒绝 ─────────────────────────

    @Test
    @DisplayName("超过 1000 个数据行 → 400；恰好 1000 行放行")
    void import_rowLimit() throws Exception {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 1001; i++) {
            rows.add(row(String.format("stu%05d", i), "学生", "学生", "Passw0rd1"));
        }
        byte[] tooMany = workbook(HEADER, rows.toArray(new Object[0][]));
        assertBadRequest(() -> service.importUsers(file("users.xlsx", tooMany)), "1000");
        verify(adminUserService, never()).insertAll(anyList());

        rows.remove(rows.size() - 1);
        byte[] exactly = workbook(HEADER, rows.toArray(new Object[0][]));
        when(userMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());
        when(passwordEncoder.encode(anyString())).thenReturn("HASH");

        AdminUserImportResultVO result = service.importUsers(file("users.xlsx", exactly));

        assertThat(result.getCreated()).isEqualTo(1000);
        verify(adminUserService).insertAll(anyList());
    }

    @Test
    @DisplayName("文件为空 / 缺失 / 扩展名不是 .xlsx / 内容不是 xlsx → 400")
    void import_badFiles() {
        assertBadRequest(() -> service.importUsers(null), "文件");
        assertBadRequest(() -> service.importUsers(file("users.xlsx", new byte[0])), "文件");
        assertBadRequest(() -> service.importUsers(file("users.csv", new byte[]{1, 2, 3})), ".xlsx");
        assertBadRequest(() -> service.importUsers(file("users.xlsx", new byte[]{1, 2, 3})), "解析");
        verify(adminUserService, never()).insertAll(anyList());
    }

    @Test
    @DisplayName("文件超过 2MB → 413")
    void import_fileTooLarge() {
        byte[] big = new byte[2 * 1024 * 1024 + 1];

        assertThatThrownBy(() -> service.importUsers(file("users.xlsx", big)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(ResultCode.PAYLOAD_TOO_LARGE.getCode()));
    }

    @Test
    @DisplayName("缺少必需表头列 / 无数据行 → 400")
    void import_headerAndEmpty() throws Exception {
        byte[] noRoleColumn = workbook(new String[]{"用户名", "姓名", "初始密码"},
                new Object[]{"someone1", "x", "Passw0rd1"});
        assertBadRequest(() -> service.importUsers(file("users.xlsx", noRoleColumn)), "角色");

        byte[] headerOnly = workbook(HEADER);
        assertBadRequest(() -> service.importUsers(file("users.xlsx", headerOnly)), "数据行");
        verify(adminUserService, never()).insertAll(anyList());
    }

    // ───────────────────────── helpers ─────────────────────────

    private static void assertBadRequest(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String messagePart) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    assertThat(((BusinessException) ex).getCode()).isEqualTo(ResultCode.BAD_REQUEST.getCode());
                    assertThat(ex.getMessage()).contains(messagePart);
                });
    }

    private static MockMultipartFile file(String name, byte[] content) {
        return new MockMultipartFile("file", name,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", content);
    }

    private static Object[] row(String username, String realName, String role, String password) {
        return new Object[]{username, realName, role, password};
    }

    private static SysUser existing(String username) {
        SysUser u = new SysUser();
        u.setUsername(username);
        return u;
    }

    /** 生成 xlsx：第 1 行表头，之后按顺序每个 Object[] 一行；null 元素不建单元格，Number 写数字单元格。 */
    private static byte[] workbook(String[] header, Object[]... rows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("users");
            Row h = sheet.createRow(0);
            for (int c = 0; c < header.length; c++) {
                h.createCell(c).setCellValue(header[c]);
            }
            for (int r = 0; r < rows.length; r++) {
                Row row = sheet.createRow(r + 1);
                for (int c = 0; c < rows[r].length; c++) {
                    Object v = rows[r][c];
                    if (v == null) {
                        continue;
                    }
                    if (v instanceof Number n) {
                        row.createCell(c).setCellValue(n.doubleValue());
                    } else {
                        row.createCell(c).setCellValue(v.toString());
                    }
                }
            }
            wb.write(out);
            return out.toByteArray();
        }
    }
}
