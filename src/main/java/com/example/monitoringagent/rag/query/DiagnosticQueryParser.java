package com.example.monitoringagent.rag.query;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 诊断查询解析器
 * 使用正则与关键词规则，从用户报错文本中提取错误码、异常类、组件、版本、环境信息。
 */
@Service
public class DiagnosticQueryParser {

    private static final Logger logger = LoggerFactory.getLogger(DiagnosticQueryParser.class);

    // 异常类：以 Exception/Error 结尾的驼峰类名
    private static final Pattern EXCEPTION_PATTERN =
            Pattern.compile("\\b([A-Z][A-Za-z0-9_]*(?:Exception|Error))\\b");

    // 形如 ERR_CONNECTION_REFUSED、ECONNRESET 的大写下划线错误码
    private static final Pattern ERROR_CODE_TOKEN_PATTERN =
            Pattern.compile("\\b([A-Z][A-Z0-9]{2,}(?:_[A-Z0-9]+)+|E[A-Z]{4,})\\b");

    // HTTP 状态码：HTTP 502 / status 404 / 500 错误
    private static final Pattern HTTP_STATUS_PATTERN =
            Pattern.compile("(?i)(?:http|status|code|错误码|状态码)\\D{0,5}([1-5][0-9]{2})\\b");

    // 版本号：3.2.6、2.6.10、v1.2
    private static final Pattern VERSION_PATTERN =
            Pattern.compile("\\bv?(\\d+\\.\\d+(?:\\.\\d+)?)\\b");

    // 常见组件名（大小写不敏感匹配）
    private static final String[] KNOWN_COMPONENTS = {
            "Milvus", "Redis", "MySQL", "PostgreSQL", "MongoDB", "Kafka", "RabbitMQ",
            "Spring Boot", "Spring", "Java", "Nginx", "Docker", "Kubernetes",
            "DashScope", "Elasticsearch", "Lucene", "Tomcat", "Netty", "OkHttp"
    };

    // 常见环境关键词
    private static final String[] KNOWN_ENVIRONMENTS = {
            "Windows", "Linux", "macOS", "Mac", "Docker", "Kubernetes", "K8s",
            "production", "prod", "staging", "test", "dev", "生产", "测试", "开发"
    };

    public DiagnosticQuery parse(String query) {
        DiagnosticQuery result = new DiagnosticQuery();
        result.setOriginalQuery(query);

        if (query == null || query.isBlank()) {
            return result;
        }

        result.setExceptions(matchAll(EXCEPTION_PATTERN, query));

        Set<String> errorCodes = new LinkedHashSet<>(matchAll(ERROR_CODE_TOKEN_PATTERN, query));
        Matcher httpMatcher = HTTP_STATUS_PATTERN.matcher(query);
        while (httpMatcher.find()) {
            errorCodes.add(httpMatcher.group(1));
        }
        result.setErrorCodes(new ArrayList<>(errorCodes));

        result.setComponents(matchKnown(KNOWN_COMPONENTS, query));
        result.setEnvironments(matchKnown(KNOWN_ENVIRONMENTS, query));
        result.setVersions(matchAll(VERSION_PATTERN, query));

        logger.info("诊断查询解析完成: 错误码={}, 异常={}, 组件={}, 版本={}, 环境={}",
                result.getErrorCodes(), result.getExceptions(), result.getComponents(),
                result.getVersions(), result.getEnvironments());

        return result;
    }

    private List<String> matchAll(Pattern pattern, String text) {
        Set<String> matches = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            matches.add(matcher.group(1));
        }
        return new ArrayList<>(matches);
    }

    private List<String> matchKnown(String[] candidates, String text) {
        String lower = text.toLowerCase();
        Set<String> matches = new LinkedHashSet<>();
        for (String candidate : candidates) {
            if (lower.contains(candidate.toLowerCase())) {
                matches.add(candidate);
            }
        }
        return new ArrayList<>(matches);
    }
}
