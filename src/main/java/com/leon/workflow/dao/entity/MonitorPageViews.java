package com.leon.workflow.dao.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

@Entity
@Table(name = "monitor_page_views")
public class MonitorPageViews {
    @Id
    @Column(name = "view_date")
    private LocalDate day;
    @Column(nullable = false)
    private long views;
}
