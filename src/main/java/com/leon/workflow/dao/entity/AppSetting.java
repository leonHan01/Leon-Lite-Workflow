package com.leon.workflow.dao.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

@Entity
@Data
@Table(name = "app_setting")
public class AppSetting {
    @Id
    @Column(length = 120)
    private String settingKey;

    @Column(columnDefinition = "TEXT")
    private String settingValue;
}
