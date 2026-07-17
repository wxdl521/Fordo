package com.wenjin.service.impl;

import com.wenjin.ai.QuestionBankCleanAiClient;
import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.dto.ImportBankResult;
import com.wenjin.dto.QuestionBankFile;
import com.wenjin.security.CourseAccessService;
import com.wenjin.service.QuestionBankImportService;
import com.wenjin.service.QuestionWriteService;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 题库导入编排：Excel 解析与 AI 清洗均在事务外；短写委托 {@link QuestionWriteService}。
 */
@Service
public class QuestionBankImportServiceImpl implements QuestionBankImportService {

    private static final Logger log = LoggerFactory.getLogger(QuestionBankImportServiceImpl.class);

    private static final Map<String, String> HEADER_ALIASES = new HashMap<>();

    static {
        HEADER_ALIASES.put("题干", "stem");
        HEADER_ALIASES.put("stem", "stem");
        HEADER_ALIASES.put("知识点编码", "nodecode");
        HEADER_ALIASES.put("nodecode", "nodecode");
        HEADER_ALIASES.put("难度", "difficulty");
        HEADER_ALIASES.put("difficulty", "difficulty");
        HEADER_ALIASES.put("解析", "analysis");
        HEADER_ALIASES.put("analysis", "analysis");
        HEADER_ALIASES.put("选项a", "optiona");
        HEADER_ALIASES.put("optiona", "optiona");
        HEADER_ALIASES.put("选项b", "optionb");
        HEADER_ALIASES.put("optionb", "optionb");
        HEADER_ALIASES.put("选项c", "optionc");
        HEADER_ALIASES.put("optionc", "optionc");
        HEADER_ALIASES.put("选项d", "optiond");
        HEADER_ALIASES.put("optiond", "optiond");
        HEADER_ALIASES.put("正确答案", "answer");
        HEADER_ALIASES.put("answer", "answer");
        HEADER_ALIASES.put("选项a考点", "optionapoint");
        HEADER_ALIASES.put("optionapoint", "optionapoint");
        HEADER_ALIASES.put("选项b考点", "optionbpoint");
        HEADER_ALIASES.put("optionbpoint", "optionbpoint");
        HEADER_ALIASES.put("选项c考点", "optioncpoint");
        HEADER_ALIASES.put("optioncpoint", "optioncpoint");
        HEADER_ALIASES.put("选项d考点", "optiondpoint");
        HEADER_ALIASES.put("optiondpoint", "optiondpoint");
    }

    private final QuestionBankCleanAiClient aiClient;
    private final CourseAccessService courseAccessService;
    private final QuestionWriteService questionWriteService;

    public QuestionBankImportServiceImpl(QuestionBankCleanAiClient aiClient,
                                         CourseAccessService courseAccessService,
                                         QuestionWriteService questionWriteService) {
        this.aiClient = aiClient;
        this.courseAccessService = courseAccessService;
        this.questionWriteService = questionWriteService;
    }

    @Override
    public ImportBankResult importFromJson(Long courseId, QuestionBankFile bank) {
        if (courseId == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "courseId 不能为空");
        }
        courseAccessService.assertManageCourse(courseId);
        if (bank == null || bank.getQuestions() == null || bank.getQuestions().isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "题库数据为空");
        }
        return questionWriteService.persistBank(courseId, bank, false);
    }

    @Override
    public ImportBankResult importFromExcel(Long courseId, MultipartFile file) {
        if (courseId == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "courseId 不能为空");
        }
        courseAccessService.assertManageCourse(courseId);
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "上传文件不能为空");
        }

        // 1) 解析 Excel（事务外）
        List<QuestionBankFile.BankQuestion> raw;
        try {
            raw = parseExcel(file);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "Excel 解析失败：" + e.getMessage());
        }

        if (raw.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "Excel 解析结果为空：无数据行");
        }

        // 2) AI 清洗（事务外；失败回退规则清洗）
        QuestionBankFile cleaned;
        boolean aiCleaned = false;
        try {
            cleaned = aiClient.clean(raw);
            log.info("AI 清洗完成：题目数={}", cleaned.getQuestions() == null ? 0 : cleaned.getQuestions().size());
            aiCleaned = true;
        } catch (Exception e) {
            log.warn("AI 清洗失败，回退到规则清洗：{}", e.getMessage());
            cleaned = ruleBasedClean(raw);
        }

        // 3) 短事务落库
        return questionWriteService.persistBank(courseId, cleaned, aiCleaned);
    }

    // ============================ Excel 解析 ============================

    List<QuestionBankFile.BankQuestion> parseExcel(MultipartFile file) throws IOException {
        try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = workbook.getNumberOfSheets() > 0 ? workbook.getSheetAt(0) : null;
            if (sheet == null) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "Excel 缺少 Sheet");
            }
            return parseSheet(sheet);
        }
    }

    private List<QuestionBankFile.BankQuestion> parseSheet(Sheet sheet) {
        List<QuestionBankFile.BankQuestion> questions = new ArrayList<>();
        Map<String, Integer> headerMap = parseHeader(sheet);

        for (int i = sheet.getFirstRowNum() + 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null || isEmptyRow(row)) {
                continue;
            }

            QuestionBankFile.BankQuestion q = new QuestionBankFile.BankQuestion();
            q.setStem(getCellString(row, headerMap.get("stem")));
            q.setNodeCode(getCellString(row, headerMap.get("nodecode")));

            Integer diff = getCellInteger(row, headerMap.get("difficulty"));
            q.setDifficulty(diff);

            q.setAnalysis(getCellString(row, headerMap.get("analysis")));

            String answer = getCellString(row, headerMap.get("answer"));
            String answerUpper = answer == null ? null : answer.trim().toUpperCase();

            List<QuestionBankFile.BankOption> options = new ArrayList<>();
            addOption(options, "A", getCellString(row, headerMap.get("optiona")), answerUpper,
                    getCellString(row, headerMap.get("optionapoint")));
            addOption(options, "B", getCellString(row, headerMap.get("optionb")), answerUpper,
                    getCellString(row, headerMap.get("optionbpoint")));
            if (headerMap.containsKey("optionc")) {
                addOption(options, "C", getCellString(row, headerMap.get("optionc")), answerUpper,
                        getCellString(row, headerMap.get("optioncpoint")));
            }
            if (headerMap.containsKey("optiond")) {
                addOption(options, "D", getCellString(row, headerMap.get("optiond")), answerUpper,
                        getCellString(row, headerMap.get("optiondpoint")));
            }
            q.setOptions(options);

            questions.add(q);
        }
        return questions;
    }

    private void addOption(List<QuestionBankFile.BankOption> options, String key, String text,
                           String answer, String pointNodeCode) {
        if (!StringUtils.hasText(text)) {
            return;
        }
        QuestionBankFile.BankOption opt = new QuestionBankFile.BankOption();
        opt.setKey(key);
        opt.setText(text.trim());
        opt.setCorrect(key.equals(answer));
        opt.setPointNodeCode(StringUtils.hasText(pointNodeCode) ? pointNodeCode.trim() : null);
        options.add(opt);
    }

    private Map<String, Integer> parseHeader(Sheet sheet) {
        Row headerRow = sheet.getRow(sheet.getFirstRowNum());
        if (headerRow == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "Excel 第一行（表头）为空");
        }

        Map<String, Integer> headerMap = new HashMap<>();
        for (int c = 0; c < headerRow.getLastCellNum(); c++) {
            Cell cell = headerRow.getCell(c);
            if (cell != null) {
                String raw = getCellValue(cell).trim().toLowerCase();
                if (StringUtils.hasText(raw)) {
                    String key = HEADER_ALIASES.get(raw);
                    if (key != null) {
                        headerMap.put(key, c);
                    }
                }
            }
        }

        String[] required = {"stem", "nodecode", "difficulty", "optiona", "optionb", "answer"};
        for (String r : required) {
            if (!headerMap.containsKey(r)) {
                throw new BusinessException(ResultCode.BAD_REQUEST,
                        "Excel 表头缺少必需列：「" + r + "」");
            }
        }
        return headerMap;
    }

    // ============================ 规则清洗 ============================

    QuestionBankFile ruleBasedClean(List<QuestionBankFile.BankQuestion> raw) {
        QuestionBankFile result = new QuestionBankFile();
        List<QuestionBankFile.BankQuestion> questions = new ArrayList<>();

        for (QuestionBankFile.BankQuestion q : raw) {
            QuestionBankFile.BankQuestion cleaned = new QuestionBankFile.BankQuestion();
            cleaned.setStem(q.getStem() == null ? null : q.getStem().trim());
            cleaned.setNodeCode(q.getNodeCode());
            cleaned.setChapter(q.getChapter());
            cleaned.setDifficulty(normalizeDifficulty(q.getDifficulty()));
            String analysis = q.getAnalysis();
            cleaned.setAnalysis(StringUtils.hasText(analysis) ? analysis.trim() : "暂无解析");

            List<QuestionBankFile.BankOption> options = new ArrayList<>();
            if (q.getOptions() != null) {
                for (QuestionBankFile.BankOption o : q.getOptions()) {
                    QuestionBankFile.BankOption opt = new QuestionBankFile.BankOption();
                    opt.setKey(o.getKey() == null ? null : o.getKey().trim().toUpperCase());
                    opt.setText(o.getText() == null ? null : o.getText().trim());
                    opt.setCorrect(Boolean.TRUE.equals(o.getCorrect()));
                    opt.setPointNodeCode(o.getPointNodeCode());
                    options.add(opt);
                }
            }
            if (options.size() < 2) {
                log.warn("规则清洗：题干「{}」选项不足 2 个，跳过", cleaned.getStem());
                continue;
            }
            long correctCount = options.stream().filter(o -> Boolean.TRUE.equals(o.getCorrect())).count();
            if (correctCount != 1) {
                log.warn("规则清洗：题干「{}」正确项数={}，跳过", cleaned.getStem(), correctCount);
                continue;
            }
            cleaned.setOptions(options);
            questions.add(cleaned);
        }

        result.setQuestions(questions);
        log.info("规则清洗完成：题目数={}", questions.size());
        return result;
    }

    private Integer normalizeDifficulty(Integer difficulty) {
        if (difficulty == null || difficulty < 1 || difficulty > 5) {
            return 3;
        }
        return difficulty;
    }

    private String getCellString(Row row, Integer colIndex) {
        if (colIndex == null) return null;
        Cell cell = row.getCell(colIndex);
        if (cell == null) return null;
        String value = getCellValue(cell).trim();
        return StringUtils.hasText(value) ? value : null;
    }

    private Integer getCellInteger(Row row, Integer colIndex) {
        if (colIndex == null) return null;
        Cell cell = row.getCell(colIndex);
        if (cell == null) return null;
        CellType type = cell.getCellType();
        if (type == CellType.NUMERIC) {
            return (int) cell.getNumericCellValue();
        }
        if (type == CellType.STRING) {
            String s = cell.getStringCellValue().trim();
            if (StringUtils.hasText(s)) {
                try {
                    return Integer.parseInt(s);
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        return null;
    }

    private String getCellValue(Cell cell) {
        if (cell == null) return "";
        CellType type = cell.getCellType();
        if (type == CellType.STRING) {
            return cell.getStringCellValue();
        }
        if (type == CellType.NUMERIC) {
            double d = cell.getNumericCellValue();
            if (d == Math.floor(d) && !Double.isInfinite(d)) {
                return String.valueOf((long) d);
            }
            return String.valueOf(d);
        }
        if (type == CellType.BOOLEAN) {
            return String.valueOf(cell.getBooleanCellValue());
        }
        return "";
    }

    private boolean isEmptyRow(Row row) {
        for (int c = row.getFirstCellNum(); c < row.getLastCellNum(); c++) {
            Cell cell = row.getCell(c);
            if (cell != null && cell.getCellType() != CellType.BLANK) {
                String v = getCellValue(cell).trim();
                if (StringUtils.hasText(v)) {
                    return false;
                }
            }
        }
        return true;
    }
}
