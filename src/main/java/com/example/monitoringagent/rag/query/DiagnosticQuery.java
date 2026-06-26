package com.example.monitoringagent.rag.query;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * 诊断查询解析结果
 * 承载从用户报错文本中提取的结构化诊断信号
 */
@Getter
@Setter
public class DiagnosticQuery {

    /** 原始用户问题 */
    private String originalQuery;

    /** 错误码，例如 502、ERR_CONNECTION_REFUSED */
    private List<String> errorCodes = new ArrayList<>();

    /** 异常类，例如 NullPointerException */
    private List<String> exceptions = new ArrayList<>();

    /** 组件名，例如 Milvus、Redis、Spring Boot */
    private List<String> components = new ArrayList<>();

    /** 版本号，例如 3.2.6、17 */
    private List<String> versions = new ArrayList<>();

    /** 运行环境，例如 Linux、Docker、production */
    private List<String> environments = new ArrayList<>();

    /**
     * 是否提取到任何精确诊断信号。
     * 用于判断 BM25 关键词召回是否有价值。
     */
    public boolean hasKeywordSignals() {
        return !errorCodes.isEmpty()
                || !exceptions.isEmpty()
                || !components.isEmpty()
                || !versions.isEmpty()
                || !environments.isEmpty();
    }

    /**
     * 拼接所有关键词，作为 BM25 关键词检索 query。
     */
    public String toKeywordQuery() {
        List<String> keywords = new ArrayList<>();
        keywords.addAll(errorCodes);
        keywords.addAll(exceptions);
        keywords.addAll(components);
        keywords.addAll(versions);
        keywords.addAll(environments);
        return String.join(" ", keywords);
    }
}
