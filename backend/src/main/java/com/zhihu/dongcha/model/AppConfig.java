package com.zhihu.dongcha.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 系统配置：大模型服务 / RAG 策略，PUT 后热生效 */
public class AppConfig {

    /** 大模型服务配置段：chat 生成参数 + embedding 模型名，PUT /models 后整体替换生效 */
    public static class ModelConfig {
        public Chat chat = new Chat();
        public Embedding embedding = new Embedding();

        /** chat 生成模型接入参数：OpenAI 兼容网关地址、模型名与采样温度 */
        public static class Chat {
            public String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
            public String model = "qwen-plus";
            public double temperature = 0.3;
        }

        /** 向量化模型配置段（当前只有模型名一项） */
        public static class Embedding {
            public String model = "bge-large-zh";
        }

        /** 复制出独立可改的新实例（含新建嵌套 chat/embedding），供 PUT 改后整体替换 */
        public ModelConfig copy() {
            ModelConfig m = new ModelConfig();
            m.chat.baseUrl = chat.baseUrl;
            m.chat.model = chat.model;
            m.chat.temperature = chat.temperature;
            m.embedding.model = embedding.model;
            return m;
        }
    }

    /** RAG 检索策略配置段：查询改写、多路查询数、topK 与相似度阈值，检索链路每请求实时读取 */
    public static class RagConfig {
        public boolean queryRewrite = true;
        public int multiQueryCount = 2;
        public int topK = 5;
        public double scoreThreshold = 0.15;

        /** 复制出独立可改的新实例（字段全为基本类型，逐字段赋值即深拷贝） */
        public RagConfig copy() {
            RagConfig r = new RagConfig();
            r.queryRewrite = queryRewrite;
            r.multiQueryCount = multiQueryCount;
            r.topK = topK;
            r.scoreThreshold = scoreThreshold;
            return r;
        }
    }

    /** 当前生效的模型配置；DbStore.saveModels 用新实例整体替换该引用，volatile 令各线程即时读到 */
    public volatile ModelConfig models = new ModelConfig();
    /** 当前生效的 RAG 策略；替换方式同 models（saveRag 换引用），实现配置热生效 */
    public volatile RagConfig rag = new RagConfig();
    /** 扩展配置位（键值直存，LinkedHashMap 保持插入顺序）；除声明外当前未见读写 */
    public final Map<String, Object> extra = new LinkedHashMap<>();
    /** 取一个随机 UUID 字符串（ChatService 用它给每条消息生成 id）；AppConfig 自身无 id 字段 */
    public static String newId() {
        return UUID.randomUUID().toString();
    }
}
