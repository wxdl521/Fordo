package com.wenjin.eval.scenario;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 从 classpath {@code eval/scenarios/*.json} 加载并 schema 校验评测场景。
 * <p>场景以代码库为唯一事实源；本加载器不做 DB 写入（同步入库见后续任务）。
 */
public final class ScenarioLoader {

    public static final String DEFAULT_LOCATION_PATTERN = "classpath:eval/scenarios/*.json";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

    private final String locationPattern;

    public ScenarioLoader() {
        this(DEFAULT_LOCATION_PATTERN);
    }

    public ScenarioLoader(String locationPattern) {
        this.locationPattern = locationPattern;
    }

    /**
     * 加载目录下全部场景 JSON，逐一 schema 校验，按 code 排序返回。
     *
     * @throws ScenarioSchemaException 任一文件 JSON/schema 非法
     */
    public List<EvalScenarioDocument> loadAll() {
        Resource[] resources;
        try {
            resources = new PathMatchingResourcePatternResolver()
                    .getResources(locationPattern);
        } catch (IOException e) {
            throw new ScenarioSchemaException(
                    "Failed to list scenario resources: " + locationPattern, e);
        }
        if (resources.length == 0) {
            throw new ScenarioSchemaException("No scenario files found at " + locationPattern);
        }

        List<EvalScenarioDocument> docs = new ArrayList<>(resources.length);
        for (Resource resource : resources) {
            docs.add(loadResource(resource));
        }
        docs.sort(Comparator.comparing(EvalScenarioDocument::code));

        // code 唯一
        for (int i = 1; i < docs.size(); i++) {
            if (docs.get(i).code().equals(docs.get(i - 1).code())) {
                throw new ScenarioSchemaException("Duplicate scenario code: " + docs.get(i).code());
            }
        }
        return List.copyOf(docs);
    }

    /**
     * 按 code 加载单个场景；找不到返回 empty。
     */
    public Optional<EvalScenarioDocument> loadByCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return loadAll().stream().filter(d -> code.equals(d.code())).findFirst();
    }

    /**
     * 解析并校验单个 JSON 输入流（供单测注入非法/合法样例）。
     */
    public EvalScenarioDocument parseAndValidate(InputStream in, String sourceName) {
        EvalScenarioDocument doc;
        try {
            doc = MAPPER.readValue(in, EvalScenarioDocument.class);
        } catch (IOException e) {
            throw new ScenarioSchemaException(
                    "Invalid scenario JSON" + (sourceName != null ? " (" + sourceName + ")" : "")
                            + ": " + e.getMessage(),
                    e);
        }
        try {
            ScenarioSchemaValidator.validate(doc);
        } catch (ScenarioSchemaException e) {
            if (sourceName != null && !sourceName.isBlank()) {
                throw new ScenarioSchemaException(sourceName + ": " + e.getMessage(), e);
            }
            throw e;
        }
        return doc;
    }

    private EvalScenarioDocument loadResource(Resource resource) {
        String name = resource.getFilename() != null ? resource.getFilename() : resource.getDescription();
        try (InputStream in = resource.getInputStream()) {
            return parseAndValidate(in, name);
        } catch (IOException e) {
            throw new ScenarioSchemaException("Failed to read scenario resource: " + name, e);
        }
    }
}
