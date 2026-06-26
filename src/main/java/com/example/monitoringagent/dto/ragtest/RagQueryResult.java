package com.example.monitoringagent.dto.ragtest;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class RagQueryResult {
    private String answer;
    private String reasoningContent;
    private List<RagRetrievedDocument> searchResults = new ArrayList<>();
    private long latencyMs;
    /** 首字时间：从收到查询到产出答案第一个字符的耗时（毫秒）。-1 表示未产出任何内容 */
    private long firstTokenMs = -1;
}
