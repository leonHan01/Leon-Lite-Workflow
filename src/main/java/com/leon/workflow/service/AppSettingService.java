package com.leon.workflow.service;

import com.leon.workflow.dao.entity.AppSetting;
import com.leon.workflow.dao.repository.AppSettingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class AppSettingService {
    public static final String DEEPSEEK_API_KEY = "DEEPSEEK_API_KEY";
    public static final String FEISHU_WEBHOOK_URL = "FEISHU_WEBHOOK_URL";
    public static final String FEISHU_WEBHOOK_SECRET = "FEISHU_WEBHOOK_SECRET";

    private final AppSettingRepository appSettingRepository;

    public AppSettingService(AppSettingRepository appSettingRepository) {
        this.appSettingRepository = appSettingRepository;
    }

    public Map<String, Object> getSetting(String key) {
        String normalized = requiredKey(key);
        AppSetting setting = appSettingRepository.findById(normalized).orElse(null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("key", normalized);
        result.put("value", setting == null ? "" : setting.getSettingValue());
        return result;
    }

    public String getValue(String key) {
        Object value = getSetting(key).get("value");
        return value == null ? "" : String.valueOf(value).trim();
    }

    @Transactional
    public Map<String, Object> saveSetting(String key, Map<String, Object> request) {
        String normalized = requiredKey(key);
        String value = request.get("value") == null ? "" : String.valueOf(request.get("value")).trim();
        AppSetting setting = appSettingRepository.findById(normalized).orElseGet(AppSetting::new);
        setting.setSettingKey(normalized);
        setting.setSettingValue(value);
        appSettingRepository.save(setting);
        return getSetting(normalized);
    }

    private String requiredKey(String key) {
        String text = key == null ? "" : key.trim();
        if (text.isBlank()) {
            throw new IllegalArgumentException("设置项不能为空");
        }
        return text;
    }
}
