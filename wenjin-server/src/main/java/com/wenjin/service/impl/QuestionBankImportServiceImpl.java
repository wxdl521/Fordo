package com.wenjin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.ai.QuestionBankCleanAiClient;
import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.dto.ImportBankResult;
import com.wenjin.dto.QuestionBankFile;
import com.wenjin.entity.Course;
import com.wenjin.entity.Question;
import com.wenjin.entity.QuestionNode;
import com.wenjin.entity.QuestionOption;
import com.wenjin.mapper.CourseMapper;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.mapper.QuestionMapper;
import com.wenjin.mapper.QuestionNodeMapper;
import com.wenjin.mapper.QuestionOptionMapper;
import com.wenjin.service.GraphQueryService;
import com.wenjin.service.QuestionBankImportService;
import com.wenjin.support.QuestionStatus;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 题库导入服务实现（JSON / Excel 上传）。
 * <p>
 * 流程：解析文件 → AI 清洗（失败回退规则清洗）→ persistBank 落库。
 */
@Service
public class QuestionBankImportServiceImpl implements QuestionBankImportService {

    private static final Logger log = LoggerFactory.getLogger(QuestionBankImportServiceImpl.class);

    /** 默认难度 */
    private static final int DEFAULT_DIFFICULTY = 3;
    /** 主考点权重 */
    private static final int WEIGHT_MAIN = 1;

    /** Excel 表头列名（不区分大小写） */
    private static final Map<String, String> HEADER_ALIASES = new HashMap<>();

    static {
        // 中文 -> 标准 key
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
        // 可选：各选项的干扰项考点（前置知识点编码）。无此列则留空，交给 AI 清洗推断。
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
    private final GraphQueryService graphQueryService;
    private final KgNodeMapper nodeMapper;
    private final QuestionMapper questionMapper;
    private final QuestionOptionMapper questionOptionMapper;
    private final QuestionNodeMapper questionNodeMapper;
    private final CourseMapper courseMapper;
    private final ObjectMapper objectMapper;

    public QuestionBankImportServiceImpl(QuestionBankCleanAiClient aiClient,
                                         GraphQueryService graphQueryService,
                                         KgNodeMapper nodeMapper,
                                         QuestionMapper questionMapper,
                                         QuestionOptionMapper questionOptionMapper,
                                         QuestionNodeMapper questionNodeMapper,
                                         CourseMapper courseMapper,
                                         ObjectMapper objectMapper) {
        this.aiClient = aiClient;
        this.graphQueryService = graphQueryService;
        this.nodeMapper = nodeMapper;
        this.questionMapper = questionMapper;
        this.questionOptionMapper = questionOptionMapper;
        this.questionNodeMapper = questionNodeMapper;
        this.courseMapper = courseMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ImportBankResult importFromJson(Long courseId, QuestionBankFile bank) {
        if (courseId == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "courseId 不能为空");
        }
        if (bank == null || bank.getQuestions() == null || bank.getQuestions().isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "题库数据为空");
        }
        return persistBank(courseId, bank, false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ImportBankResult importFromExcel(Long courseId, MultipartFile file) {
        if (courseId == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "courseId 不能为空");
        }
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "上传文件不能为空");
        }

        // 1) 解析 Excel
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

        // 2) AI 清洗（失败时回退到规则清洗）
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

        ImportBankResult result = persistBank(courseId, cleaned, aiCleaned);
        return result;
    }

    // ============================ 持久化 ============================

    /**
     * 将题库数据落库。抽取自 QuestionServiceImpl.importBank()。
     */
    private ImportBankResult persistBank(Long courseId, QuestionBankFile bank, boolean aiCleaned) {
        // 按 courseId 定位课程
        Course course = courseMapper.selectById(courseId);
        if (course == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "课程不存在: " + courseId);
        }

        // code→node_id（落库 question_node 用）
        Map<String, Long> codeToId = graphQueryService.codeToId(courseId);

        ImportBankResult result = new ImportBankResult();
        result.setAiCleaned(aiCleaned);

        if (bank.getQuestions() == null) {
            return result;
        }

        // 课程图谱白名单：所有 kg_node 的 nodeCode（复用 code→id 映射的键集，避免额外查询）
        Set<String> validCodes = codeToId.keySet();

        // 批前一次性载入题干集合，避免逐题 N+1 查重
        // 注：不加 .select(stem) 以兼容纯 Mockito 单测（lambda cache 未初始化时 select 会抛）；
        // 全字段拉取的额外开销远小于 N 次往返。
        Set<String> existingStems = questionMapper.selectList(
                new LambdaQueryWrapper<Question>().eq(Question::getCourseId, courseId)
        ).stream().map(Question::getStem).collect(Collectors.toSet());

        // 逐题：题干为空/nodeCode 非法/题干已存在则跳过，否则落库
        for (QuestionBankFile.BankQuestion bq : bank.getQuestions()) {
            String stem = bq.getStem() == null ? null : bq.getStem().trim();
            if (!StringUtils.hasText(stem)) {
                result.setSkipped(result.getSkipped() + 1);
                continue;
            }
            // nodeCode 必须在当前课程图谱内，否则为幽灵题（组卷时孤儿化），跳过
            String nodeCode = bq.getNodeCode();
            if (nodeCode == null || !validCodes.contains(nodeCode)) {
                result.setSkipped(result.getSkipped() + 1);
                result.setInvalidNodeSkipped(result.getInvalidNodeSkipped() + 1);
                log.warn("题库导入跳过：nodeCode「{}」不在课程图谱内，题干前 20 字「{}」",
                        nodeCode, stemPreview(stem));
                continue;
            }
            if (existingStems.contains(stem)) {
                result.setSkipped(result.getSkipped() + 1);
                continue;
            }
            persistBankQuestion(courseId, stem, bq, codeToId);
            existingStems.add(stem); // 同批内防止相同题干被双插
            result.setImported(result.getImported() + 1);
        }
        log.info("题库导入完成 courseId={} -> imported={} skipped={}（其中 nodeCode 非法 {}）aiCleaned={}",
                courseId, result.getImported(), result.getSkipped(),
                result.getInvalidNodeSkipped(), aiCleaned);
        return result;
    }

    /** 落库一道题库题：question(source=1,status=已通过) → option → question_node(主点 weight=1)。 */
    private void persistBankQuestion(Long courseId, String stem, QuestionBankFile.BankQuestion bq,
                                     Map<String, Long> codeToId) {
        // 答案 = 第一个 correct 选项的 key（无则 null）
        String answerKey = null;
        if (bq.getOptions() != null) {
            for (QuestionBankFile.BankOption o : bq.getOptions()) {
                if (Boolean.TRUE.equals(o.getCorrect())) {
                    answerKey = o.getKey();
                    break;
                }
            }
        }

        Question question = new Question();
        question.setCourseId(courseId);
        question.setStem(stem);
        question.setType(1); // 单选
        question.setDifficulty(bq.getDifficulty() == null ? DEFAULT_DIFFICULTY : bq.getDifficulty());
        question.setAnswer(answerKey);
        question.setAnalysis(bq.getAnalysis());
        question.setSource(1); // 学校题库（种子）
        question.setStatus(QuestionStatus.APPROVED); // 种子直接已通过
        questionMapper.insert(question);
        Long questionId = question.getId();

        // 选项落库：采用清洗返回的干扰项考点映射。
        //   · 正确项 point_node_code 恒置空；
        //   · 干扰项的 code 须在课程图谱白名单（codeToId 键集）内，越界则降级为 null（不丢题）。
        if (bq.getOptions() != null) {
            for (QuestionBankFile.BankOption o : bq.getOptions()) {
                boolean correct = Boolean.TRUE.equals(o.getCorrect());
                String pnc = o.getPointNodeCode();
                String effectivePnc = (correct || pnc == null || !codeToId.containsKey(pnc)) ? null : pnc;

                QuestionOption option = new QuestionOption();
                option.setQuestionId(questionId);
                option.setOptionKey(o.getKey());
                option.setOptionText(o.getText());
                option.setIsCorrect(correct ? 1 : 0);
                option.setPointNodeCode(effectivePnc);
                questionOptionMapper.insert(option);
            }
        }

        // 题-知识点：主点 weight=1（种子无次考点）
        insertQuestionNode(questionId, bq.getNodeCode(), WEIGHT_MAIN, codeToId);
    }

    private void insertQuestionNode(Long questionId, String code, int weight, Map<String, Long> codeToId) {
        Long nodeId = codeToId.get(code);
        if (nodeId == null) {
            log.warn("题-知识点关联跳过：code={} 无 node_id 映射", code);
            return;
        }
        QuestionNode qn = new QuestionNode();
        qn.setQuestionId(questionId);
        qn.setNodeId(nodeId);
        qn.setWeight(weight);
        questionNodeMapper.insert(qn);
    }

    /** 题干前 20 字预览（用于 log.warn，避免日志过长）。 */
    private String stemPreview(String stem) {
        if (stem == null) {
            return "";
        }
        return stem.length() > 20 ? stem.substring(0, 20) : stem;
    }

    // ============================ Excel 解析 ============================

    /**
     * 解析 Excel 文件为原始题目列表。
     * 单 Sheet，表头行：题干/stem, 知识点编码/nodeCode, 难度/difficulty, 解析/analysis,
     * 选项A/optionA, 选项B/optionB, 选项C/optionC (可选), 选项D/optionD (可选), 正确答案/answer。
     */
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

            // difficulty: 整数
            Integer diff = getCellInteger(row, headerMap.get("difficulty"));
            q.setDifficulty(diff);

            q.setAnalysis(getCellString(row, headerMap.get("analysis")));

            // 选项
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
        // 原始干扰项考点（可空）；正确项的 code 在落库时会被统一置空，越界 code 也会降级
        opt.setPointNodeCode(StringUtils.hasText(pointNodeCode) ? pointNodeCode.trim() : null);
        options.add(opt);
    }

    /**
     * 解析表头行，返回标准化 key -> 列索引的映射。
     */
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

        // 检查必须的列
        String[] required = {"stem", "nodecode", "difficulty", "optiona", "optionb", "answer"};
        for (String r : required) {
            if (!headerMap.containsKey(r)) {
                throw new BusinessException(ResultCode.BAD_REQUEST,
                        "Excel 表头缺少必需列：「" + r + "」");
            }
        }
        return headerMap;
    }

    // ============================ 规则清洗（AI 不可用时的回退） ============================

    /**
     * 规则清洗：不调用 AI，仅做格式标准化。
     */
    QuestionBankFile ruleBasedClean(List<QuestionBankFile.BankQuestion> raw) {
        QuestionBankFile result = new QuestionBankFile();
        List<QuestionBankFile.BankQuestion> questions = new ArrayList<>();

        for (QuestionBankFile.BankQuestion q : raw) {
            QuestionBankFile.BankQuestion cleaned = new QuestionBankFile.BankQuestion();
            // trim stem
            cleaned.setStem(q.getStem() == null ? null : q.getStem().trim());
            // nodeCode 保留
            cleaned.setNodeCode(q.getNodeCode());
            // chapter 保留
            cleaned.setChapter(q.getChapter());
            // difficulty 标准化：1-5，缺失或越界补 3
            cleaned.setDifficulty(normalizeDifficulty(q.getDifficulty()));
            // analysis：缺失补占位符
            String analysis = q.getAnalysis();
            cleaned.setAnalysis(StringUtils.hasText(analysis) ? analysis.trim() : "暂无解析");

            // 选项清洗
            List<QuestionBankFile.BankOption> options = new ArrayList<>();
            if (q.getOptions() != null) {
                for (QuestionBankFile.BankOption o : q.getOptions()) {
                    QuestionBankFile.BankOption opt = new QuestionBankFile.BankOption();
                    // key 标准化大写
                    opt.setKey(o.getKey() == null ? null : o.getKey().trim().toUpperCase());
                    // text trim
                    opt.setText(o.getText() == null ? null : o.getText().trim());
                    opt.setCorrect(Boolean.TRUE.equals(o.getCorrect()));
                    // 保留原始干扰项考点（落库时再做白名单降级）
                    opt.setPointNodeCode(o.getPointNodeCode());
                    options.add(opt);
                }
            }
            // 确保至少 2 个选项
            if (options.size() < 2) {
                log.warn("规则清洗：题干「{}」选项不足 2 个，跳过", cleaned.getStem());
                continue;
            }
            // 确保恰好一个正确项
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

    // ============================ 工具方法 ============================

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
