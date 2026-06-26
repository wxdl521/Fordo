package com.wenjin.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/** 抽取后返回:草稿暂存 id + 草稿内容。 */
@Data
@AllArgsConstructor
public class GraphExtractDraftResponse {
    private String draftId;
    private GraphImportRequest draft;
}
