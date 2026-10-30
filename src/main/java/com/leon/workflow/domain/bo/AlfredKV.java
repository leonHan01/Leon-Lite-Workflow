package com.leon.workflow.domain.bo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AlfredKV {
    private String key;
    private String value;
    private String url;
    private List<String> tags = new ArrayList<>();
    private List<String> cmds  = new ArrayList<>();
    private List<String> pinyin;
    private String icon;
    private String firstDiamond;
    private String secondDiamond;
}
