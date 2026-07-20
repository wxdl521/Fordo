package com.wenjin.eval.fixture;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Fixed node-code map for unit tests. */
public final class MapEvalNodeLookup implements EvalNodeLookup {

    private final long courseId;
    private final Map<String, Long> codeToId;

    public MapEvalNodeLookup(long courseId, Map<String, Long> codeToId) {
        this.courseId = courseId;
        this.codeToId = Map.copyOf(Objects.requireNonNull(codeToId, "codeToId"));
    }

    @Override
    public Optional<Long> nodeIdByCode(long courseId, String nodeCode) {
        if (courseId != this.courseId || nodeCode == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(codeToId.get(nodeCode.trim()));
    }

    @Override
    public Set<String> allNodeCodes(long courseId) {
        if (courseId != this.courseId) {
            return Set.of();
        }
        return codeToId.keySet();
    }
}
