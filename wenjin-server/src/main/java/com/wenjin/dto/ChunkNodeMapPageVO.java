package com.wenjin.dto;

import lombok.Data;

import java.util.List;

/**
 * chunk_node_map 分页结果。
 */
@Data
public class ChunkNodeMapPageVO {

    private long total;
    private int page;
    private int size;
    private List<ChunkNodeMapVO> items;
}
