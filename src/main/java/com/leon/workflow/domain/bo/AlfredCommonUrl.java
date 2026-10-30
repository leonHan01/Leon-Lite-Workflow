package com.leon.workflow.domain.bo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AlfredCommonUrl {
    private String url;

    private String name;

    private String title;

    private List<String> tags = new ArrayList<>();

    private List<String> pinyinList = new ArrayList<>();
}
