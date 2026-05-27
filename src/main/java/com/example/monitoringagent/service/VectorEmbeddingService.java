package com.example.monitoringagent.service;

import com.alibaba.dashscope.embeddings.*;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.alibaba.dashscope.utils.Constants;
import jakarta.annotation.PostConstruct;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;


@Service
public class VectorEmbeddingService {

    private static final Logger logger = LoggerFactory.getLogger(VectorEmbeddingService.class);

    @Value("${dashscope.api.key}")
    private String apiKey;

    @Value("${dashscope.embedding.model}")
    private String model;

    private TextEmbedding textEmbedding;

    @PostConstruct
    public void init(){
        // 验证 API Key
        if (apiKey == null || apiKey.trim().isEmpty() || apiKey.equals("your-api-key-here")) {
            logger.error("API Key 未正确配置！当前值: {}", apiKey);
            throw new IllegalStateException("请设置环境变量 DASHSCOPE_API_KEY 或在 application.yml 中配置正确的 API Key");
        }

        // 打印 API Key 前缀用于调试（不打印完整 Key 保证安全）
        String maskedKey = apiKey.length() > 8 ?
                apiKey.substring(0, 8) + "..." + apiKey.substring(apiKey.length() - 4) :
                "***";
        logger.info("API Key 已加载: {}", maskedKey);

        // 设置全局 API Key（确保设置成功）
        Constants.apiKey = apiKey;

        // 验证 API Key 是否设置成功
        if (Constants.apiKey == null || Constants.apiKey.isEmpty()) {
            logger.error("TextEmbedding API Key 设置失败！");
            throw new IllegalStateException("API Key 设置到 TextEmbedding 失败");
        }

        // 初始化 TextEmbedding 实例
        textEmbedding = new TextEmbedding();

        logger.info("阿里云 DashScope Embedding 服务初始化完成，模型: {}", model);
    }


    /**
     * 生成文本的向量嵌入
     * 调用阿里云DashScope Text Embedding API
     * @param content
     * @return
     */

    public List<Float> generateEmbedding(String content) {
        try {

            if (content == null || content.trim().isEmpty()) {
                logger.warn("内容为空，无法生成向量");
                throw new IllegalArgumentException("内容不能为空");
            }

            logger.debug("开始生成向量嵌入, 内容长度: {} 字符", content.length());

            // 确保 API Key 已设置（防止被其他地方覆盖）
            if (Constants.apiKey == null || Constants.apiKey.isEmpty()) {
                logger.warn("检测到 Constants.apiKey 为空，重新设置");
                Constants.apiKey = apiKey;
            }

            logger.debug("调用 API 前 Constants.apiKey: {}",
                    Constants.apiKey != null ? Constants.apiKey.substring(0, Math.min(8, Constants.apiKey.length())) + "..." : "null");

            //构建请求参数
            TextEmbeddingParam param = TextEmbeddingParam
                    .builder()
                    .model(model)
                    .texts(Collections.singletonList(content))
                    .build();

            //调用接口获取向量
            TextEmbeddingResult result = textEmbedding.call(param);
            List<Float> floatEmbedding = getFloats(result);

            logger.info("成功生成向量嵌入, 内容长度: {} 字符, 向量维度: {}",
                    content.length(), floatEmbedding.size());

            return floatEmbedding;

        } catch (NoApiKeyException e) {
            logger.error("API Key 未设置或无效", e);
            throw new RuntimeException("API Key 未设置，请配置 dashscope.api.key", e);
        } catch (Exception e) {
            logger.error("生成向量嵌入失败, 内容长度: {}", content != null ? content.length() : 0, e);
            throw new RuntimeException("生成向量嵌入失败: " + e.getMessage(), e);
        }
    }

    @NotNull
    private static List<Float> getFloats(TextEmbeddingResult result){
        if (result == null || result.getOutput() == null || result.getOutput().getEmbeddings() == null) {
            throw new RuntimeException("DashScope API 返回空结果");
        }

        TextEmbeddingOutput output = result.getOutput();
        List<TextEmbeddingResultItem> embeddings = output.getEmbeddings();

        if (embeddings.isEmpty()) {
            throw new RuntimeException("DashScope API 返回的向量列表为空");
        }

        //获取第一个文本的向量
        List<Double> embeddingDoubles = embeddings.get(0).getEmbedding();

        //转为List<Float>
        ArrayList<Float> floatEmbedding = new ArrayList<>(embeddingDoubles.size());
        for (Double d : embeddingDoubles) {
            floatEmbedding.add(d.floatValue());
        }
        return floatEmbedding;
    }

    public List<Float> generateQueryVector(String query) {
        return generateEmbedding(query);
    }
}
