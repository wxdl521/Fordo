package com.wenjin.support.rag;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 引用硬校验：{@code citedChunkIds ⊆ 本次注入集合}；越界剔除。
 * <p>纯函数；与图谱幻觉白名单校验同构。
 */
public final class CitationValidator {

    private CitationValidator() {}

    /**
     * @param injectedChunkIds 本次注入的 chunk id 集合（可空）
     * @param citedChunkIds    模型声称引用的 id（可空）
     * @return 合法 id 列表 + 被剔除的越界 id
     */
    public static Result validate(Collection<Long> injectedChunkIds, Collection<Long> citedChunkIds) {
        Set<Long> injected = toSet(injectedChunkIds);
        if (citedChunkIds == null || citedChunkIds.isEmpty()) {
            return new Result(List.of(), List.of());
        }
        List<Long> kept = new ArrayList<>();
        List<Long> stripped = new ArrayList<>();
        Set<Long> seen = new LinkedHashSet<>();
        for (Long id : citedChunkIds) {
            if (id == null || !seen.add(id)) {
                continue;
            }
            if (injected.contains(id)) {
                kept.add(id);
            } else {
                stripped.add(id);
            }
        }
        return new Result(List.copyOf(kept), List.copyOf(stripped));
    }

    private static Set<Long> toSet(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Set.of();
        }
        Set<Long> set = new LinkedHashSet<>();
        for (Long id : ids) {
            if (id != null) {
                set.add(id);
            }
        }
        return set;
    }

    public record Result(List<Long> keptChunkIds, List<Long> strippedChunkIds) {
        public Result {
            keptChunkIds = keptChunkIds == null ? List.of() : List.copyOf(keptChunkIds);
            strippedChunkIds = strippedChunkIds == null ? List.of() : List.copyOf(strippedChunkIds);
        }

        public boolean hadStripped() {
            return !strippedChunkIds.isEmpty();
        }

        public Set<Long> keptSet() {
            return new LinkedHashSet<>(keptChunkIds);
        }
    }
}
