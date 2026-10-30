package com.leon.workflow.service;

import com.leon.workflow.dao.entity.LeonDiamond;
import com.leon.workflow.dao.repository.LeonDiamondRepository;
import com.leon.workflow.domain.dto.DiamondKeyOption;
import com.leon.workflow.util.diamond.AlfredDiamondService;
import com.leon.workflow.util.diamond.DiamondConfigParser;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DiamondService {
    public static class DuplicateKeyException extends IllegalArgumentException {
        public DuplicateKeyException() { super("配置键已存在"); }
    }

    private final LeonDiamondRepository repository;
    private final AlfredDiamondService cache;
    private final DiamondConfigParser parser;

    public DiamondService(LeonDiamondRepository repository, AlfredDiamondService cache, DiamondConfigParser parser) {
        this.repository = repository;
        this.cache = cache;
        this.parser = parser;
    }

    public LeonDiamond create(LeonDiamond diamond) {
        if (diamond.getDiamondKey() == null || diamond.getDiamondKey().isBlank() || diamond.getDiamondValue() == null) {
            throw new IllegalArgumentException("diamondKey 和 diamondValue 必填");
        }
        if (repository.findByDiamondKey(diamond.getDiamondKey()) != null) {
            throw new DuplicateKeyException();
        }
        validate(diamond);
        diamond.setId(null);
        LeonDiamond saved = repository.save(diamond);
        cache.init();
        return saved;
    }

    public List<DiamondKeyOption> options() {
        return repository.findAll().stream().map(diamond -> {
            DiamondKeyOption option = new DiamondKeyOption();
            option.setText(diamond.getDiamondKey());
            option.setValue(diamond.getId().toString());
            return option;
        }).toList();
    }

    public String text(Long id) {
        return repository.findById(id).map(LeonDiamond::getDiamondValue).orElse("配置不存在");
    }

    public void save(Long id, String value) {
        repository.findById(id).ifPresent(diamond -> {
            // Validate a detached value first so rejected input cannot dirty a managed entity.
            LeonDiamond candidate = new LeonDiamond();
            candidate.setDiamondKey(diamond.getDiamondKey());
            candidate.setDiamondValue(value);
            validate(candidate);
            diamond.setDiamondValue(value);
            repository.save(diamond);
        });
        cache.init();
    }

    private void validate(LeonDiamond diamond) {
        if (diamond.getDiamondValue() == null) throw new IllegalArgumentException("配置内容不能为空");
        if ("WebUrlDiamond".equals(diamond.getDiamondKey())) parser.parseUrls(diamond.getDiamondValue());
        if (diamond.getDiamondKey().startsWith("kv-")) parser.parseKeyValues(List.of(diamond));
    }
}
