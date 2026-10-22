package com.wenjin.eval.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wenjin.ai.EmbeddingClient;
import com.wenjin.ai.HashingEmbeddingClient;
import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.config.LimitsProperties;
import com.wenjin.eval.runner.EvalHarness;
import com.wenjin.mapper.ChunkNodeMapMapper;
import com.wenjin.mapper.DocChunkMapper;
import com.wenjin.service.rag.ActiveEmbeddingResolver;
import com.wenjin.service.rag.ChunkEmbeddingRepository;
import com.wenjin.service.rag.FulltextRetriever;
import com.wenjin.service.rag.HybridRetriever;
import com.wenjin.service.rag.QueryEmbeddingCache;
import com.wenjin.service.rag.RetrievalHit;
import com.wenjin.service.rag.RetrieverPort;
import com.wenjin.service.rag.VectorIndexCache;
import com.wenjin.service.rag.VectorRetriever;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R5 labelled retrieval-recall eval: ~20-chunk corpus (see {@code eval/rag/corpus.json}, seeded into a throw-away
 * course so replay fixtures stay untouched) x ~31 labelled queries ({@code eval/rag/retrieval-set.json}).
 * Compares FULLTEXT (ngram MATCH) with HYBRID (FULLTEXT + vector, RRF) on recall@1/3/5 and MRR, writes
 * {@code target/eval-rag/report.md|json}.
 * <p>HYBRID(hash) always runs (offline hashing embeddings). HYBRID(live) runs only when the real embedding client is
 * available ({@code WENJIN_AI_EMBEDDING_API_KEY}) and {@code -Dwenjin.eval.rag.live=true}.
 * <p>Gated by {@code WENJIN_EVAL_IT=true}; never touches the demo database.
 */
@EnabledIfEnvironmentVariable(named = "WENJIN_EVAL_IT", matches = "true")
class RetrievalRecallIT extends EvalDbSupport {

    private static final String COURSE_CODE = "EVAL_RECALL";
    private static final int[] KS = {1, 3, 5};
    private static final int TOP_K = 5;

    /** Calibrated from the measured run (FULLTEXT recall@5 = 0.900, floor leaves ~0.1 margin; see README "RAG 召回评测"); a regression guard, not a target. */
    /**
     * Sanity floor only. InnoDB FULLTEXT relevance depends on table-wide statistics of {@code doc_chunk}
     * (other fixtures' rows, not-yet-optimized deleted rows) and on the MySQL version, so the absolute number
     * differs per environment: local MySQL 8.0.32 measured 0.900, CI Testcontainers mysql:8.4 measured 0.729.
     * The real regression guard is the relative HYBRID ≥ FULLTEXT assertion.
     */
    private static final double FULLTEXT_RECALL5_FLOOR = 0.60;

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    @Autowired private EvalHarness harness;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private FeatureFlagsProperties flags;
    @Autowired private LimitsProperties limits;
    @Autowired private DocChunkMapper docChunkMapper;
    @Autowired private ChunkNodeMapMapper chunkNodeMapMapper;
    @Autowired private ChunkEmbeddingRepository embeddingRepository;
    @Autowired private ObjectProvider<EmbeddingClient> realClientProvider;

    private long courseId;
    private boolean vectorBefore;
    private boolean hashingBefore;
    /** chunkId -> corpus key (source title). */
    private final Map<Long, String> keyByChunk = new LinkedHashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        vectorBefore = flags.isRagVectorEnabled();
        hashingBefore = limits.getRag().isAllowHashingEmbedding();
        flags.setRagVectorEnabled(true);
        limits.getRag().setAllowHashingEmbedding(true);
        long teacherId = harness.fixture().world().teacherId();
        dropCourse();
        jdbc.update("INSERT INTO course (code, name, description, teacher_id, status) VALUES (?, ?, ?, ?, 1)",
                COURSE_CODE, "召回评测（临时）", "RetrievalRecallIT 临时课程", teacherId);
        courseId = jdbc.queryForObject("SELECT id FROM course WHERE code = ?", Long.class, COURSE_CODE);
        for (JsonNode c : readJson("eval/rag/corpus.json").get("chunks")) {
            String key = c.get("key").asText();
            String content = c.get("content").asText();
            jdbc.update("INSERT INTO doc_source (course_id, title, kind, status, created_by) VALUES (?, ?, 0, 1, ?)",
                    courseId, key, teacherId);
            long sourceId = jdbc.queryForObject(
                    "SELECT id FROM doc_source WHERE course_id = ? AND title = ?", Long.class, courseId, key);
            jdbc.update("INSERT INTO doc_chunk (source_id, seq, content, char_count) VALUES (?, 0, ?, ?)",
                    sourceId, content, content.length());
            long chunkId = jdbc.queryForObject(
                    "SELECT id FROM doc_chunk WHERE source_id = ? AND seq = 0", Long.class, sourceId);
            jdbc.update("INSERT INTO chunk_node_map (chunk_id, course_id, node_code, review_status) VALUES (?, ?, ?, 1)",
                    chunkId, courseId, c.get("nodeCode").asText());
            keyByChunk.put(chunkId, key);
        }
    }

    @AfterEach
    void tearDown() {
        try {
            dropCourse();
        } finally {
            flags.setRagVectorEnabled(vectorBefore);
            limits.getRag().setAllowHashingEmbedding(hashingBefore);
        }
    }

    private void dropCourse() {
        List<Long> ids = jdbc.queryForList("SELECT id FROM course WHERE code = ?", Long.class, COURSE_CODE);
        for (Long id : ids) {
            jdbc.update("DELETE e FROM doc_chunk_embedding e JOIN doc_chunk c ON c.id = e.chunk_id"
                    + " JOIN doc_source s ON s.id = c.source_id WHERE s.course_id = ?", id);
            jdbc.update("DELETE FROM chunk_node_map WHERE course_id = ?", id);
            jdbc.update("DELETE c FROM doc_chunk c JOIN doc_source s ON s.id = c.source_id WHERE s.course_id = ?", id);
            jdbc.update("DELETE FROM doc_source WHERE course_id = ?", id);
            jdbc.update("DELETE FROM course WHERE id = ?", id);
        }
    }

    private static JsonNode readJson(String path) throws Exception {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return JSON.readTree(in);
        }
    }

    // ── metrics ──────────────────────────────────────────────────────────

    private record QueryResult(String id, String type, String query, List<String> relevant, List<String> ranked,
                               double[] recallAtK, double rr) {}

    /** Cosine distribution of (query, chunk) pairs: relevant = chunk is a labelled answer, irrelevant = all others. */
    private record CosineDist(String label, int n, double min, double median, double max) {}

    private record ModelCosine(String model, CosineDist relevant, CosineDist irrelevant) {}

    private record Aggregate(String name, int queries, double[] recallAtK, double mrr, Map<String, double[]> byType) {}

    private List<String> rankedKeys(RetrieverPort retriever, JsonNode q) {
        List<String> nodes = new ArrayList<>();
        if (q.has("nodeCodes")) {
            q.get("nodeCodes").forEach(n -> nodes.add(n.asText()));
        }
        List<RetrievalHit> hits = retriever.retrieve(courseId, nodes, q.get("query").asText(), TOP_K);
        Set<String> keys = new LinkedHashSet<>();
        for (RetrievalHit h : hits) {
            String key = keyByChunk.get(h.chunkId());
            if (key != null) {
                keys.add(key);
            }
        }
        return new ArrayList<>(keys);
    }

    private List<QueryResult> evaluate(RetrieverPort retriever, JsonNode queries) {
        List<QueryResult> out = new ArrayList<>();
        for (JsonNode q : queries) {
            List<String> relevant = new ArrayList<>();
            q.get("relevant").forEach(r -> relevant.add(r.asText()));
            List<String> ranked = rankedKeys(retriever, q);
            double[] recall = new double[KS.length];
            for (int i = 0; i < KS.length; i++) {
                List<String> top = ranked.subList(0, Math.min(KS[i], ranked.size()));
                long found = relevant.stream().filter(top::contains).count();
                recall[i] = (double) found / relevant.size();
            }
            double rr = 0;
            for (int i = 0; i < ranked.size(); i++) {
                if (relevant.contains(ranked.get(i))) {
                    rr = 1.0 / (i + 1);
                    break;
                }
            }
            out.add(new QueryResult(q.get("id").asText(), q.get("type").asText(), q.get("query").asText(),
                    relevant, ranked, recall, rr));
        }
        return out;
    }

    private static Aggregate aggregate(String name, List<QueryResult> results) {
        double[] sum = new double[KS.length];
        double rr = 0;
        Map<String, double[]> byType = new LinkedHashMap<>(); // [n, r@1, r@3, r@5, mrr]
        for (QueryResult r : results) {
            double[] t = byType.computeIfAbsent(r.type(), k -> new double[5]);
            t[0]++;
            for (int i = 0; i < KS.length; i++) {
                sum[i] += r.recallAtK()[i];
                t[1 + i] += r.recallAtK()[i];
            }
            rr += r.rr();
            t[4] += r.rr();
        }
        int n = results.size();
        double[] avg = new double[KS.length];
        for (int i = 0; i < KS.length; i++) {
            avg[i] = sum[i] / n;
        }
        byType.values().forEach(t -> {
            for (int i = 1; i < t.length; i++) {
                t[i] = t[i] / t[0];
            }
        });
        return new Aggregate(name, n, avg, rr / n, byType);
    }

    // ── strategies ───────────────────────────────────────────────────────

    private void embedCorpus(EmbeddingClient client) {
        List<Long> ids = new ArrayList<>(keyByChunk.keySet());
        List<String> texts = ids.stream().map(id -> docChunkMapper.selectById(id).getContent()).toList();
        List<float[]> vectors = client.embed(texts);
        assertThat(vectors).hasSameSizeAs(ids);
        for (int i = 0; i < ids.size(); i++) {
            embeddingRepository.upsert(ids.get(i), client.modelId(), vectors.get(i),
                    ChunkEmbeddingRepository.contentSha(texts.get(i)));
        }
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        return na == 0 || nb == 0 ? 0 : dot / Math.sqrt(na * nb);
    }

    private static CosineDist dist(String label, List<Double> xs) {
        if (xs.isEmpty()) {
            return new CosineDist(label, 0, 0, 0, 0);
        }
        List<Double> sorted = new ArrayList<>(xs);
        java.util.Collections.sort(sorted);
        int n = sorted.size();
        double median = n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
        return new CosineDist(label, n, sorted.get(0), median, sorted.get(n - 1));
    }

    /** Embeds every corpus chunk and every query with {@code embedder}; splits all pairs by relevance. */
    private ModelCosine cosineDistribution(EmbeddingClient embedder, JsonNode queries) {
        List<Long> ids = new ArrayList<>(keyByChunk.keySet());
        List<String> texts = ids.stream().map(id -> docChunkMapper.selectById(id).getContent()).toList();
        List<float[]> chunkVecs = embedder.embed(texts);
        List<String> qTexts = new ArrayList<>();
        queries.forEach(q -> qTexts.add(q.get("query").asText()));
        List<float[]> qVecs = embedder.embed(qTexts);
        List<Double> rel = new ArrayList<>();
        List<Double> irrel = new ArrayList<>();
        int qi = 0;
        for (JsonNode q : queries) {
            Set<String> relevant = new LinkedHashSet<>();
            q.get("relevant").forEach(r -> relevant.add(r.asText()));
            for (int ci = 0; ci < ids.size(); ci++) {
                double c = cosine(qVecs.get(qi), chunkVecs.get(ci));
                (relevant.contains(keyByChunk.get(ids.get(ci))) ? rel : irrel).add(c);
            }
            qi++;
        }
        return new ModelCosine(embedder.modelId(), dist("relevant", rel), dist("irrelevant", irrel));
    }

    private HybridRetriever hybrid(EmbeddingClient real, VectorIndexCache cache) {
        var resolver = new ActiveEmbeddingResolver(flags, limits, real);
        var vector = new VectorRetriever(resolver, cache, new QueryEmbeddingCache(limits), docChunkMapper, limits);
        return new HybridRetriever(new FulltextRetriever(docChunkMapper), vector, limits);
    }

    private List<QueryResult> runHybrid(EmbeddingClient embedder, EmbeddingClient realForResolver, JsonNode queries) {
        var cache = new VectorIndexCache(embeddingRepository, chunkNodeMapMapper);
        jdbc.update("DELETE e FROM doc_chunk_embedding e JOIN doc_chunk c ON c.id = e.chunk_id"
                + " JOIN doc_source s ON s.id = c.source_id WHERE s.course_id = ?", courseId);
        cache.invalidateCourse(courseId);
        embedCorpus(embedder);
        cache.invalidateCourse(courseId);
        return evaluate(hybrid(realForResolver, cache), queries);
    }

    @Test
    @DisplayName("FULLTEXT vs HYBRID(hash)[/live] 召回：recall@1/3/5 + MRR，写 target/eval-rag 报告")
    void recallReport() throws Exception {
        JsonNode queries = readJson("eval/rag/retrieval-set.json").get("queries");
        assertThat(queries.size()).isGreaterThanOrEqualTo(30);

        List<Aggregate> aggregates = new ArrayList<>();
        Map<String, List<QueryResult>> detail = new LinkedHashMap<>();

        List<QueryResult> ft = evaluate(new FulltextRetriever(docChunkMapper), queries);
        detail.put("FULLTEXT", ft);
        aggregates.add(aggregate("FULLTEXT", ft));

        var hashClient = new HashingEmbeddingClient(ActiveEmbeddingResolver.HASHING_DIM);
        List<QueryResult> hash = runHybrid(hashClient, null, queries);
        detail.put("HYBRID(hash)", hash);
        aggregates.add(aggregate("HYBRID(hash)", hash));

        String liveNote = "skipped (set WENJIN_AI_EMBEDDING_API_KEY and -Dwenjin.eval.rag.live=true)";
        EmbeddingClient real = realClientProvider.getIfAvailable();
        if ("true".equalsIgnoreCase(System.getProperty("wenjin.eval.rag.live")) && real != null && real.available()) {
            List<QueryResult> live = runHybrid(real, real, queries);
            detail.put("HYBRID(live)", live);
            aggregates.add(aggregate("HYBRID(live)", live));
            liveNote = "ran with " + real.modelId();
        }

        List<ModelCosine> cosines = new ArrayList<>();
        cosines.add(cosineDistribution(hashClient, queries));
        if (detail.containsKey("HYBRID(live)")) {
            cosines.add(cosineDistribution(real, queries));
        }

        writeReports(aggregates, detail, liveNote, cosines);

        Aggregate ftAgg = aggregates.get(0);
        Aggregate hashAgg = aggregates.get(1);
        assertThat(hashAgg.recallAtK()[2]).as("HYBRID(hash) recall@5 must not regress vs FULLTEXT")
                .isGreaterThanOrEqualTo(ftAgg.recallAtK()[2]);
        assertThat(ftAgg.recallAtK()[2]).as("FULLTEXT recall@5 floor")
                .isGreaterThanOrEqualTo(FULLTEXT_RECALL5_FLOOR);
    }

    // ── report ───────────────────────────────────────────────────────────

    private static String f(double v) {
        return String.format("%.3f", v);
    }

    private void writeReports(List<Aggregate> aggs, Map<String, List<QueryResult>> detail, String liveNote,
                              List<ModelCosine> cosines) throws Exception {
        Path dir = Path.of("target", "eval-rag");
        Files.createDirectories(dir);

        StringBuilder md = new StringBuilder("# RAG 召回评测\n\n");
        md.append("语料 ").append(keyByChunk.size()).append(" 块，标注查询 ")
                .append(aggs.get(0).queries()).append(" 条；topK=").append(TOP_K)
                .append("；HYBRID(live)：").append(liveNote).append("\n\n");
        md.append("| 方法 | recall@1 | recall@3 | recall@5 | MRR |\n|---|---|---|---|---|\n");
        for (Aggregate a : aggs) {
            md.append("| ").append(a.name()).append(" | ").append(f(a.recallAtK()[0])).append(" | ")
                    .append(f(a.recallAtK()[1])).append(" | ").append(f(a.recallAtK()[2])).append(" | ")
                    .append(f(a.mrr())).append(" |\n");
        }
        md.append("\n## 余弦分布（全部 query × chunk 对；用于按模型标定 vector-min-score / vector-only-min-score）\n\n");
        md.append("当前阈值：vector-min-score=").append(limits.getRag().getVectorMinScore())
                .append("，vector-only-min-score=").append(limits.getRag().getVectorOnlyMinScore()).append("\n\n");
        md.append("| 模型 | 类别 | n | min | median | max |\n|---|---|---|---|---|---|\n");
        for (ModelCosine m : cosines) {
            for (CosineDist d : List.of(m.relevant(), m.irrelevant())) {
                md.append("| ").append(m.model()).append(" | ").append(d.label()).append(" | ").append(d.n())
                        .append(" | ").append(f(d.min())).append(" | ").append(f(d.median())).append(" | ")
                        .append(f(d.max())).append(" |\n");
            }
        }
        md.append("\n## 按查询类型（recall@5 / MRR）\n\n| 类型 | n |");
        aggs.forEach(a -> md.append(' ').append(a.name()).append(" |"));
        md.append("\n|---|---|");
        aggs.forEach(a -> md.append("---|"));
        md.append('\n');
        for (String type : aggs.get(0).byType().keySet()) {
            md.append("| ").append(type).append(" | ").append((int) aggs.get(0).byType().get(type)[0]).append(" |");
            for (Aggregate a : aggs) {
                double[] t = a.byType().get(type);
                md.append(' ').append(f(t[3])).append(" / ").append(f(t[4])).append(" |");
            }
            md.append('\n');
        }
        md.append("\n## 逐条（top5 来源 key；相关项以 * 标记）\n\n| id | 类型 | 查询 |");
        aggs.forEach(a -> md.append(' ').append(a.name()).append(" |"));
        md.append("\n|---|---|---|");
        aggs.forEach(a -> md.append("---|"));
        md.append('\n');
        List<QueryResult> base = detail.get("FULLTEXT");
        for (int i = 0; i < base.size(); i++) {
            QueryResult b = base.get(i);
            md.append("| ").append(b.id()).append(" | ").append(b.type()).append(" | ").append(b.query()).append(" |");
            for (Aggregate a : aggs) {
                QueryResult r = detail.get(a.name()).get(i);
                List<String> shown = new ArrayList<>();
                for (String k : r.ranked()) {
                    shown.add(r.relevant().contains(k) ? k + "*" : k);
                }
                md.append(' ').append(shown.isEmpty() ? "(无命中)" : String.join(" ", shown)).append(" |");
            }
            md.append('\n');
        }
        Files.writeString(dir.resolve("report.md"), md.toString(), StandardCharsets.UTF_8);

        ObjectNode root = JSON.createObjectNode();
        root.put("corpusChunks", keyByChunk.size());
        root.put("queries", aggs.get(0).queries());
        root.put("topK", TOP_K);
        root.put("live", liveNote);
        ArrayNode cos = root.putArray("cosineDistribution");
        for (ModelCosine m : cosines) {
            ObjectNode o = cos.addObject();
            o.put("model", m.model());
            for (CosineDist d : List.of(m.relevant(), m.irrelevant())) {
                ObjectNode x = o.putObject(d.label());
                x.put("n", d.n());
                x.put("min", d.min());
                x.put("median", d.median());
                x.put("max", d.max());
            }
        }
        ArrayNode methods = root.putArray("methods");
        for (Aggregate a : aggs) {
            ObjectNode m = methods.addObject();
            m.put("name", a.name());
            m.put("recall@1", a.recallAtK()[0]);
            m.put("recall@3", a.recallAtK()[1]);
            m.put("recall@5", a.recallAtK()[2]);
            m.put("mrr", a.mrr());
            ObjectNode types = m.putObject("byType");
            a.byType().forEach((type, t) -> {
                ObjectNode o = types.putObject(type);
                o.put("n", (int) t[0]);
                o.put("recall@1", t[1]);
                o.put("recall@3", t[2]);
                o.put("recall@5", t[3]);
                o.put("mrr", t[4]);
            });
            ArrayNode qs = m.putArray("perQuery");
            for (QueryResult r : detail.get(a.name())) {
                ObjectNode o = qs.addObject();
                o.put("id", r.id());
                o.put("type", r.type());
                o.put("query", r.query());
                o.putPOJO("relevant", r.relevant());
                o.putPOJO("ranked", r.ranked());
                o.put("recall@5", r.recallAtK()[2]);
                o.put("rr", r.rr());
            }
        }
        Files.writeString(dir.resolve("report.json"), JSON.writeValueAsString(root), StandardCharsets.UTF_8);
    }
}
