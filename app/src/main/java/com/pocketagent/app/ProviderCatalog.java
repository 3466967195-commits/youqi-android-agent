package com.pocketagent.app;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

final class ProviderCatalog {
    static final class Provider {
        final String id;
        final String name;
        final String baseUrl;
        final String modelsUrl;

        Provider(String id, String name, String baseUrl, String modelsUrl) {
            this.id = id;
            this.name = name;
            this.baseUrl = baseUrl;
            this.modelsUrl = modelsUrl;
        }

        @Override public String toString() { return name; }
    }

    private static final List<Provider> PROVIDERS = Collections.unmodifiableList(Arrays.asList(
            new Provider("custom", "自定义 / 中转 API", "https://example.com/v1", ""),
            new Provider("openai", "OpenAI", "https://api.openai.com/v1", "https://api.openai.com/v1/models"),
            new Provider("deepseek", "DeepSeek", "https://api.deepseek.com/v1", "https://api.deepseek.com/v1/models"),
            new Provider("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "https://openrouter.ai/api/v1/models"),
            new Provider("siliconflow", "硅基流动", "https://api.siliconflow.cn/v1", "https://api.siliconflow.cn/v1/models"),
            new Provider("moonshot", "Moonshot / Kimi", "https://api.moonshot.cn/v1", "https://api.moonshot.cn/v1/models"),
            new Provider("dashscope", "阿里云百炼 / 通义", "https://dashscope.aliyuncs.com/compatible-mode/v1", "https://dashscope.aliyuncs.com/compatible-mode/v1/models"),
            new Provider("zhipu", "智谱 BigModel", "https://open.bigmodel.cn/api/paas/v4", "https://open.bigmodel.cn/api/paas/v4/models"),
            new Provider("gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "https://generativelanguage.googleapis.com/v1beta/openai/models"),
            new Provider("volcengine", "火山方舟", "https://ark.cn-beijing.volces.com/api/v3", "https://ark.cn-beijing.volces.com/api/v3/models")
    ));

    static List<Provider> all() { return PROVIDERS; }

    static Provider byId(String id) {
        for (Provider provider : PROVIDERS) if (provider.id.equals(id)) return provider;
        return PROVIDERS.get(0);
    }

    static Provider infer(String endpoint) {
        String value = endpoint == null ? "" : endpoint.toLowerCase();
        for (Provider provider : PROVIDERS) {
            if (!provider.id.equals("custom") && value.startsWith(provider.baseUrl.toLowerCase())) return provider;
        }
        return PROVIDERS.get(0);
    }

    static String modelsUrl(Provider provider, String endpoint) {
        if (!provider.modelsUrl.isEmpty()) return provider.modelsUrl;
        String value = endpoint == null ? "" : endpoint.trim();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        if (value.endsWith("/responses")) value = value.substring(0, value.length() - 10);
        if (value.endsWith("/chat/completions")) value = value.substring(0, value.length() - 17);
        return value + "/models";
    }

    private ProviderCatalog() { }
}
