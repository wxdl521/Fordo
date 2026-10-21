package com.wenjin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.dto.AdminUserImportResultVO;
import com.wenjin.entity.SysUser;
import com.wenjin.mapper.SysUserMapper;
import com.wenjin.security.UserRole;
import com.wenjin.service.AdminUserImportService;
import com.wenjin.service.AdminUserService;
import com.wenjin.support.AdminUserRules;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 管理后台用户批量导入。
 *
 * <p>流程：解析 → 逐行校验（含文件内重名、库内已存在）→ 有任何错误则一行不写、返回全部错误行；
 * 全部合法才批量编码密码并交给 {@link AdminUserService#insertAll}（单事务）。
 * 本类方法本身不开事务：Excel 解析与 BCrypt 编码（1000 行约数十秒 CPU）都留在事务之外，
 * 事务只覆盖最后的插入，避免长事务占住连接。</p>
 */
@Service
public class AdminUserImportServiceImpl implements AdminUserImportService {

    private static final Logger log = LoggerFactory.getLogger(AdminUserImportServiceImpl.class);

    static final long MAX_FILE_BYTES = 2L * 1024 * 1024;
    static final int MAX_DATA_ROWS = 1000;

    private static final String COL_USERNAME = "用户名";
    private static final String COL_REAL_NAME = "姓名";
    private static final String COL_ROLE = "角色";
    private static final String COL_PASSWORD = "初始密码";
    private static final String[] TEMPLATE_HEADER = {COL_USERNAME, COL_REAL_NAME, COL_ROLE, COL_PASSWORD};

    /** 表头别名（小写、trim 后匹配）→ 规范列名。 */
    private static final Map<String, String> HEADER_ALIASES = new HashMap<>();

    static {
        HEADER_ALIASES.put("用户名", COL_USERNAME);
        HEADER_ALIASES.put("username", COL_USERNAME);
        HEADER_ALIASES.put("姓名", COL_REAL_NAME);
        HEADER_ALIASES.put("realname", COL_REAL_NAME);
        HEADER_ALIASES.put("角色", COL_ROLE);
        HEADER_ALIASES.put("role", COL_ROLE);
        HEADER_ALIASES.put("初始密码", COL_PASSWORD);
        HEADER_ALIASES.put("密码", COL_PASSWORD);
        HEADER_ALIASES.put("password", COL_PASSWORD);
    }

    private final SysUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final AdminUserService adminUserService;

    public AdminUserImportServiceImpl(SysUserMapper userMapper,
                                      PasswordEncoder passwordEncoder,
                                      AdminUserService adminUserService) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.adminUserService = adminUserService;
    }

    // ───────────────────────── 模板 ─────────────────────────

    @Override
    public byte[] buildTemplate() {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("用户导入");

            // 用户名 / 初始密码列设为文本格式，避免 Excel 把 20240001 之类吃成数字、丢前导零
            CellStyle text = wb.createCellStyle();
            text.setDataFormat(wb.createDataFormat().getFormat("@"));
            sheet.setDefaultColumnStyle(0, text);
            sheet.setDefaultColumnStyle(3, text);

            Font bold = wb.createFont();
            bold.setBold(true);
            CellStyle headerStyle = wb.createCellStyle();
            headerStyle.setFont(bold);

            Row header = sheet.createRow(0);
            for (int c = 0; c < TEMPLATE_HEADER.length; c++) {
                Cell cell = header.createCell(c);
                cell.setCellValue(TEMPLATE_HEADER[c]);
                cell.setCellStyle(headerStyle);
            }

            Row example = sheet.createRow(1);
            String[] values = {"student001", "张三", "学生", "Wenjin@2026"};
            for (int c = 0; c < values.length; c++) {
                Cell cell = example.createCell(c);
                cell.setCellValue(values[c]);
                if (c == 0 || c == 3) {
                    cell.setCellStyle(text);
                }
            }

            // 角色列下拉：教师 / 学生（第 2 行起，覆盖上限行数）
            DataValidationHelper helper = sheet.getDataValidationHelper();
            DataValidationConstraint constraint = helper.createExplicitListConstraint(new String[]{"教师", "学生"});
            DataValidation validation = helper.createValidation(constraint,
                    new CellRangeAddressList(1, MAX_DATA_ROWS, 2, 2));
            validation.setShowErrorBox(true);
            sheet.addValidationData(validation);

            sheet.setColumnWidth(0, 20 * 256);
            sheet.setColumnWidth(1, 16 * 256);
            sheet.setColumnWidth(2, 10 * 256);
            sheet.setColumnWidth(3, 20 * 256);

            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "生成导入模板失败");
        }
    }

    // ───────────────────────── 导入 ─────────────────────────

    @Override
    public AdminUserImportResultVO importUsers(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "上传文件不能为空");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new BusinessException(ResultCode.PAYLOAD_TOO_LARGE, "文件大小不能超过 2MB");
        }
        String name = file.getOriginalFilename();
        if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "仅支持 .xlsx 文件，请使用导入模板");
        }

        List<ImportRow> rows = parse(file);
        if (rows.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "Excel 中没有数据行");
        }

        List<AdminUserImportResultVO.RowError> errors = validate(rows);
        AdminUserImportResultVO result = new AdminUserImportResultVO();
        if (!errors.isEmpty()) {
            result.setCreated(0);
            result.setErrors(errors);
            return result;
        }

        // BCrypt 编码在事务之外并行完成（编码器线程安全；1000 行串行约数十秒）
        List<SysUser> users = rows.parallelStream().map(this::toEntity).toList();
        adminUserService.insertAll(users);
        log.info("管理后台批量导入用户：{} 条", users.size());
        result.setCreated(users.size());
        return result;
    }

    private SysUser toEntity(ImportRow row) {
        SysUser user = new SysUser();
        user.setUsername(row.username());
        user.setPassword(passwordEncoder.encode(row.password()));
        user.setRealName(row.realName());
        user.setRole(row.role().code());
        user.setStatus(1);
        return user;
    }

    // ───────────────────────── 校验 ─────────────────────────

    private List<AdminUserImportResultVO.RowError> validate(List<ImportRow> rows) {
        Map<Integer, List<String>> problems = new HashMap<>();
        Map<String, Integer> firstRowByName = new HashMap<>();

        for (ImportRow r : rows) {
            List<String> msgs = new ArrayList<>();
            String err = AdminUserRules.checkUsername(r.username());
            if (err != null) {
                msgs.add(err);
            }
            err = AdminUserRules.checkPassword(r.password());
            if (err != null) {
                msgs.add(err);
            }
            err = AdminUserRules.checkRealName(r.realName());
            if (err != null) {
                msgs.add(err);
            }
            if (r.roleText() == null || r.roleText().isEmpty()) {
                msgs.add("角色不能为空");
            } else if (r.role() == null) {
                msgs.add("角色须为 教师/学生 或 1/2");
            }
            if (!r.username().isEmpty()) {
                // 数据库唯一键按不区分大小写的排序规则比较，这里同口径
                Integer first = firstRowByName.putIfAbsent(r.username().toLowerCase(Locale.ROOT), r.rowNumber());
                if (first != null) {
                    msgs.add("用户名与第 " + first + " 行重复");
                }
            }
            if (!msgs.isEmpty()) {
                problems.put(r.rowNumber(), msgs);
            }
        }

        Set<String> existing = findExistingUsernames(rows);
        for (ImportRow r : rows) {
            if (!r.username().isEmpty() && existing.contains(r.username().toLowerCase(Locale.ROOT))) {
                problems.computeIfAbsent(r.rowNumber(), k -> new ArrayList<>()).add("用户名已存在");
            }
        }

        List<AdminUserImportResultVO.RowError> errors = new ArrayList<>();
        for (ImportRow r : rows) {
            List<String> msgs = problems.get(r.rowNumber());
            if (msgs != null) {
                errors.add(new AdminUserImportResultVO.RowError(r.rowNumber(), r.username(), String.join("；", msgs)));
            }
        }
        return errors;
    }

    /** 一次 IN 查询取出库中已占用的用户名（小写）。 */
    private Set<String> findExistingUsernames(List<ImportRow> rows) {
        Set<String> names = new HashSet<>();
        for (ImportRow r : rows) {
            if (!r.username().isEmpty()) {
                names.add(r.username());
            }
        }
        Set<String> existing = new HashSet<>();
        if (names.isEmpty()) {
            return existing;
        }
        List<SysUser> found = userMapper.selectList(
                new QueryWrapper<SysUser>().select("username").in("username", names));
        if (found != null) {
            for (SysUser u : found) {
                if (u.getUsername() != null) {
                    existing.add(u.getUsername().toLowerCase(Locale.ROOT));
                }
            }
        }
        return existing;
    }

    // ───────────────────────── 解析 ─────────────────────────

    private List<ImportRow> parse(MultipartFile file) {
        try (InputStream in = file.getInputStream(); Workbook wb = new XSSFWorkbook(in)) {
            Sheet sheet = wb.getNumberOfSheets() > 0 ? wb.getSheetAt(0) : null;
            if (sheet == null) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "Excel 缺少工作表");
            }
            DataFormatter formatter = new DataFormatter();
            Map<String, Integer> columns = parseHeader(sheet, formatter);

            List<ImportRow> rows = new ArrayList<>();
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) {
                    continue;
                }
                String username = cell(row, columns.get(COL_USERNAME), formatter).trim();
                String realName = cell(row, columns.get(COL_REAL_NAME), formatter).trim();
                String roleText = cell(row, columns.get(COL_ROLE), formatter).trim();
                String password = cell(row, columns.get(COL_PASSWORD), formatter);
                if (username.isEmpty() && realName.isEmpty() && roleText.isEmpty() && password.trim().isEmpty()) {
                    continue; // 空行
                }
                if (rows.size() >= MAX_DATA_ROWS) {
                    throw new BusinessException(ResultCode.BAD_REQUEST,
                            "导入行数超过上限 " + MAX_DATA_ROWS + " 行，请分批导入");
                }
                rows.add(new ImportRow(i + 1, username, realName.isEmpty() ? null : realName,
                        roleText, parseRole(roleText), password));
            }
            return rows;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("用户导入 Excel 解析失败: {}", e.toString());
            throw new BusinessException(ResultCode.BAD_REQUEST, "Excel 解析失败：文件无法读取，请使用导入模板的 .xlsx 文件");
        }
    }

    /** 表头必须在第 1 行；用户名/角色/初始密码为必需列，姓名列可缺省。 */
    private Map<String, Integer> parseHeader(Sheet sheet, DataFormatter formatter) {
        Row header = sheet.getRow(0);
        if (header == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "Excel 第 1 行（表头）为空");
        }
        Map<String, Integer> columns = new HashMap<>();
        for (int c = 0; c < header.getLastCellNum(); c++) {
            Cell cell = header.getCell(c);
            if (cell == null) {
                continue;
            }
            String canonical = HEADER_ALIASES.get(formatter.formatCellValue(cell).trim().toLowerCase(Locale.ROOT));
            if (canonical != null) {
                columns.putIfAbsent(canonical, c);
            }
        }
        for (String required : new String[]{COL_USERNAME, COL_ROLE, COL_PASSWORD}) {
            if (!columns.containsKey(required)) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "Excel 表头缺少必需列：「" + required + "」");
            }
        }
        return columns;
    }

    private static String cell(Row row, Integer column, DataFormatter formatter) {
        if (column == null) {
            return "";
        }
        Cell cell = row.getCell(column);
        return cell == null ? "" : formatter.formatCellValue(cell);
    }

    /** 教师/学生/1/2 → 角色；其余（含管理员）返回 null。 */
    private static UserRole parseRole(String text) {
        return switch (text) {
            case "教师", "1" -> UserRole.TEACHER;
            case "学生", "2" -> UserRole.STUDENT;
            default -> null;
        };
    }

    /** 解析后的一行（rowNumber 为 Excel 行号，表头为第 1 行）。 */
    private record ImportRow(int rowNumber, String username, String realName,
                             String roleText, UserRole role, String password) {
    }
}
