package com.leon.workflow.util.diamond;

import com.leon.workflow.dao.entity.LeonDiamond;
import com.leon.workflow.dao.repository.LeonDiamondRepository;
import com.leon.workflow.domain.bo.AlfredCommonUrl;
import com.leon.workflow.domain.bo.AlfredKV;
import com.leon.workflow.domain.bo.UrlTag;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AlfredDiamondService {
    private final LeonDiamondRepository repository;
    private final DiamondConfigParser parser;
    private volatile Snapshot snapshot = new Snapshot(List.of(), List.of(), List.of());

    private record Snapshot(List<AlfredCommonUrl> urls, List<UrlTag> tags, List<AlfredKV> keyValues) {}

    public AlfredDiamondService(LeonDiamondRepository repository, DiamondConfigParser parser) {
        this.repository = repository;
        this.parser = parser;
    }

    @PostConstruct
    public synchronized void init() {
        List<LeonDiamond> diamonds = repository.findAll();
        String urls = diamonds.stream().filter(d -> "WebUrlDiamond".equals(d.getDiamondKey()))
                .map(LeonDiamond::getDiamondValue).filter(value -> value != null).findFirst().orElse(null);
        DiamondConfigParser.UrlConfig parsedUrls = parser.parseUrls(urls);
        List<AlfredKV> keyValues = parser.parseKeyValues(diamonds.stream()
                .filter(d -> d.getDiamondKey() != null && d.getDiamondKey().startsWith("kv-")).toList());
        // Publish only after every configuration has parsed successfully.
        snapshot = new Snapshot(parsedUrls.urls(), parsedUrls.tags(), keyValues);
    }

    public List<AlfredKV> getAlfredKVList() { return snapshot.keyValues(); }
    public List<AlfredCommonUrl> getCommonUrlList() { return snapshot.urls(); }
    public List<UrlTag> getTags() { return snapshot.tags(); }
}
