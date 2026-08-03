package com.example.monitoringagent.service.document;

import java.nio.file.Path;
import java.util.Set;

/**
 * 文件类型处理器接口。
 * 每个实现负责声明自己支持的扩展名，并把对应文件解析成统一的纯文本结果。
 */
public interface DocumentProcessor {

    /**
     * 该处理器支持的扩展名集合（小写、不带点），如 {"md", "markdown"}。
     */
    Set<String> supportedExtensions();

    /**
     * 是否支持指定扩展名（小写、不带点）。
     */
    default boolean supports(String extension) {
        return extension != null && supportedExtensions().contains(extension.toLowerCase());
    }

    /**
     * 把文件解析成统一的纯文本结果。
     */
    DocumentParseResult parse(Path path) throws Exception;
}
