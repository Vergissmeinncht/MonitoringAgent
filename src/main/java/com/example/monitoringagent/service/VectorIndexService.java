package com.example.monitoringagent.service;

import com.alibaba.cloud.ai.dashscope.spec.DashScopeApiSpec;
import com.example.monitoringagent.constant.MilvusConstants;
import com.example.monitoringagent.dto.DocumentChunk;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.MutationResult;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@Service
public class VectorIndexService {

    private static final Logger logger = LoggerFactory.getLogger(VectorIndexService.class);

    @Autowired
    private MilvusServiceClient milvusClient;

    @Autowired
    private VectorEmbeddingService embeddingService;

    @Autowired
    private DocumentChunkService chunkService;

    @Value("${file.upload.path}")
    private String uploadPath;

    // 这里可以添加向量索引的相关方法，例如创建索引、查询索引等
    /**
     * 实现单文件的向量索引逻辑
     * @param filePath
     * @throws Exception
     */
    public void indexSingleFile(String filePath)throws Exception {
        Path path = Paths.get(filePath).normalize();
        File file = path.toFile();

        if(!file.exists()||!file.isFile()){
            throw new IllegalArgumentException("文件不存在或不是一个有效的文件: "+filePath);
        }
        logger.info("开始索引文件: {}", filePath);
        // 实现单文件的向量索引逻辑
        //1. 读取文件内容
        String content = Files.readString(path);
        logger.info("读取文件：{}，内容长度: {}", filePath, content.length());

        //2.删除该文件的旧数据（如果存在）
        deleteExistingData(path.toString());

        //3.将文件内容切分成多个片段
        List<DocumentChunk> chunks = chunkService.chunkDocument(content, path.toString());
        logger.info("文件切分完成: {} -> {} 个分片", filePath, chunks.size());

        //4.对每个片段进行向量化，并存储到Milvus中
        for(int i = 0;i<chunks.size();i++){
            DocumentChunk chunk = chunks.get(i);
            try {
                //生成向量
                List<Float> vector = embeddingService.generateEmbedding(chunk.getContent());

                //构建元数据
                Map<String,Object> metadata = buildMetadata(path.toString(), chunk, chunks.size());

                //插入到Milvus中
                insertToMilvus(chunk.getContent(), vector, metadata,chunk.getChunkIndex());
                logger.info("成功索引分片 {}: 文件: {}, 分片索引: {}, 内容长度: {}", i, filePath, chunk.getChunkIndex(), chunk.getContent().length());
            }catch (Exception e){
                logger.error("索引分片 {} 失败: 文件: {}, 分片索引: {}, 错误信息: {}", i, filePath, chunk.getChunkIndex(), e.getMessage(), e);
                throw new RuntimeException("索引分片失败: 文件: "+filePath+", 分片索引: "+chunk.getChunkIndex()+", 错误信息: "+e.getMessage(), e);
            }
        }

        logger.info("完成索引文件: {}, 总分片数: {}", filePath, chunks.size());
    }

    /**
     * 删除文件的旧数据（根据metadata._source）
     */
    private void deleteExistingData(String filePath){
        try {
            // 使用统一的路径分隔符（正斜杠）用于Milvus存储，避免表达式解析错误
            // 将系统路径转换为统一格式
            Path path = Paths.get(filePath).normalize();
            String normalizedPath = path.toString().replace(File.separatorChar, '/');

            //构建删除表达式：metadata["_source"] == "xxx"
            String expr = String.format("metadata[\"_source\"] == \"%s\"", normalizedPath);
            logger.info("准备删除旧数据，路径：{}，表达式：{}", normalizedPath, expr);

            //确保collection已加载
            R<RpcStatus> loadResponse = milvusClient.loadCollection(
                LoadCollectionParam.newBuilder()
                    .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                    .build()
            );

            //状态码65535表示集合已加载
            if(loadResponse.getStatus()!=0&& loadResponse.getStatus()!=65535){
                logger.warn("加载集合失败，状态码: {}, 消息: {}", loadResponse.getStatus(), loadResponse.getMessage());
                return;
            }

            DeleteParam deleteParam = DeleteParam.newBuilder()
                    .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                    .withExpr(expr)
                    .build();

            R<MutationResult> response = milvusClient.delete(deleteParam);

            if(response.getStatus()!=0){
                logger.warn("删除旧数据时出现警告：{}", response.getMessage());
            }else {
                long deleteCnt = response.getData().getDeleteCnt();
                logger.info("删除旧数据完成，路径：{}，删除数量: {}", normalizedPath, deleteCnt);
            }
        }catch (Exception e){
            logger.warn("删除旧数据失败（可能是首次索引）：{}", e.getMessage());
        }
    }

    /**
     * 构建元数据
     */
    private Map<String,Object> buildMetadata(String filePath,DocumentChunk chunk,int totalChunks){
        Map<String,Object> metadata = new HashMap<>();
        // 标准化路径：使用统一的路径分隔符（正斜杠）用于存储，确保跨平台一致性
        Path path = Paths.get(filePath).normalize();
        String normalizedPath = path.toString().replace(File.separatorChar, '/');

        //文件信息
        Path fileName = path.getFileName();
        String fileNameStr = fileName != null ? fileName.toString() : "unknown";
        String extension = "";
        int dotIndex =fileNameStr.lastIndexOf(".");
        if(dotIndex>0) extension = fileNameStr.substring(dotIndex);

        metadata.put("_source", normalizedPath);
        metadata.put("_extension",extension);
        metadata.put("_file_name",fileNameStr);

        //分片信息
        metadata.put("chunkIndex",chunk.getChunkIndex());
        metadata.put("totalChunks", totalChunks);

        //标题信息
        if(chunk.getTitle()!=null&& !chunk.getTitle().isEmpty()){
            metadata.put("title", chunk.getTitle());
        }
        return metadata;
    }

    /**
     * 插入到Milvus中
     */
    public void insertToMilvus(String content,List<Float> vector,
                               Map<String, Object> metadata,int chunkIndex) throws Exception{
        try {
            //确保collection已加载
            R<RpcStatus> loadResponse = milvusClient.loadCollection(
                    LoadCollectionParam.newBuilder()
                            .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                            .build()
            );

            if(loadResponse.getStatus()!=0&&loadResponse.getStatus()!=65535){
                throw new RuntimeException("加载集合失败，状态码: "+loadResponse.getStatus()+", 消息: "+loadResponse.getMessage());
            }

            //生成唯一 ID（使用"_source"+分片索引）
            String source =(String) metadata.get("_source");
            String id = UUID.nameUUIDFromBytes((source+" "+ chunkIndex).getBytes()).toString();

            //构建字段数据
            List<InsertParam.Field> fields = new ArrayList<>();
            fields.add(new InsertParam.Field("id", Collections.singletonList(id)));
            fields.add(new InsertParam.Field("content", Collections.singletonList(content)));
            fields.add(new InsertParam.Field("vector", Collections.singletonList(vector)));

            Gson gson =new Gson();
            JsonObject metadataJson = gson.toJsonTree(metadata).getAsJsonObject();
            fields.add(new InsertParam.Field("metadata", Collections.singletonList(metadataJson.toString())));

            //构建插入参数
            InsertParam insertParam = InsertParam.newBuilder()
                    .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                    .withFields(fields)
                    .build();

            //执行插入
            R<MutationResult> insertResponse = milvusClient.insert(insertParam);

            if(insertResponse.getStatus()!=0){
                throw new RuntimeException("插入数据失败，状态码: "+insertResponse.getStatus()+", 消息: "+insertResponse.getMessage());
            }
            logger.debug("成功插入数据到Milvus，ID: {}, 文件: {}, 分片索引: {}, 内容长度: {}", id, metadata.get("_source"), chunkIndex, content.length());
        }catch (Exception e){
            logger.error("插入向量到Milvus失败",e);
            throw e;
        }
    }
}
